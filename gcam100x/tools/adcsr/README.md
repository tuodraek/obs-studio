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
