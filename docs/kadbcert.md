# KadbCert

Object: `com.flyfishxu.kadb.cert.KadbCert`

`KadbCert` uses a private-key-first identity model:

- Private key is the persisted source of truth.
- Certificate is always generated from the private key at runtime.
- Importing a certificate only affects the current in-memory identity; persistence stores the private key only.

## Configure

```kotlin
val store = OkioFilePrivateKeyStore(
    privateKeyPath = "/path/to/private_key.pem".toPath()
)

KadbCert.configure(
    store = store,
    policy = KadbCertPolicy(),
    additionalPrivateKeysPem = emptyList()
)
```

## APIs

```kotlin
KadbCert.ensureReady(): KadbIdentitySnapshot
KadbCert.rotate(): KadbIdentitySnapshot
KadbCert.importIdentity(privateKeyPem: ByteArray, certificatePem: ByteArray? = null): KadbIdentitySnapshot
KadbCert.importPrivateKey(privateKeyPem: ByteArray): KadbIdentitySnapshot
KadbCert.exportIdentityOrNull(): KadbIdentitySnapshot?
KadbCert.exportPrivateKeyOrNull(): ByteArray?
KadbCert.clear()
```

## Store Contract

`KadbPrivateKeyStore` is intentionally minimal:

- `readPrivateKeyPem()`
- `writePrivateKeyPemAtomic(...)`
- `clear()`

Kadb persists only the private key. X.509 certificates are derived on demand from that key.

`additionalPrivateKeysPem` is an in-memory-only host key ring. It lets Kadb mirror AOSP host behavior more closely:

- classic `AUTH` will try every configured private key before sending the default user public key
- TLS client auth will prefer the key requested by the device CA issuer list when a match exists
- pairing still uses the default persisted user key, matching current AOSP host behavior
- malformed optional keys are ignored; they do not block the default persisted key from being used

## Policy Defaults

`KadbCertPolicy` defaults:

- `keySizeBits = 2048`
- `certValidityDays = 3650`
- `autoHealInvalidPrivateKey = true`
- Subject defaults aligned with AOSP profile: `C=US, O=Android, CN=Adb`

## Certificate Profile

Generated certificate profile:

- Serial number: `1`
- Validity: `now` to `now + 10 years`
- Signature: `SHA256withRSA`
- Extensions: `BasicConstraints`, `KeyUsage`, `SubjectKeyIdentifier`

## Notes

- `Kadb.create()` / `Kadb.pair()` APIs are unchanged.
- `SslUtils` cache is keyed by certificate fingerprint, so certificate rotation/invalidation rebuilds `SSLContext`.
- `KadbIdentitySnapshot` is a runtime materialization. With the same persisted private key, `certificatePem`,
  `fingerprintSha256`, and `notAfterEpochMillis` can change if the certificate generation policy changes.
- `additionalPrivateKeysPem` does not change `KadbIdentitySnapshot`; the snapshot always describes the default persisted key.
