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
