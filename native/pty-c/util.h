/* Small C utilities shared by the app-built opencode-pty helper. */
#ifndef OPENCODE_PTY_UTIL_H
#define OPENCODE_PTY_UTIL_H

#include <stddef.h>
#include <stdint.h>

typedef struct {
    unsigned char *data;
    size_t len;
    size_t cap;
} Buf;

void buf_free(Buf *b);
int buf_reserve(Buf *b, size_t extra);
int buf_append(Buf *b, const void *data, size_t len);
int buf_putc(Buf *b, char c);
int buf_puts(Buf *b, const char *s);
int buf_printf(Buf *b, const char *fmt, ...);
int buf_json_string(Buf *b, const char *s, size_t len);

char *b64_encode(const unsigned char *data, size_t len, size_t *out_len);
int b64_decode(const char *text, size_t len, unsigned char **out, size_t *out_len);

typedef struct {
    uint32_t h[8];
    uint64_t total;
    unsigned char block[64];
    size_t used;
} Sha256;

void sha256_init(Sha256 *ctx);
void sha256_update(Sha256 *ctx, const void *data, size_t len);
void sha256_final(Sha256 *ctx, unsigned char out[32]);

enum {
    JSON_NULL = 0,
    JSON_BOOL = 1,
    JSON_NUMBER = 2,
    JSON_STRING = 3,
    JSON_ARRAY = 4,
    JSON_OBJECT = 5
};

typedef struct Json Json;
struct Json {
    int type;
    union {
        int boolean;
        double number;
        struct {
            char *data;
            size_t len;
        } string;
        struct {
            Json *items;
            size_t count;
        } array;
        struct {
            char **keys;
            Json *values;
            size_t count;
        } object;
    } u;
};

Json *json_parse(const char *text, size_t len, size_t *used);
/* Serialize a parsed value back to JSON (used for opaque pipe metadata). */
int json_write(Buf *b, const Json *value);
void json_free(Json *value);
Json *json_get(Json *object, const char *key);
size_t json_array_count(Json *array);
Json *json_array_item(Json *array, size_t index);
const char *json_string(Json *object, const char *key, const char *fallback);
double json_number(Json *object, const char *key, double fallback);
int json_bool(Json *object, const char *key, int fallback);

#endif
