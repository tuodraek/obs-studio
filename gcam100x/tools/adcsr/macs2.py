import sys, torch, collections
sys.argv.insert(1,'repo'); sys.path.insert(0,'tools')
from adcsr_build import build
import torch.nn as nn
m = build('repo'); macs=collections.Counter(); par=collections.Counter()
def tag(name):
    if name.startswith('0.body.1'): return 'unet'
    if name.startswith('0.body.2'): return 'decoder.mid'
    return 'decoder.up[%s]'%name.split('.')[0]
for name,mod in m.named_modules():
    t=tag(name)
    if isinstance(mod,nn.Conv2d): mod.register_forward_hook(lambda mod,i,o,t=t: macs.update({t:o.numel()*mod.in_channels*mod.kernel_size[0]*mod.kernel_size[1]}))
    if isinstance(mod,nn.Linear): mod.register_forward_hook(lambda mod,i,o,t=t: macs.update({t:o.numel()*mod.in_features}))
for n,p in m.named_parameters(): par[tag(n)]+=p.numel()
with torch.no_grad(): m(torch.zeros(1,3,128,128))
for k in sorted(macs): print("%-16s %7.1f GMACs  %6.1fM params"%(k,macs[k]/1e9,par[k]/1e6))
