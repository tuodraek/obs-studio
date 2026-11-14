// JNI wrapper around tools/adcsr/bench/lrbench.c for the test app. Everything the
// runtime prints (stdout/stderr) is appended to the log file, so a failure to load
// an accelerator is recorded with the loader's own reason.
#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>
#include <stdint.h>

int lrbench_main(int argc, char **argv);

static void try_open(const char *dir, const char *name) {
    char p[512];
    if (dir) snprintf(p, sizeof p, "%s/%s", dir, name); else snprintf(p, sizeof p, "%s", name);
    void *h = dlopen(p, RTLD_NOW | RTLD_LOCAL);
    printf("dlopen %-44s : %s\n", p, h ? "OK" : dlerror());
}

// Independent check that the vendor OpenCL driver is reachable from this process.
static void opencl_probe(void) {
    const char *names[] = {"libOpenCL.so", "libOpenCL-pixel.so"};
    for (int i = 0; i < 2; i++) {
        void *h = dlopen(names[i], RTLD_NOW | RTLD_LOCAL);
        if (!h) { printf("OpenCL %s: %s\n", names[i], dlerror()); continue; }
        int (*getPlat)(uint32_t, void **, uint32_t *) = dlsym(h, "clGetPlatformIDs");
        int (*getDev)(void *, uint64_t, uint32_t, void **, uint32_t *) = dlsym(h, "clGetDeviceIDs");
        int (*devInfo)(void *, uint32_t, size_t, void *, size_t *) = dlsym(h, "clGetDeviceInfo");
        if (!getPlat || !getDev || !devInfo) { printf("OpenCL %s: loaded, symbols missing\n", names[i]); continue; }
        void *plat = NULL, *dev = NULL; uint32_t n = 0;
        int rc = getPlat(1, &plat, &n);
        if (rc != 0 || n == 0) { printf("OpenCL %s: clGetPlatformIDs rc=%d n=%u\n", names[i], rc, n); continue; }
        rc = getDev(plat, 0xFFFFFFFFull, 1, &dev, &n);
        char name[256] = "?", ver[256] = "?"; uint64_t mem = 0;
        if (rc == 0 && n) { devInfo(dev, 0x102B, sizeof name, name, NULL); devInfo(dev, 0x102F, sizeof ver, ver, NULL); devInfo(dev, 0x101F, sizeof mem, &mem, NULL); }
        printf("OpenCL %s: OK device=\"%s\" version=\"%s\" global_mem=%llu MB (rc=%d)\n", names[i], name, ver, (unsigned long long)(mem >> 20), rc);
        return;
    }
}

JNIEXPORT jint JNICALL Java_com_experimental_adcsrbench_Native_run(JNIEnv *env, jclass c, jstring jmodel, jstring jacc,
                                                                   jint runs, jstring jlibdir, jstring jlog, jstring jcache) {
    (void)c;
    const char *model = (*env)->GetStringUTFChars(env, jmodel, 0), *acc = (*env)->GetStringUTFChars(env, jacc, 0);
    const char *libdir = (*env)->GetStringUTFChars(env, jlibdir, 0), *log = (*env)->GetStringUTFChars(env, jlog, 0);
    const char *cache = jcache ? (*env)->GetStringUTFChars(env, jcache, 0) : "";
    freopen(log, "a", stdout); freopen(log, "a", stderr);
    setvbuf(stdout, NULL, _IONBF, 0); setvbuf(stderr, NULL, _IONBF, 0);
    if (!strcmp(acc, "gpu")) { opencl_probe(); try_open(libdir, "libLiteRtClGlAccelerator.so"); }
    if (!strcmp(acc, "npu")) {
        try_open(libdir, "libLiteRtDispatch_GoogleTensor.so"); try_open(libdir, "libLiteRtCompilerPlugin_google_tensor.so");
        try_open(NULL, "libedgetpu_client.google.so"); try_open(NULL, "libedgetpu_util.so"); try_open(NULL, "libedgetpu_litert.so");
    }
    char r[16]; snprintf(r, sizeof r, "%d", (int)runs);
    char *argv[] = {"lrbench", (char *)model, (char *)acc, r, (char *)libdir, (char *)cache, NULL};
    int rc = lrbench_main(6, argv);
    fflush(stdout); fflush(stderr);
    return rc;
}
