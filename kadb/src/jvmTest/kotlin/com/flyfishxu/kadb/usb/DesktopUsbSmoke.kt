package com.flyfishxu.kadb.usb

import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbPrivateKeyStore
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import okio.Buffer
import okio.Path.Companion.toOkioPath
import java.io.File
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Opt-in hardware harness. Ordinary jvmTest never accesses USB hardware. */
object DesktopUsbSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val devices = KadbUsb.devices()
        devices.forEach { println("USB: $it") }
        val serial = args.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return
        val device = devices.single { it.serialNumber == serial }
        val local = File("build/usb-smoke").apply { mkdirs() }
        val existingKey = args.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(::File)
        if (existingKey != null) {
            check(existingKey.isFile)
            // Reuse the explicitly supplied host identity without modifying its key file.
            KadbCert.configure(object : KadbPrivateKeyStore {
                override fun readPrivateKeyPem() = existingKey.readBytes()
                override fun writePrivateKeyPemAtomic(privateKeyPem: ByteArray) = error("Test identity is read-only")
                override fun clear() = error("Test identity is read-only")
            })
        } else {
            KadbCert.configure(OkioFilePrivateKeyStore(File(local, "adbkey").toOkioPath()))
        }
        var client = KadbUsb.create(device)
        val worker = Executors.newSingleThreadExecutor()
        val remote = "/data/local/tmp/kadb-desktop-usb-${System.currentTimeMillis()}"
        var created = false
        var primaryFailure: Throwable? = null
        fun step(name: String, seconds: Long = 30, action: () -> Unit) {
            println("RUN $name")
            val result = worker.submit(Callable { action() })
            try { result.get(seconds, TimeUnit.SECONDS); println("PASS $name") }
            catch (error: Throwable) { runCatching { client.close() }; result.cancel(true); throw error }
        }
        try {
            step("identity and shell stdout/stderr/exit") {
                check(client.shell("getprop ro.serialno").output.trim() == serial)
                val result = client.shell("printf 'USB-中文'; printf 'stderr-ok' >&2; exit 7")
                check(result.output == "USB-中文" && result.errorOutput == "stderr-ok" && result.exitCode == 7)
                check(client.shell("mkdir '$remote'").exitCode == 0)
                created = true
            }
            step("interactive stdin and EOF") {
                client.openShell("cat").use {
                    it.write("USB stdin 中文\n"); it.closeStdin()
                    val result = it.readAll()
                    check(result.output == "USB stdin 中文\n" && result.exitCode == 0)
                }
            }
            step("binary exec") {
                client.open("exec:head -c 1048576 /dev/zero").use {
                    val bytes = it.source.readByteArray()
                    check(bytes.size == 1048576 && bytes.all { byte -> byte == 0.toByte() })
                }
            }
            step("12 sync boundaries, stat/list, SHA-256", 120) {
                val sizes = listOf(0, 1, 511, 512, 513, 16383, 16384, 16385, 65535, 65536, 65537, 1048607)
                sizes.forEach { size ->
                    val bytes = ByteArray(size).also { Random(size.toLong()).nextBytes(it) }
                    val path = "$remote/boundary-$size"
                    client.push(Buffer().write(bytes), path, 0x81A4, System.currentTimeMillis())
                    val received = Buffer(); client.pull(received, path)
                    check(hash(bytes) == hash(received.readByteArray())) { "Corruption at size $size" }
                    client.openSync().use { check(it.lstat(path).size == size.toLong()) }
                }
                client.openSync().use { check(it.list(remote).count { it.name.startsWith("boundary-") } == sizes.size) }
            }
            step("32 MiB push/pull and three-way SHA-256", 180) {
                val bytes = ByteArray(32 * 1024 * 1024).also { Random(20260929).nextBytes(it) }
                val expected = hash(bytes)
                val start = System.nanoTime()
                client.push(Buffer().write(bytes), "$remote/large.bin", 0x81A4, System.currentTimeMillis())
                val pushed = System.nanoTime()
                val remoteHash = client.shell("sha256sum '$remote/large.bin'")
                check(remoteHash.exitCode == 0 && remoteHash.output.substringBefore(' ') == expected)
                val received = Buffer(); val pullStart = System.nanoTime()
                client.pull(received, "$remote/large.bin")
                val pulled = System.nanoTime()
                check(hash(received.readByteArray()) == expected)
                println("32 MiB push=${(pushed-start)/1e9}s pull=${(pulled-pullStart)/1e9}s sha256=$expected")
            }
            step("three concurrent 4 MiB transfers and 20 shell probes", 180) {
                val pool = Executors.newFixedThreadPool(4); val gate = CountDownLatch(1)
                try {
                    val jobs = (0..2).map { index -> pool.submit(Callable {
                        gate.await()
                        val bytes = ByteArray(4 * 1024 * 1024 + index).also { Random(index.toLong()).nextBytes(it) }
                        val path = "$remote/concurrent-$index"
                        client.push(Buffer().write(bytes), path, 0x81A4, System.currentTimeMillis())
                        val received = Buffer(); client.pull(received, path)
                        check(hash(bytes) == hash(received.readByteArray()))
                    }) } + pool.submit(Callable {
                        gate.await(); repeat(20) { check(client.shell("echo probe-$it").output.trim() == "probe-$it") }
                    })
                    gate.countDown(); jobs.forEach { it.get(150, TimeUnit.SECONDS) }
                } finally { pool.shutdownNow() }
            }
            step("missing-file error preserves connection") {
                check(runCatching { client.pull(Buffer(), "$remote/missing") }.isFailure)
                check(client.shell("echo alive").output.trim() == "alive")
            }
            step("blocked stream cancellation and close/reclaim", 60) {
                val reader = Executors.newSingleThreadExecutor()
                try {
                    for (closeClient in listOf(false, true)) {
                        val stream = client.openShell("echo ready; sleep 30")
                        stream.read()
                        val ready = CountDownLatch(1)
                        val blocked = reader.submit(Callable { ready.countDown(); runCatching { stream.readAll() } })
                        check(ready.await(2, TimeUnit.SECONDS))
                        if (closeClient) client.close() else stream.close()
                        blocked.get(5, TimeUnit.SECONDS)
                        runCatching { stream.close() }
                        if (closeClient) client = KadbUsb.create(device)
                        check(client.shell("echo alive").output.trim() == "alive")
                    }
                    repeat(20) {
                        println("RECONNECT ${it + 1}/20 connected=${device.isConnected}")
                        client.close(); client = KadbUsb.create(device)
                        check(client.shell("echo reopen-$it").output.trim() == "reopen-$it")
                    }
                } finally { reader.shutdownNow() }
            }
            args.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { apk ->
                val fixturePackage = "com.flyfishxu.kadb.usb.smoke.fixture"
                var absentBeforeTest = false
                try {
                    step("fixture APK install", 90) {
                        check(client.shell("pm path $fixturePackage").output.isBlank())
                        absentBeforeTest = true
                        client.install(File(apk), "-t")
                        check(client.shell("pm path $fixturePackage").output.trim().startsWith("package:"))
                    }
                } finally {
                    if (absentBeforeTest) step("fixture APK cleanup") {
                        if (!client.connectionCheck()) client = KadbUsb.create(device)
                        client.shell("pm uninstall $fixturePackage")
                        check(client.shell("pm path $fixturePackage").output.isBlank())
                    }
                }
            }
            println("ALL DESKTOP USB CHECKS PASSED")
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            try {
                if (created) step("remote test files cleanup") {
                    if (!client.connectionCheck()) client = KadbUsb.create(device)
                    check(client.shell("rm -rf '$remote'").exitCode == 0)
                }
            } catch (cleanupError: Throwable) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupError) else throw cleanupError
            } finally { runCatching { client.close() }; worker.shutdownNow() }
        }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
