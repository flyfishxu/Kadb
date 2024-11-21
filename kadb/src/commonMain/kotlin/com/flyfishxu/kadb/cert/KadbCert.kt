package com.flyfishxu.kadb.cert

import com.flyfishxu.kadb.cert.CertUtils.generate
import com.flyfishxu.kadb.cert.CertUtils.vailidateCertificate
import org.bouncycastle.asn1.x509.Time
import java.math.BigInteger
import java.security.SecureRandom
import java.util.*

object KadbCert {
    internal var cert = byteArrayOf()
    internal var key = byteArrayOf()

    /**
     * Set keystore
     */
    fun set(cert: ByteArray, key: ByteArray) {
        this.cert = cert
        this.key = key
        vailidateCertificate()
    }

    /**
     * Get current in-use keystore
     */
    fun getOrError(): Pair<ByteArray, ByteArray> {
        if (cert.isEmpty() || key.isEmpty()) {
            throw IllegalStateException("Certificate or Private key is not set")
        }
        return cert to key
    }

    /**
     * Generate new keystore and set it
     */
    fun get(
        keySize: Int = 2048,
        cn: String = "Kadb",
        ou: String = "Kadb",
        o: String = "Kadb",
        l: String = "Kadb",
        st: String = "Kadb",
        c: String = "Kadb",
        notAfter: Long = System.currentTimeMillis() + 10368000000, // 120 days
        serialNumber: BigInteger = BigInteger(64, SecureRandom())
    ): Pair<ByteArray, ByteArray> {
        if (cert.isEmpty() || key.isEmpty()) {
            generate(keySize, cn, ou, o, l, st, c, Time(Date(notAfter)), serialNumber)
        }
        return cert to key
    }
}