package com.flyfishxu.kadb.stream

/**
 * Compat stat result aligned with AOSP v1 fields.
 * https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/file_sync_protocol.h#48
 */
class AdbSyncStat(
    val mode: Int,
    val size: Long,
    val mtimeSec: Long
)

/**
 * Full stat_v2/lstat_v2 result aligned with AOSP sync_stat_v2.
 * https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/file_sync_protocol.h#55
 */
class AdbSyncStatV2(
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
    val ctimeSec: Long
)

/**
 * Compat list entry aligned with AOSP v1 dent fields, plus optional v2 error code.
 * https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/file_sync_protocol.h#70
 */
class AdbSyncDirEntry(
    val name: String,
    val mode: Int,
    val size: Long,
    val mtimeSec: Long,
    val errorCode: Int?
)

/**
 * Full list_v2 entry aligned with AOSP sync_dent_v2.
 * https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/file_sync_protocol.h#78
 */
class AdbSyncDirEntryV2(
    val name: String,
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
    val ctimeSec: Long
)
