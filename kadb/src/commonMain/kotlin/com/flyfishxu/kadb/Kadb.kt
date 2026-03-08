package com.flyfishxu.kadb

import com.flyfishxu.kadb.cert.CertUtils.loadKeyPair
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
        val conn = connection ?: newConnection().also { connection = it }
        return conn.first.open(destination)
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

    fun openSync(): AdbSyncStream = AdbSyncStream(open("sync:"))

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
            val tempFile = kotlin.io.path.createTempFile().also {
                it.sink().buffer().apply { writeAll(source); flush() }
            }
            pmInstall(tempFile.toFile(), *options)
        }
    }

    private fun pmInstall(file: File, vararg options: String) {
        val remotePath = "/data/local/tmp/${file.name}"
        push(file, remotePath)
        // AOSP install command vectors append only explicit args and quote each arg with escape_arg().
        // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/client/adb_install.cpp#563
        // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/adb_utils.cpp#81
        val response = shell(buildShellCommand(listOf("pm", "install") + nonBlankOptions(options) + remotePath))
        check(response.allOutput.startsWith("Success")) { "Install failed: ${response.allOutput}" }
    }

    fun installMultiple(apks: List<File>, vararg options: String) {
        // AOSP uses "package" when abb_exec is supported; otherwise falls back to shell command paths.
        // https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/client/adb_install.cpp#558
        if (supportsFeature("abb_exec")) {
            val totalLength = apks.sumOf { it.length() }
            abbExec("package", "install-create", "-S", totalLength.toString(), *options).use { createStream ->
                val sessionId = extractSessionId(createStream.source.readUtf8())
                val error = apks.firstNotNullOfOrNull { apk ->
                    abbExec(
                        "package", "install-write", "-S", apk.length().toString(), sessionId, apk.name, "-", *options
                    ).use { adbStream ->
                        adbStream.sink.writeAll(apk.source()); adbStream.sink.flush(); adbStream.source.readUtf8()
                        .takeIf { !it.startsWith("Success") }
                    }
                }
                finalizeSession(sessionId, error, useAbbExec = true, *options)
            }
        } else {
            val response = shell(
                buildShellCommand(
                    listOf("pm", "install-create", "-S", apks.sumOf { it.length() }.toString()) + nonBlankOptions(options)
                )
            )
            val sessionId = extractSessionId(response.allOutput)
            val error = apks.mapIndexedNotNull { index, apk ->
                pushAndWrite(apk, sessionId, index).takeIf { !it.startsWith("Success") }
            }.firstOrNull()
            finalizeSession(sessionId, error, useAbbExec = false)
        }
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

    private fun connection(): AdbConnection {
        val current = connection
        return if (current == null || !current.second.isOpen) {
            newConnection().also { connection = it }.first
        } else current.first
    }

    private fun newConnection(): Pair<AdbConnection, TransportChannel> {
        var attempt = 0
        while (true) {
            attempt++
            try {
                val result = runBlocking {
                    AdbConnection.connect(host, port, loadKeyPair(), connectTimeout, socketTimeout)
                }
                return result
            } catch (e: Exception) {
                println("CONNECT LOST; TRYING TO REBUILD SOCKET $attempt TIMES")
                if (attempt >= 5) throw e
                Thread.sleep(300)
            }
        }
    }

    private fun extractSessionId(response: String) =
        """\[(\w+)]""".toRegex().find(response)?.groupValues?.get(1) ?: throw IOException("Failed to create session")

    private fun pushAndWrite(apk: File, sessionId: String, index: Int): String {
        push(apk, "/data/local/tmp/${apk.name}")
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
                    "/data/local/tmp/${apk.name}"
                )
            )
        ).allOutput
    }

    private fun finalizeSession(sessionId: String, error: String?, useAbbExec: Boolean, vararg options: String) {
        val finalCommand = if (error == null) "install-commit" else "install-abandon"
        // AOSP finalizes sessions with install-commit/install-abandon on the same install command family.
        // https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/client/adb_install.cpp#653
        val output = if (useAbbExec) {
            // abb_exec uses NUL-delimited command arguments in the service string.
            // https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/client/commandline.h#142
            abbExec("package", finalCommand, sessionId, *options).use { stream ->
                stream.source.readUtf8()
            }
        } else {
            shell(buildShellCommand(listOf("pm", finalCommand, sessionId) + nonBlankOptions(options))).allOutput
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

    private fun escapeArg(arg: String): String {
        // Mirror AOSP adb_utils.cpp::escape_arg(): wrap in single quotes and replace ' with '\''.
        // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/adb_utils.cpp#81
        return "'" + arg.replace("'", "'\\''") + "'"
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
