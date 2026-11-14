# Pro Zoom on Tensor G4 — status, tooling, and handoff

Purpose: enough to continue the investigation elsewhere without re-deriving it.

## Pipeline map (centaur_p25 package, measured)

| File (sha1 head) | Stage | Format | chip_family | Runs on G4? |
|---|---|---|---|---|
| `3721f30e` (21.9 MB) | VAE encoder | TFLite+DGC0 | 25 (buenos) | No — TPU, rejected |
| `fc68b16d` (938 MB) | denoiser / UNet | TFLite+DGC0 | 25 (buenos) | No — TPU, rejected |
| `a4f62875` (45.9 MB) | VAE decoder | TFLite+DGC0 | 25 (buenos) | No — TPU, rejected |
| `df87d79c` (2.1 MB) | aux net (256x384x2 -> 128x192x1) | TFLite+DGC0 | 25 (buenos) | No — TPU, rejected |
| `31d53482` (9.9 MB) | segmentation 640x640 | TFLite+DGC0 | 25 (buenos) | No — TPU, rejected |
| `07847f86` (18.7 MB) | SSD detector | plain TFLite | n/a | Yes (CPU/GPU/NNAPI) |
| `5f923d6a` (682 B) | pipeline config (3 runners: encoder/unet/decoder) | protobuf | — | — |
| `dee800d5` (144 B), `2be69bf8` (315 KB) | small config / embedding data | — | — | — |

**Every generative stage (encoder, denoiser, decoder, aux) and segmentation is a
G5/buenos TPU executable (chip_family 25).** All are rejected identically by the
G4 TPU firmware at graph registration — this is not specific to the denoiser.
Only the SSD detector (plain TFLite) would run on G4.

## Registration failure (device-confirmed)

From the phone's own log during a 120x shot (firmware `zpr_tpu_20260603.0.0`):
```
tflite : Replacing 1/1 node(s) with delegate (edgetpu-custom-op-2) ... subgraph main
Darwinn: ERR registration.cc:128 - Expected chip type 16 in executable, got 255.
Darwinn: ERR driver_impl.cc:104 - Registration setup failed.
native : RegisterGraphRequest ... error_code=3 ; INVALID_ARGUMENT
native : Node 1 (edgetpu-custom-op-2) failed to prepare.
native : model_runner.cc:219 INTERNAL: Failed to allocate tensors.
```
`16` = G4 "rio" HW version (independently confirmed: the xThr45hx on-device G4
compiler forces `HW=16` for A17+). `255` = 0xFF = the A17 firmware does not
recognize the buenos chip-type in the executable. The chip_family u32 in the
TFLite wrapper (25, located 16 bytes before `DGC0`) is the host-side selection
id; the firmware reads a separate chip-type from inside the DGC0 payload that is
not a labelled header byte (the 16 bytes after `DGC0` are zero).

Downstream of that rejection, the runner is left with `config_ == null`; the
pipeline calls `ModelRunner::Run` on it anyway -> the SIGSEGV at `0x55cfff8` in
every tombstone. The crash is a symptom; registration is the gate.

## Routes (status, not verdicts)

- **A — run G5 binary on G4:** blocked at firmware registration (device-proven).
  Passing registration would NOT imply correct inference (G5 ISA on G4 silicon).
- **B — patch binary to retarget:** chip identity is not one header byte; HW
  target pervades tiling + instruction schedule. No evidence a tag edit yields a
  runnable G4 program. Open: exact offset of the firmware's chip-type field.
- **C — reconstruct a portable model from the binaries:** OUT OF SCOPE for this
  assistant (reconstructing proprietary trained weights+graph into a runnable
  model). Facts established: weights are present (denoiser: 33 named param groups
  ~867 MB; stored 8-bit), physical layout is chip-specific, no portable graph is
  in the package. Unresolved: whether logical weights can be de-tiled/dequantized
  and the graph recovered.
- **D — alternative backend (GPU/CPU):** depends on C.

## Tools (`tools/prozoom_re/`)

- `dgc_container.py <file>...` — JSON container manifest: chip_family, DGC0 offset,
  I/O tensor names, param/inst group directory + sizes, scratch sections.
- `dgcmap.py <file>` — raw structural-string + trailer dump.
- `psize.py <file>` — parameter-group size directory.
- `pbraw.py <file>` — schema-less protobuf dump (used for the pipeline config).
- `xref.py`, `ann.py` — ARM64 string-xref / annotated disassembly (need an objdump).
- `survey.py`, `dgc.py`, `fbwalk.py` — earlier container/flatbuffer probes.
- `p25_stage_manifests.json` — generated manifests for the p25 TPU stages.

## Reproduce the analysis

- Binaries: the centaur_p25 (G5) and centaur_p26 (G6) packages (model files named
  by sha1). Not committed (proprietary); obtain from the device's MDD store.
- `python3 tools/prozoom_re/dgc_container.py <model-file>` for a manifest.
- Module build (unchanged): `./gradlew :app:assembleDebug`; native guard via
  `NDK=<ndk> native/build.sh`. See repo root and `native/`.

## Outstanding technical questions

1. Exact offset/encoding of the firmware chip-type field inside the DGC0 (would
   confirm whether "255" is an unknown-enum vs a version-field parse mismatch).
2. Whether a G4 on-device compile (xThr45hx method) accepts any intermediate
   representation recoverable from these artifacts — requires a device.
3. Whether the VAE encoder/decoder (smaller, simpler than the denoiser) have any
   non-TPU execution path — all are currently buenos DGC0, so no.
4. C-route questions (de-tiling, quant metadata, graph) — not pursued here.

## Known limitation of this assistant

Reconstructing Google's trained Pro Zoom model (recovering its learned weights
and graph from the compiled binary into a functioning/portable model) is the one
operation not performed here. All container/format/compatibility analysis above
was performed.
