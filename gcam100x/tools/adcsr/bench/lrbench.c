// lrbench: minimal LiteRT (CompiledModel API) benchmark for the AdcSR engine.
// Usage: lrbench <model.tflite> <cpu|gpu|npu> [runs=6] [libdir=.] [compiler_cache_dir]
// Prints init time, per-run latency, whether the model is fully accelerated,
// and the process peak memory. Uses the same runtime libraries the app will
// bundle (libLiteRt.so + accelerator libraries from Google's Maven).
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "litert/c/litert_common.h"
#include "litert/c/litert_compiled_model.h"
#include "litert/c/litert_environment.h"
#include "litert/c/litert_environment_options.h"
#include "litert/c/litert_model.h"
#include "litert/c/litert_options.h"
#include "litert/c/litert_tensor_buffer.h"
#include "litert/c/litert_tensor_buffer_requirements.h"

static double now_ms(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec * 1e3 + t.tv_nsec / 1e6; }
static long status_kb(const char *key) {
    FILE *f = fopen("/proc/self/status", "r"); char l[256]; long v = -1;
    if (!f) return -1;
    while (fgets(l, sizeof l, f)) if (!strncmp(l, key, strlen(key))) { sscanf(l + strlen(key), "%ld", &v); break; }
    fclose(f); return v;
}
#define CHECK(expr) do { LiteRtStatus s_ = (expr); if (s_ != kLiteRtStatusOk) { printf("FAILED (status %d): %s\n", (int)s_, #expr); return 2; } } while (0)

int main(int argc, char **argv) {
    if (argc < 3) { printf("usage: %s model.tflite cpu|gpu|npu [runs] [libdir]\n", argv[0]); return 1; }
    const char *path = argv[1], *acc = argv[2];
    int runs = argc > 3 ? atoi(argv[3]) : 6;
    const char *libdir = argc > 4 ? argv[4] : ".";
    setvbuf(stdout, NULL, _IONBF, 0);

    const char *cache = argc > 5 ? argv[5] : NULL;
    LiteRtEnvOption eo[4]; int neo = 3;
    eo[0].tag = kLiteRtEnvOptionTagRuntimeLibraryDir;        eo[0].value.type = kLiteRtAnyTypeString; eo[0].value.str_value = libdir;
    eo[1].tag = kLiteRtEnvOptionTagDispatchLibraryDir;       eo[1].value.type = kLiteRtAnyTypeString; eo[1].value.str_value = libdir;
    eo[2].tag = kLiteRtEnvOptionTagCompilerPluginLibraryDir; eo[2].value.type = kLiteRtAnyTypeString; eo[2].value.str_value = libdir;
    if (cache && cache[0]) { eo[3].tag = kLiteRtEnvOptionTagCompilerCacheDir; eo[3].value.type = kLiteRtAnyTypeString; eo[3].value.str_value = cache; neo = 4; }
    LiteRtEnvironment env; CHECK(LiteRtCreateEnvironment(neo, eo, &env));

    double t0 = now_ms();
    LiteRtModel model; CHECK(LiteRtCreateModelFromFile(env, path, &model));
    LiteRtOptions opts; CHECK(LiteRtCreateOptions(&opts));
    LiteRtHwAcceleratorSet set = !strcmp(acc, "gpu") ? kLiteRtHwAcceleratorGpu : !strcmp(acc, "npu") ? kLiteRtHwAcceleratorNpu : kLiteRtHwAcceleratorCpu;
    CHECK(LiteRtSetOptionsHardwareAccelerators(opts, set));
    LiteRtCompiledModel cm; CHECK(LiteRtCreateCompiledModel(env, model, opts, &cm));
    double t1 = now_ms();
    bool full = false; LiteRtCompiledModelIsFullyAccelerated(cm, &full);
    printf("RESULT accelerator=%s init_ms=%.0f fully_accelerated=%s rss_after_init_mb=%ld\n", acc, t1 - t0, full ? "yes" : "NO", status_kb("VmRSS:") / 1024);

    LiteRtSignature sig; CHECK(LiteRtGetModelSignature(model, 0, &sig));
    LiteRtTensor tin, tout; CHECK(LiteRtGetSignatureInputTensorByIndex(sig, 0, &tin)); CHECK(LiteRtGetSignatureOutputTensorByIndex(sig, 0, &tout));
    LiteRtRankedTensorType rin, rout; CHECK(LiteRtGetRankedTensorType(tin, &rin)); CHECK(LiteRtGetRankedTensorType(tout, &rout));
    LiteRtTensorBufferRequirements qin, qout;
    CHECK(LiteRtGetCompiledModelInputBufferRequirements(cm, 0, 0, &qin)); CHECK(LiteRtGetCompiledModelOutputBufferRequirements(cm, 0, 0, &qout));
    size_t nin = 0, nout = 0; LiteRtGetTensorBufferRequirementsBufferSize(qin, &nin); LiteRtGetTensorBufferRequirementsBufferSize(qout, &nout);
    LiteRtTensorBuffer bin, bout;
    CHECK(LiteRtCreateManagedTensorBufferFromRequirements(env, &rin, qin, &bin)); CHECK(LiteRtCreateManagedTensorBufferFromRequirements(env, &rout, qout, &bout));
    printf("input %zu bytes, output %zu bytes\n", nin, nout);

    double sum = 0, best = 1e18, worst = 0;
    for (int i = 0; i < runs; i++) {
        void *p; CHECK(LiteRtLockTensorBuffer(bin, &p, kLiteRtTensorBufferLockModeWrite));
        float *f = (float *)p; for (size_t k = 0; k < nin / 4; k++) f[k] = (float)((k * 2654435761u >> 8) & 0xffff) / 32768.0f - 1.0f;
        CHECK(LiteRtUnlockTensorBuffer(bin));
        double a = now_ms();
        CHECK(LiteRtRunCompiledModel(cm, 0, 1, &bin, 1, &bout));
        CHECK(LiteRtLockTensorBuffer(bout, &p, kLiteRtTensorBufferLockModeRead)); // forces the result back to host memory
        float chk = ((float *)p)[0] + ((float *)p)[nout / 8];
        CHECK(LiteRtUnlockTensorBuffer(bout));
        double d = now_ms() - a;
        printf("run %d: %.0f ms (out sample %.4f)\n", i + 1, d, chk);
        if (i > 0 || runs == 1) { sum += d; if (d < best) best = d; if (d > worst) worst = d; }
    }
    int n = runs > 1 ? runs - 1 : 1;
    printf("RESULT accelerator=%s tile_ms_avg=%.0f min=%.0f max=%.0f (first run excluded) peak_rss_mb=%ld\n", acc, sum / n, best, worst, status_kb("VmHWM:") / 1024);
    return 0;
}
