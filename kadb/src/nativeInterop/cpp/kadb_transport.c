#include "kadb_transport.h"

#include <openssl/err.h>
#include <openssl/pem.h>
#include <openssl/ssl.h>
#include <openssl/x509.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

typedef struct kadb_identity {
    EVP_PKEY *key;
    X509 *cert;
    char fingerprint[65];
    struct kadb_identity *next;
} kadb_identity;

struct kadb_io {
    pthread_mutex_t lock;
    int fd;
    int closed;
    int active;
    SSL_CTX *context;
    SSL *ssl;
    kadb_identity *identities;
};

static void set_error(char *error, size_t size, const char *message) {
    if (size > 0) snprintf(error, size, "%s", message);
}

static void system_error(char *error, size_t size, const char *operation) {
    if (size > 0) snprintf(error, size, "%s: %s", operation, strerror(errno));
}

static void tls_error(char *error, size_t size, const char *operation, int code) {
    char detail[256];
    unsigned long reason = ERR_get_error();
    if (reason) ERR_error_string_n(reason, detail, sizeof(detail));
    else snprintf(detail, sizeof(detail), "TLS error %d", code);
    if (size > 0) snprintf(error, size, "%s: %s", operation, detail);
}

static int64_t monotonic_ms(void) {
    struct timespec time;
    clock_gettime(CLOCK_MONOTONIC, &time);
    return (int64_t) time.tv_sec * 1000 + time.tv_nsec / 1000000;
}

static int64_t make_deadline(int64_t timeout_ms) {
    return timeout_ms > 0 ? monotonic_ms() + timeout_ms : 0;
}

static int await_fd(int fd, short events, int64_t deadline, char *error, size_t size) {
    for (;;) {
        int64_t remaining = deadline ? deadline - monotonic_ms() : -1;
        if (deadline && remaining <= 0) {
            set_error(error, size, "Socket operation timed out");
            return 0;
        }
        struct pollfd descriptor = {.fd = fd, .events = events};
        int result = poll(&descriptor, 1, remaining > INT_MAX ? INT_MAX : (int) remaining);
        if (result > 0) return 1; /* The next I/O reports EOF or the precise socket error. */
        if (result == 0) {
            set_error(error, size, "Socket operation timed out");
            return 0;
        }
        if (errno != EINTR) {
            system_error(error, size, "poll");
            return 0;
        }
    }
}

static int configure_fd(int fd, char *error, size_t size) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0) {
        system_error(error, size, "fcntl");
        return 0;
    }
    int yes = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &yes, sizeof(yes));
    /* JVM/Android always enable keepalive; an absent policy retains OS timings. */
    setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &yes, sizeof(yes));
#ifdef SO_NOSIGPIPE
    if (setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &yes, sizeof(yes)) < 0) {
        system_error(error, size, "SO_NOSIGPIPE");
        return 0;
    }
#endif
    return 1;
}

kadb_io *kadb_io_adopt(int fd, char *error, size_t error_size) {
    if (fd < 0) {
        set_error(error, error_size, "Invalid socket descriptor");
        return NULL;
    }
    if (!configure_fd(fd, error, error_size)) {
        close(fd);
        return NULL;
    }
    kadb_io *io = calloc(1, sizeof(*io));
    if (!io) {
        set_error(error, error_size, "Unable to allocate socket transport");
        close(fd);
        return NULL;
    }
    if (pthread_mutex_init(&io->lock, NULL) != 0) {
        set_error(error, error_size, "Unable to initialize socket lock");
        close(fd);
        free(io);
        return NULL;
    }
    io->fd = fd;
    return io;
}

kadb_io *kadb_io_connect(const char *host, int port, int64_t timeout_ms,
                         int idle, int interval, int count, char *error, size_t error_size) {
    if (port < 1 || port > 65535) {
        set_error(error, error_size, "Port must be between 1 and 65535");
        return NULL;
    }
    char service[6];
    snprintf(service, sizeof(service), "%d", port);
    struct addrinfo hints = {.ai_family = AF_UNSPEC, .ai_socktype = SOCK_STREAM};
    struct addrinfo *addresses = NULL;
    int lookup = getaddrinfo(host, service, &hints, &addresses);
    if (lookup) {
        set_error(error, error_size, gai_strerror(lookup));
        return NULL;
    }
    int64_t deadline = make_deadline(timeout_ms);
    kadb_io *connected = NULL;
    set_error(error, error_size, "No address available for host");
    for (struct addrinfo *address = addresses; address; address = address->ai_next) {
        int fd = socket(address->ai_family, address->ai_socktype, address->ai_protocol);
        if (fd < 0) {
            system_error(error, error_size, "socket");
            continue;
        }
        if (!configure_fd(fd, error, error_size)) {
            close(fd);
            continue;
        }
        int result = connect(fd, address->ai_addr, address->ai_addrlen);
        if (result < 0 && errno == EINPROGRESS) {
            if (await_fd(fd, POLLOUT, deadline, error, error_size)) {
                int failure = 0;
                socklen_t length = sizeof(failure);
                result = getsockopt(fd, SOL_SOCKET, SO_ERROR, &failure, &length);
                if (result == 0 && failure) {
                    errno = failure;
                    system_error(error, error_size, "connect");
                    result = -1;
                } else if (result < 0) system_error(error, error_size, "SO_ERROR");
            } else result = -1;
        } else if (result < 0) system_error(error, error_size, "connect");
        if (result < 0) {
            close(fd);
            if (deadline && monotonic_ms() >= deadline) break;
            continue;
        }
        if (idle > 0) {
#ifdef TCP_KEEPALIVE
            setsockopt(fd, IPPROTO_TCP, TCP_KEEPALIVE, &idle, sizeof(idle));
#else
            setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE, &idle, sizeof(idle));
#endif
            setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &interval, sizeof(interval));
            setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT, &count, sizeof(count));
        }
        connected = kadb_io_adopt(fd, error, error_size);
        break;
    }
    freeaddrinfo(addresses);
    return connected;
}

static int begin_io(kadb_io *io, char *error, size_t size) {
    pthread_mutex_lock(&io->lock);
    if (io->closed) {
        pthread_mutex_unlock(&io->lock);
        set_error(error, size, "Socket closed");
        return 0;
    }
    ++io->active;
    pthread_mutex_unlock(&io->lock);
    return 1;
}

static void end_io(kadb_io *io) {
    pthread_mutex_lock(&io->lock);
    if (--io->active == 0 && io->closed && io->fd >= 0) {
        close(io->fd);
        io->fd = -1;
    }
    pthread_mutex_unlock(&io->lock);
}

int kadb_io_is_open(kadb_io *io) {
    pthread_mutex_lock(&io->lock);
    int open = !io->closed;
    pthread_mutex_unlock(&io->lock);
    return open;
}

void kadb_io_close(kadb_io *io) {
    if (!io) return;
    pthread_mutex_lock(&io->lock);
    if (!io->closed) {
        io->closed = 1;
        shutdown(io->fd, SHUT_RDWR);
        /* Do not recycle a descriptor while another thread is still polling it. */
        if (io->active == 0) {
            close(io->fd);
            io->fd = -1;
        }
    }
    pthread_mutex_unlock(&io->lock);
}

void kadb_io_free(kadb_io *io) {
    if (!io) return;
    kadb_io_close(io);
    SSL_free(io->ssl);
    SSL_CTX_free(io->context);
    kadb_identity *identity = io->identities;
    while (identity) {
        kadb_identity *next = identity->next;
        EVP_PKEY_free(identity->key);
        X509_free(identity->cert);
        free(identity);
        identity = next;
    }
    pthread_mutex_destroy(&io->lock);
    free(io);
}

static int transfer(kadb_io *io, uint8_t *bytes, int size, int64_t timeout_ms,
                    int writing, char *error, size_t error_size) {
    if (size <= 0) return 0;
    if (!begin_io(io, error, error_size)) return -1;
    int64_t deadline = make_deadline(timeout_ms);
    int result = -1;
    for (;;) {
        pthread_mutex_lock(&io->lock);
        if (io->closed) {
            pthread_mutex_unlock(&io->lock);
            set_error(error, error_size, "Socket closed");
            result = -1;
            break;
        }
        short wait_for = writing ? POLLOUT : POLLIN;
        if (io->ssl) {
            ERR_clear_error();
            result = writing ? SSL_write(io->ssl, bytes, size) : SSL_read(io->ssl, bytes, size);
            int status = result > 0 ? SSL_ERROR_NONE : SSL_get_error(io->ssl, result);
            if (status == SSL_ERROR_ZERO_RETURN) result = 0;
            else if (status == SSL_ERROR_WANT_READ || status == SSL_ERROR_WANT_WRITE) {
                wait_for = status == SSL_ERROR_WANT_READ ? POLLIN : POLLOUT;
                result = -2;
            } else if (status != SSL_ERROR_NONE) {
                tls_error(error, error_size, writing ? "TLS write" : "TLS read", status);
                result = -1;
            }
        } else {
            result = (int) (writing ? send(io->fd, bytes, (size_t) size, 0)
                                   : recv(io->fd, bytes, (size_t) size, 0));
            if (result < 0) {
                if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) result = -2;
                else system_error(error, error_size, writing ? "send" : "recv");
            }
        }
        pthread_mutex_unlock(&io->lock);
        if (result != -2) break;
        if (!await_fd(io->fd, wait_for, deadline, error, error_size)) {
            result = -1;
            break;
        }
    }
    end_io(io);
    return result;
}

int kadb_io_read(kadb_io *io, uint8_t *bytes, int size, int64_t timeout_ms,
                 char *error, size_t error_size) {
    return transfer(io, bytes, size, timeout_ms, 0, error, error_size);
}

int kadb_io_write(kadb_io *io, const uint8_t *bytes, int size, int64_t timeout_ms,
                  char *error, size_t error_size) {
    return transfer(io, (uint8_t *) bytes, size, timeout_ms, 1, error, error_size);
}

int kadb_io_add_identity(kadb_io *io, const uint8_t *key_pem, size_t key_size,
                         const uint8_t *cert_pem, size_t cert_size,
                         const char *fingerprint_hex, char *error, size_t error_size) {
    if (key_size > INT_MAX || cert_size > INT_MAX || strlen(fingerprint_hex) != 64) {
        set_error(error, error_size, "Invalid TLS identity");
        return 0;
    }
    BIO *key_bio = BIO_new_mem_buf(key_pem, (int) key_size);
    BIO *cert_bio = BIO_new_mem_buf(cert_pem, (int) cert_size);
    kadb_identity *identity = calloc(1, sizeof(*identity));
    if (identity && key_bio && cert_bio) {
        identity->key = PEM_read_bio_PrivateKey(key_bio, NULL, NULL, NULL);
        identity->cert = PEM_read_bio_X509(cert_bio, NULL, NULL, NULL);
    }
    BIO_free(key_bio);
    BIO_free(cert_bio);
    if (!identity || !identity->key || !identity->cert ||
        X509_check_private_key(identity->cert, identity->key) != 1) {
        tls_error(error, error_size, "Load TLS identity", 0);
        if (identity) {
            EVP_PKEY_free(identity->key);
            X509_free(identity->cert);
            free(identity);
        }
        return 0;
    }
    memcpy(identity->fingerprint, fingerprint_hex, 65);
    pthread_mutex_lock(&io->lock);
    if (io->closed || io->ssl) {
        pthread_mutex_unlock(&io->lock);
        EVP_PKEY_free(identity->key);
        X509_free(identity->cert);
        free(identity);
        set_error(error, error_size, "TLS identity must be configured before handshake");
        return 0;
    }
    kadb_identity **tail = &io->identities;
    while (*tail) tail = &(*tail)->next;
    *tail = identity;
    pthread_mutex_unlock(&io->lock);
    return 1;
}

/* ADB advertises paired keys as O=AdbKey-0,CN=<SPKI SHA256>. Preserve issuer order. */
static int select_client_identity(SSL *ssl, void *argument) {
    kadb_io *io = argument;
    kadb_identity *selected = io->identities;
    const STACK_OF(X509_NAME) *issuers = SSL_get_client_CA_list(ssl);
    for (size_t i = 0; issuers && i < sk_X509_NAME_num(issuers); ++i) {
        X509_NAME *issuer = sk_X509_NAME_value(issuers, i);
        char organization[64], common_name[128];
        if (X509_NAME_get_text_by_NID(issuer, NID_organizationName, organization, sizeof(organization)) != 8 ||
            strcmp(organization, "AdbKey-0") != 0 ||
            X509_NAME_get_text_by_NID(issuer, NID_commonName, common_name, sizeof(common_name)) != 64) continue;
        for (kadb_identity *identity = io->identities; identity; identity = identity->next) {
            if (strcasecmp(identity->fingerprint, common_name) == 0) {
                selected = identity;
                goto found;
            }
        }
    }
found:
    return selected && SSL_use_certificate(ssl, selected->cert) == 1 &&
           SSL_use_PrivateKey(ssl, selected->key) == 1;
}

int kadb_io_start_tls(kadb_io *io, int64_t timeout_ms, char *error, size_t error_size) {
    if (!begin_io(io, error, error_size)) return 0;
    pthread_mutex_lock(&io->lock);
    if (io->ssl || !io->identities) {
        pthread_mutex_unlock(&io->lock);
        set_error(error, error_size, "TLS requires an identity and an unencrypted socket");
        end_io(io);
        return 0;
    }
    io->context = SSL_CTX_new(TLS_method());
    int setup = io->context && SSL_CTX_set_min_proto_version(io->context, TLS1_3_VERSION) &&
                SSL_CTX_set_max_proto_version(io->context, TLS1_3_VERSION);
    if (setup) {
        /* ADB authenticates the client identity; pairing binds the otherwise untrusted server via SPAKE2. */
        SSL_CTX_set_verify(io->context, SSL_VERIFY_NONE, NULL);
        SSL_CTX_set_cert_cb(io->context, select_client_identity, io);
        io->ssl = SSL_new(io->context);
        setup = io->ssl && SSL_set_fd(io->ssl, io->fd);
        if (setup) SSL_set_connect_state(io->ssl);
    }
    if (!setup) tls_error(error, error_size, "Configure TLS", 0);
    pthread_mutex_unlock(&io->lock);
    int64_t deadline = make_deadline(timeout_ms);
    int success = 0;
    while (setup) {
        pthread_mutex_lock(&io->lock);
        if (io->closed) {
            pthread_mutex_unlock(&io->lock);
            set_error(error, error_size, "Socket closed");
            break;
        }
        ERR_clear_error();
        int result = SSL_connect(io->ssl);
        int status = result == 1 ? SSL_ERROR_NONE : SSL_get_error(io->ssl, result);
        if (status != SSL_ERROR_NONE && status != SSL_ERROR_WANT_READ && status != SSL_ERROR_WANT_WRITE)
            tls_error(error, error_size, "TLS handshake", status);
        pthread_mutex_unlock(&io->lock);
        if (result == 1) {
            success = 1;
            break;
        }
        if (status != SSL_ERROR_WANT_READ && status != SSL_ERROR_WANT_WRITE) break;
        if (!await_fd(io->fd, status == SSL_ERROR_WANT_READ ? POLLIN : POLLOUT,
                      deadline, error, error_size)) break;
    }
    end_io(io);
    return success;
}

int kadb_io_export_pairing_key(kadb_io *io, uint8_t *output, size_t output_size,
                              char *error, size_t error_size) {
    /* sizeof intentionally includes the trailing NUL used by AOSP pairing. No context. */
    static const char label[] = "adb-label";
    pthread_mutex_lock(&io->lock);
    int success = !io->closed && io->ssl && output_size == 64 &&
                  SSL_export_keying_material(io->ssl, output, output_size,
                                             label, sizeof(label), NULL, 0, 0);
    if (!success) tls_error(error, error_size, "Export pairing key", 0);
    pthread_mutex_unlock(&io->lock);
    return success;
}
