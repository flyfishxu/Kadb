# KadbCert

Object `com.flyfishxu.kadb.cert.KadbCert`

Manage Certificates for `Kadb`.

You do not need to use this object if you are using one-time authorization, which does not require the storage of a
keystore elsewhere. In such cases, Kadb will generate a new keystore for each connection. However, if you wish to store
the keystore for future use, you can use this object to manage it.

## Methods

Use the following methods to import keystore:

```
 KadbCert.set(cert: ByteArray, key: ByteArray)
```

An exception will be thrown if the certificate is invalid.

To generate a new keystore and import it into Kadb, use the following method:

```
  KadbCert.get(
    keySize: Int = 2048,
    cn: String = "Kadb",
    ou: String = "Kadb",
    o: String = "Kadb",
    l: String = "Kadb",
    st: String = "Kadb",
    c: String = "Kadb",
    notAfter: Long = System.currentTimeMillis()+10368000000, // 120 days
    serialNumber: BigInteger = BigInteger(64, SecureRandom())
    ): Pair<ByteArray, ByteArray>
```

This method will return a pair consisting of a certificate and a key. You can convert the keystore to a string using
`kotlin.text.ByteArray.decodeToString()` and save it to a database or `SharedPreferences`. It will appear as follows:

```
-----BEGIN CERTIFICATE-----
[B64 encoded certificate]    
-----END CERTIFICATE-----
```

```
-----BEGIN PRIVATE KEY-----
[B64 encoded private key]
-----END PRIVATE KEY-----
```

You can also store the ByteArrays directly in a file.

To retrieve the keystore currently in use, use the following method:

```
  KadbCert.getOrError(): Pair<ByteArray, ByteArray>
```

If you have been using Kadb directly without managing the keystore through `KadbCert`, keep in mind that Kadb
automatically generates a new keystore in memory each time. While using Kadb, you can export this auto-generated
keystore for safekeeping.