package com.flyfishxu.kadb
import com.flyfishxu.kadb.cert.CertUtils.loadKeyPair
import com.flyfishxu.kadb.cert.CertUtils.loadKeySet
import com.flyfishxu.kadb.cert.platform.defaultDeviceName
import com.flyfishxu.kadb.core.AdbConnection
import com.flyfishxu.kadb.core.AdbProtocol
import com.flyfishxu.kadb.forwarding.TcpForwarder
import com.flyfishxu.kadb.pair.PairingConnectionCtx
import com.flyfishxu.kadb.shell.AdbPtyShellSession
import com.flyfishxu.kadb.shell.AdbShellResponse
import com.flyfishxu.kadb.shell.AdbShellStream
import com.flyfishxu.kadb.stream.AdbStream
import com.flyfishxu.kadb.stream.AdbSyncStream
import com.flyfishxu.kadb.transport.TransportChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import okio.*
import java.io.File
import java.io.EOFException
import java.io.IOException
import kotlin.Throws

@Suppress("unused", "MemberVisibilityCanBePrivate")
class Kadb(
    private val host: String,
    private val port: Int,
    private val connectTimeout: Int = 0,
    private val socketTimeout: Int = 0
) : AutoCloseable {

    private var connection: Pair<AdbConnection, TransportChannel>? = null

    fun connectionCheck(): Boolean = connection?.second?.isOpen == true

    fun open(destination: String): AdbStream {
        return openStream(destination).second
    }

    fun supportsFeature(feature: String): Boolean = connection().supportsFeature(feature)

    fun shell(command: String): AdbShellResponse {
        // AOSP host checks kFeatureShell2 before selecting shell protocol framing.
        // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/client/commandline.cpp#1122
        return if (supportsFeature(SHELL_V2_FEATURE)) {
            openShell(command).use { it.readAll() }
        } else {
            // Legacy shell transport has no protocol-framed stderr/exit packets.
            // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/client/commandline.cpp#595
            val legacyService = buildShellService(command = command, useShellProtocol = false)
            enforceLegacyShellServiceLength(legacyService)
            open(legacyService).use { legacy ->
                val output = legacy.source.readUtf8()
                AdbShellResponse(output = output, errorOutput = "", exitCode = 0)
            }
        }
    }

    fun openShell(command: String = ""): AdbShellStream {
        requireShellV2(apiName = "openShell")
        return AdbShellStream(open(buildShellService(command = command, useShellProtocol = true, typeArg = SHELL_TYPE_RAW)))
    }

    fun openPtyShellSession(
        command: String = "",
        term: String = DEFAULT_TERM_TYPE
    ): AdbPtyShellSession {
        requireShellV2(apiName = "openPtyShellSession")
        // AOSP daemon accepts "v2", "TERM=...", and "pty/raw" shell service args.
        // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/daemon/services.cpp#107
        val service = buildShellService(
            command = command,
            useShellProtocol = true,
            term = term,
            typeArg = SHELL_TYPE_PTY
        )
        return AdbPtyShellSession(AdbShellStream(open(service)))
    }

    fun push(src: File, remotePath: String, mode: Int = readMode(src), lastModifiedMs: Long = src.lastModified()) =
        push(src.source(), remotePath, mode, lastModifiedMs)

    fun push(source: Source, remotePath: String, mode: Int, lastModifiedMs: Long) {
        openSync().use { it.send(source, remotePath, mode, lastModifiedMs) }
    }

    fun pull(dst: File, remotePath: String) = pull(dst.sink(false), remotePath)

    fun pull(sink: Sink, remotePath: String) {
        openSync().use { it.recv(sink, remotePath) }
    }

    fun openSync(): AdbSyncStream {
        val (conn, stream) = openStream("sync:")
        // AOSP sync client behavior is feature-gated by the negotiated CNXN feature set.
        // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/client/file_sync_client.cpp#238
        return AdbSyncStream(stream, conn.featureSnapshot())
    }

    fun install(file: File, vararg options: String) {
        if (supportsFeature("cmd")) {
            install(file.source(), file.length(), *options)
        } else {
            pmInstall(file, *options)
        }
    }

    fun install(source: Source, size: Long, vararg options: String) {
        if (supportsFeature("cmd")) {
            execCmd("package", "install", "-S", size.toString(), *options).use { stream ->
                stream.sink.writeAll(source)
                stream.sink.flush()
                val response = stream.source.readUtf8()
                check(response.startsWith("Success")) { "Install failed: $response" }
            }
        } else {
            val remotePath = "/data/local/tmp/kadb-install-${System.nanoTime()}.apk"
            try {
                push(source, remotePath, mode = 420, lastModifiedMs = System.currentTimeMillis())
                pmInstallRemote(remotePath, *options)
            } finally {
                cleanupRemoteTempFile(remotePath)
            }
        }
    }

    private fun pmInstallRemote(remotePath: String, vararg options: String) {
        // AOSP install command vectors append only explicit args and quote each arg with escape_arg().
        // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/client/adb_install.cpp#563
        // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/adb_utils.cpp#81
        val response = shell(buildShellCommand(listOf("pm", "install") + nonBlankOptions(options) + remotePath))
        check(response.allOutput.startsWith("Success")) { "Install failed: ${response.allOutput}" }
    }

    private fun pmInstall(file: File, vararg options: String) {
        val remotePath = "/data/local/tmp/${file.name}"
        val mode = readMode(file)
        val lastModifiedMs = file.lastModified()
        try {
            push(file, remotePath, mode = mode, lastModifiedMs = lastModifiedMs)
            pmInstallRemote(remotePath, *options)
        } finally {
            cleanupRemoteTempFile(remotePath)
        }
    }

    fun installMultiple(apks: List<File>, vararg options: String) {
        val installOptions = nonBlankOptions(options)
        val sessionMode = installSessionMode()
        val sessionId = createInstallSession(apks.sumOf { it.length() }, installOptions, sessionMode)
        val error = if (sessionMode.usesStreaming) {
            apks.firstNotNullOfOrNull { apk ->
                streamInstallWrite(apk, sessionId, sessionMode).takeIf { !it.startsWith("Success") }
            }
        } else {
            apks.mapIndexedNotNull { index, apk ->
                pushAndWrite(apk, sessionId, index).takeIf { !it.startsWith("Success") }
            }.firstOrNull()
        }
        finalizeSession(sessionId, error, sessionMode)
    }

    fun uninstall(packageName: String) {
        // AOSP routes uninstall through "cmd package uninstall" when streamed/cmd mode is available,
        // and falls back to "pm uninstall" in legacy push mode.
        // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/client/adb_install.cpp#131
        val command = if (supportsFeature("cmd")) {
            listOf("cmd", "package", "uninstall", packageName)
        } else {
            listOf("pm", "uninstall", packageName)
        }
        val response = shell(buildShellCommand(command))
        check(response.exitCode == 0) { "Uninstall failed: ${response.allOutput}" }
    }

    // AOSP non-abb install path opens exec:cmd services and applies shell escaping per argument.
    // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/client/adb_install.cpp#210
    fun execCmd(vararg command: String): AdbStream = open(buildExecCmdService(command.asList()))

    fun abbExec(vararg command: String): AdbStream = open("abb_exec:${command.joinToString("\u0000")}")

    fun root() = restartAdb("root:")

    fun unroot() = restartAdb("unroot:")

    override fun close() {
        connection?.first?.close()
        connection = null
    }

    /**
     * Reset current transport only.
     * The Kadb instance remains reusable and reconnects lazily on next command.
     */
    fun resetConnection() {
        connection?.first?.close()
        connection = null
    }

    private fun connection(): AdbConnection {
        val current = connection
        return if (current == null || !current.second.isOpen) {
            newConnection().also { connection = it }.first
        } else current.first
    }

    private fun newConnection(): Pair<AdbConnection, TransportChannel> {
        return runBlocking {
            AdbConnection.connect(host, port, loadKeySet(), connectTimeout, socketTimeout)
        }
    }

    private fun openStream(
        destination: String,
        retryOnStaleTransport: Boolean = true
    ): Pair<AdbConnection, AdbStream> {
        val conn = connection()
        return try {
            conn to conn.open(destination)
        } catch (t: Throwable) {
            if (!isRecoverableTransportOpenFailure(t)) {
                throw t
            }
            discardConnection(conn)
            if (!retryOnStaleTransport) {
                throw t
            }
            openStream(destination, retryOnStaleTransport = false)
        }
    }

    private fun discardConnection(failedConnection: AdbConnection) {
        val current = connection
        if (current?.first === failedConnection) {
            resetConnection()
        } else {
            runCatching { failedConnection.close() }
        }
    }

    private fun isRecoverableTransportOpenFailure(error: Throwable): Boolean {
        return when (error) {
            is EOFException -> true
            is IOException, is IllegalStateException -> {
                val message = error.message.orEmpty().lowercase()
                OPEN_TRANSPORT_FAILURE_MARKERS.any(message::contains) ||
                    error.cause?.let(::isRecoverableTransportOpenFailure) == true
            }

            else -> error.cause?.let(::isRecoverableTransportOpenFailure) == true
        }
    }

    private fun extractSessionId(response: String) =
        """\[(\w+)]""".toRegex().find(response)?.groupValues?.get(1) ?: throw IOException("Failed to create session")

    private fun installSessionMode(): InstallSessionMode = when {
        supportsFeature("abb_exec") -> InstallSessionMode.ABB_EXEC
        supportsFeature("cmd") -> InstallSessionMode.EXEC_CMD
        else -> InstallSessionMode.PM_SHELL
    }

    private fun createInstallSession(
        totalLength: Long,
        installOptions: List<String>,
        sessionMode: InstallSessionMode
    ): String {
        val response = when (sessionMode) {
            InstallSessionMode.ABB_EXEC, InstallSessionMode.EXEC_CMD -> {
                openInstallCommand(
                    sessionMode,
                    "package",
                    "install-create",
                    "-S",
                    totalLength.toString(),
                    *installOptions.toTypedArray()
                ).use { stream ->
                    stream.source.readUtf8()
                }
            }

            InstallSessionMode.PM_SHELL -> {
                shell(
                    buildShellCommand(
                        listOf("pm", "install-create", "-S", totalLength.toString()) + installOptions
                    )
                ).allOutput
            }
        }
        return extractSessionId(response)
    }

    private fun streamInstallWrite(apk: File, sessionId: String, sessionMode: InstallSessionMode): String {
        return openInstallCommand(
            sessionMode,
            "package",
            "install-write",
            "-S",
            apk.length().toString(),
            sessionId,
            apk.name,
            "-"
        ).use { stream ->
            stream.sink.writeAll(apk.source())
            stream.sink.flush()
            stream.source.readUtf8()
        }
    }

    private fun openInstallCommand(sessionMode: InstallSessionMode, vararg command: String): AdbStream = when (sessionMode) {
        InstallSessionMode.ABB_EXEC -> abbExec(*command)
        InstallSessionMode.EXEC_CMD -> execCmd(*command)
        InstallSessionMode.PM_SHELL -> error("Legacy pm shell path does not open streamed install commands")
    }

    private fun pushAndWrite(apk: File, sessionId: String, index: Int): String {
        val remotePath = "/data/local/tmp/${apk.name}"
        val mode = readMode(apk)
        val lastModifiedMs = apk.lastModified()
        try {
            push(apk, remotePath, mode = mode, lastModifiedMs = lastModifiedMs)
            // Legacy install-write path uses shell command assembly with escape_arg() for each argv token.
            // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/client/adb_install.cpp#570
            return shell(
                buildShellCommand(
                    listOf(
                        "pm",
                        "install-write",
                        "-S",
                        apk.length().toString(),
                        sessionId,
                        index.toString(),
                        remotePath
                    )
                )
            ).allOutput
        } finally {
            cleanupRemoteTempFile(remotePath)
        }
    }

    private fun cleanupRemoteTempFile(remotePath: String) {
        // Best-effort cleanup to match AOSP install_app_legacy() delete_device_file() behavior.
        runCatching { shell(buildDeleteDeviceFileCommand(remotePath)) }
    }

    private fun finalizeSession(sessionId: String, error: String?, sessionMode: InstallSessionMode) {
        val finalCommand = if (error == null) "install-commit" else "install-abandon"
        // AOSP finalizes sessions with install-commit/install-abandon on the same install command family.
        // https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/client/adb_install.cpp#653
        val output = when (sessionMode) {
            InstallSessionMode.ABB_EXEC, InstallSessionMode.EXEC_CMD -> {
                openInstallCommand(sessionMode, "package", finalCommand, sessionId).use { stream ->
                    stream.source.readUtf8()
                }
            }
            InstallSessionMode.PM_SHELL -> {
                shell(buildShellCommand(listOf("pm", finalCommand, sessionId))).allOutput
            }
        }
        check(output.startsWith("Success")) { "Failed to finalize session: $output" }
        error?.let { throw IOException("Install failed: $it") }
    }

    // Shell fallback builds one command string; mirror AOSP argv behavior by dropping empty args first.
    // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/client/adb_install.cpp#563
    private fun nonBlankOptions(options: Array<out String>): List<String> = options.filter { it.isNotBlank() }

    private fun buildShellCommand(parts: List<String>): String =
        parts.filter { it.isNotBlank() }.joinToString(" ") { escapeArg(it) }

    private fun buildExecCmdService(parts: List<String>): String {
        // adb_install.cpp joins cmd_args into one service string; escaped args stay space-delimited.
        // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/client/adb_install.cpp#152
        val escapedArgs = parts.filter { it.isNotBlank() }.joinToString(" ") { escapeArg(it) }
        return if (escapedArgs.isEmpty()) "exec:cmd" else "exec:cmd $escapedArgs"
    }

    private fun buildDeleteDeviceFileCommand(remotePath: String): String {
        // AOSP uses "rm <file> </dev/null" here rather than "rm -f" for old-device compatibility.
        // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/client/adb_install.cpp#983
        return "rm ${escapeArg(remotePath)} </dev/null"
    }

    private fun escapeArg(arg: String): String {
        // Mirror AOSP adb_utils.cpp::escape_arg(): wrap in single quotes and replace ' with '\''.
        // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/adb_utils.cpp#81
        return "'" + arg.replace("'", "'\\''") + "'"
    }

    private enum class InstallSessionMode(val usesStreaming: Boolean) {
        ABB_EXEC(usesStreaming = true),
        EXEC_CMD(usesStreaming = true),
        PM_SHELL(usesStreaming = false)
    }

    private fun enforceLegacyShellServiceLength(service: String) {
        // AOSP rejects legacy shell service strings longer than MAX_PAYLOAD_V1.
        // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/client/commandline.cpp#628
        val size = service.encodeToByteArray().size
        require(size <= AdbProtocol.MAX_PAYLOAD_V1) {
            "error: shell command too long"
        }
    }

    private fun requireShellV2(apiName: String) {
        // AOSP shell protocol mode is selected only when kFeatureShell2 is available.
        // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/client/commandline.cpp#1122
        check(supportsFeature(SHELL_V2_FEATURE)) {
            "$apiName requires peer feature '$SHELL_V2_FEATURE'; use shell(command) for legacy shell fallback."
        }
    }

    private fun buildShellService(
        command: String,
        useShellProtocol: Boolean,
        term: String? = null,
        typeArg: String? = null
    ): String {
        val args = mutableListOf<String>()
        if (useShellProtocol) {
            // AOSP builds service strings as shell[,arg1,arg2,...]:command.
            // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/client/commandline.cpp#611
            // Argument tokens are "v2", "raw", and "pty".
            // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/services.h#24
            args += SHELL_ARG_V2
            if (!term.isNullOrBlank()) {
                args += "$SHELL_ARG_TERM_PREFIX$term"
            }
        }
        if (!typeArg.isNullOrBlank()) {
            args += typeArg
        }
        val prefix = if (args.isEmpty()) SHELL_SERVICE_BASE else "$SHELL_SERVICE_BASE,${args.joinToString(",")}"
        return "$prefix:$command"
    }

    companion object {
        private val OPEN_TRANSPORT_FAILURE_MARKERS = listOf(
            "broken pipe",
            "connection reset",
            "connection aborted",
            "socket closed",
            "stream closed",
            "transport closed",
            "closed",
            "eof",
            "disconnected",
            "connection closed"
        )
        private const val SHELL_V2_FEATURE = "shell_v2"
        private const val SHELL_SERVICE_BASE = "shell"
        private const val SHELL_ARG_V2 = "v2"
        private const val SHELL_ARG_TERM_PREFIX = "TERM="
        private const val SHELL_TYPE_RAW = "raw"
        private const val SHELL_TYPE_PTY = "pty"
        private const val DEFAULT_TERM_TYPE = "xterm-256color"

        suspend fun pair(host: String, port: Int, pairingCode: String, name: String = defaultDeviceName()) =
            withContext(Dispatchers.Default) {
                PairingConnectionCtx(host, port, pairingCode.toByteArray(), loadKeyPair(), name).use { it.start() }
            }

        fun create(
            host: String,
            port: Int,
            connectTimeout: Int = 0,
            socketTimeout: Int = 0
        ): Kadb = Kadb(host, port, connectTimeout, socketTimeout)

        fun tryConnection(host: String, port: Int) = runCatching {
            val kadb = create(host, port)
            val ok = runCatching { kadb.shell("echo success").allOutput == "success\n" }
                .getOrElse { error ->
                    kadb.close()
                    throw error
                }
            if (ok) kadb else null.also { kadb.close() }
        }.getOrNull()

        fun tcpForward(host: String, port: Int, targetPort: Int) = Kadb(host, port).tcpForward(port, targetPort)
    }

    @Throws(InterruptedException::class)
    fun tcpForward(hostPort: Int, targetPort: Int): AutoCloseable {
        val forwarder = TcpForwarder(this, hostPort, targetPort)
        forwarder.start()
        return forwarder
    }

    private fun restartAdb(destination: String): String {
        this.open(destination).use { stream ->
            return stream.source.readUntil('\n'.code.toByte()).readString(Charsets.UTF_8)
        }
    }
}

private fun BufferedSource.readUntil(endByte: Byte): Buffer {
    val buffer = Buffer()
    while (true) {
        val b = readByte()
        buffer.writeByte(b.toInt())
        if (b == endByte) return buffer
    }
}

internal expect fun Kadb.readMode(file: File): Int
