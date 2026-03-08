package com.flyfishxu.kadb.cert

import java.security.cert.X509Certificate

object KadbCert {
    private sealed interface State {
        data object Uninitialized : State
        data class Ready(val snapshot: KadbIdentitySnapshot, val keySet: HostKeySet) : State
        data class Broken(val cause: Throwable) : State
    }

    private val lock = Any()

    @Volatile
    private var store: KadbPrivateKeyStore = InMemoryPrivateKeyStore()

    @Volatile
    private var policy: KadbCertPolicy = KadbCertPolicy()

    @Volatile
    private var additionalPrivateKeysPem: List<ByteArray> = emptyList()

    @Volatile
    private var state: State = State.Uninitialized

    fun configure(
        store: KadbPrivateKeyStore,
        policy: KadbCertPolicy = KadbCertPolicy(),
        additionalPrivateKeysPem: List<ByteArray> = emptyList()
    ) {
        validatePolicy(policy)
        synchronized(lock) {
            this.store = store
            this.policy = policy
            this.additionalPrivateKeysPem = additionalPrivateKeysPem
                .filter { it.isNotEmpty() }
                .map(ByteArray::copyOf)
            state = State.Uninitialized
        }
    }

    fun ensureReady(): KadbIdentitySnapshot = synchronized(lock) {
        ensureReadyStateLocked().snapshot.deepCopy()
    }

    fun rotate(): KadbIdentitySnapshot = synchronized(lock) {
        val previousReady = state as? State.Ready
        try {
            val material = CertUtils.generateNewIdentity(policy)
            writePrivateKeyOrThrow(material.privateKeyPem)
            val ready = State.Ready(material.snapshot.deepCopy(), resolveHostKeySet(material.keyPair))
            state = ready
            ready.snapshot.deepCopy()
        } catch (error: Throwable) {
            state = previousReady ?: State.Broken(error)
            throw toKadbCertException(error)
        }
    }

    fun importIdentity(privateKeyPem: ByteArray, certificatePem: ByteArray? = null): KadbIdentitySnapshot = synchronized(lock) {
        val previousReady = state as? State.Ready
        try {
            val material = resolveImportedIdentity(privateKeyPem, certificatePem)
            writePrivateKeyOrThrow(material.privateKeyPem)
            val ready = State.Ready(material.snapshot.deepCopy(), resolveHostKeySet(material.keyPair))
            state = ready
            ready.snapshot.deepCopy()
        } catch (error: Throwable) {
            state = previousReady ?: State.Broken(error)
            throw toKadbCertException(error)
        }
    }

    fun importPrivateKey(privateKeyPem: ByteArray): KadbIdentitySnapshot {
        return importIdentity(privateKeyPem, null)
    }

    fun exportIdentityOrNull(): KadbIdentitySnapshot? = synchronized(lock) {
        when (val current = state) {
            is State.Ready -> current.snapshot.deepCopy()
            else -> {
                val privateKeyPem = readPrivateKeyFromStoreOrThrow() ?: return@synchronized null
                if (privateKeyPem.isEmpty()) return@synchronized null
                validatePrivateKeyOrThrow(privateKeyPem)
                CertUtils.identityFromPrivateKey(privateKeyPem, policy).snapshot.deepCopy()
            }
        }
    }

    fun exportPrivateKeyOrNull(): ByteArray? = synchronized(lock) {
        when (val current = state) {
            is State.Ready -> current.snapshot.privateKeyPem.copyOf()
            else -> readPrivateKeyFromStoreOrThrow()?.copyOf()
        }
    }

    fun clear() = synchronized(lock) {
        try {
            store.clear()
            state = State.Uninitialized
        } catch (error: Throwable) {
            throw KadbCertException.StoreWriteFailed(error)
        }
    }

    internal fun currentKeyPair(): AdbKeyPair = synchronized(lock) {
        ensureReadyStateLocked().keySet.defaultKeyPair
    }

    internal fun currentKeySet(): HostKeySet = synchronized(lock) {
        ensureReadyStateLocked().keySet
    }

    private fun ensureReadyStateLocked(): State.Ready {
        val current = state
        if (current is State.Ready) {
            return current
        }

        return try {
            val ready = loadOrGenerateState()
            state = ready
            ready
        } catch (error: Throwable) {
            state = State.Broken(error)
            throw toKadbCertException(error)
        }
    }

    private fun loadOrGenerateState(): State.Ready {
        val privateKeyPem = readPrivateKeyFromStoreOrThrow()
        if (privateKeyPem == null || privateKeyPem.isEmpty()) {
            return regenerateAndPersistState()
        }

        return try {
            validatePrivateKeyOrThrow(privateKeyPem)
            val resolved = CertUtils.identityFromPrivateKey(privateKeyPem, policy)
            State.Ready(resolved.snapshot.deepCopy(), resolveHostKeySet(resolved.keyPair))
        } catch (error: KadbCertException.PrivateKeyParseFailed) {
            if (!policy.autoHealInvalidPrivateKey) throw error
            regenerateAndPersistState()
        }
    }

    private fun regenerateAndPersistState(): State.Ready {
        val generated = CertUtils.generateNewIdentity(policy)
        writePrivateKeyOrThrow(generated.privateKeyPem)
        return State.Ready(generated.snapshot.deepCopy(), resolveHostKeySet(generated.keyPair))
    }

    private fun resolveImportedIdentity(privateKeyPem: ByteArray, certificatePem: ByteArray?): ResolvedIdentity {
        validatePrivateKeyOrThrow(privateKeyPem)
        if (certificatePem == null || certificatePem.isEmpty()) {
            return CertUtils.identityFromPrivateKey(privateKeyPem, policy)
        }
        val certificatePemBytes = certificatePem

        val imported = runCatching {
            CertUtils.identityFromPrivateKeyAndCertificate(privateKeyPem, certificatePemBytes)
        }.getOrNull()

        if (imported != null) {
            val certificate = imported.keyPair.certificate as? X509Certificate
            if (certificate != null) {
                val certificateValid = runCatching {
                    CertUtils.validateCertificate(certificate)
                    true
                }.getOrElse { false }
                if (certificateValid) {
                    return imported
                }
            }
        }

        return CertUtils.identityFromPrivateKey(privateKeyPem, policy)
    }
    private fun validatePrivateKeyOrThrow(privateKeyPem: ByteArray) {
        try {
            CertUtils.parsePrivateKeyPem(privateKeyPem)
        } catch (error: Throwable) {
            throw KadbCertException.fromPrivateKeyParseError(error)
        }
    }

    private fun readPrivateKeyFromStoreOrThrow(): ByteArray? {
        return try {
            store.readPrivateKeyPem()
        } catch (error: Throwable) {
            throw KadbCertException.StoreReadFailed(error)
        }
    }

    private fun writePrivateKeyOrThrow(privateKeyPem: ByteArray) {
        try {
            store.writePrivateKeyPemAtomic(privateKeyPem)
        } catch (error: Throwable) {
            throw KadbCertException.StoreWriteFailed(error)
        }
    }

    private fun resolveHostKeySet(defaultKeyPair: AdbKeyPair): HostKeySet {
        val keyPairs = mutableListOf(defaultKeyPair)
        val seenFingerprints = mutableSetOf(CertUtils.fingerprintSha256(defaultKeyPair.publicKey))
        for (privateKeyPem in additionalPrivateKeysPem) {
            val extraKeyPair = runCatching {
                CertUtils.identityFromPrivateKey(privateKeyPem, policy).keyPair
            }.getOrNull() ?: continue
            val fingerprint = CertUtils.fingerprintSha256(extraKeyPair.publicKey)
            if (seenFingerprints.add(fingerprint)) {
                keyPairs += extraKeyPair
            }
        }
        return HostKeySet(defaultKeyPair = defaultKeyPair, keyPairs = keyPairs)
    }

    private fun validatePolicy(policy: KadbCertPolicy) {
        if (policy.keySizeBits != 2048) {
            throw KadbCertException.PolicyViolation("Only RSA 2048 is supported")
        }
        if (policy.certValidityDays <= 0) {
            throw KadbCertException.PolicyViolation("Certificate validity must be greater than 0")
        }
    }

    private fun toKadbCertException(error: Throwable): KadbCertException {
        return when (error) {
            is KadbCertException -> error
            else -> KadbCertException.PolicyViolation("Unexpected KadbCert error", error)
        }
    }
}
