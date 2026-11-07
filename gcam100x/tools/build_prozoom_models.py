"""Builds the Pro Zoom upscaler models in app/src/main/assets.

Model: Real-ESRGAN SRVGGNetCompact x4 (BSD-3-Clause, xinntao/Real-ESRGAN),
weights interpolated between realesr-general-x4v3 and
realesr-general-wdn-x4v3 (Real-ESRGAN's "denoise strength", here 0.5) so the
result keeps natural texture instead of a painted, over-sharpened look.

    python3 build_prozoom_models.py realesr-general-x4v3.pth realesr-general-wdn-x4v3.pth [strength]

Outputs (static 1x256x256x3 input, x4 output). The app ships only the float
model: int8 degrades this network badly (detail residual correlation ~0.56),
so the int8 build is kept for reference only.
  prozoom_x4_int8.tflite  full-integer (uint8 I/O) for the Edge TPU via NNAPI;
                          outputs the x4 detail residual only (app adds the
                          nearest-upsampled input)
  prozoom_x4_f32.tflite   float for the GPU / CPU fallback
The .pth files are read with pth_reader (no unpickling).
Requires: tensorflow, numpy, scikit-image (calibration images).
"""
import sys
import numpy as np
import skimage.data as sd
import tensorflow as tf
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pth_reader import load_pth  # noqa: E402

T = 256

def perm():
    # PyTorch pixel_shuffle channel order -> TF depth_to_space order.
    p = np.zeros(48, int)
    for c in range(3):
        for i in range(4):
            for j in range(4):
                p[(i * 4 + j) * 3 + c] = c * 16 + i * 4 + j
    return p

def build(W, residual_only=False, H=None, Wd=None):
    layers = []; idx = 0
    while f"body.{idx}.weight" in W:
        w = W[f"body.{idx}.weight"]
        if w.ndim == 4:
            k = np.transpose(w, (2, 3, 1, 0)).astype(np.float32); b = W[f"body.{idx}.bias"].astype(np.float32)
            if k.shape[3] == 48:
                pp = perm(); k = k[..., pp]; b = b[pp]
            layers.append(("c", tf.constant(k), tf.constant(b)))
        else:
            layers.append(("p", tf.constant(w.reshape(1, 1, 1, -1).astype(np.float32)), None))
        idx += 1

    H = H or T; Wd = Wd or T

    @tf.function(input_signature=[tf.TensorSpec([1, H, Wd, 3], tf.float32)])
    def f(x):
        h = x
        for t, a, b in layers:
            h = tf.nn.conv2d(h, a, 1, "SAME") + b if t == "c" else tf.maximum(h, 0.) + a * tf.minimum(h, 0.)
        r = tf.nn.depth_to_space(h, 4)
        # The int8 (TPU) model outputs only the detail residual: quantizing
        # "input + residual" leaves too few levels for the detail (4x4 blocks).
        # The app adds the nearest-upsampled input back in float.
        return r if residual_only else r + tf.image.resize(x, (H * 4, Wd * 4), method="nearest")
    return f

if __name__ == "__main__":
    A = load_pth(sys.argv[1]); B = load_pth(sys.argv[2])
    s = float(sys.argv[3]) if len(sys.argv) > 3 and not sys.argv[3].startswith("--") else 0.5
    Wm = {k: s * A[k] + (1 - s) * B[k] for k in A}
    conc = build(Wm).get_concrete_function()
    conc_res = build(Wm, residual_only=True).get_concrete_function()
    imgs = [i for i in (sd.astronaut(), sd.coffee(), sd.chelsea(), sd.rocket(), sd.hubble_deep_field(),
                        sd.immunohistochemistry(), sd.retina()) if i.ndim == 3]
    rng = np.random.default_rng(0)

    def rep():
        for k in range(48):
            im = imgs[k % len(imgs)].astype(np.float32) / 255.0
            y = rng.integers(0, im.shape[0] - T); x = rng.integers(0, im.shape[1] - T)
            yield [im[y:y + T, x:x + T, :3][None]]

    open("prozoom_x4_f32.tflite", "wb").write(tf.lite.TFLiteConverter.from_concrete_functions([conc]).convert())
    print("float model written", flush=True)
    if "--float-only" in sys.argv:
        sys.exit(0)
    q = tf.lite.TFLiteConverter.from_concrete_functions([conc_res])
    q.optimizations = [tf.lite.Optimize.DEFAULT]
    q.representative_dataset = rep
    q.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    q.inference_input_type = tf.uint8
    q.inference_output_type = tf.uint8
    open("prozoom_x4_int8.tflite", "wb").write(q.convert())
    print("built, strength", s)
