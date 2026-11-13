import sys, numpy as np, time, warnings; warnings.filterwarnings('ignore')
from ai_edge_quantizer import quantizer, recipe, qtyping
from ai_edge_litert.interpreter import Interpreter
src='out/adcsr_128_fp32.tflite'; x=np.load('out/x.npy'); ref=np.load('out/ref.npy')
def run(path):
    it=Interpreter(model_path=path, num_threads=4); it.allocate_tensors()
    it.set_tensor(it.get_input_details()[0]['index'], x); t=time.time(); it.invoke(); dt=time.time()-t
    o=it.get_tensor(it.get_output_details()[0]['index']); d=np.abs(o-ref)
    psnr=10*np.log10(4.0/np.mean((o-ref)**2))
    return "max|d| %.4f mean|d| %.5f PSNR-vs-fp32 %.1f dB (%.1fs CPU)"%(d.max(),d.mean(),psnr,dt)
which=sys.argv[1]
qt=quantizer.Quantizer(src)
if which=='fp16':
    qt.update_quantization_recipe(regex='.*', operation_name=qtyping.TFLOperationName.ALL_SUPPORTED,
        op_config=qtyping.OpQuantizationConfig(weight_tensor_config=qtyping.TensorQuantizationConfig(num_bits=16, dtype=qtyping.TensorDataType.FLOAT),
        compute_precision=qtyping.ComputePrecision.FLOAT, explicit_dequantize=True), algorithm_key=recipe.AlgorithmName.FLOAT_CASTING)
else:
    qt.load_quantization_recipe(recipe.weight_only_wi8_afp32())
res=qt.quantize(); out='out/adcsr_128_%s.tflite'%which; res.export_model(out, overwrite=True)
import os; print(which, "%.0f MB"%(os.path.getsize(out)/1e6), run(out))
