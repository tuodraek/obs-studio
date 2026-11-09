# Pro Zoom denoiser — model-artifact analysis (G5 vs G6)

Files: `centaur_p25/fc68b16d…` (G5/buenos, 938.5 MB) and
`centaur_p26/3897d53f…` (G6/santafe, 918.0 MB). Both are the **same model**
(the `l8_c320_pixelsynth…consistency…denoiser`), each AOT-compiled for its chip.

## Container layout
- Outer **TFLite** flatbuffer; one op `edgetpu-custom-op-2` whose custom_options
  are `{ u32 chip_family, <DGC0 payload> }`.
- The `chip_family` u32 sits 16 bytes before the `DGC0` magic:
  **G5 = 0x19 (25, buenos), G6 = 0x1a (26, santafe)**. This is the host-side
  executable-selection id (matches the camera's selection logic). It is NOT the
  value the TPU firmware reports as "255" — that is a separate chip-type the
  firmware parses from inside the DGC0.
- **DGC0 payload**: 16-byte-zeroed header, then a flatbuffer directory of named
  sections, then the data. Sections:
  - `main_STAND_ALONE_params_Group_0_*` — weight blocks (the learned parameters)
  - `TPUOffloadFunc_STAND_ALONE_inst_Group_0_*` — compiled TPU instructions
  - `scratch_*`, `dive_scratch_*`, `dive_model_info` — runtime buffers (DIVE)
  - `main_STAND_ALONE_cluster2` — cluster binding
  - I/O tensor names: `serving_default_sample:0`,
    `serving_default_encoder_hidden_states:0`, `serving_default_timesteps:0`,
    `StatefulPartitionedCall:0`.

## Parameters ARE present (recoverable as numbers)
- G5: **33 weight groups, ≈ 867 MB** total (largest group 22 ≈ 264 MB; also
  133, 77, 69, 59, 56, 55 MB …). G6: 33 groups, ≈ 871 MB.
- Sampling a weight region: byte values form a smooth **Gaussian centred on
  0x80** (0x80,0x7f,0x81,0x7e… most common), 151/256 distinct values. That is
  the signature of **plain int8 weights (uint8, zero-point ~128)** — NOT
  compressed, NOT encrypted. The numbers are readable.

## But they are physically chip-specific (the barrier to reuse)
- 64 KB-block hash overlap between G5 and G6: **~0%** (1 shared block). The same
  logical tensors are re-laid-out per chip.
- Per-tensor physical sizes differ (G5 max 264 MB vs G6 max 232 MB; G5 groups
  indexed 0–40 with `_2_0_0` suffix, G6 indexed 0–134 with `_0_0_0`): each chip's
  compiler tiles/pads weights to its own systolic-array geometry and emits its
  own instruction schedule.

## Route assessment (evidence-based)
- **A (run G5 binary on G4):** firmware rejects at registration (chip type 16 vs
  255). Dead before execution.
- **B (patch the binary to retarget):** the chip identity the firmware reads is
  not a single header byte (header is zeroed); the HW target pervades the tiled
  weights and the instruction schedule (independent RE project forces HW=16 in
  the *compiler*, never by editing a foreign binary). Flipping a tag cannot turn
  buenos tiling+ISA into rio tiling+ISA.
- **C (reconstruct a portable model):** weights are present as int8 and readable,
  which refutes "nothing recoverable". To make them *usable* you must (1) reverse
  the undocumented buenos weight-tiling/padding to recover each logical tensor,
  (2) recover per-tensor quant scales/zero-points, (3) recover the full UNet graph
  (op list + connectivity) — the graph is only present as compiled TPU
  instructions, not a portable form. Large, uncertain RE; not refuted, not close.
- **D (GPU/CPU backend):** feasible *only if* C yields a portable graph+weights;
  then a float/int8 TFLite GPU/CPU path is standard. Gated entirely by C.

## Tools (this dir)
- `dgcmap.py <file>` — list DGC0 structural strings + trailer bytes.
- `psize.py <file>` — parameter-group size directory.

## Corrections (claims that were overstated)

Two earlier statements went beyond what the evidence supports. Correcting them,
at the container/format level only:

1. **Quantization format — not proven by the histogram.** A byte distribution
   centred on 0x80 is equally consistent with (a) uint8 with zero-point 128 and
   (b) signed int8 stored as `byte = int8 XOR 0x80`. The public libredgetpu
   project documents that DarwiNN parameter blobs use exactly (b)
   (`SIGN_BIT_FLIP = 0x80`), plus 64-row groups, 512-byte per-group overhead
   (per-channel float32 requant multipliers), and 4-column tiling — for the
   Coral Edge TPU. The G5/G6 Tensor denoisers may or may not match that exact
   scheme; the histogram alone cannot decide, and confirming it on Google's
   binaries would require reconstructing the weights (not done — see below).

2. **"Re-tiled / different logical weights" — not established.** The ~0% 64 KB
   block overlap proves the two builds' *physical byte layouts* differ. It does
   NOT prove re-tiling specifically (a permutation, alignment/padding change, or
   different grouping would also destroy block hashes), and it does NOT prove
   the logical weights differ OR match. Equal group counts and near-equal totals
   likewise do not prove identical learned parameters. Whether G5 and G6 carry
   the same logical weights is unresolved, and resolving it is the reconstruction
   operation this project does not perform.

Format observation (supported): G5 param-group sizes are clean multiples of 256
(and mostly 512), with several exactly recurring sizes — consistent with a
regular tiled container, but this does not by itself identify tile geometry or
recover values.

## Scope boundary of this analysis

This file characterises the **container format** (sections, sizes, chip ids,
registration mechanism) for interoperability/compatibility research. It does not
de-tile or dequantize the parameter blocks into logical tensors, does not recover
the computational graph, and does not reconstruct a runnable copy of Google's
trained Pro Zoom model. Those steps are out of scope.
