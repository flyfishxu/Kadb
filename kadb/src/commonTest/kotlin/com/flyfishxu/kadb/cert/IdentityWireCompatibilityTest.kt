package com.flyfishxu.kadb.cert

import com.flyfishxu.kadb.core.AdbMessage
import com.flyfishxu.kadb.core.AdbProtocol
import kotlin.io.encoding.Base64
import kotlin.test.*

class IdentityWireCompatibilityTest {
    @Test fun importedJvmIdentityKeepsItsAdbWireRepresentation() {
        val privatePem = Base64.decode(JvmIdentityFixture.privatePem)
        val certificatePem = Base64.decode(JvmIdentityFixture.certificatePem)
        val identity = CertUtils.identityFromPrivateKeyAndCertificate(privatePem, certificatePem)
        assertContentEquals(privatePem, identity.snapshot.privateKeyPem)
        assertContentEquals(certificatePem, identity.snapshot.certificatePem)
        assertEquals(JvmIdentityFixture.certificateFingerprint, identity.snapshot.fingerprintSha256)
        assertEquals(JvmIdentityFixture.publicFingerprint, identity.keyPair.publicKeyFingerprint())
        assertTrue(identity.keyPair.isCertificateValid())
        assertContentEquals(Base64.decode(JvmIdentityFixture.adbPublicKey), identity.keyPair.encodeAdbPublicKey("kadb-test"))

        val token = Base64.decode(JvmIdentityFixture.token)
        val message = AdbMessage(AdbProtocol.CMD_AUTH, 1, 0, token.size, 0, 0, token)
        assertContentEquals(Base64.decode(JvmIdentityFixture.signature), identity.keyPair.signPayload(message))
    }

    @Test fun generatedIdentityCanBeExportedAndReimported() {
        val identity = CertUtils.generateNewIdentity(KadbCertPolicy())
        val imported = CertUtils.identityFromPrivateKeyAndCertificate(identity.privateKeyPem, identity.snapshot.certificatePem)
        assertContentEquals(identity.snapshot.certificatePem, imported.snapshot.certificatePem)
        assertEquals(identity.snapshot.fingerprintSha256, imported.snapshot.fingerprintSha256)
        assertEquals(identity.keyPair.publicKeyFingerprint(), imported.keyPair.publicKeyFingerprint())
        assertTrue(imported.keyPair.isCertificateValid())
        assertTrue(identity.snapshot.notAfterEpochMillis > 0)
    }

    @Test fun unicodeDeviceNameKeepsEveryUtf8ByteAndNullTerminator() {
        val identity = CertUtils.identityFromPrivateKeyAndCertificate(
            Base64.decode(JvmIdentityFixture.privatePem), Base64.decode(JvmIdentityFixture.certificatePem))
        val asciiFixture = Base64.decode(JvmIdentityFixture.adbPublicKey)
        val base64Key = asciiFixture.decodeToString().substringBefore(' ')
        val name = "飞鱼的 iPhone 📱"
        val expected = (base64Key + " $name\u0000").encodeToByteArray()
        val actual = identity.keyPair.encodeAdbPublicKey(name)
        assertEquals(base64Key.length + 2 + name.encodeToByteArray().size, actual.size)
        assertContentEquals(expected, actual)
        assertEquals(0, actual.last().toInt())
    }

    @Test fun mismatchedCertificateIsRejected() {
        val generated = CertUtils.generateNewIdentity(KadbCertPolicy())
        assertFailsWith<KadbCertException.PolicyViolation> {
            CertUtils.identityFromPrivateKeyAndCertificate(generated.privateKeyPem,
                Base64.decode(JvmIdentityFixture.certificatePem))
        }
    }
}
