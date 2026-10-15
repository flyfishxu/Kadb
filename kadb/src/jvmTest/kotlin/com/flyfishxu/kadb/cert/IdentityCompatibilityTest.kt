package com.flyfishxu.kadb.cert

import com.flyfishxu.kadb.core.AdbMessage
import com.flyfishxu.kadb.core.AdbProtocol
import java.security.MessageDigest
import java.security.Signature
import kotlin.test.*

class IdentityCompatibilityTest {
    @Test fun importedIdentityKeepsCertificateFingerprintAndAdbSignature() {
        val identity = CertUtils.generateNewIdentity(KadbCertPolicy())
        val imported = CertUtils.identityFromPrivateKeyAndCertificate(
            identity.privateKeyPem, CertUtils.encodeCertificatePem(identity.keyPair.certificate)
        )
        assertContentEquals(identity.privateKeyPem, imported.privateKeyPem)
        assertContentEquals(identity.keyPair.certificate.encoded, imported.keyPair.certificate.encoded)
        assertEquals(identity.keyPair.publicKeyFingerprint(), imported.keyPair.publicKeyFingerprint())
        assertTrue(imported.keyPair.isCertificateValid())
        imported.keyPair.certificate.verify(imported.keyPair.publicKey)

        // ADB signs an already hashed token; SHA1withRSA must verify the original data.
        val original = "ADB authentication compatibility".encodeToByteArray()
        val token = MessageDigest.getInstance("SHA-1").digest(original)
        val message = AdbMessage(AdbProtocol.CMD_AUTH, 1, 0, token.size, 0, 0, token)
        val signature = imported.keyPair.signPayload(message)
        assertEquals(256, signature.size)
        assertTrue(Signature.getInstance("SHA1withRSA").run {
            initVerify(imported.keyPair.publicKey)
            update(original)
            verify(signature)
        })
        assertContentEquals(identity.keyPair.signPayload(message), signature)
        assertContentEquals(identity.keyPair.encodeAdbPublicKey("test"), imported.keyPair.encodeAdbPublicKey("test"))
    }
}
