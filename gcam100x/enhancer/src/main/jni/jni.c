#include <jni.h>
#include <stdio.h>
#include <string.h>
#include "engine.h"

static volatile int g_done, g_total;
static void progress(void *u, int d, int t) { (void)u; g_done = d; g_total = t; }
static char g_err[512];

#define J(name) Java_com_experimental_gcam100x_enhancer_Native_##name

JNIEXPORT jlong JNICALL J(open)(JNIEnv *env, jclass c, jstring jmodel, jstring jacc, jstring jlib, jstring jcache, jstring jlog) {
    (void)c;
    const char *model = (*env)->GetStringUTFChars(env, jmodel, 0), *acc = (*env)->GetStringUTFChars(env, jacc, 0);
    const char *lib = (*env)->GetStringUTFChars(env, jlib, 0), *cache = jcache ? (*env)->GetStringUTFChars(env, jcache, 0) : NULL;
    if (jlog) { const char *log = (*env)->GetStringUTFChars(env, jlog, 0); freopen(log, "a", stderr); setvbuf(stderr, NULL, _IONBF, 0); }
    g_err[0] = 0;
    return (jlong)(intptr_t)engine_open(model, acc, lib, cache, g_err, sizeof g_err);
}
JNIEXPORT jint JNICALL J(enhance)(JNIEnv *env, jclass c, jlong h, jbyteArray jin, jint w, jint hh, jbyteArray jout, jfloat lock) {
    (void)c;
    jbyte *in = (*env)->GetByteArrayElements(env, jin, 0), *out = (*env)->GetByteArrayElements(env, jout, 0);
    g_done = 0; g_total = 0; g_err[0] = 0;
    int rc = engine_enhance((Engine *)(intptr_t)h, (const uint8_t *)in, w, hh, (uint8_t *)out, lock, progress, NULL, g_err, sizeof g_err);
    (*env)->ReleaseByteArrayElements(env, jin, in, JNI_ABORT); (*env)->ReleaseByteArrayElements(env, jout, out, 0);
    return rc;
}
JNIEXPORT jstring JNICALL J(error)(JNIEnv *env, jclass c) { (void)c; return (*env)->NewStringUTF(env, g_err); }
JNIEXPORT jint JNICALL J(progress)(JNIEnv *env, jclass c) { (void)env; (void)c; return (g_done << 16) | (g_total & 0xffff); }
JNIEXPORT jstring JNICALL J(info)(JNIEnv *env, jclass c, jlong h) {
    (void)c; Engine *e = (Engine *)(intptr_t)h; char b[200];
    snprintf(b, sizeof b, "init %.0f ms, last tile %.0f ms, fully accelerated: %s, gpu cache: %s", engine_init_ms(e), engine_last_tile_ms(e),
             engine_fully_accelerated(e) ? "yes" : "NO", engine_cache_off(e) ? "off (options rejected)" : "on");
    return (*env)->NewStringUTF(env, b);
}
JNIEXPORT void JNICALL J(close)(JNIEnv *env, jclass c, jlong h) { (void)env; (void)c; engine_close((Engine *)(intptr_t)h); }
