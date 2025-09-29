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
import java.net.Socket
import java.security.*
import java.security.cert.X509Certificate
import javax.net.ssl.*

internal object SslUtils {
    var customConscrypt = false
    private var sslContext: SSLContext? = null

    fun newClientEngine(sslContext: SSLContext, host: String?, port: Int): SSLEngine {
        val engine = if (host != null) sslContext.createSSLEngine(host, port) else sslContext.createSSLEngine()
        engine.useClientMode = true
        // adbd 端强制 TLS 1.3
        engine.enabledProtocols = arrayOf("TLSv1.3")
        return engine
    }

    fun getSslContext(keyPair: AdbKeyPair): SSLContext {
        sslContext?.let { return it }
        try {
            val providerClass = Class.forName("org.conscrypt.OpenSSLProvider")
            val openSslProvider = providerClass.getDeclaredConstructor().newInstance() as Provider
            sslContext = SSLContext.getInstance("TLSv1.3", openSslProvider)
            customConscrypt = true
        } catch (e: NoSuchAlgorithmException) {
            throw e
        } catch (_: Throwable) {
            sslContext = SSLContext.getInstance("TLSv1.3")
            customConscrypt = false
        }
        sslContext!!.init(
            arrayOf(getKeyManager(keyPair)), arrayOf(getAllAcceptingTrustManager()), SecureRandom()
        )
        return sslContext!!
    }

    private fun getKeyManager(keyPair: AdbKeyPair): KeyManager {
        return object : X509ExtendedKeyManager() {
            private val mAlias = "key"

            override fun getClientAliases(
                keyType: String?, issuers: Array<out Principal>?
            ): Array<String>? {
                return if (keyType?.equals("RSA", ignoreCase = true) == true) arrayOf(mAlias) else arrayOf(mAlias)
            }

            override fun chooseClientAlias(
                keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?
            ): String? {
                keyTypes?.let {
                    if (it.contains("RSA")) return mAlias
                }
                return if (keyTypes?.any { it.equals("RSA", ignoreCase = true) } == true) mAlias else mAlias
            }

            override fun chooseEngineClientAlias(
                keyTypes: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?
            ): String? {
                return if (keyTypes?.any { it.equals("RSA", ignoreCase = true) } == true) mAlias else mAlias
            }

            override fun getServerAliases(
                keyType: String?, issuers: Array<out Principal>?
            ): Array<String>? {
                return null
            }

            override fun chooseServerAlias(
                keyType: String?, issuers: Array<out Principal>?, socket: Socket?
            ): String? {
                return null
            }

            override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
                if (mAlias == alias) {
                    return arrayOf(keyPair.certificate as X509Certificate)
                }
                return null
            }

            override fun getPrivateKey(alias: String?): PrivateKey? {
                if (mAlias == alias) {
                    return keyPair.privateKey
                }
                return null
            }
        }
    }

    private fun getAllAcceptingTrustManager(): X509TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> {
                return emptyArray()
            }
        }
    }
}