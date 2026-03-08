package com.flyfishxu.kadb.cert

class InMemoryPrivateKeyStore : KadbPrivateKeyStore {
    private val lock = Any()
    private var privateKeyPem: ByteArray? = null

    override fun readPrivateKeyPem(): ByteArray? = synchronized(lock) {
        privateKeyPem?.copyOf()
    }

    override fun writePrivateKeyPemAtomic(privateKeyPem: ByteArray) {
        synchronized(lock) {
            this.privateKeyPem = privateKeyPem.copyOf()
        }
    }

    override fun clear() {
        synchronized(lock) {
            privateKeyPem = null
        }
    }
}
