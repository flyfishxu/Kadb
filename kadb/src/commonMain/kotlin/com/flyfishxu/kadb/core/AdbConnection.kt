package com.flyfishxu.kadb.core

import com.flyfishxu.kadb.cert.AdbKeyPair
import com.flyfishxu.kadb.cert.AndroidPubkey
import com.flyfishxu.kadb.cert.HostKeySet
import com.flyfishxu.kadb.cert.platform.defaultDeviceName
import com.flyfishxu.kadb.KadbOptions
import com.flyfishxu.kadb.pair.SslUtils
import com.flyfishxu.kadb.queue.AdbMessageQueue
import com.flyfishxu.kadb.stream.AdbStream
import com.flyfishxu.kadb.transport.TlsNioChannel
import com.flyfishxu.kadb.transport.TransportChannel
import com.flyfishxu.kadb.transport.TransportFactory
import com.flyfishxu.kadb.transport.asOkioSink
import com.flyfishxu.kadb.transport.asOkioSource
import com.flyfishxu.kadb.tls.TlsErrorMapper
import okio.Buffer
import org.jetbrains.annotations.TestOnly
import java.io.Closeable
import java.io.IOException
import java.security.interfaces.RSAPublicKey
import java.util.*
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLProtocolException
import kotlin.Throws

internal class AdbConnection internal constructor(
    adbReader: AdbReader,
    private val adbWriter: AdbWriter,
    private val closeable: Closeable?,
    private val supportedFeatures: Set<String>,
    private val version: Int,
    private val outboundMaxPayloadSize: Int
) : AutoCloseable {

    private val random = Random()
    private val messageQueue = AdbMessageQueue(adbReader)
    private val delayedAckEnabled = supportedFeatures.contains(AdbProtocol.FEATURE_DELAYED_ACK)

    @Throws(IOException::class)
    fun open(destination: String): AdbStream {
        val localId = newId()
        messageQueue.startListening(localId)
        try {
            val initialReceiveWindow = if (delayedAckEnabled) AdbProtocol.INITIAL_DELAYED_ACK_BYTES else 0
            adbWriter.writeOpen(localId, destination, initialReceiveWindow)
            val message = messageQueue.take(localId, AdbProtocol.CMD_OKAY)
            val remoteId = message.arg0
            val initialAvailableSendBytes = decodeOkayAckBytes(message, delayedAckEnabled).toLong()

            return AdbStream(
                messageQueue = messageQueue,
                adbWriter = adbWriter,
                outboundMaxPayloadSize = outboundMaxPayloadSize,
                localId = localId,
                remoteId = remoteId,
                delayedAckEnabled = delayedAckEnabled,
                initialAvailableSendBytes = initialAvailableSendBytes
            )
        } catch (e: Throwable) {
            messageQueue.stopListening(localId)
            throw e
        }
    }

    fun supportsFeature(feature: String): Boolean {
        return supportedFeatures.contains(feature)
    }

    // Expose the negotiated CNXN feature set to feature-gated protocol clients (e.g. sync v1/v2).
    // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/client/file_sync_client.cpp#238
    internal fun featureSnapshot(): Set<String> = supportedFeatures.toSet()

    private fun newId(): Int {
        var id: Int
        do {
            id = random.nextInt()
            // local-id must be non-zero per protocol.
            // https://android.googlesource.com/platform/system/core/+/dd7bc3319deb2b77c5d07a51b7d6cd7e11b5beb0/adb/protocol.txt
        } while (id == 0)
        return id
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
        suspend fun connect(
            host: String,
            port: Int,
            hostKeySet: HostKeySet,
            options: KadbOptions = KadbOptions(),
            connectTimeoutMs: Int = 10_000,
            ioTimeoutMs: Int = 0
        ): Pair<AdbConnection, TransportChannel> {
            val connectTimeout = connectTimeoutMs.toLong()
            val ioTimeout = ioTimeoutMs.toLong()
            var authKeyIndex = 0

            var channel: TransportChannel = TransportFactory.connect(host, port, connectTimeout)
            var reader = AdbReader(channel.asOkioSource(ioTimeout))
            var writer = AdbWriter(channel.asOkioSink(ioTimeout))

            try {
                val advertisedFeatures = AdbProtocol.connectFeatures(options.delayedAckMode).toSet()
                val connectPayload = AdbProtocol.connectPayload(
                    advertisedFeatures.toList()
                )
                writer.writeConnect(connectPayload)

                var message: AdbMessage = try {
                    reader.readMessage()
                } catch (e: SSLProtocolException) {
                    throw TlsErrorMapper.map(e)
                }

                while (true) {
                    when (message.command) {
                        AdbProtocol.CMD_STLS -> {
                            // AOSP host always sends STLS with the fixed protocol value A_STLS_VERSION.
                            // https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/adb.cpp#318
                            writer.writeStls(AdbProtocol.A_STLS_VERSION)
                            val sslContext = SslUtils.getSslContext(hostKeySet)
                            val engine = SslUtils.newClientEngine(sslContext, host, port)
                            val tlsChannel = TlsNioChannel(channel, engine)
                            try {
                                tlsChannel.handshake(ioTimeout, TimeUnit.MILLISECONDS)
                            } catch (t: Throwable) {
                                throw TlsErrorMapper.map(t)
                            }

                            reader.close()
                            writer.close()

                            channel = tlsChannel
                            reader = AdbReader(channel.asOkioSource(ioTimeout))
                            writer = AdbWriter(channel.asOkioSink(ioTimeout))
                            message = reader.readMessage()
                        }

                        AdbProtocol.CMD_AUTH -> {
                            check(message.arg0 == AdbProtocol.AUTH_TYPE_TOKEN) { "Unsupported auth type: $message" }
                            val authKey = hostKeySet.keyPairs.getOrNull(authKeyIndex)
                            if (authKey != null) {
                                authKeyIndex += 1
                                writer.writeAuth(AdbProtocol.AUTH_TYPE_SIGNATURE, authKey.signPayload(message))
                            } else {
                                writer.writeAuth(AdbProtocol.AUTH_TYPE_RSA_PUBLIC, adbPublicKey(hostKeySet.defaultKeyPair))
                            }
                            message = reader.readMessage()
                        }

                        AdbProtocol.CMD_CNXN -> break

                        else -> throw IOException("Connection failed: $message")
                    }
                }

                // parse_banner() accepts missing features and resets feature set to empty.
                // https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/adb.cpp#351
                val connectionString = parseConnectionString(String(message.payload))
                val negotiatedFeatures = connectionString.features.intersect(advertisedFeatures)
                // Mirror atransport::update_version(): protocol_version = min(peer_version, A_VERSION).
                // https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/transport.cpp#1172
                val version = minOf(message.arg0, AdbProtocol.A_VERSION)
                writer.updateProtocolVersion(version)
                val peerMaxPayloadSize = message.arg1
                // CNXN arg1 carries the peer's maxdata (its receive limit).
                // https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/master/adb.cpp
                if (peerMaxPayloadSize <= 0) {
                    throw IOException("Peer maxdata must be > 0: $peerMaxPayloadSize")
                }
                val localHardCap = AdbProtocol.CONNECT_MAXDATA
                val negotiatedMaxPayloadSize = minOf(peerMaxPayloadSize, localHardCap)
                    .coerceAtLeast(1)
                    .coerceAtMost(localHardCap)
                // AOSP negotiates max payload as min(peer_max, MAX_PAYLOAD).
                // https://android.googlesource.com/platform/system/core/+/3d2904c%5E%21/
                // ADB payload size constants.
                // https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/master/adb.h

                val inboundMaxPayloadSize = negotiatedMaxPayloadSize
                val outboundMaxPayloadSize = negotiatedMaxPayloadSize
                // WRTE payloads must fit within the receiver's maxdata.
                // https://android.googlesource.com/platform/system/core/+/dd7bc3319deb2b77c5d07a51b7d6cd7e11b5beb0/adb/protocol.txt

                reader.setInboundMaxPayloadSize(inboundMaxPayloadSize)

                val connection = AdbConnection(
                    reader,
                    writer,
                    channel,
                    negotiatedFeatures,
                    version,
                    outboundMaxPayloadSize
                )

                return connection to channel
            } catch (t: Throwable) {
                try {
                    reader.close()
                } catch (_: Throwable) {
                }
                try {
                    writer.close()
                } catch (_: Throwable) {
                }
                try {
                    channel.close()
                } catch (_: Throwable) {
                }
                throw t
            }
        }

        suspend fun connect(
            host: String,
            port: Int,
            keyPair: AdbKeyPair,
            options: KadbOptions = KadbOptions(),
            connectTimeoutMs: Int = 10_000,
            ioTimeoutMs: Int = 0
        ): Pair<AdbConnection, TransportChannel> {
            return connect(
                host = host,
                port = port,
                hostKeySet = HostKeySet.single(keyPair),
                options = options,
                connectTimeoutMs = connectTimeoutMs,
                ioTimeoutMs = ioTimeoutMs
            )
        }

        private data class ConnectionString(val features: Set<String>)

        // ie: "device::ro.product.name=sdk_gphone_x86;ro.product.model=Android SDK built for x86;ro.product.device=generic_x86;,features=fixed_push_symlink_timestamp,apex,fixed_push_mkdir,stat_v2,abb_exec,cmd,abb,shell_v2"
        private fun parseConnectionString(connectionString: String): ConnectionString {
            // parse_banner() splits on ":" and only parses props when pieces.size() > 2.
            // https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/adb.cpp#351
            val pieces = connectionString.split(":")
            if (pieces.size <= 2) return ConnectionString(emptySet())

            // AOSP resets features to empty first, then fills from "features=" when present.
            // https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/adb.cpp#359
            var features: Set<String> = emptySet()
            for (prop in pieces[2].split(";")) {
                if (prop.isEmpty()) continue
                // Match parse_banner() behavior that keeps only strict key=value pairs.
                // https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/adb.cpp#367
                val delimiter = prop.indexOf('=')
                if (delimiter <= 0 || delimiter == prop.lastIndex) continue
                val key = prop.substring(0, delimiter)
                val value = prop.substring(delimiter + 1)
                if (key == "features") {
                    features = value.split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toSet()
                }
            }
            return ConnectionString(features)
        }

        private fun decodeOkayAckBytes(message: AdbMessage, delayedAckEnabled: Boolean): Int {
            require(message.command == AdbProtocol.CMD_OKAY) { "Expected OKAY message, got ${message.command}" }
            return when (message.payloadLength) {
                0 -> {
                    if (delayedAckEnabled) {
                        throw IOException("Delayed ACK stream missing OKAY payload for localId: ${message.arg1.toString(16)}")
                    }
                    0
                }

                Int.SIZE_BYTES -> {
                    if (!delayedAckEnabled) {
                        throw IOException("Unexpected OKAY payload for non-delayed-ack stream: ${message.payloadLength}")
                    }
                    Buffer()
                        .write(message.payload)
                        .readIntLe()
                }

                else -> throw IOException("Invalid OKAY payload size: ${message.payloadLength}")
            }
        }
    }
}


/*** ADB RSA Public Key Transformation Section ***/
private fun adbPublicKey(keyPair: AdbKeyPair): ByteArray {
    val publicKey = keyPair.publicKey as RSAPublicKey
    return AndroidPubkey.encodeWithName(publicKey, defaultDeviceName())
}
