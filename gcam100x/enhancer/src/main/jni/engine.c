#include "engine.h"
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "litert/c/litert_common.h"
#include "litert/c/litert_compiled_model.h"
#include "litert/c/litert_environment.h"
#include "litert/c/litert_environment_options.h"
#include "litert/c/litert_model.h"
#include "litert/c/litert_opaque_options.h"
#include "litert/c/litert_options.h"
#include "litert/c/litert_tensor_buffer.h"
#include "litert/c/litert_tensor_buffer_requirements.h"

#define T 128          // model input tile
#define S 4            // scale
#define OV 16          // tile overlap (input pixels)

struct Engine {
    LiteRtEnvironment env; LiteRtModel model; LiteRtOptions opts; LiteRtCompiledModel cm;
    LiteRtTensorBuffer bin, bout;
    double init_ms, tile_ms; int full, cache_off;
};

static double now_ms(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec * 1e3 + t.tv_nsec / 1e6; }
#define FAIL(...) do { if (err) snprintf(err, err_len, __VA_ARGS__); goto fail; } while (0)
#define OK(expr) do { LiteRtStatus s_ = (expr); if (s_ != kLiteRtStatusOk) FAIL("%s -> status %d", #expr, (int)s_); } while (0)

Engine *engine_open(const char *model_path, const char *acc, const char *lib_dir, const char *cache_dir, char *err, int err_len) {
    Engine *e = calloc(1, sizeof *e);
    double t0 = now_ms();
    LiteRtEnvOption eo[4]; int n = 0;
    eo[n].tag = kLiteRtEnvOptionTagRuntimeLibraryDir; eo[n].value.type = kLiteRtAnyTypeString; eo[n++].value.str_value = lib_dir;
    eo[n].tag = kLiteRtEnvOptionTagDispatchLibraryDir; eo[n].value.type = kLiteRtAnyTypeString; eo[n++].value.str_value = lib_dir;
    eo[n].tag = kLiteRtEnvOptionTagCompilerPluginLibraryDir; eo[n].value.type = kLiteRtAnyTypeString; eo[n++].value.str_value = lib_dir;
    if (cache_dir) { eo[n].tag = kLiteRtEnvOptionTagCompilerCacheDir; eo[n].value.type = kLiteRtAnyTypeString; eo[n++].value.str_value = cache_dir; }
    OK(LiteRtCreateEnvironment(n, eo, &e->env));
    OK(LiteRtCreateModelFromFile(e->env, model_path, &e->model));
    OK(LiteRtCreateOptions(&e->opts));
    OK(LiteRtSetOptionsHardwareAccelerators(e->opts, !strcmp(acc, "gpu") ? kLiteRtHwAcceleratorGpu : !strcmp(acc, "npu") ? kLiteRtHwAcceleratorNpu : kLiteRtHwAcceleratorCpu));
    if (!strcmp(acc, "gpu") && cache_dir) {
        // FP16 + on-disk cache of the compiled GPU programs and weights, so only the first start is slow.
        char *toml = malloc(1024);
        snprintf(toml, 1024, "precision = 1\nserialization_dir = \"%s\"\nmodel_cache_key = \"adcsr128\"\nserialize_program_cache = true\nserialize_external_tensors = true\n", cache_dir);
        LiteRtOpaqueOptions oq = NULL;
        if (LiteRtCreateOpaqueOptions("gpu_options", toml, free, &oq) == kLiteRtStatusOk) { if (LiteRtAddOpaqueOptions(e->opts, oq) != kLiteRtStatusOk) LiteRtDestroyOpaqueOptions(oq); }
        else free(toml);
    }
    if (LiteRtCreateCompiledModel(e->env, e->model, e->opts, &e->cm) != kLiteRtStatusOk) {
        // Retry with plain default options (no cache) before giving up.
        LiteRtDestroyOptions(e->opts); e->opts = NULL; e->cm = NULL;
        OK(LiteRtCreateOptions(&e->opts));
        OK(LiteRtSetOptionsHardwareAccelerators(e->opts, !strcmp(acc, "gpu") ? kLiteRtHwAcceleratorGpu : !strcmp(acc, "npu") ? kLiteRtHwAcceleratorNpu : kLiteRtHwAcceleratorCpu));
        OK(LiteRtCreateCompiledModel(e->env, e->model, e->opts, &e->cm));
        e->cache_off = 1;
    }
    bool full = false; LiteRtCompiledModelIsFullyAccelerated(e->cm, &full); e->full = full;
    LiteRtSignature sig; OK(LiteRtGetModelSignature(e->model, 0, &sig));
    LiteRtTensor tin, tout; OK(LiteRtGetSignatureInputTensorByIndex(sig, 0, &tin)); OK(LiteRtGetSignatureOutputTensorByIndex(sig, 0, &tout));
    LiteRtRankedTensorType rin, rout; OK(LiteRtGetRankedTensorType(tin, &rin)); OK(LiteRtGetRankedTensorType(tout, &rout));
    LiteRtTensorBufferRequirements qin, qout;
    OK(LiteRtGetCompiledModelInputBufferRequirements(e->cm, 0, 0, &qin)); OK(LiteRtGetCompiledModelOutputBufferRequirements(e->cm, 0, 0, &qout));
    size_t bi = 0, bo = 0; LiteRtGetTensorBufferRequirementsBufferSize(qin, &bi); LiteRtGetTensorBufferRequirementsBufferSize(qout, &bo);
    if (bi != (size_t)3 * T * T * 4 || bo != (size_t)3 * T * S * T * S * 4) FAIL("unexpected model I/O size %zu / %zu", bi, bo);
    OK(LiteRtCreateManagedTensorBufferFromRequirements(e->env, &rin, qin, &e->bin));
    OK(LiteRtCreateManagedTensorBufferFromRequirements(e->env, &rout, qout, &e->bout));
    e->init_ms = now_ms() - t0;
    return e;
fail:
    engine_close(e); return NULL;
}

void engine_close(Engine *e) {
    if (!e) return;
    if (e->bin) LiteRtDestroyTensorBuffer(e->bin);
    if (e->bout) LiteRtDestroyTensorBuffer(e->bout);
    if (e->cm) LiteRtDestroyCompiledModel(e->cm);
    if (e->opts) LiteRtDestroyOptions(e->opts);
    if (e->model) LiteRtDestroyModel(e->model);
    if (e->env) LiteRtDestroyEnvironment(e->env);
    free(e);
}
double engine_last_tile_ms(const Engine *e) { return e->tile_ms; }
double engine_init_ms(const Engine *e) { return e->init_ms; }
int engine_fully_accelerated(const Engine *e) { return e->full; }
int engine_cache_off(const Engine *e) { return e->cache_off; }

// Tile origins along one axis: stride T-OV, last tile flush with the edge.
static int origins(int len, int *o) {
    if (len <= T) { o[0] = 0; return 1; }
    int n = 0, step = T - OV;
    for (int p = 0; p + T < len; p += step) o[n++] = p;
    o[n++] = len - T;
    return n;
}
static inline int clampi(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

// In-place separable box blur (3 passes ~ Gaussian) on a single float plane.
static void box_blur(float *p, int w, int h, int r, float *tmp) {
    for (int pass = 0; pass < 3; pass++) {
        for (int y = 0; y < h; y++) {
            float *row = p + (size_t)y * w; double acc = 0;
            for (int x = -r; x <= r; x++) acc += row[clampi(x, 0, w - 1)];
            for (int x = 0; x < w; x++) { tmp[x] = (float)(acc / (2 * r + 1)); acc += row[clampi(x + r + 1, 0, w - 1)] - row[clampi(x - r, 0, w - 1)]; }
            memcpy(row, tmp, sizeof(float) * w);
        }
        for (int x = 0; x < w; x++) {
            double acc = 0;
            for (int y = -r; y <= r; y++) acc += p[(size_t)clampi(y, 0, h - 1) * w + x];
            for (int y = 0; y < h; y++) { tmp[y] = (float)(acc / (2 * r + 1)); acc += p[(size_t)clampi(y + r + 1, 0, h - 1) * w + x] - p[(size_t)clampi(y - r, 0, h - 1) * w + x]; }
            for (int y = 0; y < h; y++) p[(size_t)y * w + x] = tmp[y];
        }
    }
}

int engine_enhance(Engine *e, const uint8_t *rgb, int w, int h, uint8_t *out, float colour_lock, EngineProgress cb, void *user, char *err, int err_len) {
    int W = w * S, H = h * S, ox[256], oy[256];
    int nx = origins(w, ox), ny = origins(h, oy);
    size_t N = (size_t)W * H;
    float *acc = calloc(N * 3, sizeof(float)), *wsum = calloc(N, sizeof(float));
    float *lowS = NULL, *lowL = NULL, *tmp = NULL;
    if (!acc || !wsum) FAIL("out of memory");
    int done = 0, total = nx * ny;
    for (int ty = 0; ty < ny; ty++) for (int tx = 0; tx < nx; tx++) {
        void *p; OK(LiteRtLockTensorBuffer(e->bin, &p, kLiteRtTensorBufferLockModeWrite));
        float *in = p; double mean[3] = {0}, var[3] = {0};
        for (int c = 0; c < 3; c++) for (int y = 0; y < T; y++) for (int x = 0; x < T; x++) {
            int sx = clampi(ox[tx] + x, 0, w - 1), sy = clampi(oy[ty] + y, 0, h - 1);      // edge-replicate when the image is smaller than a tile
            float v = rgb[((size_t)sy * w + sx) * 3 + c] / 127.5f - 1.0f;
            in[(size_t)c * T * T + y * T + x] = v; mean[c] += v;
        }
        for (int c = 0; c < 3; c++) { mean[c] /= T * T; for (int i = 0; i < T * T; i++) { double d = in[(size_t)c * T * T + i] - mean[c]; var[c] += d * d; } var[c] = sqrt(var[c] / (T * T - 1)); }
        OK(LiteRtUnlockTensorBuffer(e->bin));
        double t0 = now_ms();
        OK(LiteRtRunCompiledModel(e->cm, 0, 1, &e->bin, 1, &e->bout));
        OK(LiteRtLockTensorBuffer(e->bout, &p, kLiteRtTensorBufferLockModeRead));
        e->tile_ms = now_ms() - t0;
        const float *o = p; const int TS = T * S; size_t plane = (size_t)TS * TS;
        for (int c = 0; c < 3; c++) {
            // AdcSR's own output normalisation: match each channel's mean/std to the input tile.
            double m = 0, v = 0; const float *oc = o + c * plane;
            for (size_t i = 0; i < plane; i++) m += oc[i];
            m /= plane; for (size_t i = 0; i < plane; i++) { double d = oc[i] - m; v += d * d; } v = sqrt(v / (plane - 1));
            float gain = v > 1e-6 ? (float)(var[c] / v) : 1.0f;
            for (int y = 0; y < TS; y++) {
                int Y = oy[ty] * S + y; if (Y >= H) break;
                float wy = 1.0f;                                                    // feather only on sides that have a neighbour
                if (ty > 0 && y < OV * S) wy = (y + 0.5f) / (OV * S);
                if (ty < ny - 1 && y >= TS - OV * S) wy = (TS - y - 0.5f) / (OV * S);
                for (int x = 0; x < TS; x++) {
                    int X = ox[tx] * S + x; if (X >= W) break;
                    float wx = 1.0f;
                    if (tx > 0 && x < OV * S) wx = (x + 0.5f) / (OV * S);
                    if (tx < nx - 1 && x >= TS - OV * S) wx = (TS - x - 0.5f) / (OV * S);
                    float wgt = wx * wy, val = (float)((oc[(size_t)y * TS + x] - m) * gain + mean[c]);
                    acc[((size_t)Y * W + X) * 3 + c] += val * wgt;
                    if (c == 0) wsum[(size_t)Y * W + X] += wgt;
                }
            }
        }
        OK(LiteRtUnlockTensorBuffer(e->bout));
        if (cb) cb(user, ++done, total);
    }
    for (size_t i = 0; i < N; i++) { float ws = wsum[i] > 1e-6f ? wsum[i] : 1.0f; acc[i * 3] /= ws; acc[i * 3 + 1] /= ws; acc[i * 3 + 2] /= ws; }

    if (colour_lock > 0) {
        // Low-frequency colour lock: keep the generated detail, take the broad colour/brightness from the input.
        lowS = malloc(N * sizeof(float)); lowL = malloc(N * sizeof(float)); tmp = malloc(sizeof(float) * (W > H ? W : H));
        if (!lowS || !lowL || !tmp) FAIL("out of memory");
        int r = 6 * S;
        for (int c = 0; c < 3; c++) {
            for (int Y = 0; Y < H; Y++) for (int X = 0; X < W; X++) {
                float fx = (X + 0.5f) / S - 0.5f, fy = (Y + 0.5f) / S - 0.5f;          // bilinear upsample of the input
                int x0 = (int)floorf(fx), y0 = (int)floorf(fy); float ax = fx - x0, ay = fy - y0;
                int x1 = clampi(x0 + 1, 0, w - 1), y1 = clampi(y0 + 1, 0, h - 1); x0 = clampi(x0, 0, w - 1); y0 = clampi(y0, 0, h - 1);
                float a = rgb[((size_t)y0 * w + x0) * 3 + c], b = rgb[((size_t)y0 * w + x1) * 3 + c], cc = rgb[((size_t)y1 * w + x0) * 3 + c], d = rgb[((size_t)y1 * w + x1) * 3 + c];
                lowL[(size_t)Y * W + X] = ((a * (1 - ax) + b * ax) * (1 - ay) + (cc * (1 - ax) + d * ax) * ay) / 127.5f - 1.0f;
                lowS[(size_t)Y * W + X] = acc[((size_t)Y * W + X) * 3 + c];
            }
            box_blur(lowL, W, H, r, tmp); box_blur(lowS, W, H, r, tmp);
            for (size_t i = 0; i < N; i++) acc[i * 3 + c] += colour_lock * (lowL[i] - lowS[i]);
        }
    }
    for (size_t i = 0; i < N * 3; i++) { float v = (acc[i] + 1.0f) * 127.5f; out[i] = (uint8_t)(v < 0 ? 0 : v > 255 ? 255 : v + 0.5f); }
    free(acc); free(wsum); free(lowS); free(lowL); free(tmp);
    return 0;
fail:
    free(acc); free(wsum); free(lowS); free(lowL); free(tmp);
    return 1;
}
