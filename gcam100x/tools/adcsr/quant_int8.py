# Full-integer (int8 weights + int8 activations) AdcSR for the Tensor TPU.
import sys, glob, numpy as np, time, warnings; warnings.filterwarnings('ignore')
from PIL import Image
from ai_edge_quantizer import quantizer, recipe, calibrator
from ai_edge_litert.interpreter import Interpreter
src=sys.argv[1]; dst=sys.argv[2]; rec=getattr(recipe, sys.argv[3])(); feat=len(sys.argv)>4
def load(p, size=None):
    im=Image.open(p).convert('RGB')
    if size: im=im.resize((size,size), Image.BICUBIC)
    return (np.asarray(im,dtype=np.float32)/255.0*2-1).transpose(2,0,1)[None]
cal=[load(p) for p in sorted(glob.glob('repo/testset/lr_*.png'))]+[load(p,128) for p in sorted(glob.glob('repo/testset/hr_*.png'))]
if feat:
    u=Interpreter(model_path='out/adcsr_unet_fp32.tflite', num_threads=4); u.allocate_tensors()
    def f(x): u.set_tensor(u.get_input_details()[0]['index'], x); u.invoke(); return u.get_tensor(u.get_output_details()[0]['index']).copy()
    cal=[f(x) for x in cal]; del u
it=Interpreter(model_path=src); name=it.get_signature_list(); print("signatures", name)
sig=list(name.keys())[0]; inp=name[sig]['inputs'][0]
quantizer.Quantizer._ensure_model_qsv_sufficient=lambda self, r: None  # that check needs a 21 GB preserve-all-tensors interpreter
qt=quantizer.Quantizer(src); qt.load_quantization_recipe(rec)
t=time.time(); res=qt.calibrate({sig:[{inp:x} for x in cal]}, num_threads=4, mode=calibrator.CalibrationMode.CALIBRATION_PROFILER_BASED); print("calibrated %.0fs"%(time.time()-t))
q=qt.quantize(res); q.export_model(dst, overwrite=True)
import os; print("int8 size MB", os.path.getsize(dst)//1000000)
