/*
 * Copyright (c) 2021 mobile.dev inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
/*
 * Copyright (c) 2024 Flyfish-Xu
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.flyfishxu.kadb

import android.content.Context
import android.os.Build
import androidx.annotation.WorkerThread
import androidx.documentfile.provider.DocumentFile
import com.flyfishxu.kadb.AdbKeyPair.Companion.getDeviceName
import com.flyfishxu.kadb.AdbKeyPair.Companion.read
import com.flyfishxu.kadb.adbserver.AdbServer
import com.flyfishxu.kadb.forwarding.TcpForwarder
import com.flyfishxu.kadb.pair.PairingConnectionCtx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.*
import java.io.File
import java.io.IOException
import java.nio.file.Files

interface Kadb : AutoCloseable {

    @Throws(IOException::class)
    fun open(destination: String): AdbStream

    fun supportsFeature(feature: String): Boolean

    @Throws(IOException::class)
    fun shell(command: String): AdbShellResponse {
        openShell(command).use { stream ->
            return stream.readAll()
        }
    }

    @Throws(IOException::class)
    fun openShell(command: String = ""): AdbShellStream {
        val stream = open("shell,v2,raw:$command")
        return AdbShellStream(stream)
    }

    @Throws(IOException::class)
    fun push(
        src: DocumentFile,
        remotePath: String,
        context: Context,
        mode: Int = readMode(src),
        lastModifiedMs: Long = src.lastModified()
    ) {
        val inputStream = context.contentResolver.openInputStream(src.uri)
        checkNotNull(inputStream)
        val source = inputStream.source()
        push(source, remotePath, mode, lastModifiedMs)
        inputStream.close()
    }

    @Throws(IOException::class)
    fun push(
        src: File,
        remotePath: String,
        mode: Int = readMode(src),
        lastModifiedMs: Long = src.lastModified()
    ) {
        push(src.source(), remotePath, mode, lastModifiedMs)
    }

    @Throws(IOException::class)
    fun push(source: Source, remotePath: String, mode: Int, lastModifiedMs: Long) {
        openSync().use { stream ->
            stream.send(source, remotePath, mode, lastModifiedMs)
        }
    }

    @Throws(IOException::class)
    fun pull(
        dst: DocumentFile,
        remotePath: String,
        context: Context
    ) {
        val outputStream = context.contentResolver.openOutputStream(dst.uri)
        checkNotNull(outputStream)
        val sink = outputStream.sink()
        pull(sink, remotePath)
        outputStream.close()
    }

    @Throws(IOException::class)
    fun pull(dst: File, remotePath: String) {
        pull(dst.sink(append = false), remotePath)
    }

    @Throws(IOException::class)
    fun pull(sink: Sink, remotePath: String) {
        openSync().use { stream ->
            stream.recv(sink, remotePath)
        }
    }

    @Throws(IOException::class)
    fun openSync(): AdbSyncStream {
        val stream = open("sync:")
        return AdbSyncStream(stream)
    }



    @Throws(IOException::class)
    fun install(file: File, vararg options: String) {
        if (supportsFeature("cmd")) {
            install(file.source(), file.length(), *options)
        } else {
            pmInstall(file, *options)
        }
    }

    @Throws(IOException::class)
    fun install(source: Source, size: Long, vararg options: String) {
        if (supportsFeature("cmd")) {
            execCmd("package", "install", "-S", size.toString(), *options).use { stream ->
                stream.sink.writeAll(source)
                stream.sink.flush()
                val response = stream.source.readString(Charsets.UTF_8)
                if (!response.startsWith("Success")) {
                    throw IOException("Install failed: $response")
                }
            }
        } else {
            val tempFile = kotlin.io.path.createTempFile()
            val fileSink = tempFile.sink().buffer()
            fileSink.writeAll(source)
            fileSink.flush()
            pmInstall(tempFile.toFile(), *options)
        }
    }

    private fun pmInstall(file: File, vararg options: String) {
        val fileName = file.name
        val remotePath = "/data/local/tmp/$fileName"
        push(file, remotePath)
        shell("pm install ${options.joinToString(" ")} \"$remotePath\"")
    }

    @Throws(IOException::class)
    fun installMultiple(apks: List<File>, vararg options: String) {
        // http://aospxref.com/android-12.0.0_r3/xref/packages/modules/adb/client/adb_install.cpp#538
        if (supportsFeature("abb_exec")) {
            val totalLength = apks.map { it.length() }.reduce { acc, l -> acc + l }
            abbExec(
                "package", "install-create", "-S", totalLength.toString(), *options
            ).use { createStream ->
                val response = createStream.source.readString(Charsets.UTF_8)
                if (!response.startsWith("Success")) {
                    throw IOException("connect error for create: $response")
                }
                val pattern = """\[(\w+)]""".toRegex()
                val sessionId = pattern.find(response)?.groups?.get(1)?.value
                    ?: throw IOException("failed to create session")

                var error: String? = null
                apks.forEach { apk ->
                    // install write every apk file to stream
                    abbExec(
                        "package",
                        "install-write",
                        "-S",
                        apk.length().toString(),
                        sessionId,
                        apk.name,
                        "-",
                        *options
                    ).use { writeStream ->
                        writeStream.sink.writeAll(apk.source())
                        writeStream.sink.flush()

                        val writeResponse = writeStream.source.readString(Charsets.UTF_8)
                        if (!writeResponse.startsWith("Success")) {
                            error = writeResponse
                            return@forEach
                        }
                    }
                }

                // commit the session
                val finalCommand = if (error == null) "install-commit" else "install-abandon"
                abbExec("package", finalCommand, sessionId, *options).use { commitStream ->
                    val finalResponse = commitStream.source.readString(Charsets.UTF_8)
                    if (!finalResponse.startsWith("Success")) {
                        throw IOException("failed to finalize session: $commitStream")
                    }
                }

                if (error != null) {
                    throw IOException("Install failed: $error")
                }
            }
        } else {
            val totalLength = apks.map { it.length() }.reduce { acc, l -> acc + l }
            // step1: create session
            val response = shell("pm install-create -S $totalLength ${options.joinToString(" ")}")
            if (!response.allOutput.startsWith("Success")) {
                throw IOException("pm create session failed: $response")
            }

            val pattern = """\[(\w+)]""".toRegex()
            val sessionId = pattern.find(response.allOutput)?.groups?.get(1)?.value
                ?: throw IOException("failed to create session")
            var error: String? = null

            val fileNames = apks.map { it.name }
            val remotePaths = fileNames.map { "/data/local/tmp/$it" }

            // step2: write apk to the session
            apks.zip(remotePaths).forEachIndexed { index, pair ->
                val apk = pair.first
                val remotePath = pair.second

                try {
                    // we should push the apk files to device, when push failed, it would stop the installation
                    push(apk, remotePath)
                } catch (t: IOException) {
                    error = t.message
                    return@forEachIndexed
                }

                // pm install-write -S APK_SIZE SESSION_ID INDEX PATH
                val writeResponse =
                    shell("pm install-write -S ${apk.length()} $sessionId $index $remotePath")
                if (!writeResponse.allOutput.startsWith("Success")) {
                    error = writeResponse.allOutput
                    return@forEachIndexed
                }
            }

            // step3: commit or abandon the session
            val finalCommand =
                if (error == null) "pm install-commit $sessionId" else "pm install-abandon $sessionId"
            val finalResponse = shell(finalCommand)
            if (!finalResponse.allOutput.startsWith("Success")) {
                throw IOException("failed to finalize session: $finalResponse")
            }
            if (error != null) {
                throw IOException("Install failed: $error")
            }
        }
    }

    @Throws(IOException::class)
    fun uninstall(packageName: String) {
        val response = shell("cmd package uninstall $packageName")
        if (response.exitCode != 0) {
            throw IOException("Uninstall failed: ${response.allOutput}")
        }
    }

    @Throws(IOException::class)
    fun execCmd(vararg command: String): AdbStream {
        if (!supportsFeature("cmd")) throw UnsupportedOperationException("cmd is not supported on this version of Android")
        val destination = (listOf("exec:cmd") + command).joinToString(" ")
        return open(destination)
    }

    @Throws(IOException::class)
    fun abbExec(vararg command: String): AdbStream {
        if (!supportsFeature("abb_exec")) throw UnsupportedOperationException("abb_exec is not supported on this version of Android")
        val destination = "abb_exec:${command.joinToString("\u0000")}"
        return open(destination)
    }

    @Throws(IOException::class)
    fun root() {
        val response = restartAdb(this, "root:")
        if (!response.startsWith("restarting") && !response.contains("already")) {
            throw IOException("Failed to restart adb as root: $response")
        }
        waitRootOrClose(this, root = true)
    }

    @Throws(IOException::class)
    fun unroot() {
        val response = restartAdb(this, "unroot:")
        if (!response.startsWith("restarting") && !response.contains("not running as root")) {
            throw IOException("Failed to restart adb as root: $response")
        }
        waitRootOrClose(this, root = false)
    }

    @Throws(InterruptedException::class)
    fun tcpForward(hostPort: Int, targetPort: Int): AutoCloseable {
        val forwarder = TcpForwarder(this, hostPort, targetPort)
        forwarder.start()

        return forwarder
    }

    companion object {

        private const val MIN_EMULATOR_PORT = 5555
        private const val MAX_EMULATOR_PORT = 5683

        /**
         * Pair with an ADB daemon given host address, port number and pairing code.
         *
         * @param host        Host address to use instead of taking it from the
         * @param port        Port number
         * @param pairingCode The six-digit pairing code as string
         * @return `true` if the pairing is successful and `false` otherwise.
         * @throws Exception If pairing failed for some reason.
         */
        @WorkerThread
        @Throws(Exception::class)
        suspend fun pair(
            host: String,
            port: Int,
            pairingCode: String,
        ) = withContext(Dispatchers.Default) {
            val keyPair = read()
            PairingConnectionCtx(
                host,
                port,
                pairingCode.toByteArray(Charsets.UTF_8),
                keyPair,
                getDeviceName()
            ).use { pairingClient ->
                pairingClient.start()
            }
        }

        @JvmStatic
        @JvmOverloads
        fun create(host: String, port: Int, keyPair: AdbKeyPair? = read()): Kadb =
            KadbImpl(host, port, keyPair)

        @JvmStatic
        @JvmOverloads
        fun discover(
            host: String = "localhost", keyPair: AdbKeyPair? = read()
        ): Kadb? {
            return list(host, keyPair).firstOrNull()
        }

        @JvmStatic
        @JvmOverloads
        fun list(
            host: String = "localhost", keyPair: AdbKeyPair? = read()
        ): List<Kadb> {
            val kadbs = AdbServer.listKadbs(adbServerHost = host)
            if (kadbs.isNotEmpty()) return kadbs

            return (MIN_EMULATOR_PORT..MAX_EMULATOR_PORT).mapNotNull { port ->
                val kadb = create(host, port, keyPair)
                val response = try {
                    kadb.shell("echo success").allOutput
                } catch (ignore: Throwable) {
                    null
                }
                if (response == "success\n") {
                    kadb
                } else {
                    null
                }
            }
        }

        private fun waitRootOrClose(kadb: Kadb, root: Boolean) {
            while (true) {
                try {
                    val response = kadb.shell("getprop service.adb.root")
                    val propValue = if (root) 1 else 0
                    if (response.output == "$propValue\n") return
                } catch (e: IOException) {
                    return
                }
            }
        }

        private fun restartAdb(kadb: Kadb, destination: String): String {
            kadb.open(destination).use { stream ->
                return stream.source.readUntil('\n'.code.toByte()).readString(Charsets.UTF_8)
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

        private fun readMode(file: File): Int {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Files.getAttribute(file.toPath(), "unix:mode") as? Int ?: throw RuntimeException(
                    "Unable to read file mode"
                )
            } else {
                var mode = 0
                if (file.canRead()) {
                    mode = mode or 400
                }
                if (file.canWrite()) {
                    mode = mode or 200
                }
                if (file.canExecute()) {
                    mode = mode or 100
                }
                mode
            }
        }

        private fun readMode(file: DocumentFile): Int {
            return if (file.canRead()) 400
            else if (file.canWrite()) 200
            else 0
        }

    }
}
