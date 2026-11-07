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
// Nothing is touched unless the loaded libgcastartup.so has exactly that
// BuildId.
#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <link.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define TAG "GCam100x-native"
#define LOG_PATH "/data/user/0/com.google.android.GoogleCamera/files/gcam100x.log"

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
static int g_installed;

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
    if (g_installed || !g_hook) return;
    struct find_ctx ctx;
    memset(&ctx, 0, sizeof ctx);
    dl_iterate_phdr(find_cb, &ctx);
    if (!ctx.base) return;
    g_installed = 1;
    if (strcmp(ctx.build_id, kBuildId) != 0) {
        gc_log("libgcastartup.so BuildId %s is not the supported %s; native guards NOT installed", ctx.build_id, kBuildId);
        return;
    }
    g_base = ctx.base;
    int r1 = g_hook((void *)(g_base + kOffFormatString), (void *)hooked_format, (void **)&orig_format);
    int r2 = g_hook((void *)(g_base + kOffModelRunnerRun), (void *)run_stub, &orig_run);
    gc_log("libgcastartup.so (BuildId %s) at %p: formatter guard %s, ModelRunner::Run observer %s", ctx.build_id,
         (void *)g_base, r1 == 0 ? "installed" : "FAILED", r2 == 0 ? "installed" : "FAILED");
}

static void on_library_loaded(const char *name, void *handle) {
    (void)handle;
    if (name && strstr(name, "libgcastartup.so")) try_install();
}

__attribute__((visibility("default"))) NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    g_hook = entries ? entries->hook_func : NULL;
    __android_log_print(ANDROID_LOG_INFO, TAG, "native_init (API version %u)", entries ? entries->version : 0);
    try_install(); // in case it is already loaded
    return on_library_loaded;
}
