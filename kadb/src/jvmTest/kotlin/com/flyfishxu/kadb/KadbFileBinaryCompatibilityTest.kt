package com.flyfishxu.kadb

import java.io.File
import java.io.IOException
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import okio.Path.Companion.toOkioPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KadbFileBinaryCompatibilityTest {
    @Test
    fun apkMetadataFollowsSymlinksAndForwardingKeepsCheckedException() {
        val directory = Files.createTempDirectory("kadb-file-link-")
        val target = directory.resolve("target.apk")
        val link = directory.resolve("alias.apk")
        try {
            Files.write(target, ByteArray(4096))
            Files.setLastModifiedTime(target, FileTime.fromMillis(1_700_000_000_000L))
            Files.createSymbolicLink(link, target.fileName)
            val path = link.toFile().toOkioPath()
            assertEquals(link.toFile().length(), path.fileSize())
            assertEquals(link.toFile().lastModified(), path.lastModifiedMillis())
            assertEquals("alias.apk", path.name)
            assertEquals(listOf(InterruptedException::class.java),
                Kadb::class.java.getMethod("tcpForward", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .exceptionTypes.toList())
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(target)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun oldFilePushDefaultDescriptorResolvesAndHonorsDefaultMasks() {
        // Resolve exactly the symbolic owner and descriptor emitted by Kotlin before File
        // overloads moved into the platform compatibility superclass.
        val bridge = MethodHandles.lookup().findStatic(
            Kadb::class.java,
            "push\$default",
            MethodType.methodType(
                Void.TYPE, Kadb::class.java, File::class.java, String::class.java,
                Int::class.javaPrimitiveType, Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Any::class.java,
            ),
        )
        val path = Files.createTempFile("kadb-file-bridge-", ".bin")
        try {
            val file = MetadataTrackingFile(path.toString())
            val client = Kadb("127.0.0.1", 5555).also { it.close() }
            for (mask in listOf(0, 4, 8, 12)) {
                file.modeReads = 0
                file.timestampReads = 0
                // A closed client stops before network access, after resolving file defaults.
                val failure = assertFailsWith<IOException> {
                    bridge.invokeWithArguments(client, file, "/data/local/tmp/file", 420, 1234L, mask, null)
                }
                assertEquals("Kadb is closed", failure.message)
                assertEquals(if (mask and 4 != 0) 1 else 0, file.modeReads)
                assertEquals(if (mask and 8 != 0) 1 else 0, file.timestampReads)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private class MetadataTrackingFile(path: String) : File(path) {
        var modeReads = 0
        var timestampReads = 0

        override fun toPath(): Path {
            modeReads++
            return super.toPath()
        }

        override fun lastModified(): Long {
            timestampReads++
            return super.lastModified()
        }
    }
}
