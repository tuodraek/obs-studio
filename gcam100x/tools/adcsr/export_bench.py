# Structural benchmark model: the exact AdcSR inference graph with all weights replaced by a short repeating pseudo-random pattern
# (GPU latency/memory do not depend on weight values; the file then compresses to ~MBs).
import sys, torch, numpy as np, warnings; warnings.filterwarnings('ignore')
sys.argv.insert(1,'repo'); sys.path.insert(0,'tools')
from adcsr_build import build
import litert_torch
from ai_edge_quantizer import quantizer, recipe, qtyping
m = build('repo')
with torch.no_grad():
    g=torch.Generator().manual_seed(0); pat=torch.randn(4099,generator=g)*0.02
    for i,p in enumerate(m.parameters()):
        n=p.numel(); p.copy_(torch.roll(pat,i*7+1).repeat(n//4099+1)[:n].reshape(p.shape)*(1+0.001*i))
x = torch.zeros(1,3,128,128)
litert_torch.convert(m,(x,)).export('out/zero_fp32.tflite')
qt=quantizer.Quantizer('out/zero_fp32.tflite')
qt.update_quantization_recipe(regex='.*', operation_name=qtyping.TFLOperationName.ALL_SUPPORTED,
    op_config=qtyping.OpQuantizationConfig(weight_tensor_config=qtyping.TensorQuantizationConfig(num_bits=16, dtype=qtyping.TensorDataType.FLOAT),
    compute_precision=qtyping.ComputePrecision.FLOAT, explicit_dequantize=True), algorithm_key=recipe.AlgorithmName.FLOAT_CASTING)
qt.quantize().export_model('out/adcsr_bench_fp16.tflite', overwrite=True)
from ai_edge_litert.interpreter import Interpreter
it=Interpreter(model_path='out/adcsr_bench_fp16.tflite', num_threads=4); it.allocate_tensors()
it.set_tensor(it.get_input_details()[0]['index'], np.random.rand(1,3,128,128).astype('float32')); it.invoke()
o=it.get_tensor(it.get_output_details()[0]['index']); print("zero model out shape",o.shape,"finite",bool(np.isfinite(o).all()))
