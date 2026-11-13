import sys, torch, numpy as np, time
sys.argv.insert(1,'repo'); sys.path.insert(0,'tools')
from adcsr_build import build
import litert_torch
m = build('repo')
x = torch.from_numpy(np.load('out/x.npy'))
t=time.time()
edge = litert_torch.convert(m, (x,))
print("converted in %.0fs"%(time.time()-t))
edge.export('out/adcsr_128_fp32.tflite')
o = edge(x)
ref=np.load('out/ref.npy')
print("tflite(fp32, CPU) max abs diff vs torch:", float(np.abs(np.asarray(o)-ref).max()), "mean", float(np.abs(np.asarray(o)-ref).mean()))
