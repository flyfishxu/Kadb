package com.flyfishxu.kadb

import com.flyfishxu.kadb.cert.CertUtils.loadKeyPair
import com.flyfishxu.kadb.cert.platform.defaultDeviceName
import com.flyfishxu.kadb.core.AdbConnection
import com.flyfishxu.kadb.forwarding.TcpForwarder
import com.flyfishxu.kadb.pair.PairingConnectionCtx
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

    fun shell(command: String): AdbShellResponse = openShell(command).use { it.readAll() }

    fun openShell(command: String = ""): AdbShellStream = AdbShellStream(open("shell,v2,raw:$command"))

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
        shell("pm install ${options.joinToString(" ")} \"$remotePath\"")
    }

    fun installMultiple(apks: List<File>, vararg options: String) {
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
                finalizeSession(sessionId, error, *options)
            }
        } else {
            val response = shell("pm install-create -S ${apks.sumOf { it.length() }} ${options.joinToString(" ")}")
            val sessionId = extractSessionId(response.allOutput)
            val error = apks.mapIndexedNotNull { index, apk ->
                pushAndWrite(apk, sessionId, index).takeIf { !it.startsWith("Success") }
            }.firstOrNull()
            finalizeSession(sessionId, error)
        }
    }

    fun uninstall(packageName: String) {
        val response = shell("cmd package uninstall $packageName")
        check(response.exitCode == 0) { "Uninstall failed: ${response.allOutput}" }
    }

    fun execCmd(vararg command: String): AdbStream = open("exec:cmd ${command.joinToString(" ")}")

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
        return shell("pm install-write -S ${apk.length()} $sessionId $index /data/local/tmp/${apk.name}").allOutput
    }

    private fun finalizeSession(sessionId: String, error: String?, vararg options: String) {
        val finalCommand = if (error == null) "install-commit" else "install-abandon"
        shell("pm $finalCommand $sessionId ${options.joinToString(" ")}").apply {
            check(allOutput.startsWith("Success")) { "Failed to finalize session: $allOutput" }
            error?.let { throw IOException("Install failed: $it") }
        }
    }

    companion object {
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
            create(host, port).takeIf { it.shell("echo success").allOutput == "success\n" }
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