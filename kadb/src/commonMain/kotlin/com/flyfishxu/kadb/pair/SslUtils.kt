/*
 * Copyright (c) 2024 Flyfish-Xu
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

package com.flyfishxu.kadb.pair

import com.flyfishxu.kadb.cert.AdbKeyPair
import com.flyfishxu.kadb.cert.CertUtils
import com.flyfishxu.kadb.cert.HostKeySet
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import java.net.Socket
import java.security.NoSuchAlgorithmException
import java.security.Principal
import java.security.PrivateKey
import java.security.Provider
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLContext
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

internal object SslUtils {
    @Volatile
    var customConscrypt = false

    private val lock = Any()

    @Volatile
    private var cachedContext: CachedContext? = null

    private data class CachedContext(
        val hostKeyCacheKey: String,
        val context: SSLContext,
        val customConscrypt: Boolean
    )

    private data class ClientIdentity(
        val alias: String,
        val keyPair: AdbKeyPair,
        val adbTlsFingerprintHex: String
    )

    fun newClientEngine(sslContext: SSLContext, host: String?, port: Int): SSLEngine {
        val engine = if (host != null) sslContext.createSSLEngine(host, port) else sslContext.createSSLEngine()
        engine.useClientMode = true
        engine.enabledProtocols = arrayOf("TLSv1.3")
        return engine
    }

    fun getSslContext(hostKeySet: HostKeySet): SSLContext {
        val hostKeyCacheKey = buildCacheKey(hostKeySet)

        cachedContext?.let {
            if (it.hostKeyCacheKey == hostKeyCacheKey) {
                customConscrypt = it.customConscrypt
                return it.context
            }
        }

        synchronized(lock) {
            cachedContext?.let {
                if (it.hostKeyCacheKey == hostKeyCacheKey) {
                    customConscrypt = it.customConscrypt
                    return it.context
                }
            }

            val context = createSslContext(hostKeySet)
            val cached = CachedContext(
                hostKeyCacheKey = hostKeyCacheKey,
                context = context.first,
                customConscrypt = context.second
            )
            cachedContext = cached
            customConscrypt = cached.customConscrypt
            return cached.context
        }
    }

    fun getSslContext(keyPair: AdbKeyPair): SSLContext {
        return getSslContext(HostKeySet.single(keyPair))
    }

    private fun createSslContext(hostKeySet: HostKeySet): Pair<SSLContext, Boolean> {
        var sslContext: SSLContext
        var isCustomConscrypt: Boolean
        try {
            val providerClass = Class.forName("org.conscrypt.OpenSSLProvider")
            val provider = providerClass.getDeclaredConstructor().newInstance() as Provider
            sslContext = SSLContext.getInstance("TLSv1.3", provider)
            isCustomConscrypt = true
        } catch (e: NoSuchAlgorithmException) {
            throw e
        } catch (_: Throwable) {
            sslContext = SSLContext.getInstance("TLSv1.3")
            isCustomConscrypt = false
        }

        sslContext.init(
            arrayOf<KeyManager>(newClientKeyManager(hostKeySet)),
            arrayOf(getAllAcceptingTrustManager()),
            SecureRandom()
        )

        return sslContext to isCustomConscrypt
    }

    internal fun newClientKeyManager(hostKeySet: HostKeySet): X509ExtendedKeyManager {
        val identities = hostKeySet.keyPairs.mapIndexed { index, keyPair ->
            ClientIdentity(
                alias = "key-$index",
                keyPair = keyPair,
                adbTlsFingerprintHex = CertUtils.adbTlsFingerprintHex(keyPair.publicKey)
            )
        }
        val identityByAlias = identities.associateBy { it.alias }
        val defaultAlias = identities.first().alias

        return object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> {
                if (!supportsKeyType(keyType)) return emptyArray()
                return identities.map { it.alias }.toTypedArray()
            }

            override fun chooseClientAlias(
                keyTypes: Array<out String>?,
                issuers: Array<out Principal>?,
                socket: Socket?
            ): String? {
                return chooseAlias(keyTypes, issuers, defaultAlias, identities)
            }

            override fun chooseEngineClientAlias(
                keyTypes: Array<out String>?,
                issuers: Array<out Principal>?,
                engine: SSLEngine?
            ): String? {
                return chooseAlias(keyTypes, issuers, defaultAlias, identities)
            }

            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

            override fun chooseServerAlias(
                keyType: String?,
                issuers: Array<out Principal>?,
                socket: Socket?
            ): String? = null

            override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
                val identity = identityByAlias[alias] ?: return null
                return arrayOf(identity.keyPair.certificate as X509Certificate)
            }

            override fun getPrivateKey(alias: String?): PrivateKey? {
                return identityByAlias[alias]?.keyPair?.privateKey
            }
        }
    }

    private fun getAllAcceptingTrustManager(): X509TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }

    private fun buildCacheKey(hostKeySet: HostKeySet): String {
        return hostKeySet.keyPairs.joinToString("|") {
            CertUtils.fingerprintSha256(it.certificate)
        }
    }

    private fun chooseAlias(
        keyTypes: Array<out String>?,
        issuers: Array<out Principal>?,
        defaultAlias: String,
        identities: List<ClientIdentity>
    ): String? {
        val compatibleIdentities = if (keyTypes.isNullOrEmpty() || keyTypes.any(::supportsKeyType)) {
            identities
        } else {
            emptyList()
        }
        if (compatibleIdentities.isEmpty()) {
            return null
        }

        if (!issuers.isNullOrEmpty()) {
            for (issuer in issuers) {
                val fingerprintHex = parseAdbKeyFingerprintHexFromIssuer(issuer) ?: continue
                val matched = compatibleIdentities.firstOrNull { it.adbTlsFingerprintHex == fingerprintHex }
                if (matched != null) {
                    return matched.alias
                }
            }
        }

        return compatibleIdentities.firstOrNull { it.alias == defaultAlias }?.alias
            ?: compatibleIdentities.first().alias
    }

    private fun supportsKeyType(keyType: String?): Boolean {
        if (keyType == null) return true
        return keyType.uppercase(Locale.US).contains("RSA")
    }

    internal fun parseAdbKeyFingerprintHexFromIssuer(issuer: Principal): String? {
        val name = runCatching { X500Name(issuer.name) }.getOrNull() ?: return null
        val organization = name.getRDNs(BCStyle.O).firstOrNull()?.first?.value?.toString() ?: return null
        if (organization != "AdbKey-0") {
            return null
        }
        val commonName = name.getRDNs(BCStyle.CN).firstOrNull()?.first?.value?.toString() ?: return null
        if (commonName.length != 64 || commonName.any { !it.isDigit() && it !in 'a'..'f' && it !in 'A'..'F' }) {
            return null
        }
        return commonName.uppercase(Locale.US)
    }
}
