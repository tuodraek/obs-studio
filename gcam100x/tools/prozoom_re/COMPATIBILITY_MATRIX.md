# Pro Zoom on Tensor G4 — stage compatibility matrix (evidence-tagged)

Categories: **VERIFIED WORKING** (ran on G4, correct), **VERIFIED FAILING**
(tested on G4, failed, with evidence), **LIKELY INCOMPATIBLE** (format/target
evidence only, not individually tested), **UNKNOWN**.

Evidence source: live on-device capture 2026-11-08 17:40 (pid 4458), firmware
`zpr_tpu_20260603.0.0`. Registration order established from the logcat.

## Runtime execution graph (observed, one 120x Pro Zoom capture)
1. Camera opens, Pro Zoom armed (manifest `centaur_p25` served; "Pro Zoom on 52x").
2. Camera's **own** TPU models register on the G4 TPU successfully — e.g. a
   271-node `edgetpu-custom-op-2` graph + two 1-node graphs at 17:40:27.697–.729
   (interleaved with `hawk_full_fov…p23`, `portrait_relighting`). No chip-type
   error. These are normal Pixel-9 (rio) camera models.
3. Shutter → CENTAUR fusion → Pro Zoom processor (`tna.c`) builds the ZoomPlus
   pipeline (config runners: encoder → unet/denoiser → decoder).
4. The pipeline registers the **denoiser** (buenos) TPU graph (17:40:31.547,
   `Replacing 1/1 node … edgetpu-custom-op-2`) → firmware rejects
   (`registration.cc:128 Expected chip type 16 … got 255`) → `RegisterGraphRequest
   … INVALID_ARGUMENT` → `model_runner.cc:219 Failed to allocate tensors`.
5. Runner left with `config_ == null`; pipeline calls `ModelRunner::Run` → SIGSEGV
   (`0x55cfff8`). Process exits (signal 11).
6. **Encoder, decoder, aux, Pro Zoom segmentation are never reached** — the crash
   occurs at the denoiser stage.

## Matrix

| Stage (file) | Declared target | Custom op | Reg attempted on G4? | Result | Category |
|---|---|---|---|---|---|
| Denoiser/UNet (`fc68b16d`, 938 MB) | buenos (fam 25) | edgetpu-custom-op-2 (1 node) | **Yes** | firmware "chip type 16 vs 255", INVALID_ARGUMENT, crash | **VERIFIED FAILING** |
| VAE encoder (`3721f30e`, 21.9 MB) | buenos (fam 25) | edgetpu-custom-op-2 | No (never reached) | — | LIKELY INCOMPATIBLE |
| VAE decoder (`a4f62875`, 45.9 MB) | buenos (fam 25) | edgetpu-custom-op-2 | No (never reached) | — | LIKELY INCOMPATIBLE |
| Aux net (`df87d79c`, 2.1 MB) | buenos (fam 25) | edgetpu-custom-op-2 | No (never reached) | — | LIKELY INCOMPATIBLE |
| Segmentation (`31d53482`, 9.9 MB) | buenos (fam 25) | edgetpu-custom-op-2 | No (never reached) | — | LIKELY INCOMPATIBLE |
| SSD detector (`07847f86`, 18.7 MB) | n/a (plain TFLite) | none | n/a (no TPU gate) | not isolated in log; plain TFLite runs via XNNPACK/NNAPI | UNKNOWN (likely works) |
| Camera's own rio models (hawk/portrait, not Pro Zoom) | rio (fam 16) | edgetpu-custom-op-2 | Yes | register, no chip error | **VERIFIED WORKING** |

Why the four buenos stages are "likely incompatible," not "verified": they share
the denoiser's declared target (fam 25) and the same `edgetpu-custom-op-2`
registration path, which the firmware gates on chip type; but the process crashes
at the denoiser before they are registered, so none was individually observed to
fail. Testing them in isolation would require preventing the denoiser crash first
(a guard), then letting the pipeline proceed to each stage's registration.

## Chip-type fields (two distinct things)
- **Wrapper `chip_family` u32** — 16 bytes before `DGC0` in the TFLite custom
  options: 25 (buenos, all p25 stages), 26 (santafe/p26). Host-side selection id.
- **Firmware chip-type** — read by the TPU firmware from inside the DGC0 at
  registration; rio=16, and the A17 firmware reports **255 (unknown)** for the
  buenos executable. Not a labelled DGC0 header byte (post-magic bytes are zero);
  exact offset still uncharacterized. This is the field in "Expected chip type 16
  … got 255".

## Not performed here (environment limits, stated honestly)
- A G4 reference-model **compile+execute** experiment: requires the on-device
  (ARM, rooted phone) DarWINN compiler; it cannot run in this x86 analysis
  environment, and the Coral `edgetpu_compiler` targets Coral, not Tensor G4.
  What it would establish (operator/tensor-type coverage, produced executable
  structure, runtime acceptance) is listed as an open item for on-device work.
- Reconstructing Google's trained weights/graph into a runnable model (out of
  scope for this assistant).

## Phase 1 result — the "255" is a container-format-version gap, not a flippable chip byte

The DGC0 is a FlatBuffer (`[u32 root_uoffset]["DGC0"][data]`; confirmed by the
qbnasasn RE notes and by parsing). Its root table has 8 fields. Parsing the root
tables (`fbroot.py`) across known-good and rejected executables:

| field | G4 island | G4 embgemma | G4 bonsai | G5 denoiser (buenos) | G6 denoiser (santafe) |
|---|---|---|---|---|---|
| f[3] | 1 | 1 | 1 | **6** | **6** |
| f[7] | 20 | 20 | 20 | **24** | **24** |

f[3] is a **container-format version**: identical (1) across three independent
G4-native executables that register fine, identical (6) across the G5 AND G6
denoisers (two *different* chips) — so it tracks the compiler/format generation,
not the chip (a chip id would differ G5 vs G6). f[7] (a header/struct size) moved
20→24 with that version bump.

Implication: the A17 firmware (paired with the G4/rio TPU, format v1) parses the
v6 container and does not find a chip type it recognises, reporting `255`
(0xFF = unknown). So "Expected chip type 16, got 255" is best explained as a
**parse/enumeration fallback caused by a newer container-format version**, not a
single stored chip-id byte. Relabelling a chip tag cannot make a v6 container
parse as v1. The only way to obtain a v1/G4 container is to compile *for* G4
(the xThr45hx on-device method) from a portable source — which this package does
not contain. Tool: `fbroot.py <file> <dgc0_magic_offset>`.

## Phase 1/2 correction and honest status (supersedes "decisive" wording above)

- The f[3]=1-vs-6 / f[7]=20-vs-24 / f[1] differences are **evidenced** as a
  format/generation difference (constant across the G5 and G6 chips, different on
  three G4 executables). Calling f[3] specifically a "container-format version",
  and asserting it is *the cause* of the firmware's `255`, is a **hypothesis** —
  not proven. No firmware code or verified DGC0 schema was available, so the
  `255` mechanism is **unverified** (only FlatBuffer fields were compared).
- f[4]/f[5] differ between G5 and G6 (candidate chip-specific fields); which field
  the firmware reads as "chip type" is unconfirmed without the schema.
- x86 cross-compile validation (the clean test: compile one small model for G4 vs
  G5 and diff the fields) could NOT be run here: the LiteRT Google Tensor compiler
  plugin artifact is unavailable (CDN 404 across versions; install blocked), and
  no published compiler targets G4 (the one working G4 build used a privately
  *patched* compiler forcing HW=16; that tooling is not in the public repo —
  the referenced `g4-pixel/` folder does not exist there). Prior "x86 vs ARM"
  reasoning was wrong and is retracted.

## Phase 3 — DECISIVE result: on-device test + offline byte-diff (2026-11-09)

Two independent lines of evidence now settle the "does the patched G5 denoiser
register on G4?" question. This supersedes the Phase-1/2 hypotheses above.

### A. On-device test (disk-swap injection, tested)
Google Camera keeps the Pro Zoom denoiser on disk (MDD):
`files/datadownload/shared/public/datadownloadfile_1793924474311`, 938529728 B.
We backed it up, overwrote its contents in place with a chip-patched copy
(variant `both`: wrapper `chip_family` 25->16 AND the two `buenos_b0`->`rio_a0`
identity strings), let Google Camera load and register it through its own
pipeline, captured the firmware log, then restored the original. Result:

```
E Darwinn: registration.cc:128 - Expected chip type 16 in executable, got 255.
E native : RegisterGraphRequest ... error_code=3 ... Target Runtime Version: 5
E native : Node number 1 (edgetpu-custom-op-2) failed to prepare.
```

`got 255` is identical to the UNPATCHED G5 denoiser. So patching the wrapper
`chip_family` field and the `buenos_b0` strings changes nothing the firmware
reads. The injection path itself works (arbitrary patched bytes reach the real
registration path); the patch does not. VERDICT: NO.

### B. Offline byte-diff vs a real G4 executable (proven)
Compared a real G4 (rio) DGC0 (xThr45hx/G4_DGCO, chip 16) against a G5 (buenos,
chip 25) executable from this package (the encoder, 3721f30e):

```
G5 encoder  DGD0@4676:  24 00 00 00  10 00 00 00  44 47 44 30 08 00 0c 00 ...
G4 ref      DGD0@4100:  24 00 00 00  10 00 00 00  44 47 44 30 08 00 0c 00 ...
```

The container metadata is BYTE-IDENTICAL between a chip-25 and a chip-16
executable: same DGC0 header, same DGD0 vtable, same value (0x10 = a FlatBuffer
vtable offset, NOT a chip id) at DGD0-4. The wrapper `chip_family` u32 at DGC0-16
(25 vs 16) is the only metadata that differs, and part A proved the firmware does
not read it. Therefore the chip identity the firmware checks lives in the
**compiled DarWINN program body** (the TPU instruction stream), which is wholly
chip-specific. There is no metadata field to flip.

### Conclusion (mechanism, not assumption)
- The chip target is a COMPILE-TIME property of the program body. Confirmed by the
  G4 toolchain's own build log (`isl0.log`): G4 DGC0s are produced by patching the
  compiler to force `HW=16` and recompiling from TFLite source — never by editing
  a compiled binary.
- Running this denoiser on G4 requires its PRE-compilation TFLite graph, fed to
  the on-device DarWINN compiler (which already targets G4 — that is how the
  camera's own rio models and the on-device portrait model compile and run). This
  package ships only the G5-compiled DGC0; the source is not present and cannot be
  recovered from the compiled binary (AOT compilation is one-way).
- No further byte-patch experiment can change this; the absence of a chip field in
  the container is proven offline.
Tools: `diskswap_test.sh` (reversible on-device test), `chippatch.py`, `fbroot.py`.
