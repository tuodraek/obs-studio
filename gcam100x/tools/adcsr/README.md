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

## Phase 3 gate — measure on the Pixel 9 Pro XL
`bench_g4.sh` runs Google's LiteRT `benchmark_model` on the exact graph with
placeholder weights (`export_bench.py`; identical op list and tensor sizes, so
latency/memory are representative; the output is not an image). It reports GPU
(OpenCL FP16) init time, per-tile latency over repeated runs, peak memory, and a
CPU reference. Nothing in the camera or module is touched.

Scripts: `adcsr_build.py` (model), `export_onnx.py`, `export_tflite.py`, `quant.py fp16`,
`export_bench.py`, `macs2.py`, `tfl_ops.py`.
