#include "util.h"

#include <ctype.h>
#include <errno.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void oom(void) {
    fputs("opencode-pty: out of memory\n", stderr);
    abort();
}

void buf_free(Buf *b) {
    free(b->data);
    b->data = NULL;
    b->len = 0;
    b->cap = 0;
}

int buf_reserve(Buf *b, size_t extra) {
    if (extra > SIZE_MAX - b->len) return -1;
    size_t need = b->len + extra;
    if (need <= b->cap) return 0;
    size_t cap = b->cap ? b->cap : 256;
    while (cap < need) {
        if (cap > SIZE_MAX / 2) {
            cap = need;
            break;
        }
        cap *= 2;
    }
    unsigned char *next = realloc(b->data, cap);
    if (!next) return -1;
    b->data = next;
    b->cap = cap;
    return 0;
}

int buf_append(Buf *b, const void *data, size_t len) {
    if (buf_reserve(b, len)) return -1;
    if (len) memcpy(b->data + b->len, data, len);
    b->len += len;
    return 0;
}

int buf_putc(Buf *b, char c) {
    return buf_append(b, &c, 1);
}

int buf_puts(Buf *b, const char *s) {
    return buf_append(b, s, strlen(s));
}

int buf_printf(Buf *b, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    va_list copy;
    va_copy(copy, ap);
    int n = vsnprintf(NULL, 0, fmt, copy);
    va_end(copy);
    if (n < 0 || buf_reserve(b, (size_t)n + 1)) {
        va_end(ap);
        return -1;
    }
    vsnprintf((char *)b->data + b->len, b->cap - b->len, fmt, ap);
    va_end(ap);
    b->len += (size_t)n;
    return 0;
}

static size_t utf8_width(const unsigned char *s, size_t len) {
    unsigned char c = s[0];
    if (c < 0x80) return 1;
    size_t need = 0;
    unsigned char first_lo = 0x80, first_hi = 0xbf;
    if (c >= 0xc2 && c <= 0xdf) need = 1;
    else if (c == 0xe0) { need = 2; first_lo = 0xa0; }
    else if (c >= 0xe1 && c <= 0xec) need = 2;
    else if (c == 0xed) { need = 2; first_hi = 0x9f; }
    else if (c >= 0xee && c <= 0xef) need = 2;
    else if (c == 0xf0) { need = 3; first_lo = 0x90; }
    else if (c >= 0xf1 && c <= 0xf3) need = 3;
    else if (c == 0xf4) { need = 3; first_hi = 0x8f; }
    else return 0;
    if (len < need + 1) return 0;
    if (s[1] < first_lo || s[1] > first_hi) return 0;
    for (size_t i = 2; i <= need; i++) {
        if (s[i] < 0x80 || s[i] > 0xbf) return 0;
    }
    return need + 1;
}

/* Serde emits lossy UTF-8; match it so PTY bytes cannot produce invalid JSON frames. */
int buf_json_string(Buf *b, const char *s, size_t len) {
    if (buf_putc(b, '"')) return -1;
    for (size_t i = 0; i < len; i++) {
        unsigned char c = (unsigned char)s[i];
        if (c >= 0x80) {
            size_t width = utf8_width((const unsigned char *)s + i, len - i);
            if (width) {
                if (buf_append(b, s + i, width)) return -1;
                i += width - 1;
            } else if (buf_puts(b, "\xef\xbf\xbd")) {
                return -1;
            }
            continue;
        }
        switch (c) {
        case '"':
            if (buf_puts(b, "\\\"")) return -1;
            break;
        case '\\':
            if (buf_puts(b, "\\\\")) return -1;
            break;
        case '\b':
            if (buf_puts(b, "\\b")) return -1;
            break;
        case '\f':
            if (buf_puts(b, "\\f")) return -1;
            break;
        case '\n':
            if (buf_puts(b, "\\n")) return -1;
            break;
        case '\r':
            if (buf_puts(b, "\\r")) return -1;
            break;
        case '\t':
            if (buf_puts(b, "\\t")) return -1;
            break;
        default:
            if (c < 0x20) {
                if (buf_printf(b, "\\u%04x", c)) return -1;
            } else if (buf_putc(b, (char)c)) {
                return -1;
            }
        }
    }
    return buf_putc(b, '"');
}

static const char b64_alphabet[] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

char *b64_encode(const unsigned char *data, size_t len, size_t *out_len) {
    size_t cap = ((len + 2) / 3) * 4 + 1;
    char *out = malloc(cap);
    if (!out) oom();
    size_t j = 0;
    for (size_t i = 0; i < len; i += 3) {
        uint32_t v = (uint32_t)data[i] << 16;
        if (i + 1 < len) v |= (uint32_t)data[i + 1] << 8;
        if (i + 2 < len) v |= data[i + 2];
        out[j++] = b64_alphabet[(v >> 18) & 63];
        out[j++] = b64_alphabet[(v >> 12) & 63];
        out[j++] = i + 1 < len ? b64_alphabet[(v >> 6) & 63] : '=';
        out[j++] = i + 2 < len ? b64_alphabet[v & 63] : '=';
    }
    out[j] = 0;
    if (out_len) *out_len = j;
    return out;
}

static int b64_value(unsigned char c) {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+') return 62;
    if (c == '/') return 63;
    return -1;
}

int b64_decode(const char *text, size_t len, unsigned char **out, size_t *out_len) {
    size_t cap = (len / 4) * 3 + 3;
    unsigned char *data = malloc(cap ? cap : 1);
    if (!data) oom();
    size_t j = 0;
    int values[4];
    int count = 0;
    for (size_t i = 0; i < len; i++) {
        unsigned char c = (unsigned char)text[i];
        if (c == '\r' || c == '\n' || c == ' ' || c == '\t') continue;
        if (c == '=') break;
        int v = b64_value(c);
        if (v < 0) {
            free(data);
            return -1;
        }
        values[count++] = v;
        if (count == 4) {
            data[j++] = (unsigned char)((values[0] << 2) | (values[1] >> 4));
            data[j++] = (unsigned char)((values[1] << 4) | (values[2] >> 2));
            data[j++] = (unsigned char)((values[2] << 6) | values[3]);
            count = 0;
        }
    }
    if (count == 2) {
        data[j++] = (unsigned char)((values[0] << 2) | (values[1] >> 4));
    } else if (count == 3) {
        data[j++] = (unsigned char)((values[0] << 2) | (values[1] >> 4));
        data[j++] = (unsigned char)((values[1] << 4) | (values[2] >> 2));
    } else if (count == 1) {
        free(data);
        return -1;
    }
    *out = data;
    *out_len = j;
    return 0;
}

static const uint32_t sha_k[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
};

static uint32_t rotr(uint32_t x, unsigned n) {
    return (x >> n) | (x << (32 - n));
}

static void sha_block(Sha256 *ctx, const unsigned char *block) {
    uint32_t w[64];
    for (size_t i = 0; i < 16; i++) {
        w[i] = ((uint32_t)block[i * 4] << 24) | ((uint32_t)block[i * 4 + 1] << 16) |
               ((uint32_t)block[i * 4 + 2] << 8) | block[i * 4 + 3];
    }
    for (size_t i = 16; i < 64; i++) {
        uint32_t s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
        uint32_t s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    uint32_t a = ctx->h[0], b = ctx->h[1], c = ctx->h[2], d = ctx->h[3];
    uint32_t e = ctx->h[4], f = ctx->h[5], g = ctx->h[6], h = ctx->h[7];
    for (size_t i = 0; i < 64; i++) {
        uint32_t s1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
        uint32_t ch = (e & f) ^ ((~e) & g);
        uint32_t t1 = h + s1 + ch + sha_k[i] + w[i];
        uint32_t s0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
        uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        uint32_t t2 = s0 + maj;
        h = g; g = f; f = e; e = d + t1;
        d = c; c = b; b = a; a = t1 + t2;
    }
    ctx->h[0] += a; ctx->h[1] += b; ctx->h[2] += c; ctx->h[3] += d;
    ctx->h[4] += e; ctx->h[5] += f; ctx->h[6] += g; ctx->h[7] += h;
}

void sha256_init(Sha256 *ctx) {
    static const uint32_t initial[8] = {
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
        0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
    };
    memcpy(ctx->h, initial, sizeof(initial));
    ctx->total = 0;
    ctx->used = 0;
}

void sha256_update(Sha256 *ctx, const void *data, size_t len) {
    const unsigned char *p = data;
    ctx->total += len;
    while (len) {
        size_t take = 64 - ctx->used;
        if (take > len) take = len;
        memcpy(ctx->block + ctx->used, p, take);
        ctx->used += take;
        p += take;
        len -= take;
        if (ctx->used == 64) {
            sha_block(ctx, ctx->block);
            ctx->used = 0;
        }
    }
}

void sha256_final(Sha256 *ctx, unsigned char out[32]) {
    uint64_t bits = ctx->total * 8;
    unsigned char pad = 0x80;
    sha256_update(ctx, &pad, 1);
    unsigned char zero = 0;
    while (ctx->used != 56) sha256_update(ctx, &zero, 1);
    unsigned char length[8];
    for (size_t i = 0; i < 8; i++) length[i] = (unsigned char)(bits >> (56 - i * 8));
    for (size_t i = 0; i < 8; i++) ctx->block[56 + i] = length[i];
    sha_block(ctx, ctx->block);
    for (size_t i = 0; i < 8; i++) {
        out[i * 4] = (unsigned char)(ctx->h[i] >> 24);
        out[i * 4 + 1] = (unsigned char)(ctx->h[i] >> 16);
        out[i * 4 + 2] = (unsigned char)(ctx->h[i] >> 8);
        out[i * 4 + 3] = (unsigned char)ctx->h[i];
    }
}

/* ---------------- JSON ---------------- */

typedef struct {
    const char *text;
    size_t len;
    size_t pos;
    int depth;
} Parser;

static Json *json_value(Parser *p);

static void skip_space(Parser *p) {
    while (p->pos < p->len && isspace((unsigned char)p->text[p->pos])) p->pos++;
}

static Json *json_new(int type) {
    Json *v = calloc(1, sizeof(Json));
    if (!v) oom();
    v->type = type;
    return v;
}

static int append_utf8(Buf *b, uint32_t cp) {
    if (cp <= 0x7f) return buf_putc(b, (char)cp);
    if (cp <= 0x7ff)
        return buf_putc(b, (char)(0xc0 | (cp >> 6))) || buf_putc(b, (char)(0x80 | (cp & 0x3f)));
    if (cp <= 0xffff)
        return buf_putc(b, (char)(0xe0 | (cp >> 12))) ||
               buf_putc(b, (char)(0x80 | ((cp >> 6) & 0x3f))) ||
               buf_putc(b, (char)(0x80 | (cp & 0x3f)));
    return buf_putc(b, (char)(0xf0 | (cp >> 18))) ||
           buf_putc(b, (char)(0x80 | ((cp >> 12) & 0x3f))) ||
           buf_putc(b, (char)(0x80 | ((cp >> 6) & 0x3f))) ||
           buf_putc(b, (char)(0x80 | (cp & 0x3f)));
}

static int hex4(Parser *p, uint32_t *out) {
    if (p->pos + 4 > p->len) return -1;
    uint32_t v = 0;
    for (size_t i = 0; i < 4; i++) {
        unsigned char c = (unsigned char)p->text[p->pos + i];
        v <<= 4;
        if (c >= '0' && c <= '9') v |= c - '0';
        else if (c >= 'a' && c <= 'f') v |= c - 'a' + 10;
        else if (c >= 'A' && c <= 'F') v |= c - 'A' + 10;
        else return -1;
    }
    p->pos += 4;
    *out = v;
    return 0;
}

static Json *json_string_value(Parser *p) {
    if (p->pos >= p->len || p->text[p->pos] != '"') return NULL;
    p->pos++;
    Buf b = {0};
    while (p->pos < p->len) {
        unsigned char c = (unsigned char)p->text[p->pos++];
        if (c == '"') {
            /* Keep a NUL terminator: the daemon compares string fields with strcmp/strlen. */
            if (buf_putc(&b, 0)) goto fail;
            Json *v = json_new(JSON_STRING);
            v->u.string.data = (char *)b.data;
            v->u.string.len = b.len - 1;
            return v;
        }
        if (c == '\\') {
            if (p->pos >= p->len) break;
            unsigned char e = (unsigned char)p->text[p->pos++];
            switch (e) {
            case '"': buf_putc(&b, '"'); break;
            case '\\': buf_putc(&b, '\\'); break;
            case '/': buf_putc(&b, '/'); break;
            case 'b': buf_putc(&b, '\b'); break;
            case 'f': buf_putc(&b, '\f'); break;
            case 'n': buf_putc(&b, '\n'); break;
            case 'r': buf_putc(&b, '\r'); break;
            case 't': buf_putc(&b, '\t'); break;
            case 'u': {
                uint32_t cp = 0;
                if (hex4(p, &cp)) goto fail;
                if (cp >= 0xd800 && cp <= 0xdbff && p->pos + 6 <= p->len &&
                    p->text[p->pos] == '\\' && p->text[p->pos + 1] == 'u') {
                    p->pos += 2;
                    uint32_t low = 0;
                    if (hex4(p, &low) || low < 0xdc00 || low > 0xdfff) goto fail;
                    cp = 0x10000 + ((cp - 0xd800) << 10) + (low - 0xdc00);
                }
                /* String fields are consumed with strcmp/strlen, so embedded NULs are unsafe. */
                if (cp == 0) goto fail;
                if (append_utf8(&b, cp)) goto fail;
                break;
            }
            default: goto fail;
            }
            continue;
        }
        if (c < 0x20) goto fail;
        if (buf_putc(&b, (char)c)) goto fail;
    }
fail:
    buf_free(&b);
    return NULL;
}

static Json *json_number_value(Parser *p) {
    size_t start = p->pos;
    if (p->pos < p->len && p->text[p->pos] == '-') p->pos++;
    while (p->pos < p->len && isdigit((unsigned char)p->text[p->pos])) p->pos++;
    if (p->pos < p->len && p->text[p->pos] == '.') {
        p->pos++;
        while (p->pos < p->len && isdigit((unsigned char)p->text[p->pos])) p->pos++;
    }
    if (p->pos < p->len && (p->text[p->pos] == 'e' || p->text[p->pos] == 'E')) {
        p->pos++;
        if (p->pos < p->len && (p->text[p->pos] == '+' || p->text[p->pos] == '-')) p->pos++;
        while (p->pos < p->len && isdigit((unsigned char)p->text[p->pos])) p->pos++;
    }
    if (p->pos == start) return NULL;
    /* Frames are not NUL-terminated, so strtod needs a bounded copy. */
    size_t n = p->pos - start;
    char tmp[128];
    if (n >= sizeof(tmp)) return NULL;
    memcpy(tmp, p->text + start, n);
    tmp[n] = 0;
    char *end = NULL;
    double value = strtod(tmp, &end);
    if (!end || end != tmp + n) return NULL;
    Json *v = json_new(JSON_NUMBER);
    v->u.number = value;
    return v;
}

static int json_literal(Parser *p, const char *word) {
    size_t n = strlen(word);
    if (p->pos + n > p->len || memcmp(p->text + p->pos, word, n)) return 0;
    p->pos += n;
    return 1;
}

static Json *json_value(Parser *p) {
    skip_space(p);
    if (p->pos >= p->len || p->depth > 64) return NULL;
    char c = p->text[p->pos];
    if (c == '"') return json_string_value(p);
    if (c == '-' || isdigit((unsigned char)c)) return json_number_value(p);
    if (c == 't') {
        if (!json_literal(p, "true")) return NULL;
        Json *v = json_new(JSON_BOOL);
        v->u.boolean = 1;
        return v;
    }
    if (c == 'f') {
        if (!json_literal(p, "false")) return NULL;
        Json *v = json_new(JSON_BOOL);
        v->u.boolean = 0;
        return v;
    }
    if (c == 'n') {
        if (!json_literal(p, "null")) return NULL;
        return json_new(JSON_NULL);
    }
    p->depth++;
    if (c == '[') {
        p->pos++;
        Json *v = json_new(JSON_ARRAY);
        size_t cap = 0;
        skip_space(p);
        if (p->pos < p->len && p->text[p->pos] == ']') {
            p->pos++;
            p->depth--;
            return v;
        }
        for (;;) {
            Json *item = json_value(p);
            if (!item) goto fail;
            if (v->u.array.count == cap) {
                size_t next = cap ? cap * 2 : 8;
                Json *items = realloc(v->u.array.items, next * sizeof(Json));
                if (!items) oom();
                v->u.array.items = items;
                cap = next;
            }
            v->u.array.items[v->u.array.count++] = *item;
            free(item);
            skip_space(p);
            if (p->pos >= p->len) goto fail;
            if (p->text[p->pos] == ']') {
                p->pos++;
                p->depth--;
                return v;
            }
            if (p->text[p->pos] != ',') goto fail;
            p->pos++;
        }
    fail:
        json_free(v);
        p->depth--;
        return NULL;
    }
    if (c == '{') {
        p->pos++;
        Json *v = json_new(JSON_OBJECT);
        size_t cap = 0;
        skip_space(p);
        if (p->pos < p->len && p->text[p->pos] == '}') {
            p->pos++;
            p->depth--;
            return v;
        }
        for (;;) {
            Json *key = json_string_value(p);
            if (!key) goto fail_obj;
            skip_space(p);
            if (p->pos >= p->len || p->text[p->pos] != ':') {
                json_free(key);
                goto fail_obj;
            }
            p->pos++;
            Json *item = json_value(p);
            if (!item) {
                json_free(key);
                goto fail_obj;
            }
            if (v->u.object.count == cap) {
                size_t next = cap ? cap * 2 : 8;
                char **keys = realloc(v->u.object.keys, next * sizeof(char *));
                Json *values = realloc(v->u.object.values, next * sizeof(Json));
                if (!keys || !values) oom();
                v->u.object.keys = keys;
                v->u.object.values = values;
                cap = next;
            }
            v->u.object.keys[v->u.object.count] = (char *)key->u.string.data;
            v->u.object.values[v->u.object.count] = *item;
            v->u.object.count++;
            free(key);
            free(item);
            skip_space(p);
            if (p->pos >= p->len) goto fail_obj;
            if (p->text[p->pos] == '}') {
                p->pos++;
                p->depth--;
                return v;
            }
            if (p->text[p->pos] != ',') goto fail_obj;
            p->pos++;
            skip_space(p);
        }
    fail_obj:
        json_free(v);
        p->depth--;
        return NULL;
    }
    return NULL;
}

Json *json_parse(const char *text, size_t len, size_t *used) {
    Parser p = {text, len, 0, 0};
    Json *value = json_value(&p);
    if (!value) return NULL;
    skip_space(&p);
    if (p.pos != len) {
        json_free(value);
        return NULL;
    }
    if (used) *used = p.pos;
    return value;
}

/* Recurse into values stored inline in arrays/objects. Only json_free() owns the wrapper. */
static void json_dispose(Json *value) {
    if (!value) return;
    switch (value->type) {
    case JSON_STRING:
        free(value->u.string.data);
        break;
    case JSON_ARRAY:
        for (size_t i = 0; i < value->u.array.count; i++) json_dispose(&value->u.array.items[i]);
        free(value->u.array.items);
        break;
    case JSON_OBJECT:
        for (size_t i = 0; i < value->u.object.count; i++) {
            free(value->u.object.keys[i]);
            json_dispose(&value->u.object.values[i]);
        }
        free(value->u.object.keys);
        free(value->u.object.values);
        break;
    default:
        break;
    }
}

void json_free(Json *value) {
    if (!value) return;
    json_dispose(value);
    free(value);
}

Json *json_get(Json *object, const char *key) {
    if (!object || object->type != JSON_OBJECT) return NULL;
    for (size_t i = 0; i < object->u.object.count; i++) {
        if (!strcmp(object->u.object.keys[i], key)) return &object->u.object.values[i];
    }
    return NULL;
}

size_t json_array_count(Json *array) {
    return array && array->type == JSON_ARRAY ? array->u.array.count : 0;
}

Json *json_array_item(Json *array, size_t index) {
    if (!array || array->type != JSON_ARRAY || index >= array->u.array.count) return NULL;
    return &array->u.array.items[index];
}

const char *json_string(Json *object, const char *key, const char *fallback) {
    Json *value = json_get(object, key);
    return value && value->type == JSON_STRING ? value->u.string.data : fallback;
}

double json_number(Json *object, const char *key, double fallback) {
    Json *value = json_get(object, key);
    return value && value->type == JSON_NUMBER ? value->u.number : fallback;
}

int json_bool(Json *object, const char *key, int fallback) {
    Json *value = json_get(object, key);
    return value && value->type == JSON_BOOL ? value->u.boolean : fallback;
}
