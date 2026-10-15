# BoringSSL backend

Source: https://github.com/google/boringssl

Pinned commit: `5b83e5d1abf0ae0033ae52ab579d906b67633b2c`

Archive SHA-256: `4117ae02de040d4469284673ed63a14e648af7c3974aa9e85bcef2975a9c9ea8`

The iOS build fetches the pinned source into the ignored build directory and
compiles static libraries with `BORINGSSL_PREFIX=KADB_BSSL`. No prebuilt native
binaries are checked into the repository. `LICENSE` is copied from this exact
upstream revision and must accompany redistribution.

`../../gradle/ios-native.gradle.kts` pins and verifies both the source archive
and the optional project-local CMake bootstrap. No Python runtime is required.
Update the source commit, archive checksum, license and cross-platform pairing
tests together when upgrading this backend.
