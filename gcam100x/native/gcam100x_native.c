// GCam100x native part: guards Google Camera's Pro Zoom pipeline on Tensor G4.
//
// Google Camera 11.1.040.982810059.19, libgcastartup.so (BuildId
// dcec58ae3422114fadc933c1c8a6331c):
//  - ModelRunner::Run (0x55cffc4) starts by formatting
//    "ModelRunner::Run:%s" with its config's name, read from
//    this->[0x10]->[0x38] (a protobuf string field).
//  - On the G4 that pointer is null for one runner of the diffusion
//    super-resolver (super_resolution_diffuser.cc, Inference) and the string
//    formatter (0x78016d0) dereferences it: SIGSEGV, null pointer.
// This library:
//  1. hooks the formatter: a null std::string argument becomes an empty one
//     (and is logged with its caller), so the camera does not crash there;
//  2. hooks ModelRunner::Run with a register-preserving stub that logs the
//     runner, its config pointer and name pointer before the call proceeds.
//  3. captures Google's own native log messages (liblog) into the module's
//     log: every warning/error of the process, and everything (info and up)
//     while Google's Pro Zoom model runs (the Java side marks that window with
//     the file gcam100x_model_running). Messages of the model window are also
//     written to gcam100x_model_messages, so the per-photo verdict can show
//     Google's own reason when its model gives no output.
// 1 and 2 touch nothing unless the loaded libgcastartup.so has exactly that
// BuildId; 3 only reads messages.
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
#define MODEL_MARKER FILES_DIR "gcam100x_model_running"
#define MODEL_MESSAGES FILES_DIR "gcam100x_model_messages"

static const char kBuildId[] = "dcec58ae3422114fadc933c1c8a6331c";
static const uintptr_t kOffFormatString = 0x78016d0; // std::string %s formatter
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
#define ST_GUARD 2        // formatter guard + ModelRunner::Run observer installed
#define ST_LOG 4          // Google log capture installed
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

// --- 1. String formatter guard ------------------------------------------------

// An empty libc++ std::string (short form: size byte 0).
static const uint64_t kEmptyString[3] = {0, 0, 0};
static uint64_t (*orig_format)(const void *s, uint64_t a1, uint64_t a2, uint64_t a3, uint64_t a4, uint64_t a5,
                               uint64_t a6, uint64_t a7);
static int g_null_reports;

static uint64_t hooked_format(const void *s, uint64_t a1, uint64_t a2, uint64_t a3, uint64_t a4, uint64_t a5,
                              uint64_t a6, uint64_t a7) {
    if (s == NULL) {
        uintptr_t caller = (uintptr_t)__builtin_return_address(0);
        if (g_null_reports++ < 20)
            gc_log("Guard: null std::string passed to the %%s formatter (caller libgcastartup+0x%lx); using an empty string",
                 (unsigned long)(caller - g_base));
        s = kEmptyString;
    }
    return orig_format(s, a1, a2, a3, a4, a5, a6, a7);
}

// --- 2. ModelRunner::Run observer ---------------------------------------------

static void *orig_run;   // backup trampoline
static int g_run_reports;

// Called from the stub with the runner (x0) and the caller (x30).
__attribute__((used)) static void on_model_runner_run(uintptr_t self, uintptr_t caller) {
    if (g_run_reports++ > 40) return;
    uintptr_t cfg = 0, name = 0;
    if (self) cfg = *(uintptr_t *)(self + 0x10);
    if (cfg) name = *(uintptr_t *)(cfg + 0x38) & ~(uintptr_t)3;
    char shown[80] = "(null)";
    if (name) {
        const uint8_t *str = (const uint8_t *)name;
        const char *p;
        size_t len;
        if (str[0] & 1) { len = *(const size_t *)(str + 8); p = *(const char *const *)(str + 16); }
        else { len = str[0] >> 1; p = (const char *)(str + 1); }
        if (len > sizeof shown - 1) len = sizeof shown - 1;
        memcpy(shown, p, len);
        shown[len] = 0;
    }
    gc_log("ModelRunner::Run runner=%p config=%p name=%s (caller libgcastartup+0x%lx)", (void *)self, (void *)cfg,
         shown, (unsigned long)(caller - g_base));
    if (cfg) {
        const uint64_t *q = (const uint64_t *)cfg;
        gc_log("  config words: %016llx %016llx %016llx %016llx %016llx %016llx %016llx %016llx",
             (unsigned long long)q[0], (unsigned long long)q[1], (unsigned long long)q[2], (unsigned long long)q[3],
             (unsigned long long)q[4], (unsigned long long)q[5], (unsigned long long)q[6], (unsigned long long)q[7]);
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

// --- 3. Google's own log messages ----------------------------------------------

// liblog's common sink (Android 11+): every __android_log_print/_write/
// _vprint/_buf_write call, Java's android.util.Log included, ends here.
struct log_message {
    size_t struct_size;
    int32_t buffer_id;
    int32_t priority;
    const char *tag;
    const char *file;
    uint32_t line;
    const char *message;
};
static void (*orig_write_log_message)(struct log_message *m);
static atomic_int g_outside_lines; // warnings/errors outside the model window
static atomic_int g_window_lines;  // lines inside the current model window
static atomic_llong g_window_checked_ms;
static atomic_int g_in_window;

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* Is Google's model running right now? (marker file, re-checked every 40 ms) */
static int in_model_window(void) {
    long long t = now_ms();
    if (t - atomic_load(&g_window_checked_ms) > 40) {
        int in = access(MODEL_MARKER, F_OK) == 0;
        if (in && !atomic_load(&g_in_window)) atomic_store(&g_window_lines, 0);
        atomic_store(&g_in_window, in);
        atomic_store(&g_window_checked_ms, t);
    }
    return atomic_load(&g_in_window);
}

static void append(const char *path, const char *line, int n) {
    int fd = open(path, O_WRONLY | O_APPEND | O_CREAT | O_CLOEXEC, 0600);
    if (fd >= 0) {
        write(fd, line, n);
        close(fd);
    }
}

static void capture(int prio, const char *tag, const char *msg) {
    if (!msg || prio < ANDROID_LOG_INFO) return;
    if (tag && strncmp(tag, "GCam100x", 8) == 0) return; // our own lines
    int window = in_model_window();
    if (window) {
        if (atomic_fetch_add(&g_window_lines, 1) >= 400) return;
    } else {
        if (prio < ANDROID_LOG_WARN || atomic_fetch_add(&g_outside_lines, 1) >= 300) return;
    }
    static const char kPrio[] = "VVVDIWEF";
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    struct tm tm;
    localtime_r(&ts.tv_sec, &tm);
    char line[1200];
    int n = snprintf(line, sizeof line, "%02d-%02d %02d:%02d:%02d.%03ld G [google%s] %c/%s: %.1000s\n", tm.tm_mon + 1,
                     tm.tm_mday, tm.tm_hour, tm.tm_min, tm.tm_sec, ts.tv_nsec / 1000000, window ? " model" : "",
                     kPrio[prio & 7], tag ? tag : "-", msg);
    if (n <= 0) return;
    if (n >= (int)sizeof line) n = sizeof line - 1;
    append(LOG_PATH, line, n);
    if (window) append(MODEL_MESSAGES, line, n);
}

static void hooked_write_log_message(struct log_message *m) {
    if (m) capture(m->priority, m->tag, m->message);
    orig_write_log_message(m);
}

static void install_log_capture(void) {
    void *liblog = dlopen("liblog.so", RTLD_NOW | RTLD_NOLOAD);
    void *sink = liblog ? dlsym(liblog, "__android_log_write_log_message") : NULL;
    if (!sink) {
        gc_log("Google log capture unavailable (no __android_log_write_log_message)");
        return;
    }
    int r = g_hook(sink, (void *)hooked_write_log_message, (void **)&orig_write_log_message);
    if (r == 0) atomic_fetch_or(&g_state, ST_LOG);
    gc_log("Google log capture %s", r == 0 ? "installed (warnings/errors, everything during the model run)" : "FAILED");
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
    int r1 = g_hook((void *)(g_base + kOffFormatString), (void *)hooked_format, (void **)&orig_format);
    int r2 = g_hook((void *)(g_base + kOffModelRunnerRun), (void *)run_stub, &orig_run);
    atomic_fetch_or(&g_state, r1 == 0 && r2 == 0 ? ST_GUARD : ST_HOOK_FAILED);
    gc_log("libgcastartup.so (BuildId %s) at %p: formatter guard %s, ModelRunner::Run observer %s", ctx.build_id,
         (void *)g_base, r1 == 0 ? "installed" : "FAILED", r2 == 0 ? "installed" : "FAILED");
}

static void on_library_loaded(const char *name, void *handle) {
    (void)handle;
    if (name && strstr(name, "libgcastartup.so")) try_install();
}

__attribute__((visibility("default"))) NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    g_hook = entries ? entries->hook_func : NULL;
    if (g_hook) atomic_fetch_or(&g_state, ST_API);
    __android_log_print(ANDROID_LOG_INFO, TAG, "native_init (API version %u)", entries ? entries->version : 0);
    if (g_hook) install_log_capture();
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
