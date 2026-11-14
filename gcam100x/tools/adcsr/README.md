# AdcSR engine for GCam100x (replaces the Google-model route)

Model: AdcSR (CVPR 2025, Apache-2.0), official weights from
huggingface.co/Guaishou74851/AdcSR — `net_params_200.pkl`
(sha256 87fc2d3b…6111) + `halfDecoder.ckpt` (sha256 60c8763a…5032).

## Phase 1 — reference inference (done, x86 CPU)
- Network built exactly as the official `test.py` (strict weight load); the SD 2.1
  UNet is only needed as an architecture, so no Stable Diffusion download.
- 456.1 M parameters; one tile is 128x128 -> 512x512 (4x), single step.
- Output is generative (new texture/edges), so PSNR is below bicubic; colours can
  drift from ground truth. x86 CPU: ~4.5 s/tile, 5.6 GB peak (PyTorch FP32).

## Phase 2 — conversion (done offline, device run pending)
| Artifact | Size | Check |
|---|---|---|
| ONNX opset 17 | 1.8 GB | max abs diff vs PyTorch 7e-5 |
| LiteRT FP32 (litert-torch 0.10) | 1.8 GB | max abs diff 3e-5 |
| LiteRT FP16 weights | 915 MB | 76.6 dB vs FP32 |
- Ops: CONV_2D, FULLY_CONNECTED, BATCH_MATMUL, SOFTMAX, GELU, MEAN, RSQRT, MUL/ADD/SUB,
  RESHAPE/TRANSPOSE/SLICE/CONCAT, RESIZE_NEAREST, PAD, LOGISTIC; max tensor rank 4.
- Weight-only int8 recipe fails in the quantizer on one conv (not yet resolved).
- Cost per tile: ~495 GMACs conv/linear (+~55 attention). UNet 185 GMACs / 444 M params;
  VAE decoder 310 GMACs / 12 M params -> the decoder is 62% of the compute.

## Phase 3 — measured on the Pixel 9 Pro XL (Tensor G4, Android 17), 2026-11-13
One 128x128 -> 512x512 tile, exact AdcSR graph (placeholder weights), LiteRT 2.3.2,
inside an app process (`gcam100x/bench`):

| Path | Result |
|---|---|
| GPU, ML Drift OpenCL (Mali-G715) | **1565 ms/tile** (1561-1568 over 7 runs), all 3173 nodes on GPU, first init 68 s, peak RSS 4.7 GB |
| NPU (Tensor TPU) | not available to a normal app: the Google Tensor compiler plugin needs `/vendor/lib64/libedgetpu_litert.so`, outside the app linker namespace |
| CPU, XNNPACK | 7.9 s/tile with the Feb-2025 benchmark tool; 134 s/tile through LiteRT 2.3.2 CompiledModel on the FP16 file |

A 120x photo (384x510 -> 1536x2040) needs >= 12 tiles: ~19 s without overlap, ~30 s
with overlap. **AdcSR as released misses the <= 5 s target on the G4 GPU by 4-6x**, and
its memory footprint is too high for use next to the camera. Even a free decoder
would leave ~0.6 s/tile for the UNet (37% of the MACs), i.e. ~7 s per photo.

Earlier attempts that produced no accelerator numbers: Google's Feb-2025
`benchmark_model` (old GPU delegate took 691/3173 nodes; Mali driver not loadable from
the Termux folder) and a bare executable under KernelSU (GPU accelerator would not
register). Use the app.

Scripts: `adcsr_build.py` (model), `export_onnx.py`, `export_tflite.py`, `quant.py fp16`,
`export_bench.py` (placeholder-weight benchmark graph), `macs2.py`, `tfl_ops.py`,
`bench/lrbench.c` (benchmark core, also built into the app).

## Engine and enhancer app (GPU path, in progress)
- `gcam100x/enhancer`: the engine as its own app/process. `jni/engine.c` tiles a photo
  (128-px tiles, 16-px overlap, feathered blend), applies AdcSR's per-tile mean/std
  normalisation and a low-frequency colour lock to the input, and caches the compiled
  GPU programs. Verified on x86 with the real weights (4-tile image, no seams).
  `EnhanceService` (":engine" process) reduces a photo to <= 512 px on the long side,
  restores 4x, and saves `Pictures/GCam100x/<name>_AI.jpg` (EXIF marks it AI-enhanced);
  the source is never modified. Not yet verified on the phone.

## Quantization for the TPU
- `quant_int8.py <src> <dst> <recipe>`: static quantization with ai-edge-quantizer 0.10.1.
  Two local fixes were needed: skip `_ensure_model_qsv_sufficient` (needs 21 GB) and a bug
  in `uniform_quantize_tensor.py` for weights > 32 MiB (scales not reshaped with the data).
- W8A8 (`static_wi8_ai8`): output is garbage (13.5 dB vs float).
- **W8A16 (`static_wi8_ai16`, the paper's setting): 33.1 dB vs float, visually equivalent,
  463 MB.** Whether the G4 TPU accepts 16-bit activations is what `tpu_probe.sh` tests
  (NNAPI google-edgetpu, and the LiteRT Google Tensor plugin run as root).

## 7.0: camera integration (GPU path, built, not yet run on the phone)
- Module 7.0.0: Google's G5-only model path is retired (never loaded, never run). Every photo
  above 30x is cropped as before, and a copy of the crop is dropped into
  `Android/media/com.google.android.GoogleCamera/gcam100x/` (Google Camera may write there
  without any permission; a direct cross-app start is blocked by package visibility).
- Enhancer 1.0: a small foreground service watches that folder, restores each photo with
  AdcSR on the GPU (1536x2040 crop -> 368x488 input, 12 tiles, 8-px overlap -> resized back to
  the crop's size) and saves `DCIM/Camera/<name>_AI.jpg` with the source's EXIF dates and an
  AI-generated note. Expected ~19 s of model time per photo from the measured 1565 ms/tile.

## TPU status (2026-11-14)
- Nothing has executed on the TPU yet.
- Root, bare executable: the Google Tensor plugin selects every op (quantized and float) and
  starts compiling; the whole 456 M-parameter model was still compiling after 15 min.
- Normal app: libraries load, compile starts, then "Compile failed, error code 16: Current
  application should not be allowed to access EdgeTPU" (EdgeTPU app allowlist).
- Decoder alone on the GPU: 945 ms/tile (so the UNet half is ~620 ms).
- `tpu_dec.sh`: compiles and runs the decoder (W8A16 and float) on the TPU as root and reads
  `/sys/devices/platform/1a000000.rio/tpu_usage` before/after. Result pending.
