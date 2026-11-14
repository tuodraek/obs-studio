# Splits AdcSR into two LiteRT models so each half can go to the accelerator that suits it:
#   A (unet):    [1,3,128,128] -> latent features [1,C,64,64]   (PixelUnshuffle + pruned UNet + decoder mid block, 444 M params)
#   B (decoder): [1,C,64,64]   -> [1,3,512,512]                 (VAE up blocks, 12 M params, 62% of the compute, conv-only)
import sys, torch, numpy as np, warnings; warnings.filterwarnings('ignore')
sys.argv.insert(1,'repo'); sys.path.insert(0,'tools')
from adcsr_build import build
import litert_torch
m = build('repo'); A = m[0].eval(); B = torch.nn.Sequential(*list(m.children())[1:]).eval()
x = torch.from_numpy(np.load('out/x.npy'))
with torch.no_grad(): f = A(x); y = B(f)
print("feature shape", tuple(f.shape), "range", float(f.min()), float(f.max()), "| full-model diff", float(np.abs(y.numpy()-np.load('out/ref.npy')).max()))
np.save('out/feat.npy', f.numpy())
litert_torch.convert(A,(x,)).export('out/adcsr_unet_fp32.tflite'); litert_torch.convert(B,(f,)).export('out/adcsr_dec_fp32.tflite')
print("params: unet %.1fM decoder %.1fM"%(sum(p.numel() for p in A.parameters())/1e6, sum(p.numel() for p in B.parameters())/1e6))
