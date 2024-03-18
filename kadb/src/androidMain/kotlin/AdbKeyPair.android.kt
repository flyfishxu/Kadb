package com.flyfishxu.kadb

import android.os.Build
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.util.*
import javax.security.auth.x500.X500Principal


// TODO: DO NOT HARD CODE THE DEVICE NAME
actual fun AdbKeyPair.Companion.getDeviceName(): String {
    return "${Build.MODEL.replace(" ", "")}@Kadb"
}