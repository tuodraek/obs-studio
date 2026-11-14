import sys, torch, numpy as np, collections, time
sys.argv.insert(1,'repo'); sys.path.insert(0,'tools')
from adcsr_build import build
m = build('repo')
x = torch.rand(1,3,128,128)*2-1
with torch.no_grad(): ref = m(x).numpy()
torch.onnx.export(m, x, 'out/adcsr_128.onnx', opset_version=17, input_names=['lr'], output_names=['sr'], dynamo=False, do_constant_folding=True)
import onnx, onnxruntime as ort
g = onnx.load('out/adcsr_128.onnx', load_external_data=False)
print("ops:", dict(collections.Counter(n.op_type for n in g.graph.node).most_common()))
s = ort.InferenceSession('out/adcsr_128.onnx', providers=['CPUExecutionProvider'])
t=time.time(); o = s.run(None, {'lr': x.numpy()})[0]; print("ort %.1fs"%(time.time()-t))
print("max abs diff vs torch:", float(np.abs(o-ref).max()), "mean:", float(np.abs(o-ref).mean()), "out range", float(ref.min()), float(ref.max()))
np.save('out/x.npy', x.numpy()); np.save('out/ref.npy', ref)
