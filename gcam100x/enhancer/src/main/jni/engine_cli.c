// Host test: engine_cli model.tflite cpu in.ppm out.ppm [colour_lock] [libdir]
#include <stdio.h>
#include <stdlib.h>
#include "engine.h"
static void prog(void *u, int d, int t) { (void)u; fprintf(stderr, "tile %d/%d\n", d, t); }
int main(int argc, char **argv) {
    if (argc < 5) return 1;
    FILE *f = fopen(argv[3], "rb"); int w, h, mx; if (!f || fscanf(f, "P6 %d %d %d", &w, &h, &mx) != 3) return 2; fgetc(f);
    unsigned char *in = malloc((size_t)w * h * 3), *out = malloc((size_t)w * h * 48);
    if (fread(in, 3, (size_t)w * h, f) != (size_t)w * h) return 3; fclose(f);
    char err[256] = "";
    Engine *e = engine_open(argv[1], argv[2], argc > 6 ? argv[6] : ".", NULL, err, sizeof err);
    if (!e) { fprintf(stderr, "open failed: %s\n", err); return 4; }
    if (engine_enhance(e, in, w, h, out, argc > 5 ? atof(argv[5]) : 1.0f, prog, NULL, err, sizeof err)) { fprintf(stderr, "enhance failed: %s\n", err); return 5; }
    f = fopen(argv[4], "wb"); fprintf(f, "P6 %d %d 255\n", w * 4, h * 4); fwrite(out, 3, (size_t)w * h * 16, f); fclose(f);
    fprintf(stderr, "init %.0f ms, last tile %.0f ms\n", engine_init_ms(e), engine_last_tile_ms(e)); engine_close(e); return 0;
}
