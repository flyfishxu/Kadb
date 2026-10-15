package com.flyfishxu.kadb.cert

import com.flyfishxu.kadb.concurrent.BlockingLock
import com.flyfishxu.kadb.concurrent.withLock

class InMemoryPrivateKeyStore : KadbPrivateKeyStore {
    private val lock = BlockingLock()
    private var privateKeyPem: ByteArray? = null

    override fun readPrivateKeyPem(): ByteArray? = lock.withLock {
        privateKeyPem?.copyOf()
    }

    override fun writePrivateKeyPemAtomic(privateKeyPem: ByteArray) {
        lock.withLock {
            this.privateKeyPem = privateKeyPem.copyOf()
        }
    }

    override fun clear() {
        lock.withLock {
            privateKeyPem = null
        }
    }
}
