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
