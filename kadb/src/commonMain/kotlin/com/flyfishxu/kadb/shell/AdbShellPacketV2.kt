package com.flyfishxu.kadb.shell

/**
 * Value of the "packet kind" byte in a [shell v2 packet]
 */
class AdbShellPacketV2 {
    companion object {
        const val ID_STDIN = 0
        const val ID_STDOUT = 1
        const val ID_STDERR = 2
        const val ID_EXIT = 3
        const val ID_CLOSE_STDIN = 4
        const val ID_WINDOW_SIZE_CHANGE = 5
        const val ID_INVALID = 255
    }
}