#include "kadb_crypto.h"

#include <limits.h>
#include <string.h>
#include <time.h>
#include <openssl/aead.h>
#include <openssl/bn.h>
#include <openssl/bytestring.h>
#include <openssl/curve25519.h>
#include <openssl/digest.h>
#include <openssl/evp.h>
#include <openssl/hkdf.h>
#include <openssl/mem.h>
#include <openssl/rsa.h>
#include <openssl/x509.h>
#include <openssl/x509v3.h>

struct kadb_identity {
    EVP_PKEY *key;
    X509 *certificate;
};

struct kadb_spake {
    SPAKE2_CTX *context;
};

void kadb_crypto_free(void *buffer, size_t length) {
    if (buffer != NULL) OPENSSL_cleanse(buffer, length);
    OPENSSL_free(buffer);
}

void kadb_identity_free(kadb_identity *identity) {
    if (identity == NULL) return;
    EVP_PKEY_free(identity->key);
    X509_free(identity->certificate);
    OPENSSL_free(identity);
}

kadb_identity *kadb_identity_generate(int bits) {
    if (bits != 2048) return NULL;
    kadb_identity *identity = OPENSSL_zalloc(sizeof(*identity));
    RSA *rsa = RSA_new();
    BIGNUM *exponent = BN_new();
    int ok = identity != NULL && rsa != NULL && exponent != NULL &&
        BN_set_word(exponent, RSA_F4) && RSA_generate_key_ex(rsa, bits, exponent, NULL);
    if (ok) {
        identity->key = EVP_PKEY_new();
        ok = identity->key != NULL && EVP_PKEY_set1_RSA(identity->key, rsa);
    }
    BN_free(exponent);
    RSA_free(rsa);
    if (!ok) {
        kadb_identity_free(identity);
        return NULL;
    }
    return identity;
}

kadb_identity *kadb_identity_import(const uint8_t *pkcs8, size_t length) {
    CBS cbs;
    CBS_init(&cbs, pkcs8, length);
    EVP_PKEY *key = EVP_parse_private_key(&cbs);
    if (key == NULL || CBS_len(&cbs) != 0 || EVP_PKEY_id(key) != EVP_PKEY_RSA) {
        EVP_PKEY_free(key);
        return NULL;
    }
    kadb_identity *identity = OPENSSL_zalloc(sizeof(*identity));
    if (identity == NULL) {
        EVP_PKEY_free(key);
        return NULL;
    }
    identity->key = key;
    return identity;
}

int kadb_identity_set_certificate(kadb_identity *identity, const uint8_t *der, size_t length) {
    if (identity == NULL || length > LONG_MAX) return 0;
    const uint8_t *cursor = der;
    X509 *certificate = d2i_X509(NULL, &cursor, (long)length);
    if (certificate == NULL || cursor != der + length ||
        !X509_check_private_key(certificate, identity->key)) {
        X509_free(certificate);
        return 0;
    }
    X509_free(identity->certificate);
    identity->certificate = certificate;
    return 1;
}

static int add_name(X509_NAME *name, const char *field, const char *value) {
    return value == NULL || value[0] == 0 ||
        X509_NAME_add_entry_by_txt(name, field, MBSTRING_UTF8,
            (const uint8_t *)value, -1, -1, 0);
}

static int add_extension(X509 *certificate, X509V3_CTX *context, int nid, const char *value) {
    X509_EXTENSION *extension = X509V3_EXT_nconf_nid(NULL, context, nid, value);
    if (extension == NULL) return 0;
    int ok = X509_add_ext(certificate, extension, -1);
    X509_EXTENSION_free(extension);
    return ok;
}

int kadb_identity_create_certificate(kadb_identity *identity, int days,
    const char *country, const char *organization, const char *common_name,
    const char *organization_unit, const char *locality, const char *state) {
    if (identity == NULL || days <= 0 || (int64_t)days * 86400 > LONG_MAX) return 0;
    X509 *certificate = X509_new();
    if (certificate == NULL) return 0;
    X509_NAME *name = X509_get_subject_name(certificate);
    X509V3_CTX context;
    X509V3_set_ctx(&context, certificate, certificate, NULL, NULL, 0);
    int ok = X509_set_version(certificate, 2) &&
        ASN1_INTEGER_set(X509_get_serialNumber(certificate), 1) &&
        X509_gmtime_adj(X509_getm_notBefore(certificate), 0) != NULL &&
        X509_gmtime_adj(X509_getm_notAfter(certificate), (long)days * 86400) != NULL &&
        X509_set_pubkey(certificate, identity->key) &&
        add_name(name, "C", country) && add_name(name, "O", organization) &&
        add_name(name, "CN", common_name) && add_name(name, "OU", organization_unit) &&
        add_name(name, "L", locality) && add_name(name, "ST", state) &&
        X509_set_issuer_name(certificate, name) &&
        add_extension(certificate, &context, NID_basic_constraints, "critical,CA:TRUE") &&
        add_extension(certificate, &context, NID_key_usage, "critical,digitalSignature,keyCertSign,cRLSign") &&
        add_extension(certificate, &context, NID_subject_key_identifier, "hash") &&
        X509_sign(certificate, identity->key, EVP_sha256()) > 0;
    if (!ok) {
        X509_free(certificate);
        return 0;
    }
    X509_free(identity->certificate);
    identity->certificate = certificate;
    return 1;
}

int kadb_identity_private_der(kadb_identity *identity, uint8_t **out, size_t *length) {
    CBB cbb;
    if (!CBB_init(&cbb, 2048)) return 0;
    int ok = EVP_marshal_private_key(&cbb, identity->key) && CBB_finish(&cbb, out, length);
    CBB_cleanup(&cbb);
    return ok;
}

int kadb_identity_public_der(kadb_identity *identity, uint8_t **out, size_t *length) {
    CBB cbb;
    if (!CBB_init(&cbb, 512)) return 0;
    int ok = EVP_marshal_public_key(&cbb, identity->key) && CBB_finish(&cbb, out, length);
    CBB_cleanup(&cbb);
    return ok;
}

int kadb_identity_certificate_der(kadb_identity *identity, uint8_t **out, size_t *length) {
    if (identity->certificate == NULL) return 0;
    *out = NULL;
    int size = i2d_X509(identity->certificate, out);
    if (size <= 0) return 0;
    *length = (size_t)size;
    return 1;
}

int64_t kadb_identity_not_after(kadb_identity *identity) {
    int64_t value;
    if (identity->certificate == NULL ||
        !ASN1_TIME_to_posix(X509_get0_notAfter(identity->certificate), &value)) return -1;
    return value * 1000;
}

int kadb_identity_certificate_valid(kadb_identity *identity) {
    if (identity->certificate == NULL) return 0;
    int before = X509_cmp_current_time(X509_get0_notBefore(identity->certificate));
    int after = X509_cmp_current_time(X509_get0_notAfter(identity->certificate));
    return before < 0 && after > 0;
}

int kadb_identity_sign_token(kadb_identity *identity, const uint8_t *token, size_t length,
    uint8_t out[256]) {
    if (identity == NULL || length != 20) return 0;
    RSA *rsa = EVP_PKEY_get1_RSA(identity->key);
    unsigned int written = 0;
    /* RSA_sign receives the SHA-1 digest itself; it must not hash the ADB token again. */
    int ok = rsa != NULL && RSA_size(rsa) == 256 &&
        RSA_sign(NID_sha1, token, length, out, &written, rsa) && written == 256;
    RSA_free(rsa);
    return ok;
}

static void write_le32(uint8_t *output, uint32_t value) {
    for (int i = 0; i < 4; ++i) output[i] = (uint8_t)(value >> (8 * i));
}

int kadb_identity_adb_public_key(kadb_identity *identity, uint8_t out[524]) {
    if (identity == NULL) return 0;
    RSA *rsa = EVP_PKEY_get1_RSA(identity->key);
    BN_CTX *ctx = BN_CTX_new();
    BIGNUM *r32 = BN_new(), *inverse = BN_new(), *rr = BN_new();
    const BIGNUM *n = NULL, *e = NULL;
    if (rsa != NULL) RSA_get0_key(rsa, &n, &e, NULL);
    int ok = rsa != NULL && RSA_size(rsa) == 256 && ctx != NULL &&
        r32 != NULL && inverse != NULL && rr != NULL && BN_num_bits(e) <= 32 &&
        BN_set_bit(r32, 32) && BN_mod_inverse(inverse, n, r32, ctx) != NULL &&
        BN_sub(inverse, r32, inverse) && BN_set_bit(rr, 4096) && BN_mod(rr, rr, n, ctx) &&
        BN_bn2le_padded(out + 8, 256, n) && BN_bn2le_padded(out + 264, 256, rr);
    if (ok) {
        write_le32(out, 64);
        write_le32(out + 4, (uint32_t)BN_get_word(inverse));
        write_le32(out + 520, (uint32_t)BN_get_word(e));
    }
    BN_free(r32);
    BN_free(inverse);
    BN_free(rr);
    BN_CTX_free(ctx);
    RSA_free(rsa);
    return ok;
}

kadb_spake *kadb_spake_new(int alice, const uint8_t *my_name, size_t my_name_length,
    const uint8_t *their_name, size_t their_name_length) {
    kadb_spake *result = OPENSSL_zalloc(sizeof(*result));
    if (result == NULL) return NULL;
    result->context = SPAKE2_CTX_new(alice ? spake2_role_alice : spake2_role_bob,
        my_name, my_name_length, their_name, their_name_length);
    if (result->context == NULL) {
        OPENSSL_free(result);
        return NULL;
    }
    return result;
}

void kadb_spake_free(kadb_spake *context) {
    if (context == NULL) return;
    SPAKE2_CTX_free(context->context);
    OPENSSL_free(context);
}

int kadb_spake_generate(kadb_spake *context, const uint8_t *password, size_t length,
    uint8_t out[32]) {
    size_t written = 0;
    return SPAKE2_generate_msg(context->context, out, &written, 32, password, length) && written == 32;
}

int kadb_spake_process(kadb_spake *context, const uint8_t *message, size_t length,
    uint8_t out[64]) {
    size_t written = 0;
    return SPAKE2_process_msg(context->context, out, &written, 64, message, length) && written == 64;
}

int kadb_hkdf_sha256(const uint8_t *material, size_t material_length,
    const uint8_t *info, size_t info_length, uint8_t out[16]) {
    return HKDF(out, 16, EVP_sha256(), material, material_length, NULL, 0, info, info_length);
}

int kadb_aes_gcm(int encrypt, const uint8_t key[16], const uint8_t nonce[12],
    const uint8_t *input, size_t input_length, uint8_t *output, size_t output_capacity,
    size_t *output_length) {
    EVP_AEAD_CTX *context = EVP_AEAD_CTX_new(EVP_aead_aes_128_gcm(), key, 16, 16);
    if (context == NULL) return 0;
    int ok = encrypt
        ? EVP_AEAD_CTX_seal(context, output, output_length, output_capacity, nonce, 12,
            input, input_length, NULL, 0)
        : EVP_AEAD_CTX_open(context, output, output_length, output_capacity, nonce, 12,
            input, input_length, NULL, 0);
    EVP_AEAD_CTX_free(context);
    return ok;
}
