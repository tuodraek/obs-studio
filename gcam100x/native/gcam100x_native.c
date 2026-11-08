// GCam100x native part: guards Google Camera's Pro Zoom pipeline on Tensor G4.
//
// Google Camera 11.1.040.982810059.19, libgcastartup.so (BuildId
// dcec58ae3422114fadc933c1c8a6331c):
//  - ModelRunner::Run (0x55cffc4) starts by formatting
//    "ModelRunner::Run:%s" with its config's name, read from
//    this->[0x10]->[0x38] (a protobuf string field).
//  - On the G4 that pointer is null for one runner of the diffusion
//    super-resolver (super_resolution_diffuser.cc, Inference), and the
//    string formatter (0x78016d0) dereferences it: SIGSEGV, null pointer.
//    Run crashed there before running anything.
// This library hooks only ModelRunner::Run (a function that runs only inside
// a diffusion model call, never at camera start) with a register-preserving
// stub: when the config or its name is null, a valid empty one is put in its
// place, and Run returns its own "Inference Runner is not ready." error
// instead of crashing. Nothing is touched unless the loaded libgcastartup.so
// has exactly that BuildId.
#include <android/log.h>
#include <jni.h>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <link.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define TAG "GCam100x-native"
#define FILES_DIR "/data/user/0/com.google.android.GoogleCamera/files/"
#define LOG_PATH FILES_DIR "gcam100x.log"

static const char kBuildId[] = "dcec58ae3422114fadc933c1c8a6331c";
static const uintptr_t kOffModelRunnerRun = 0x55cffc4; // ModelRunner::Run

typedef int (*HookFunType)(void *func, void *replace, void **backup);
typedef int (*UnhookFunType)(void *func);
typedef void (*NativeOnModuleLoaded)(const char *name, void *handle);
typedef struct {
    uint32_t version;
    HookFunType hook_func;
    UnhookFunType unhook_func;
} NativeAPIEntries;

static HookFunType g_hook;
static uintptr_t g_base;
static atomic_int g_installed;

// State reported to the Java side (Native.status()).
#define ST_API 1          // native_init called with the hook API
#define ST_GUARD 2        // ModelRunner::Run guard installed
#define ST_WRONG_BUILD 8  // libgcastartup.so has another BuildId: nothing installed
#define ST_HOOK_FAILED 16 // a guard hook could not be installed
#define ST_LIB_SEEN 32    // libgcastartup.so is loaded
static atomic_int g_state;

static void gc_log(const char *fmt, ...) {
    char msg[900];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof msg, fmt, ap);
    va_end(ap);
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", msg);
    // Same file as the Java log (one line, flushed by write()).
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    struct tm tm;
    localtime_r(&ts.tv_sec, &tm);
    char line[1000];
    int n = snprintf(line, sizeof line, "%02d-%02d %02d:%02d:%02d.%03ld N [native] %s\n", tm.tm_mon + 1, tm.tm_mday,
                     tm.tm_hour, tm.tm_min, tm.tm_sec, ts.tv_nsec / 1000000, msg);
    int fd = open(LOG_PATH, O_WRONLY | O_APPEND | O_CLOEXEC);
    if (fd >= 0) {
        write(fd, line, n > 0 && n < (int)sizeof line ? n : (int)strlen(line));
        close(fd);
    }
}

// --- 2. ModelRunner::Run observer ---------------------------------------------

static void *orig_run;   // backup trampoline

// An empty libc++ std::string (short form, size 0) and a zeroed stand-in
// config whose name is that string.
static uint64_t g_empty_string[3];
static uint64_t g_fake_config[64];

/* Is [a, a+len) inside a writable mapping of this process? */
static int writable(uintptr_t a, size_t len) {
    FILE *f = fopen("/proc/self/maps", "re");
    if (!f) return 0;
    char line[512];
    int ok = 0;
    while (fgets(line, sizeof line, f)) {
        unsigned long lo, hi;
        char perms[5];
        if (sscanf(line, "%lx-%lx %4s", &lo, &hi, perms) != 3) continue;
        if (a >= lo && a + len <= hi) { ok = perms[1] == 'w'; break; }
    }
    fclose(f);
    return ok;
}

// Called from the stub with the runner (x0) and the caller (x30), before
// ModelRunner::Run reads this->[0x10]->[0x38] (its config's name). On the G4
// one diffusion runner was never initialized: that name (or the config) is
// null and Run would crash formatting it. Make it a valid empty name; Run then
// reaches its own "Inference Runner is not ready." check and returns an
// error, so Google's pipeline reports "no image" instead of crashing.
__attribute__((used)) static void on_model_runner_run(uintptr_t self, uintptr_t caller) {
    if (!self) return;
    uintptr_t *cfgp = (uintptr_t *)(self + 0x10);
    uintptr_t cfg = *cfgp;
    if (!cfg) {
        if (writable((uintptr_t)cfgp, 8)) {
            g_fake_config[0x38 / 8] = (uint64_t)(uintptr_t)g_empty_string;
            *cfgp = (uintptr_t)g_fake_config;
            gc_log("Guard: ModelRunner::Run with no config (runner %p, caller +0x%lx): empty config supplied",
                   (void *)self, (unsigned long)(caller - g_base));
        }
        return;
    }
    uintptr_t *namep = (uintptr_t *)(cfg + 0x38);
    if ((*namep & ~(uintptr_t)3) == 0) {
        if (writable((uintptr_t)namep, 8)) {
            *namep = (uintptr_t)g_empty_string; // tag 0: not owned, never freed
            gc_log("Guard: ModelRunner::Run with a null config name (runner %p, caller +0x%lx): empty name supplied",
                   (void *)self, (unsigned long)(caller - g_base));
        } else {
            gc_log("Guard: ModelRunner::Run with a null config name in read-only memory (runner %p)", (void *)self);
        }
    }
}

// Register-preserving stub: x0-x8 and x30 are kept (x8 carries the result
// pointer of ModelRunner::Run), then control continues in the original.
__attribute__((naked, used)) static void run_stub(void) {
    __asm__ volatile(
        "stp x29, x30, [sp, #-96]!\n"
        "stp x0, x1, [sp, #16]\n"
        "stp x2, x3, [sp, #32]\n"
        "stp x4, x5, [sp, #48]\n"
        "stp x6, x7, [sp, #64]\n"
        "str x8, [sp, #80]\n"
        "mov x1, x30\n"
        "bl on_model_runner_run\n"
        "ldp x0, x1, [sp, #16]\n"
        "ldp x2, x3, [sp, #32]\n"
        "ldp x4, x5, [sp, #48]\n"
        "ldp x6, x7, [sp, #64]\n"
        "ldr x8, [sp, #80]\n"
        "ldp x29, x30, [sp], #96\n"
        "adrp x16, orig_run\n"
        "ldr x16, [x16, :lo12:orig_run]\n"
        "br x16\n");
}

// --- Locating libgcastartup.so --------------------------------------------------

struct find_ctx {
    uintptr_t base;
    char build_id[64];
};

static int find_cb(struct dl_phdr_info *info, size_t size, void *data) {
    (void)size;
    struct find_ctx *ctx = (struct find_ctx *)data;
    if (!info->dlpi_name || !strstr(info->dlpi_name, "libgcastartup.so")) return 0;
    ctx->base = info->dlpi_addr;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr) *ph = &info->dlpi_phdr[i];
        if (ph->p_type != PT_NOTE) continue;
        const uint8_t *p = (const uint8_t *)(info->dlpi_addr + ph->p_vaddr);
        const uint8_t *end = p + ph->p_memsz;
        while (p + sizeof(ElfW(Nhdr)) <= end) {
            const ElfW(Nhdr) *nh = (const ElfW(Nhdr) *)p;
            const uint8_t *nm = p + sizeof(ElfW(Nhdr));
            const uint8_t *desc = nm + ((nh->n_namesz + 3) & ~3u);
            if (nh->n_type == NT_GNU_BUILD_ID && nh->n_namesz == 4 && memcmp(nm, "GNU", 4) == 0) {
                size_t k = 0;
                for (uint32_t j = 0; j < nh->n_descsz && k + 2 < sizeof ctx->build_id; j++)
                    k += snprintf(ctx->build_id + k, sizeof ctx->build_id - k, "%02x", desc[j]);
            }
            p = desc + ((nh->n_descsz + 3) & ~3u);
        }
    }
    return 1;
}

static void try_install(void) {
    if (atomic_load(&g_installed) || !g_hook) return;
    struct find_ctx ctx;
    memset(&ctx, 0, sizeof ctx);
    dl_iterate_phdr(find_cb, &ctx);
    if (!ctx.base) return;
    int expected = 0;
    if (!atomic_compare_exchange_strong(&g_installed, &expected, 1)) return; // installed by another thread
    atomic_fetch_or(&g_state, ST_LIB_SEEN);
    if (strcmp(ctx.build_id, kBuildId) != 0) {
        atomic_fetch_or(&g_state, ST_WRONG_BUILD);
        gc_log("libgcastartup.so BuildId %s is not the supported %s; native guards NOT installed", ctx.build_id, kBuildId);
        return;
    }
    g_base = ctx.base;
    int r = g_hook((void *)(g_base + kOffModelRunnerRun), (void *)run_stub, &orig_run);
    atomic_fetch_or(&g_state, r == 0 ? ST_GUARD : ST_HOOK_FAILED);
    gc_log("libgcastartup.so (BuildId %s) at %p: ModelRunner::Run guard %s", ctx.build_id, (void *)g_base,
           r == 0 ? "installed" : "FAILED");
}

static void on_library_loaded(const char *name, void *handle) {
    (void)handle;
    if (name && strstr(name, "libgcastartup.so")) try_install();
}

__attribute__((visibility("default"))) NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    g_hook = entries ? entries->hook_func : NULL;
    if (g_hook) atomic_fetch_or(&g_state, ST_API);
    __android_log_print(ANDROID_LOG_INFO, TAG, "native_init (API version %u)", entries ? entries->version : 0);
    try_install(); // in case it is already loaded
    return on_library_loaded;
}

// --- Java side ----------------------------------------------------------------

// com.experimental.gcam100x.Native.status(): installs the guards if
// libgcastartup.so is loaded by now (in case its load was not reported), then
// returns the ST_* bits.
JNIEXPORT jint JNICALL Java_com_experimental_gcam100x_Native_status(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    try_install();
    return atomic_load(&g_state);
}
