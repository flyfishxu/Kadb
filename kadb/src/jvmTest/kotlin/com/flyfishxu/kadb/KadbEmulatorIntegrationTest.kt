package com.flyfishxu.kadb

import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import com.flyfishxu.kadb.shell.AdbShellPacket
import okio.Path.Companion.toPath
import okio.source
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KadbEmulatorIntegrationTest {
    private val enabled = System.getenv("KADB_EMULATOR_TESTS") == "1"
    private val host = System.getenv("KADB_TEST_HOST") ?: "127.0.0.1"
    private val port = System.getenv("KADB_TEST_PORT")?.toIntOrNull() ?: 15555

    @Test
    fun connectionShellInteractiveShellAndPty() = withEmulator {
        newClient().use { kadb ->
            assertFalse(kadb.connectionCheck())

            val response = kadb.shell("printf stdout; printf stderr >&2; exit 7")
            assertEquals("stdout", response.output)
            assertEquals("stderr", response.errorOutput)
            assertEquals(7, response.exitCode)
            assertTrue(kadb.connectionCheck())
            assertTrue(kadb.supportsFeature("shell_v2"))

            kadb.openShell("cat").use { shell ->
                shell.write("interactive-shell\n")
                shell.closeStdin()
                assertEquals("interactive-shell\n", shell.readAll().output)
            }

            kadb.openPtyShellSession("cat").use { pty ->
                pty.resize(rows = 40, cols = 120)
                pty.write("pty-shell\n")
                pty.closeStdin()
                val stdout = StringBuilder()
                while (true) {
                    when (val packet = pty.read()) {
                        is AdbShellPacket.StdOut -> stdout.append(packet.payload.decodeToString())
                        is AdbShellPacket.StdError -> Unit
                        is AdbShellPacket.Exit -> break
                    }
                }
                assertTrue(stdout.contains("pty-shell"))
            }

            kadb.resetConnection()
            assertFalse(kadb.connectionCheck())
            assertEquals("reconnected\n", kadb.shell("echo reconnected").output)
        }

        assertNotNull(Kadb.tryConnection(host, port, enabledOptions())).close()
    }

    @Test
    fun syncMetadataAndLargeFileRoundTripWithClassicAndDelayedAcks() = withEmulator {
        val tempDir = createTempDirectory("kadb-large-transfer").toFile()
        try {
            val source = File(tempDir, "source.bin")
            val pulled = File(tempDir, "pulled.bin")
            createPatternFile(source, largeFileBytes())
            val expectedDigest = sha256(source)

            for (mode in listOf(DelayedAckMode.DISABLED, DelayedAckMode.ENABLED)) {
                val remote = "/data/local/tmp/kadb-${mode.name.lowercase()}.bin"
                Kadb.create(
                    host = host,
                    port = port,
                    connectTimeout = 10_000,
                    socketTimeout = 60_000,
                    options = KadbOptions(mode)
                ).use { kadb ->
                    val pushStart = System.nanoTime()
                    kadb.push(source, remote)
                    val pushSeconds = elapsedSeconds(pushStart)

                    kadb.openSync().use { sync ->
                        val stat = sync.lstat(remote)
                        assertEquals(source.length(), stat.size)
                        assertTrue(sync.list("/data/local/tmp").any { it.name == File(remote).name })
                        if (kadb.supportsFeature("stat_v2")) {
                            assertEquals(source.length(), sync.statV2(remote).size)
                        }
                    }

                    val deviceDigest = kadb.shell("sha256sum '$remote' | cut -d' ' -f1").output.trim()
                    assertEquals(expectedDigest, deviceDigest)

                    val pullStart = System.nanoTime()
                    kadb.pull(pulled, remote)
                    val pullSeconds = elapsedSeconds(pullStart)
                    assertEquals(expectedDigest, sha256(pulled))

                    println(
                        "Kadb ${mode.name}: push=${mbps(source.length(), pushSeconds)} MiB/s, " +
                            "pull=${mbps(source.length(), pullSeconds)} MiB/s, bytes=${source.length()}"
                    )
                    kadb.shell("rm -f '$remote'")
                }
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun streamedFileAndSplitSessionInstallThenUninstall() = withEmulator {
        val apk = File(requireEnv("KADB_TEST_APK"))
        val packageName = requireEnv("KADB_TEST_PACKAGE")
        assertTrue(apk.isFile)

        newClient().use { kadb ->
            kadb.install(apk, "-r")
            assertPackageInstalled(kadb, packageName)

            apk.source().use { source ->
                kadb.install(source, apk.length(), "-r")
            }
            assertPackageInstalled(kadb, packageName)

            kadb.installMultiple(listOf(apk), "-r")
            assertPackageInstalled(kadb, packageName)

            val execOutput = kadb.execCmd("package", "path", packageName).use { it.source.readUtf8() }
            assertTrue(execOutput.contains("package:"))

            if (kadb.supportsFeature("abb_exec")) {
                val abbOutput = kadb.abbExec("package", "path", packageName).use { it.source.readUtf8() }
                assertTrue(abbOutput.contains("package:"))
            }

            kadb.uninstall(packageName)
            assertFalse(kadb.shell("pm path '$packageName'").output.contains("package:"))
        }
    }

    @Test
    fun tcpForwardCarriesBidirectionalAdbTraffic() = withEmulator {
        val targetPort = System.getenv("KADB_TEST_DEVICE_ADBD_PORT")?.toIntOrNull() ?: 5555
        val hostPort = ServerSocket(0).use { it.localPort }

        newClient().use { kadb ->
            kadb.tcpForward(hostPort, targetPort).use {
                Socket("127.0.0.1", hostPort).use { socket ->
                    socket.soTimeout = 10_000
                    socket.getOutputStream().write(connectPacket())
                    socket.getOutputStream().flush()

                    val header = socket.getInputStream().readNBytes(24)
                    assertEquals(24, header.size)
                    val command = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
                    assertTrue(command == CMD_AUTH || command == CMD_CNXN)
                }
            }
        }
    }

    @Test
    fun emptyInstallSetIsRejectedBeforeConnecting() = withEmulator {
        newClient().use { kadb ->
            val error = runCatching { kadb.installMultiple(emptyList()) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
        }
    }

    private fun newClient(): Kadb = Kadb.create(
        host = host,
        port = port,
        connectTimeout = 10_000,
        socketTimeout = 60_000,
        options = enabledOptions()
    )

    private fun enabledOptions() = KadbOptions(DelayedAckMode.ENABLED)

    private fun withEmulator(block: () -> Unit) {
        if (!enabled) return
        configureAuthorizedIdentity()
        block()
    }

    private fun configureAuthorizedIdentity() {
        synchronized(identityLock) {
            val keyPath = requireEnv("KADB_TEST_ADBKEY").toPath()
            KadbCert.configure(OkioFilePrivateKeyStore(keyPath))
            KadbCert.ensureReady()
        }
    }

    private fun assertPackageInstalled(kadb: Kadb, packageName: String) {
        assertTrue(kadb.shell("pm path '$packageName'").output.contains("package:"))
    }

    private fun createPatternFile(file: File, byteCount: Long) {
        val block = ByteArray(1024 * 1024) { index -> ((index * 31 + 17) and 0xff).toByte() }
        file.outputStream().buffered().use { output ->
            var remaining = byteCount
            while (remaining > 0) {
                val count = minOf(block.size.toLong(), remaining).toInt()
                output.write(block, 0, count)
                remaining -= count
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun connectPacket(): ByteArray {
        val payload = "host::features=shell_v2".encodeToByteArray()
        val checksum = payload.sumOf { it.toUByte().toInt() }
        return ByteBuffer.allocate(24 + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(CMD_CNXN)
            .putInt(0x01000001)
            .putInt(1024 * 1024)
            .putInt(payload.size)
            .putInt(checksum)
            .putInt(CMD_CNXN.inv())
            .put(payload)
            .array()
    }

    private fun largeFileBytes(): Long =
        System.getenv("KADB_LARGE_FILE_BYTES")?.toLongOrNull() ?: 64L * 1024 * 1024

    private fun elapsedSeconds(startNanos: Long): Double =
        (System.nanoTime() - startNanos) / 1_000_000_000.0

    private fun mbps(bytes: Long, seconds: Double): String =
        "%.2f".format((bytes / 1024.0 / 1024.0) / seconds)

    private fun requireEnv(name: String): String =
        requireNotNull(System.getenv(name)) { "Missing environment variable: $name" }

    private companion object {
        val identityLock = Any()
        const val CMD_AUTH = 0x48545541
        const val CMD_CNXN = 0x4e584e43
    }
}
