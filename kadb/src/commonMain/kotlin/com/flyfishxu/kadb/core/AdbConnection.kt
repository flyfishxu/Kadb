package com.flyfishxu.kadb.core

import com.flyfishxu.kadb.cert.AdbKeyPair
import com.flyfishxu.kadb.cert.CertUtils.loadKeyPair
import com.flyfishxu.kadb.cert.platform.defaultDeviceName
import com.flyfishxu.kadb.exception.AdbPairAuthException
import com.flyfishxu.kadb.pair.SslUtils
import com.flyfishxu.kadb.queue.AdbMessageQueue
import com.flyfishxu.kadb.stream.AdbStream
import okio.Sink
import okio.Source
import okio.sink
import okio.source
import org.jetbrains.annotations.TestOnly
import java.io.Closeable
import java.io.IOException
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey
import java.util.*
import javax.net.ssl.SSLProtocolException
import kotlin.Throws
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal class AdbConnection internal constructor(
    adbReader: AdbReader,
    private val adbWriter: AdbWriter,
    private val closeable: Closeable?,
    private val supportedFeatures: Set<String>,
    private val version: Int,
    private val maxPayloadSize: Int
) : AutoCloseable {

    private val random = Random()
    private val messageQueue = AdbMessageQueue(adbReader)

    @Throws(IOException::class)
    fun open(destination: String): AdbStream {
        val localId = newId()
        messageQueue.startListening(localId)
        try {
            adbWriter.writeOpen(localId, destination)
            val message = messageQueue.take(localId, AdbProtocol.CMD_OKAY)
            val remoteId = message.arg0

            return AdbStream(messageQueue, adbWriter, maxPayloadSize, localId, remoteId)
        } catch (e: Throwable) {
            messageQueue.stopListening(localId)
            throw e
        }
    }

    fun supportsFeature(feature: String): Boolean {
        return supportedFeatures.contains(feature)
    }

    private fun newId(): Int {
        return random.nextInt()
    }

    @TestOnly
    internal fun ensureEmpty() {
        messageQueue.ensureEmpty()
    }

    override fun close() {
        try {
            messageQueue.close()
            adbWriter.close()
            closeable?.close()
        } catch (_: Throwable) {
        }
    }

    companion object {
        fun connect(socket: Socket, keyPair: AdbKeyPair): AdbConnection {
            val source = socket.source()
            val sink = socket.sink()
            return connect(socket, source, sink, keyPair, socket)
        }

        private fun connect(
            socket: Socket, source: Source, sink: Sink, keyPair: AdbKeyPair, closeable: Closeable? = null
        ): AdbConnection {
            val adbReader = AdbReader(source)
            val adbWriter = AdbWriter(sink)

            try {
                return connect(socket, adbReader, adbWriter, keyPair, closeable)
            } catch (t: Throwable) {
                adbReader.close()
                adbWriter.close()
                throw t
            }
        }

        private fun connect(
            socket: Socket, adbReader: AdbReader, adbWriter: AdbWriter, keyPair: AdbKeyPair, closeable: Closeable?
        ): AdbConnection {
            adbWriter.writeConnect()

            var message: AdbMessage = try {
                adbReader.readMessage() // TODO：Happened Connection Reset
            } catch (e: SSLProtocolException) {
                if (e.message?.contains("SSLV3_ALERT_CERTIFICATE_UNKNOWN") == true) {
                    throw AdbPairAuthException()
                } else {
                    throw e
                }
            }

            if (message.command == AdbProtocol.CMD_STLS) {
                val host = socket.inetAddress.hostAddress!!
                val port = socket.port
                val newSocket = Socket(host, port)
                val sslSocket = SslUtils.getSSLSocket(
                    newSocket, host, port, loadKeyPair()
                )
                adbReader.close()
                adbWriter.close()
                return connect(sslSocket, keyPair)
            } else if (message.command == AdbProtocol.CMD_AUTH) {
                check(message.arg0 == AdbProtocol.AUTH_TYPE_TOKEN) { "Unsupported auth type: $message" }

                val signature = keyPair.signPayload(message)
                adbWriter.writeAuth(AdbProtocol.AUTH_TYPE_SIGNATURE, signature)

                message = adbReader.readMessage()
                if (message.command == AdbProtocol.CMD_AUTH) {
                    adbWriter.writeAuth(AdbProtocol.AUTH_TYPE_RSA_PUBLIC, adbPublicKey(keyPair))
                    message = adbReader.readMessage()
                }
            }

            if (message.command != AdbProtocol.CMD_CNXN) throw IOException("Connection failed: ${message.command}")

            val connectionString = parseConnectionString(String(message.payload))
            val version = message.arg0
            val maxPayloadSize = message.arg1

            return AdbConnection(
                adbReader, adbWriter, closeable, connectionString.features, version, maxPayloadSize
            )
        }

        private data class ConnectionString(val features: Set<String>)

        // ie: "device::ro.product.name=sdk_gphone_x86;ro.product.model=Android SDK built for x86;ro.product.device=generic_x86;features=fixed_push_symlink_timestamp,apex,fixed_push_mkdir,stat_v2,abb_exec,cmd,abb,shell_v2"
        private fun parseConnectionString(connectionString: String): ConnectionString {
            val keyValues = connectionString.substringAfter("device::").split(";").map { it.split("=") }
                .mapNotNull { if (it.size != 2) null else it[0] to it[1] }.toMap()
            if ("features" !in keyValues) throw IOException("Failed to parse features from connection string: $connectionString")
            val features = keyValues.getValue("features").split(",").toSet()
            return ConnectionString(features)
        }
    }
}


/*** ADB RSA Public Key Transformation Section ***/
private const val KEY_LENGTH_BITS = 2048
private const val KEY_LENGTH_BYTES = KEY_LENGTH_BITS / 8
private const val KEY_LENGTH_WORDS = KEY_LENGTH_BYTES / 4

@OptIn(ExperimentalEncodingApi::class)
private fun adbPublicKey(keyPair: AdbKeyPair): ByteArray {
    val pubkey = keyPair.publicKey as RSAPublicKey
    val bytes = convertRsaPublicKeyToAdbFormat(pubkey)
    return Base64.encodeToByteArray(bytes) + " ${defaultDeviceName()}}".encodeToByteArray()
}

// https://github.com/cgutman/AdbLib/blob/d6937951eb98557c76ee2081e383d50886ce109a/src/com/cgutman/adblib/AdbCrypto.java#L83-L137
@Suppress("JoinDeclarationAndAssignment")
private fun convertRsaPublicKeyToAdbFormat(pubkey: RSAPublicKey): ByteArray {/*
     * ADB literally just saves the RSAPublicKey struct to a file.
     *
     * typedef struct RSAPublicKey {
     * int len; // Length of n[] in number of uint32_t
     * uint32_t n0inv;  // -1 / n[0] mod 2^32
     * uint32_t n[RSANUMWORDS]; // modulus as little endian array
     * uint32_t rr[RSANUMWORDS]; // R^2 as little endian array
     * int exponent; // 3 or 65537
     * } RSAPublicKey;
     */

    /* ------ This part is a Java-ified version of RSA_to_RSAPublicKey from adb_host_auth.c ------ */
    val r32: BigInteger
    val r: BigInteger
    var rr: BigInteger
    var rem: BigInteger
    var n: BigInteger
    val n0inv: BigInteger
    r32 = BigInteger.ZERO.setBit(32)
    n = pubkey.modulus
    r = BigInteger.ZERO.setBit(KEY_LENGTH_WORDS * 32)
    rr = r.modPow(BigInteger.valueOf(2), n)
    rem = n.remainder(r32)
    n0inv = rem.modInverse(r32)
    val myN = IntArray(KEY_LENGTH_WORDS)
    val myRr = IntArray(KEY_LENGTH_WORDS)
    var res: Array<BigInteger>
    for (i in 0 until KEY_LENGTH_WORDS) {
        res = rr.divideAndRemainder(r32)
        rr = res[0]
        rem = res[1]
        myRr[i] = rem.toInt()
        res = n.divideAndRemainder(r32)
        n = res[0]
        rem = res[1]
        myN[i] = rem.toInt()
    }

    /* ------------------------------------------------------------------------------------------- */
    val bbuf: ByteBuffer = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
    bbuf.putInt(KEY_LENGTH_WORDS)
    bbuf.putInt(n0inv.negate().toInt())
    for (i in myN) bbuf.putInt(i)
    for (i in myRr) bbuf.putInt(i)
    bbuf.putInt(pubkey.publicExponent.toInt())
    return bbuf.array()
}