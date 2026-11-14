# Tiny single-purpose models that isolate each layer type of the AdcSR decoder, in float,
# W8A16 and W8A8, to find out what the Tensor G4 TPU compiler accepts and how fast it is.
import torch, torch.nn as nn, numpy as np, warnings, os; warnings.filterwarnings('ignore')
import litert_torch
from ai_edge_quantizer import quantizer, recipe, calibrator
quantizer.Quantizer._ensure_model_qsv_sufficient=lambda self, r: None
C=128; x=torch.randn(1,C,128,128)
class Res(nn.Module):
    def __init__(s):
        super().__init__(); s.n1=nn.GroupNorm(32,C); s.c1=nn.Conv2d(C,C,3,padding=1); s.n2=nn.GroupNorm(32,C); s.c2=nn.Conv2d(C,C,3,padding=1); s.a=nn.SiLU()
    def forward(s,x): return x+s.c2(s.a(s.n2(s.c1(s.a(s.n1(x))))))
class ResBN(nn.Module):   # same block with a per-channel affine instead of GroupNorm (what a folded norm would cost)
    def __init__(s):
        super().__init__(); s.c1=nn.Conv2d(C,C,3,padding=1); s.c2=nn.Conv2d(C,C,3,padding=1); s.a=nn.ReLU()
    def forward(s,x): return x+s.c2(s.a(s.c1(s.a(x))))
models={'conv':nn.Sequential(nn.Conv2d(C,C,3,padding=1),nn.Conv2d(C,C,3,padding=1)),
        'groupnorm':nn.GroupNorm(32,C), 'silu':nn.SiLU(), 'relu_conv':nn.Sequential(nn.ReLU(),nn.Conv2d(C,C,3,padding=1)),
        'upsample_conv':nn.Sequential(nn.Upsample(scale_factor=2.0,mode='nearest'),nn.Conv2d(C,C,3,padding=1)),
        'resblock_gn_silu':Res(), 'resblock_relu':ResBN()}
for name,m in models.items():
    m=m.eval(); f='out/probe/%s_float.tflite'%name
    litert_torch.convert(m,(x,)).export(f)
    for tag,rec in (('w8a16',recipe.static_wi8_ai16()),('w8a8',recipe.static_wi8_ai8())):
        try:
            from ai_edge_litert.interpreter import Interpreter
            sig=Interpreter(model_path=f).get_signature_list(); k=list(sig)[0]; inp=sig[k]['inputs'][0]
            qt=quantizer.Quantizer(f); qt.load_quantization_recipe(rec)
            res=qt.calibrate({k:[{inp:torch.randn(1,C,128,128).numpy()} for _ in range(4)]}, num_threads=4, mode=calibrator.CalibrationMode.CALIBRATION_PROFILER_BASED)
            qt.quantize(res).export_model('out/probe/%s_%s.tflite'%(name,tag), overwrite=True)
        except Exception as e: print("FAILED",name,tag,str(e)[:120])
print(sorted((f, os.path.getsize('out/probe/'+f)//1024) for f in os.listdir('out/probe')))
