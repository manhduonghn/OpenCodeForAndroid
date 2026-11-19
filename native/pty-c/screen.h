/* Minimal VT screen model for snapshot/read_rows responses. */
#ifndef OPENCODE_PTY_SCREEN_H
#define OPENCODE_PTY_SCREEN_H

#include "util.h"

#define SCREEN_MAX_PARAMS 8

typedef struct {
    int cols;
    int rows;
    int x;
    int y;
    int saved_x;
    int saved_y;
    unsigned char *cells;
    int parser_state;
    int params[SCREEN_MAX_PARAMS];
    int param_count;
    int param_building;
} Screen;

void screen_init(Screen *screen, int cols, int rows);
void screen_free(Screen *screen);
void screen_resize(Screen *screen, int cols, int rows);
void screen_feed(Screen *screen, const unsigned char *data, size_t len);
void screen_row(Screen *screen, int y, Buf *out);
void screen_text(Screen *screen, Buf *out);
void screen_rows_json(Screen *screen, int wanted, Buf *out);

#endif
