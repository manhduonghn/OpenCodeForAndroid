#include "screen.h"

#include <stdlib.h>
#include <string.h>

static void allocate(Screen *screen, int cols, int rows) {
    unsigned char *cells = calloc((size_t)cols * (size_t)rows, 1);
    if (!cells) abort();
    free(screen->cells);
    screen->cells = cells;
    screen->cols = cols;
    screen->rows = rows;
}

void screen_init(Screen *screen, int cols, int rows) {
    memset(screen, 0, sizeof(*screen));
    if (cols < 1) cols = 80;
    if (rows < 1) rows = 24;
    allocate(screen, cols, rows);
}

void screen_free(Screen *screen) {
    free(screen->cells);
    memset(screen, 0, sizeof(*screen));
}

void screen_resize(Screen *screen, int cols, int rows) {
    if (cols < 1 || rows < 1 || (cols == screen->cols && rows == screen->rows)) return;
    unsigned char *next = calloc((size_t)cols * (size_t)rows, 1);
    if (!next) abort();
    int copy_cols = cols < screen->cols ? cols : screen->cols;
    int copy_rows = rows < screen->rows ? rows : screen->rows;
    for (int y = 0; y < copy_rows; y++) {
        memcpy(next + (size_t)y * cols, screen->cells + (size_t)y * screen->cols, (size_t)copy_cols);
    }
    free(screen->cells);
    screen->cells = next;
    screen->cols = cols;
    screen->rows = rows;
    if (screen->x >= cols) screen->x = cols - 1;
    if (screen->y >= rows) screen->y = rows - 1;
}

static void scroll(Screen *screen) {
    size_t row = (size_t)screen->cols;
    memmove(screen->cells, screen->cells + row, row * (size_t)(screen->rows - 1));
    memset(screen->cells + row * (size_t)(screen->rows - 1), 0, row);
}

static void newline(Screen *screen) {
    if (++screen->y >= screen->rows) {
        screen->y = screen->rows - 1;
        scroll(screen);
    }
}

static void put(Screen *screen, unsigned char c) {
    if (screen->x >= screen->cols) {
        screen->x = 0;
        newline(screen);
    }
    screen->cells[(size_t)screen->y * screen->cols + screen->x] = c;
    screen->x++;
}

static int param(const int *params, int index, int fallback) {
    return params[index] > 0 ? params[index] : fallback;
}

static void clear_line(Screen *screen, int mode) {
    size_t base = (size_t)screen->y * screen->cols;
    int from = 0, to = screen->cols;
    if (mode == 0) from = screen->x;
    else if (mode == 1) to = screen->x + 1;
    memset(screen->cells + base + from, 0, (size_t)(to - from));
}

static void clear_screen(Screen *screen, int mode) {
    if (mode == 0) {
        clear_line(screen, 0);
        for (int y = screen->y + 1; y < screen->rows; y++) {
            memset(screen->cells + (size_t)y * screen->cols, 0, (size_t)screen->cols);
        }
    } else if (mode == 1) {
        for (int y = 0; y < screen->y; y++) {
            memset(screen->cells + (size_t)y * screen->cols, 0, (size_t)screen->cols);
        }
        clear_line(screen, 1);
    } else {
        memset(screen->cells, 0, (size_t)screen->cols * (size_t)screen->rows);
    }
}

static void csi(Screen *screen, unsigned char final, const int *params) {
    switch (final) {
    case 'A': {
        int n = param(params, 0, 1);
        screen->y -= n;
        if (screen->y < 0) screen->y = 0;
        break;
    }
    case 'B': {
        int n = param(params, 0, 1);
        screen->y += n;
        if (screen->y >= screen->rows) screen->y = screen->rows - 1;
        break;
    }
    case 'C': {
        int n = param(params, 0, 1);
        screen->x += n;
        if (screen->x >= screen->cols) screen->x = screen->cols - 1;
        break;
    }
    case 'D': {
        int n = param(params, 0, 1);
        screen->x -= n;
        if (screen->x < 0) screen->x = 0;
        break;
    }
    case 'E':
        screen->x = 0;
        screen->y += param(params, 0, 1);
        if (screen->y >= screen->rows) screen->y = screen->rows - 1;
        break;
    case 'F':
        screen->x = 0;
        screen->y -= param(params, 0, 1);
        if (screen->y < 0) screen->y = 0;
        break;
    case 'G':
    case '`':
        screen->x = param(params, 0, 1) - 1;
        if (screen->x < 0) screen->x = 0;
        if (screen->x >= screen->cols) screen->x = screen->cols - 1;
        break;
    case 'H':
    case 'f':
        screen->y = param(params, 0, 1) - 1;
        screen->x = param(params, 1, 1) - 1;
        if (screen->y < 0) screen->y = 0;
        if (screen->y >= screen->rows) screen->y = screen->rows - 1;
        if (screen->x < 0) screen->x = 0;
        if (screen->x >= screen->cols) screen->x = screen->cols - 1;
        break;
    case 'J':
        clear_screen(screen, param(params, 0, 0));
        break;
    case 'K':
        clear_line(screen, param(params, 0, 0));
        break;
    case 'm':
    case 'h':
    case 'l':
    case 'r':
    case 'q':
    case 's':
    case 'u':
        break;
    default:
        break;
    }
}

enum {
    SCREEN_NORMAL = 0,
    SCREEN_ESC,
    SCREEN_CSI,
    SCREEN_OSC,
    SCREEN_OSC_ESC
};

void screen_feed(Screen *screen, const unsigned char *data, size_t len) {
    /* Keep the parser state across reads: PTY output can split an escape sequence. */
    int state = screen->parser_state;
    int *params = screen->params;
    int count = screen->param_count;
    int building = screen->param_building;
    for (size_t i = 0; i < len; i++) {
        unsigned char c = data[i];
        switch (state) {
        case SCREEN_NORMAL:
            if (c == 0x1b) {
                state = SCREEN_ESC;
            } else if (c == '\r') {
                screen->x = 0;
            } else if (c == '\n') {
                newline(screen);
            } else if (c == '\b') {
                if (screen->x > 0) screen->x--;
            } else if (c == '\t') {
                screen->x = (screen->x / 8 + 1) * 8;
                if (screen->x >= screen->cols) screen->x = screen->cols - 1;
            } else if (c >= 0x20 && c != 0x7f) {
                put(screen, c);
            }
            break;
        case SCREEN_ESC:
            state = SCREEN_NORMAL;
            if (c == '[') {
                memset(params, 0, sizeof(screen->params));
                count = 0;
                building = 0;
                state = SCREEN_CSI;
            } else if (c == ']') {
                state = SCREEN_OSC;
            } else if (c == 'c') {
                memset(screen->cells, 0, (size_t)screen->cols * (size_t)screen->rows);
                screen->x = screen->y = 0;
                screen->saved_x = screen->saved_y = 0;
            } else if (c == '7') {
                screen->saved_x = screen->x;
                screen->saved_y = screen->y;
            } else if (c == '8') {
                screen->x = screen->saved_x;
                screen->y = screen->saved_y;
            }
            break;
        case SCREEN_CSI:
            if (c >= '0' && c <= '9') {
                if (count < SCREEN_MAX_PARAMS) {
                    if (!building) {
                        params[count] = 0;
                        building = 1;
                    }
                    params[count] = params[count] * 10 + (c - '0');
                }
            } else if (c == ';' || c == '?' || c == '>' || c == '<') {
                if (count < SCREEN_MAX_PARAMS) {
                    count++;
                    building = 0;
                }
            } else if (c >= 0x40 && c <= 0x7e) {
                csi(screen, c, params);
                state = SCREEN_NORMAL;
            }
            break;
        case SCREEN_OSC:
            if (c == 0x07) state = SCREEN_NORMAL;
            else if (c == 0x1b) state = SCREEN_OSC_ESC;
            break;
        case SCREEN_OSC_ESC:
            state = c == '\\' ? SCREEN_NORMAL : SCREEN_OSC;
            break;
        }
    }
    screen->parser_state = state;
    screen->param_count = count;
    screen->param_building = building;
}

/* Blank cells are stored as 0; serializers must emit spaces so the cursor advances. */
void screen_row(Screen *screen, int y, Buf *out) {
    if (y < 0 || y >= screen->rows) return;
    const unsigned char *row = screen->cells + (size_t)y * screen->cols;
    int last = screen->cols;
    while (last > 0 && row[last - 1] == 0) last--;
    for (int x = 0; x < last; x++) buf_putc(out, row[x] ? (char)row[x] : ' ');
}

void screen_text(Screen *screen, Buf *out) {
    for (int y = 0; y < screen->rows; y++) {
        if (y) buf_putc(out, '\n');
        screen_row(screen, y, out);
    }
}

void screen_rows_json(Screen *screen, int wanted, Buf *out) {
    int first = 0;
    if (wanted > 0 && wanted < screen->rows) first = screen->rows - wanted;
    buf_putc(out, '[');
    for (int y = first; y < screen->rows; y++) {
        if (y > first) buf_putc(out, ',');
        Buf row = {0};
        screen_row(screen, y, &row);
        buf_json_string(out, (const char *)row.data, row.len);
        buf_free(&row);
    }
    buf_putc(out, ']');
}
