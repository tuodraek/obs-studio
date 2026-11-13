// AdcSR enhancer engine: 4x generative restoration of an RGB image through a
// LiteRT CompiledModel (one 128x128 -> 512x512 tile at a time), with feathered
// tile blending and a low-frequency colour lock to the input.
#ifndef GCAM100X_ENGINE_H_
#define GCAM100X_ENGINE_H_
#include <stdint.h>
typedef struct Engine Engine;
typedef void (*EngineProgress)(void *user, int done, int total);
// accelerator: "gpu", "npu" or "cpu". cache_dir may be NULL. Returns NULL on failure (reason in err).
Engine *engine_open(const char *model_path, const char *accelerator, const char *lib_dir, const char *cache_dir, char *err, int err_len);
// rgb: w*h*3 bytes. out: (4w)*(4h)*3 bytes. Returns 0 on success. colour_lock: 0..1 strength.
int engine_enhance(Engine *e, const uint8_t *rgb, int w, int h, uint8_t *out, float colour_lock, EngineProgress cb, void *user, char *err, int err_len);
double engine_last_tile_ms(const Engine *e);
double engine_init_ms(const Engine *e);
int engine_fully_accelerated(const Engine *e);
int engine_cache_off(const Engine *e); // 1 when the cached-GPU options were rejected and defaults were used
void engine_close(Engine *e);
#endif
