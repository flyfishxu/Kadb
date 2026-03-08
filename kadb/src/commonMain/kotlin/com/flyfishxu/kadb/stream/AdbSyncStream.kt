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

package com.flyfishxu.kadb.stream

import okio.Buffer
import okio.BufferedSink
import okio.Sink
import okio.Source
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlin.Throws

private const val ID_LSTAT_V1 = "STAT"
private const val ID_STAT_V2 = "STA2"
private const val ID_LSTAT_V2 = "LST2"

private const val ID_LIST_V1 = "LIST"
private const val ID_LIST_V2 = "LIS2"
private const val ID_DENT_V1 = "DENT"
private const val ID_DENT_V2 = "DNT2"

private const val ID_SEND_V1 = "SEND"
private const val ID_SEND_V2 = "SND2"
private const val ID_RECV_V1 = "RECV"
private const val ID_RECV_V2 = "RCV2"

private const val ID_DATA = "DATA"
private const val ID_DONE = "DONE"
private const val ID_OKAY = "OKAY"
private const val ID_FAIL = "FAIL"
private const val ID_QUIT = "QUIT"

private const val FEATURE_STAT_V2 = "stat_v2"
private const val FEATURE_LS_V2 = "ls_v2"
private const val FEATURE_SENDRECV_V2 = "sendrecv_v2"

private const val SYNC_PATH_MAX = 1024
private const val NAME_MAX = 255
private const val LIST_V1_DONE_TAIL_BYTES = 16L
// do_list<true>() writes ID_DONE as a full sync_dent_v2 struct (76 bytes total),
// and readListV2() has already consumed the 4-byte ID.
// https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/daemon/file_sync_service.cpp#249
private const val LIST_V2_DONE_TAIL_BYTES = 72L
// AOSP file sync caps each DATA chunk to 64 KiB.
// https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/file_sync_protocol.h
private const val SYNC_DATA_MAX = 64 * 1024

internal val SYNC_IDS = setOf(
    ID_LSTAT_V1,
    ID_STAT_V2,
    ID_LSTAT_V2,
    ID_LIST_V1,
    ID_LIST_V2,
    ID_DENT_V1,
    ID_DENT_V2,
    ID_SEND_V1,
    ID_SEND_V2,
    ID_RECV_V1,
    ID_RECV_V2,
    ID_DATA,
    ID_DONE,
    ID_OKAY,
    ID_FAIL,
    ID_QUIT
)

private class Packet(val id: String, val arg: Int)

private class DentV2Fields(
    val errorCode: Int,
    val dev: Long,
    val ino: Long,
    val mode: Int,
    val nlink: Int,
    val uid: Int,
    val gid: Int,
    val size: Long,
    val atimeSec: Long,
    val mtimeSec: Long,
    val ctimeSec: Long,
    val name: String
)

class AdbSyncStream(
    private val stream: AdbStream,
    private val features: Set<String> = emptySet()
) : AutoCloseable {

    private val buffer = Buffer()
    private val isWindowsHost =
        System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) == true

    @Throws(IOException::class)
    fun send(source: Source, remotePath: String, mode: Int, lastModifiedMs: Long) {
        if (supportsFeature(FEATURE_SENDRECV_V2)) {
            sendV2(source, remotePath, mode, lastModifiedMs)
        } else {
            sendV1(source, remotePath, mode, lastModifiedMs)
        }
    }

    @Throws(IOException::class)
    fun recv(sink: Sink, remotePath: String) {
        if (supportsFeature(FEATURE_SENDRECV_V2)) {
            recvV2(sink, remotePath)
        } else {
            recvV1(sink, remotePath)
        }
    }

    @Throws(IOException::class)
    fun statV2(path: String): AdbSyncStatV2 {
        requireFeature(FEATURE_STAT_V2, "statV2")
        return readStatV2(path, ID_STAT_V2, "statV2")
    }

    @Throws(IOException::class)
    fun lstatV2(path: String): AdbSyncStatV2 {
        requireFeature(FEATURE_STAT_V2, "lstatV2")
        return readStatV2(path, ID_LSTAT_V2, "lstatV2")
    }

    @Throws(IOException::class)
    fun lstat(path: String): AdbSyncStat {
        return if (supportsFeature(FEATURE_STAT_V2)) {
            val stat = readStatV2(path, ID_LSTAT_V2, "lstat")
            AdbSyncStat(mode = stat.mode, size = stat.size, mtimeSec = stat.mtimeSec)
        } else {
            readLstatV1(path)
        }
    }

    @Throws(IOException::class)
    fun listV2(path: String): List<AdbSyncDirEntryV2> {
        requireFeature(FEATURE_LS_V2, "listV2")
        return readListV2(path)
    }

    @Throws(IOException::class)
    fun list(path: String): List<AdbSyncDirEntry> {
        return if (supportsFeature(FEATURE_LS_V2)) {
            readListV2(path).map {
                AdbSyncDirEntry(
                    name = it.name,
                    mode = it.mode,
                    size = it.size,
                    mtimeSec = it.mtimeSec,
                    errorCode = it.errorCode.takeIf { code -> code != 0 }
                )
            }
        } else {
            readListV1(path)
        }
    }

    @Throws(IOException::class)
    private fun sendV1(source: Source, remotePath: String, mode: Int, lastModifiedMs: Long) {
        val remote = "$remotePath,$mode".toByteArray(StandardCharsets.UTF_8)
        enforcePathLength(remote.size, ID_SEND_V1)
        writeRequest(ID_SEND_V1, remote)
        writeSourcePayload(source, lastModifiedMs)
        readSendStatus()
    }

    @Throws(IOException::class)
    private fun sendV2(source: Source, remotePath: String, mode: Int, lastModifiedMs: Long) {
        val path = remotePath.toByteArray(StandardCharsets.UTF_8)
        enforcePathLength(path.size, ID_SEND_V2)
        stream.sink.apply {
            writeString(ID_SEND_V2, StandardCharsets.UTF_8)
            writeIntLe(path.size)
            write(path)
            // AOSP send_v2 setup is sync_send_v2{id, mode, flags}.
            // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/file_sync_protocol.h#113
            writeString(ID_SEND_V2, StandardCharsets.UTF_8)
            writeIntLe(mode)
            writeIntLe(0)
            flush()
        }
        writeSourcePayload(source, lastModifiedMs)
        readSendStatus()
    }

    @Throws(IOException::class)
    private fun writeSourcePayload(source: Source, lastModifiedMs: Long) {
        buffer.clear()

        while (true) {
            val read = source.read(buffer, SYNC_DATA_MAX.toLong())
            if (read == -1L) break
            stream.sink.apply {
                writeFrameHeader(ID_DATA, read.toInt())
                val sent = writeAll(this@AdbSyncStream.buffer)
                check(read == sent)
                flush()
            }
        }
        stream.sink.apply {
            writeFrameHeader(ID_DONE, (lastModifiedMs / 1000).toInt())
            flush()
        }
    }

    @Throws(IOException::class)
    private fun readSendStatus() {
        val packet = readPacket()
        when (packet.id) {
            ID_OKAY -> return
            ID_FAIL -> {
                val message = stream.source.readString(packet.arg.toLong(), StandardCharsets.UTF_8)
                throw IOException("Sync failed: $message")
            }

            else -> throw IOException("Unexpected sync packet id: ${packet.id}")
        }
    }

    @Throws(IOException::class)
    private fun recvV1(sink: Sink, remotePath: String) {
        val path = remotePath.toByteArray(StandardCharsets.UTF_8)
        enforcePathLength(path.size, ID_RECV_V1)
        writeRequest(ID_RECV_V1, path)
        readRecvPayload(sink)
    }

    @Throws(IOException::class)
    private fun recvV2(sink: Sink, remotePath: String) {
        val path = remotePath.toByteArray(StandardCharsets.UTF_8)
        enforcePathLength(path.size, ID_RECV_V2)
        stream.sink.apply {
            writeString(ID_RECV_V2, StandardCharsets.UTF_8)
            writeIntLe(path.size)
            write(path)
            // AOSP recv_v2 setup is sync_recv_v2{id, flags}.
            // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/file_sync_protocol.h#120
            writeString(ID_RECV_V2, StandardCharsets.UTF_8)
            writeIntLe(0)
            flush()
        }
        readRecvPayload(sink)
    }

    @Throws(IOException::class)
    private fun readRecvPayload(sink: Sink) {
        buffer.clear()
        while (true) {
            val packet = readPacket()
            when (packet.id) {
                ID_DATA -> {
                    val chunkSize = packet.arg
                    // AOSP sync client rejects daemon frames that exceed SYNC_DATA_MAX.
                    // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/client/file_sync_client.cpp#1129
                    if (chunkSize < 0 || chunkSize > SYNC_DATA_MAX) {
                        throw IOException("Sync DATA chunk too large: $chunkSize > $SYNC_DATA_MAX")
                    }
                    stream.source.readFully(buffer, chunkSize.toLong())
                    buffer.readAll(sink)
                }

                ID_DONE -> break
                ID_FAIL -> {
                    val message = stream.source.readString(packet.arg.toLong(), StandardCharsets.UTF_8)
                    throw IOException("Sync failed: $message")
                }

                else -> throw IOException("Unexpected sync packet id: ${packet.id}")
            }
        }

        sink.flush()
    }

    @Throws(IOException::class)
    private fun readStatV2(path: String, requestId: String, apiName: String): AdbSyncStatV2 {
        val pathBytes = path.toByteArray(StandardCharsets.UTF_8)
        enforcePathLength(pathBytes.size, requestId)
        writeRequest(requestId, pathBytes)

        val responseId = readSyncId()
        if (responseId != ID_STAT_V2 && responseId != ID_LSTAT_V2) {
            throw IOException("Unexpected sync stat id: $responseId")
        }

        val stat = AdbSyncStatV2(
            errorCode = stream.source.readIntLe(),
            dev = stream.source.readLongLe(),
            ino = stream.source.readLongLe(),
            mode = stream.source.readIntLe(),
            nlink = stream.source.readIntLe(),
            uid = stream.source.readIntLe(),
            gid = stream.source.readIntLe(),
            size = stream.source.readLongLe(),
            atimeSec = stream.source.readLongLe(),
            mtimeSec = stream.source.readLongLe(),
            ctimeSec = stream.source.readLongLe()
        )

        if (stat.errorCode != 0) {
            throw IOException("$apiName failed with error code: ${stat.errorCode}")
        }
        return stat
    }

    @Throws(IOException::class)
    private fun readLstatV1(path: String): AdbSyncStat {
        val pathBytes = path.toByteArray(StandardCharsets.UTF_8)
        enforcePathLength(pathBytes.size, ID_LSTAT_V1)
        writeRequest(ID_LSTAT_V1, pathBytes)

        val responseId = readSyncId()
        if (responseId != ID_LSTAT_V1) {
            throw IOException("Unexpected sync lstat id: $responseId")
        }
        val mode = stream.source.readIntLe()
        val size = readUInt32AsLong()
        val mtime = readUInt32AsLong()
        if (mode == 0 && size == 0L && mtime == 0L) {
            // AOSP v1 lstat treats all-zero as unknown failure.
            // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/client/file_sync_client.cpp#511
            throw IOException("Sync lstat failed: unknown error")
        }
        return AdbSyncStat(mode = mode, size = size, mtimeSec = mtime)
    }

    @Throws(IOException::class)
    private fun readListV1(path: String): List<AdbSyncDirEntry> {
        val pathBytes = path.toByteArray(StandardCharsets.UTF_8)
        enforcePathLength(pathBytes.size, ID_LIST_V1)
        writeRequest(ID_LIST_V1, pathBytes)

        val entries = mutableListOf<AdbSyncDirEntry>()
        while (true) {
            when (val id = readSyncId()) {
                ID_DENT_V1 -> {
                    val mode = stream.source.readIntLe()
                    val size = readUInt32AsLong()
                    val mtime = readUInt32AsLong()
                    val nameLength = stream.source.readIntLe()
                    val name = readSyncName(nameLength)
                    validateEntryName(name)
                    entries += AdbSyncDirEntry(name = name, mode = mode, size = size, mtimeSec = mtime, errorCode = null)
                }

                ID_DONE -> {
                    stream.source.skip(LIST_V1_DONE_TAIL_BYTES)
                    return entries
                }

                ID_FAIL -> throw readFailException()
                else -> throw IOException("Unexpected sync list id: $id")
            }
        }
    }

    @Throws(IOException::class)
    private fun readListV2(path: String): List<AdbSyncDirEntryV2> {
        val pathBytes = path.toByteArray(StandardCharsets.UTF_8)
        enforcePathLength(pathBytes.size, ID_LIST_V2)
        writeRequest(ID_LIST_V2, pathBytes)

        val entries = mutableListOf<AdbSyncDirEntryV2>()
        while (true) {
            when (val id = readSyncId()) {
                ID_DENT_V2 -> {
                    val dent = readDentV2()
                    val entry = AdbSyncDirEntryV2(
                        name = dent.name,
                        errorCode = dent.errorCode,
                        dev = dent.dev,
                        ino = dent.ino,
                        mode = dent.mode,
                        nlink = dent.nlink,
                        uid = dent.uid,
                        gid = dent.gid,
                        size = dent.size,
                        atimeSec = dent.atimeSec,
                        mtimeSec = dent.mtimeSec,
                        ctimeSec = dent.ctimeSec
                    )
                    entries += entry
                }

                ID_DONE -> {
                    // do_list<true>() sends ID_DONE as a full sync_dent_v2 struct.
                    // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/daemon/file_sync_service.cpp#249
                    stream.source.skip(LIST_V2_DONE_TAIL_BYTES)
                    return entries
                }

                ID_FAIL -> throw readFailException()
                else -> throw IOException("Unexpected sync list id: $id")
            }
        }
    }

    @Throws(IOException::class)
    private fun readDentV2(): DentV2Fields {
        val errorCode = stream.source.readIntLe()
        val dev = stream.source.readLongLe()
        val ino = stream.source.readLongLe()
        val mode = stream.source.readIntLe()
        val nlink = stream.source.readIntLe()
        val uid = stream.source.readIntLe()
        val gid = stream.source.readIntLe()
        val size = stream.source.readLongLe()
        val atimeSec = stream.source.readLongLe()
        val mtimeSec = stream.source.readLongLe()
        val ctimeSec = stream.source.readLongLe()
        val nameLength = stream.source.readIntLe()
        val name = readSyncName(nameLength)
        validateEntryName(name)
        return DentV2Fields(
            errorCode = errorCode,
            dev = dev,
            ino = ino,
            mode = mode,
            nlink = nlink,
            uid = uid,
            gid = gid,
            size = size,
            atimeSec = atimeSec,
            mtimeSec = mtimeSec,
            ctimeSec = ctimeSec,
            name = name
        )
    }

    private fun writeRequest(id: String, path: ByteArray) {
        stream.sink.apply {
            writeFrameHeader(id, path.size)
            write(path)
            flush()
        }
    }

    private fun writePacket(id: String, arg: Int) {
        stream.sink.apply {
            writeFrameHeader(id, arg)
            flush()
        }
    }

    private fun BufferedSink.writeFrameHeader(id: String, arg: Int) {
        writeString(id, StandardCharsets.UTF_8)
        writeIntLe(arg)
    }

    private fun readPacket(): Packet {
        val id = readSyncId()
        val arg = stream.source.readIntLe()
        return Packet(id, arg)
    }

    @Throws(IOException::class)
    private fun readSyncName(length: Int): String {
        if (length < 0 || length > NAME_MAX) {
            throw IOException("Invalid sync name length: $length")
        }
        return stream.source.readString(length.toLong(), StandardCharsets.UTF_8)
    }

    private fun validateEntryName(name: String) {
        // AOSP sync client rejects '/' in listed names and rejects '\\' on Windows hosts.
        // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/client/file_sync_client.cpp#555
        if (name.contains('/')) {
            throw IOException("Invalid sync entry name: $name")
        }
        if (isWindowsHost && name.contains('\\')) {
            throw IOException("Invalid sync entry name: $name")
        }
    }

    private fun supportsFeature(feature: String): Boolean = features.contains(feature)

    private fun requireFeature(feature: String, apiName: String) {
        if (!supportsFeature(feature)) {
            throw UnsupportedOperationException("$apiName requires peer feature '$feature'")
        }
    }

    private fun enforcePathLength(length: Int, id: String) {
        // AOSP SendRequest/SendSend2/SendRecv2 path_length limit is 1024 bytes.
        // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/client/file_sync_client.cpp#338
        require(length <= SYNC_PATH_MAX) {
            "Sync path too long for $id: $length > $SYNC_PATH_MAX"
        }
    }

    private fun readFailException(): IOException {
        val messageLength = stream.source.readIntLe()
        if (messageLength < 0) {
            return IOException("Sync failed with invalid message length: $messageLength")
        }
        val message = stream.source.readString(messageLength.toLong(), StandardCharsets.UTF_8)
        return IOException("Sync failed: $message")
    }

    private fun readSyncId(): String = stream.source.readString(4, StandardCharsets.UTF_8)

    private fun readUInt32AsLong(): Long = stream.source.readIntLe().toLong() and 0xFFFF_FFFFL

    override fun close() {
        writePacket(ID_QUIT, 0)
        stream.close()
    }
}
