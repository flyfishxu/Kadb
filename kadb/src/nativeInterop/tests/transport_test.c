/* Standalone integration test: compile with the same prefixed BoringSSL as the iOS bridge. */
#include "kadb_transport.h"
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <openssl/rsa.h>
#include <openssl/sha.h>
#include <openssl/ssl.h>
#include <openssl/x509.h>
#include <assert.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

typedef struct {
    EVP_PKEY *key;
    X509 *cert;
    BIO *key_pem;
    BIO *cert_pem;
    char fingerprint[65];
} identity;

static identity generate_identity(int serial) {
    identity result = {0};
    BIGNUM *exponent = BN_new();
    assert(BN_set_word(exponent, RSA_F4));
    RSA *rsa = RSA_new();
    assert(RSA_generate_key_ex(rsa, 2048, exponent, NULL));
    BN_free(exponent);
    result.key = EVP_PKEY_new();
    assert(EVP_PKEY_assign_RSA(result.key, rsa));
    result.cert = X509_new();
    assert(X509_set_version(result.cert, 2));
    assert(ASN1_INTEGER_set(X509_get_serialNumber(result.cert), serial));
    assert(X509_gmtime_adj(X509_getm_notBefore(result.cert), -60));
    assert(X509_gmtime_adj(X509_getm_notAfter(result.cert), 86400));
    assert(X509_set_pubkey(result.cert, result.key));
    X509_NAME *name = X509_get_subject_name(result.cert);
    assert(X509_NAME_add_entry_by_txt(name, "CN", MBSTRING_ASC,
                                    (const unsigned char *) "Kadb test", -1, -1, 0));
    assert(X509_set_issuer_name(result.cert, name));
    assert(X509_sign(result.cert, result.key, EVP_sha256()));
    result.key_pem = BIO_new(BIO_s_mem());
    result.cert_pem = BIO_new(BIO_s_mem());
    assert(PEM_write_bio_PrivateKey(result.key_pem, result.key, NULL, NULL, 0, NULL, NULL));
    assert(PEM_write_bio_X509(result.cert_pem, result.cert));
    unsigned char *der = NULL;
    int length = i2d_PUBKEY(result.key, &der);
    assert(length > 0);
    uint8_t digest[32];
    SHA256(der, (size_t) length, digest);
    OPENSSL_free(der);
    for (int i = 0; i < 32; ++i) snprintf(result.fingerprint + i * 2, 3, "%02X", digest[i]);
    return result;
}

static void free_identity(identity *identity) {
    BIO_free(identity->key_pem);
    BIO_free(identity->cert_pem);
    X509_free(identity->cert);
    EVP_PKEY_free(identity->key);
}

static void add_identity(kadb_io *io, identity *identity) {
    char *key_bytes, *cert_bytes, error[512];
    long key_size = BIO_get_mem_data(identity->key_pem, &key_bytes);
    long cert_size = BIO_get_mem_data(identity->cert_pem, &cert_bytes);
    assert(kadb_io_add_identity(io, (const uint8_t *) key_bytes, key_size,
                               (const uint8_t *) cert_bytes, cert_size,
                               identity->fingerprint, error, sizeof(error)) == 1);
}

static int accept_certificate(int verified, X509_STORE_CTX *context) {
    (void) verified;
    (void) context;
    return 1;
}

typedef struct {
    int fd;
    SSL_CTX *context;
    X509 *expected_client;
} server_args;

static void *tls_server(void *argument) {
    server_args *args = argument;
    uint8_t bytes[64];
    assert(recv(args->fd, bytes, 4, MSG_WAITALL) == 4 && memcmp(bytes, "CNXN", 4) == 0);
    assert(send(args->fd, "STLS", 4, 0) == 4);
    assert(recv(args->fd, bytes, 4, MSG_WAITALL) == 4 && memcmp(bytes, "STLS", 4) == 0);
    SSL *ssl = SSL_new(args->context);
    assert(SSL_set_fd(ssl, args->fd));
    assert(SSL_accept(ssl) == 1);
    assert(SSL_version(ssl) == TLS1_3_VERSION);
    X509 *peer = SSL_get_peer_certificate(ssl);
    assert(peer && X509_cmp(peer, args->expected_client) == 0);
    X509_free(peer);
    static const char label[] = "adb-label";
    assert(SSL_export_keying_material(ssl, bytes, sizeof(bytes), label, sizeof(label), NULL, 0, 0));
    assert(SSL_write(ssl, bytes, sizeof(bytes)) == sizeof(bytes));
    assert(SSL_read(ssl, bytes, 4) == 4 && memcmp(bytes, "OPEN", 4) == 0);
    assert(SSL_write(ssl, "OKAY", 4) == 4);
    SSL_shutdown(ssl);
    SSL_free(ssl);
    close(args->fd);
    return NULL;
}

static void test_stls_and_exporter(void) {
    identity first = generate_identity(1), selected = generate_identity(2);
    SSL_CTX *server = SSL_CTX_new(TLS_method());
    assert(SSL_CTX_set_min_proto_version(server, TLS1_3_VERSION));
    assert(SSL_CTX_set_max_proto_version(server, TLS1_3_VERSION));
    assert(SSL_CTX_use_certificate(server, first.cert));
    assert(SSL_CTX_use_PrivateKey(server, first.key));
    SSL_CTX_set_verify(server, SSL_VERIFY_PEER | SSL_VERIFY_FAIL_IF_NO_PEER_CERT, accept_certificate);
    STACK_OF(X509_NAME) *issuers = sk_X509_NAME_new_null();
    X509_NAME *issuer = X509_NAME_new();
    assert(X509_NAME_add_entry_by_txt(issuer, "O", MBSTRING_ASC,
                                    (const unsigned char *) "AdbKey-0", -1, -1, 0));
    assert(X509_NAME_add_entry_by_txt(issuer, "CN", MBSTRING_ASC,
                                    (const unsigned char *) selected.fingerprint, -1, -1, 0));
    assert(sk_X509_NAME_push(issuers, issuer));
    SSL_CTX_set_client_CA_list(server, issuers);
    int sockets[2];
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) == 0);
    server_args args = {.fd = sockets[1], .context = server, .expected_client = selected.cert};
    pthread_t thread;
    assert(pthread_create(&thread, NULL, tls_server, &args) == 0);
    char error[512];
    kadb_io *client = kadb_io_adopt(sockets[0], error, sizeof(error));
    assert(client);
    uint8_t bytes[64], exported[64];
    assert(kadb_io_write(client, (const uint8_t *) "CNXN", 4, 1000, error, sizeof(error)) == 4);
    assert(kadb_io_read(client, bytes, 4, 1000, error, sizeof(error)) == 4 && memcmp(bytes, "STLS", 4) == 0);
    assert(kadb_io_write(client, (const uint8_t *) "STLS", 4, 1000, error, sizeof(error)) == 4);
    add_identity(client, &first);
    add_identity(client, &selected);
    if (!kadb_io_start_tls(client, 5000, error, sizeof(error))) {
        fprintf(stderr, "%s\n", error);
        abort();
    }
    assert(kadb_io_export_pairing_key(client, exported, sizeof(exported), error, sizeof(error)) == 1);
    int received = 0;
    while (received < 64) {
        int count = kadb_io_read(client, bytes + received, 64 - received, 1000, error, sizeof(error));
        assert(count > 0);
        received += count;
    }
    assert(memcmp(bytes, exported, sizeof(bytes)) == 0);
    assert(kadb_io_write(client, (const uint8_t *) "OPEN", 4, 1000, error, sizeof(error)) == 4);
    assert(kadb_io_read(client, bytes, 4, 1000, error, sizeof(error)) == 4 && memcmp(bytes, "OKAY", 4) == 0);
    assert(kadb_io_read(client, bytes, 4, 1000, error, sizeof(error)) == 0);
    kadb_io_close(client);
    assert(!kadb_io_is_open(client));
    kadb_io_free(client);
    pthread_join(thread, NULL);
    SSL_CTX_free(server);
    free_identity(&first);
    free_identity(&selected);
}

static void *blocked_reader(void *argument) {
    uint8_t byte;
    char error[512];
    assert(kadb_io_read(argument, &byte, 1, 0, error, sizeof(error)) == -1);
    assert(strstr(error, "closed"));
    return NULL;
}

static void test_timeout_and_close(void) {
    int sockets[2];
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) == 0);
    char error[512];
    kadb_io *client = kadb_io_adopt(sockets[0], error, sizeof(error));
    assert(client);
    uint8_t byte;
    assert(kadb_io_read(client, &byte, 1, 20, error, sizeof(error)) == -1);
    assert(strstr(error, "timed out"));
    pthread_t thread;
    assert(pthread_create(&thread, NULL, blocked_reader, client) == 0);
    usleep(20000);
    kadb_io_close(client);
    pthread_join(thread, NULL);
    kadb_io_free(client);
    close(sockets[1]);
}

static void test_tcp_connect(void) {
    int listener = socket(AF_INET, SOCK_STREAM, 0);
    assert(listener >= 0);
    struct sockaddr_in address = {.sin_family = AF_INET, .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    assert(bind(listener, (struct sockaddr *) &address, sizeof(address)) == 0);
    assert(listen(listener, 1) == 0);
    socklen_t size = sizeof(address);
    assert(getsockname(listener, (struct sockaddr *) &address, &size) == 0);
    char error[512];
    kadb_io *client = kadb_io_connect("localhost", ntohs(address.sin_port), 1000, 1, 1, 10,
                                     error, sizeof(error));
    assert(client);
    int server = accept(listener, NULL, NULL);
    assert(server >= 0);
    assert(send(server, "CNXN", 4, 0) == 4);
    uint8_t bytes[4];
    assert(kadb_io_read(client, bytes, 4, 1000, error, sizeof(error)) == 4);
    assert(memcmp(bytes, "CNXN", 4) == 0);
    kadb_io_free(client);
    close(server);
    close(listener);
}

int main(void) {
    test_stls_and_exporter();
    test_timeout_and_close();
    test_tcp_connect();
    puts("PASS: TCP connect, same-socket STLS/TLS 1.3, client identity selection, pairing exporter, I/O, EOF, timeout, concurrent close");
    return 0;
}
