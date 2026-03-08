package com.flyfishxu.kadb.shell

import java.io.IOException

// PTY shell sessions use shell protocol control frames on top of the ADB WRTE stream.
// https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/shell_protocol.h
class AdbPtyShellSession internal constructor(
    private val shell: AdbShellStream
) : AutoCloseable {

    @Throws(IOException::class)
    fun read(): AdbShellPacket = shell.read()

    @Throws(IOException::class)
    fun write(input: String) = shell.write(input)

    @Throws(IOException::class)
    fun write(input: ByteArray) = shell.write(input)

    @Throws(IOException::class)
    // Shell protocol defines close-stdin as a control packet so PTY sessions can stop input explicitly.
    // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/shell_protocol.h#51
    fun closeStdin() = shell.closeStdin()

    @Throws(IOException::class)
    // Shell daemon consumes resize payload as "<rows>x<cols>,<xpixels>x<ypixels>".
    // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/daemon/shell_service.cpp#687
    fun resize(rows: Int, cols: Int, xPixels: Int = 0, yPixels: Int = 0) =
        shell.resize(rows, cols, xPixels, yPixels)

    override fun close() = shell.close()
}
