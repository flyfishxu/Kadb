package com.flyfishxu.kadb.cert

internal data class HostKeySet(
    val defaultKeyPair: AdbKeyPair,
    val keyPairs: List<AdbKeyPair>
) {
    init {
        require(keyPairs.isNotEmpty()) { "HostKeySet must contain at least one key pair" }
        require(keyPairs.first() === defaultKeyPair) { "Default key pair must be the first entry in key set" }
    }

    companion object {
        fun single(keyPair: AdbKeyPair): HostKeySet = HostKeySet(
            defaultKeyPair = keyPair,
            keyPairs = listOf(keyPair)
        )
    }
}
