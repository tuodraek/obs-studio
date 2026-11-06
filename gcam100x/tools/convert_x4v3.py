"""Builds the two Pro Zoom upscaler models in app/src/main/assets.

Source: Real-ESRGAN realesr-general-x4v3 (BSD-3-Clause, xinntao/Real-ESRGAN),
TensorFlow SavedModel from huggingface.co/JoPmt/Real_Esrgan_x2_Onnx_Tflite_Tfjs
(ano_test/). Output: static 1x256x256x3 input, x4 output.
  prozoom_x4_int8.tflite  full-integer (uint8 I/O) for the Edge TPU via NNAPI
  prozoom_x4_f32.tflite   float for the GPU / CPU fallback
Requires: tensorflow, scikit-image (calibration images).
"""
import sys
import numpy as np
import skimage.data as sd
import tensorflow as tf

sm = sys.argv[1] if len(sys.argv) > 1 else "sm"
T = 256
m = tf.saved_model.load(sm)
f = m.signatures["serving_default"]
conc = tf.function(lambda x: f(input=x)["output_0"]).get_concrete_function(tf.TensorSpec([1, T, T, 3], tf.float32))
imgs = [i for i in (sd.astronaut(), sd.coffee(), sd.chelsea(), sd.rocket(), sd.hubble_deep_field(),
                    sd.immunohistochemistry(), sd.retina()) if i.ndim == 3]
rng = np.random.default_rng(0)

def rep():
    for k in range(120):
        im = imgs[k % len(imgs)].astype(np.float32) / 255.0
        y = rng.integers(0, im.shape[0] - T); x = rng.integers(0, im.shape[1] - T)
        yield [im[y:y + T, x:x + T, :3][None]]

q = tf.lite.TFLiteConverter.from_concrete_functions([conc], m)
q.optimizations = [tf.lite.Optimize.DEFAULT]
q.representative_dataset = rep
q.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
q.inference_input_type = tf.uint8
q.inference_output_type = tf.uint8
open("prozoom_x4_int8.tflite", "wb").write(q.convert())
open("prozoom_x4_f32.tflite", "wb").write(tf.lite.TFLiteConverter.from_concrete_functions([conc], m).convert())
