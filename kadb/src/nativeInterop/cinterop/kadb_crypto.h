#ifndef KADB_CRYPTO_H
#define KADB_CRYPTO_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* The bridge owns all returned handles and buffers. No BoringSSL types cross the ABI. */
typedef struct kadb_identity kadb_identity;
typedef struct kadb_spake kadb_spake;
void kadb_crypto_free(void *buffer, size_t length);
kadb_identity *kadb_identity_generate(int bits);
kadb_identity *kadb_identity_import(const uint8_t *pkcs8, size_t length);
void kadb_identity_free(kadb_identity *identity);
int kadb_identity_set_certificate(kadb_identity *identity, const uint8_t *der, size_t length);
int kadb_identity_create_certificate(kadb_identity *identity, int days,
    const char *country, const char *organization, const char *common_name,
    const char *organization_unit, const char *locality, const char *state);
int kadb_identity_private_der(kadb_identity *identity, uint8_t **out, size_t *length);
int kadb_identity_public_der(kadb_identity *identity, uint8_t **out, size_t *length);
int kadb_identity_certificate_der(kadb_identity *identity, uint8_t **out, size_t *length);
int64_t kadb_identity_not_after(kadb_identity *identity);
int kadb_identity_certificate_valid(kadb_identity *identity);
int kadb_identity_sign_token(kadb_identity *identity, const uint8_t *token, size_t length,
    uint8_t out[256]);
int kadb_identity_adb_public_key(kadb_identity *identity, uint8_t out[524]);

kadb_spake *kadb_spake_new(int alice, const uint8_t *my_name, size_t my_name_length,
    const uint8_t *their_name, size_t their_name_length);
void kadb_spake_free(kadb_spake *context);
int kadb_spake_generate(kadb_spake *context, const uint8_t *password, size_t length,
    uint8_t out[32]);
int kadb_spake_process(kadb_spake *context, const uint8_t *message, size_t length,
    uint8_t out[64]);
int kadb_hkdf_sha256(const uint8_t *material, size_t material_length,
    const uint8_t *info, size_t info_length, uint8_t out[16]);
/* Explicit nonce and ciphertext || tag preserve the ADB pairing wire format. */
int kadb_aes_gcm(int encrypt, const uint8_t key[16], const uint8_t nonce[12],
    const uint8_t *input, size_t input_length, uint8_t *output, size_t output_capacity,
    size_t *output_length);

#ifdef __cplusplus
}
#endif
#endif
