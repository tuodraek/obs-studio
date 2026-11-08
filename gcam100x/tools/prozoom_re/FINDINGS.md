# Pro Zoom (centaur) model package: findings

## Variants in Google Camera 11.1.040
Google Camera configures each Pixel generation separately:

| Config | Pro Zoom package | Companion TPU model |
|---|---|---|
| `gei.i` | `centaur_p25` | `siamese_fe_darwinn_custom_op_buenos_b0` |
| `fue.bF` | `centaur_p26` | `siamese_fe_darwinn_custom_op_yooto_a0` |
| `kov.a` (Pixel 9 Pro XL, komodo) | none | `siamese_fe_darwinn_custom_op_rio_a0` |

The installed bundle only contains `split_mdd_zoomplus_manifest_assets_module_p26.apk`.

## centaur_p26 package (pixelcamera-centaur/19523), all SHA-1 verified

| File | Size | Content | Target |
|---|---|---|---|
| 3897d53f… | 918 MB | `l8_c320_pixelsynth_model_soup_consistency_xid182214891_denoiser_qat-w8a8-drq` (`ildm_denoiser`): in `encoder_hidden_states[1,77,1024]`, `sample[1,128,128,16]`, `timesteps[]` → `[1,128,128,8]` | TPU `santafe_a0` |
| 83fb27a3… | 27 MB | `20250522_acat_search_c32_student_vae_latent_128x128x8_fp16_encoder`: `[1,1024,1024,3]` f32 → `[1,128,128,8]`; scratch 144 MB | TPU `santafe_a0` |
| 8538fb3d… | 40 MB | same VAE, decoder: `[1,128,128,8]` → `[1,1024,1024,3]`; scratch 192 MB | TPU `santafe_a0` |
| 49c52bdd… | 8 MB | `picko26_titan_100xzoom_ih640iw640_v2…_20260621`: person/skin/class masks from `[1,640,640,3]` u8 | TPU `santafe_a0` |
| e2c86a14… | 2 MB | `source[1,256,384,2]` → `[1,256,384,1]` | TPU (`DGC0`, no chip string) |
| 07847f86… | 19 MB | SSD detector (79 CONV_2D, 34 DEPTHWISE_CONV_2D, …), u8 | plain TFLite (CPU/GPU/NNAPI) |
| 2be69bf8… | 315 KB | NumPy f32 `[1,77,1024]`: fixed text conditioning | data |
| 4c9a6a21… | 725 B | gldm pipeline: encoder → denoiser → decoder, schedule `1.0,1.0,0.98,0.5,0.02,0.0` | config |
| dee800d5… | 144 B | numeric config | config |

## TPU executable format
- A TFLite model with one op, custom code `edgetpu-custom-op-2`, whose custom options hold the program.
- The custom options are a flatbuffer: `vector[1]` of `{u32 26, bytes payload}`.
- The payload is `u32 header_size` (3952–4048) followed by the `DGC0` magic, a zero header, metadata strings and the program. The metadata names the model, the parameter groups (`main_STAND_ALONE_params_Group_0_…`), the scratch buffers (`scratch_<bytes>`), `dive_model_info`, the cluster (`main_STAND_ALONE_cluster0`), `TPUOffloadFunc_STAND_ALONE_inst_Group…` and the chip (`santafe_a0`).
- Some executables embed DarwiNN firmware sources (`third_party/darwinn/firmware/...`).
- This is not the public Coral `DWN1` executable format.

Hardware reports identify Santafe as the Tensor G6 TPU; Google Camera's own config for the Pixel 9 Pro XL uses `rio_a0` executables.

## Tools
- `fetch_centaur.sh` downloads and verifies a package from its manifest.
- `survey.py` lists format, ops, tensors and targets.
- `extract.py` / `dgc.py` extract the TPU blob and the `DGC0` header.
- `fbwalk.py` walks a flatbuffer without its schema.

## Runtime (libgcastartup.so, 11.1.040.982810059.19)
- Google Camera's native engine (213 MB, from `split_startup_jni_split_module.apk`) drives the TPU through the phone's own vendor client, `libedgetpu_client.google.so` / `libedgetpu_tachyon.google.so`, using 233 `DarwinnApi2_*` entry points resolved at runtime.
- A TPU model's custom options hold a list of (chip family, executable) entries. Every Pro Zoom file has one entry, family `26`, matching Google's `*_tpu_26.tflite` naming.
- Executable selection, function at `0x6325470` (the "Unable to locate an executable that matches the target chip %d." message is referenced from `0x63255e4`):
  - one entry: it is returned as-is, with no chip check;
  - two or more entries: the current chip is queried (`bl 0x6328168`) and matched against `entry+0x10`; with no match, the error above.
- Result: on the G4 the family-26 (`santafe_a0`, G6) program is passed to the G4 TPU unchanged. The Pro Zoom controller reports initialized, and the native run then fails during execution (v2.4.1 log: the camera ended about 1.5 s into the run).
- Other runtime strings: `Unsupported chip family %d` (xrefs `0x62cd624`, `0x62d30a4`, `0x632bcf4`), `DIVE heap size is not set. Is the model compiled for the right target?` (`0x633e510`), `DarwinnApi2_ChipFamilyFromString` (`0x634ffe4`), `Running model on Rio cluster 2 ...`.
- `re/xref.py` finds ADRP+ADD code references to any string in the library.

## Denoiser architecture (from names and tensors)
`l8_c320_pixelsynth…consistency…denoiser_qat-w8a8-drq`:
- 8 latent channels, 320 base channels;
- cross-attention context `[1,77,1024]` (the OpenCLIP-H width used by Stable Diffusion 2.x);
- 16 input channels (8 noisy and 8 conditioning latents);
- consistency-distilled;
- int8 weights; 918 MB is the scale of the SD 2.x UNet (about 865M parameters).

The VAE is a separate small "student" VAE: `c32`, latent 128×128×8, fp16.

## centaur_p25 (Pixel 10, Tensor G5), the package GCam100x 4.0.0 uses
Obtained from Google Play with a Pixel 10 Pro (`blazer`) device profile: `split_mdd_zoomplus_manifest_assets_module_p25.apk`, delivered to that device group only (the p26 split is delivered to `grizzly_only` and `kodiak_only`). Its manifest lists model set **11422**; all 9 files are SHA-1 verified.

| File | Size | Stage | Executables |
|---|---|---|---|
| fc68b16d… | 939 MB | denoiser (same tensors as p26) | family **25**, `buenos_b0` |
| 3721f30e… | 22 MB | VAE encoder | family 25, `buenos_b0` |
| a4f62875… | 46 MB | VAE decoder | family 25, `buenos_b0` |
| 31d53482… | 10 MB | segmentation 640×640 | family 25 |
| df87d79c… | 2 MB | aux net, 256×384×2 → **128×192×1** | family 25 |
| 07847f86…, 2be69bf8…, dee800d5… | — | detector, text embedding, numeric config: identical to p26 | — |
| 5f923d6a… | 682 B | gldm pipeline config | — |

Chip families: 25 = `buenos` (Tensor G5), 26 = `santafe` (Tensor G6). The G4's executables are `rio_a0`.

## The G4 crash (two tombstones, identical)
`SIGSEGV`/`SEGV_MAPERR`, null pointer read, thread `GcaGeneric-*`, in libgcastartup.so (BuildId `dcec58ae3422114fadc933c1c8a6331c`):

```
#00 0x78016dc  std::string %s formatter: ldrb w9,[x0], with x0 = 0
#01 0x78016b8
#02-#04 0x7802650 / 0x78020f4 / 0x78023d8  string formatting
#05 0x55d0024  ModelRunner::Run (0x55cffc4) formats "ModelRunner::Run:%s" with this->[0x10]->[0x38] (protobuf string, tag bits masked)
#06 0x55ae04c  super_resolution_diffuser.cc, Inference: virtual call into the model runner
#07-#14 ...
#15 Java_com_google_android_apps_camera_zoomplus_jni_NativeZoomPlus_nativeRunZoomPlusPipeline+520
#18 com.google.android.apps.camera.zoomplus.jni.NativeZoomPlus.c
```

On the G4, one model runner of the diffusion super-resolver has a null name (unconfigured runner config). The crash happens before the runner executes anything, so the TPU has not run the model at this point. It is the same with the p25 and p26 packages.

GCam100x 4.1.0 adds a native part (`native/gcam100x_native.c`, loaded via `META-INF/xposed/native_init.list`). It acts only on that exact BuildId:
- it guards the formatter against a null string;
- it logs every `ModelRunner::Run` (runner, config, name, caller) through a register-preserving stub.

## Pipeline result status (decoded in 5.1.0)

`Java_…_NativeZoomPlus_nativeRunZoomPlusPipeline` (`0x2a9e1f4`) builds the
`ZoomPlusPipelineResponse` (Java `tnz`) on its stack at `sp+0x70`: has-bits at
`+0x10`, `b` (image handle) at `+0x18`, `a` (status) at `+0x20`, `c`/`d`/`e`
after it. After the pipeline call (`bl 0x2aa11ac`, result in `x0`):

```
2a9e404  mov  w10, #1
2a9e408  cmn  x0, #1          ; handle == -1 ?
2a9e410  cinc w10, w10, eq    ; status = (handle == -1) ? 2 : 1
2a9e420  str  x0,  [sp,#0x88] ; b = handle
2a9e424  str  w10, [sp,#0x90] ; a = status
```

The error path at `0x2a9e650` (`zoom_plus_jni: Unable to marshal …`) also
sets status 2. So **status 2 = no image** (handle -1, which `aaau.a()` never
maps to an image). `tmx` then logs "Final image not found. Promoting already
saved secondary original image to primary", calls `FusionProgressCallback.e`,
and Google keeps the photo it already saved. The 4.0/4.1 runs on the G4
(862 ms, status 2) gave no model output.

`ModelRunner::Run` (`0x55cffc4`), after the trace string, calls the runner's
`IsReady` (vtable `+0x28`). When that returns false, it fails with
"Inference Runner is not ready." (`model_runner.cc:243`). The null config
name that crashed 3.x/4.0 is that runner's config, which was never filled
in: the runner was never initialized. Google's own message for that
(`InitInferenceRunners: %s`, `ModelRunner::Initialize: %s`, DarwinN driver
errors) goes to logcat. Since 5.1.0 the native part captures it (liblog
`__android_log_write_log_message`) and the per-photo verdict shows it.

Engines in the diffusion code: `DarwinnInferenceRunner` (TPU),
`MlDriftInferenceRunner` (GPU) and `TfLiteInferenceRunner`. The Pro Zoom
package ships only TPU programs (`edgetpu-custom-op-2` + DGC0, family 25/26),
so there is no GPU model to select.

Google's own crop: with an active Pro Zoom state on the shot (`pcl.n.b`) and
no high-res (`!pcl.k`), Google's pipeline writes the photo already zoomed
(4.1.0 device run: 1536x2040 = 6144x8160 x 0.25) and crops the capture
thumbnail (`rah.aj` → `sxh.r`). The intermediate thumbnail (`rah.ak`, from
`ofv`/`pha` via `pnw.p`) is not cropped by Google.

## Audit (after 5.3.0): confirmed vs open

Confirmed by analysis of the files on disk:
- Model config (`5f923d6a…`, 682 bytes, decoded with `pbraw.py`): three
  runners, `encoder`, `unet` (inputs sample / encoder_hidden_states /
  timesteps) and `decoder`. Each uses a TFLite engine config (field 14) that
  points at a `*-graph-custom_op.tflite` file and is enabled (`3: 1`).
- Each of those files is a TFLite shell with a single `edgetpu-custom-op-2` op
  holding one DarwiNN executable, chip `buenos_b0` (family 25). The denoiser's
  parameters are in 35 groups (`main_STAND_ALONE_params_Group_0_*`), with
  scratch 146,800,640 B and a DIVE part (`dive_model_info`,
  `dive_scratch_147419136`).
- Model I/O shapes are known (denoiser: [1,77,1024], [1,128,128,16], [] →
  [1,128,128,8]). Internal layer shapes and the graph are not in readable
  form.
- Google Camera does not check the executable's chip itself before use:
  - executable selection returns a single entry unchanged;
  - it registers the executable with the vendor TPU library
    (`libedgetpu_client.google.so`, `DarwinnApi2_VirtualDevice_RegisterGraph*`).
  Any chip-based rejection would be in that vendor library (on the phone,
  `/vendor/lib64`), not in the camera.
- Checks inside libgcastartup on this path: ModelRunner init
  (`CheckModelConfig`, `CheckEngineConfig(tflite_config)`, "Missing model
  name", "Missing enabled inference engine", ...), the DIVE heap check in the
  statically linked `darwinn/driver_shared/graph_utils`, and a chip-family
  switch (`0x632bc58`) that accepts families 4, 5, 11, 14, 15, 16, 24, 25, 26
  and 31.
- Published precedent (litert-community/Gemma3-1B-IT-Tensor-NPU README): a
  G3-compiled program runs on G4 (the runtime does not enforce the device
  tag). A G5-compiled Gemma model loaded on G4 and its prefill ran, but its
  decode did not. That went through the LiteRT dispatch path; it is not
  verified for Google Camera's path.
- Google's Tensor ML SDK compiler: the URL in LiteRT's own `setup.py`
  (`…/tensor-ml-sdk/sdk/releases/6.1.0/litert_plugin_compiler.tar.gz`)
  returns 404, so a G4-vs-G5 test compile could not be made here.

Open (not yet established):
- The exact error behind "Inference Runner is not ready" on the G4: does
  graph registration in the vendor library fail, does the DIVE or scratch
  setup fail, or does initialization fail for another reason?
- Whether the vendor library on the G4 accepts a family-25 executable from
  Google Camera's path, as the LiteRT precedent suggests.

Needed next (data, no APK):
- `/vendor/lib64/libedgetpu_client.google.so` and
  `/vendor/lib64/libedgetpu_util.so` from the phone (world-readable), to
  analyze the vendor-side graph registration checks.
- One root logcat capture of a single Pro Zoom shot, to get Google's own
  initialization error.

## Vendor TPU runtime on the Pixel 9 Pro XL (libedgetpu_util.so, "libedgetpu_util_rio.so")

Analyzed from the phone's own `/vendor/lib64/libedgetpu_util.so` (BuildId
a2b5f2ea…) and `libedgetpu_client.google.so` (c030f5f4…):
- Chip compatibility (`0x702278`): it maps the program's chip name to a
  family (`0x7b90c8` → `0x27de74`) and requires it to equal the platform's.
  If the name is unknown, it logs "Failed to get chip family from model chip
  name. Assuming compatibility." and accepts the program. This runtime
  contains no `buenos` or `santafe` strings (only `rio`, `abrolhos`,
  `janeiro`), so a `buenos_b0` program takes the "assuming compatibility"
  path. **The chip check does not reject the G5 programs on the G4.** This
  agrees with the published report of a G5 Gemma program loading on a G4.
- Clusters: the runtime knows Rio cluster 2 ("WARNING: Running model on Rio
  cluster 2 might reduce the TPU overall concurrency performance"). The
  fatal check "Only one hardware cluster id is supported on this platform"
  (`0x2785bc`) is for programs that name several clusters. Each Pro Zoom
  file names one, `cluster2`.
- Other load-time checks exist and are not yet evaluated against these files:
  graph container version (`0x6dcca8`), firmware VII version, DIVE heap.
- The phone also runs an on-device DarwiNN compiler in
  `vendor.google.edgetpu_vendor_service` (`CompileTfliteFlatbuffer2`, "Darwinn
  TFlite ODC"). It compiles TFLite graphs for this TPU. That needs a
  compilable source graph.

## 5.3.0 device run (23:12:54)

The model started at 23:12:54.314 and the process died with SIGSEGV at
23:12:55.872. The `ModelRunner::Run` guard was installed and logged nothing.
So the crash was not a null-name `Run` call. Three more entry points
(`Run` overload `0x55d03a8`, `RunPartial` `0x55d078c` and `0x55d0b70`) have
the same prologue and read the same config name. 5.3.1-diag guards all four
and logs every call, to show which runner the pipeline reaches.

The 5.2.0 startup failure in the exit records is "ANR: failed to complete
startup", with the main thread blocked on a mutex. That was a deadlock at
startup, not a crash.

## Static validation of the ModelRunner hooks (5.3.1-diag, not released)

Checked against libgcastartup.so (BuildId dcec58ae…):
- Interface: in all four functions (`0x55cffc4`, `0x55d03a8`, `0x55d078c`,
  `0x55d0b70`), the only registers read before being written, up to the first
  call, are x0 (this), x1–x3 (arguments), x8 (result pointer) and the
  callee-saved x19–x28 (saved by the prologue). No floating-point or vector
  argument registers are used. A stub that preserves x0–x8 and x30 and calls
  an AAPCS-compliant C function matches this interface.
- Patched bytes: the first four instructions of each (`sub sp`, three `stp`)
  are not PC-relative, so relocating them into the trampoline is plain.
  Decoding every branch in `.text` (B/BL, B.cond, CBZ/CBNZ, TBZ/TBNZ) finds
  none that targets bytes 4–15 of these functions.
- Callers: no direct branch to any of them. Their addresses are stored in
  four consecutive 8-byte entries in `.data.rel.ro` (`0x79b3cf8`–`0x79b3d10`),
  which fits a table of virtual functions. Which class's table that is is not
  established: walking back from the entries to the table's start found a
  type-info name for an unrelated `std::function` helper
  (`DmabufAlignedAllocator`), so that walk stopped in the wrong place.
- Handler side effects: liblog and appends to the module's own log file. It
  calls nothing that reaches a ModelRunner. `/proc/self/maps` is read only on
  the null-config path.
- Not verifiable here: the hook framework's trampoline at run time, and
  whether the 5.3.0 `Run` hook itself was involved in the 23:12:55 crash.
  Only the crash report (tombstone) for that crash can show the faulting
  frame.

Google Camera's own native pipeline messages (tag `native`, e.g.
`inference_runner_impl.cc`, `darwinn_adapter.cc`) and the TPU driver's
messages (tag `Darwinn`) already go to logcat. A complete capture taken from
before the shot shows how far Google's pipeline gets, without new camera
hooks.

Format note: `DGC0` is not the public Coral `DWN1` executable schema
(`executable.fbs`), so container and instruction fields cannot be read
with that schema.
