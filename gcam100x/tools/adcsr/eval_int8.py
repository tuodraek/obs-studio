import sys, glob, os, numpy as np, time
from PIL import Image
from ai_edge_litert.interpreter import Interpreter
def run(path, x):
    it=Interpreter(model_path=path, num_threads=4); it.allocate_tensors(); d=it.get_input_details()[0]; o=it.get_output_details()[0]
    xi=x if d['dtype']==np.float32 else np.clip(np.round(x/d['quantization'][0]+d['quantization'][1]),np.iinfo(d['dtype']).min,np.iinfo(d['dtype']).max).astype(d['dtype'])
    it.set_tensor(d['index'], xi); t=time.time(); it.invoke(); dt=time.time()-t; y=it.get_tensor(o['index'])
    if o['dtype']!=np.float32: y=(y.astype(np.float32)-o['quantization'][1])*o['quantization'][0]
    return y, dt, str(d['dtype'])
p='repo/testset/lr_0810_pch_00005.png'
x=(np.asarray(Image.open(p).convert('RGB'),dtype=np.float32)/255*2-1).transpose(2,0,1)[None]
yf,_,_=run('out/adcsr_128_fp32.tflite',x); yq,dt,dty=run(sys.argv[1],x)
print("io dtype",dty,"| int8 vs fp32 on a real tile: PSNR %.1f dB, %.1fs CPU"%(10*np.log10(4.0/np.mean((yq-yf)**2)),dt), "finite", bool(np.isfinite(yq).all()))
def img(y): 
    y=(y-y.mean(axis=(2,3),keepdims=True))/y.std(axis=(2,3),keepdims=True)*x.std(axis=(2,3),keepdims=True)+x.mean(axis=(2,3),keepdims=True)
    return Image.fromarray((np.clip(y[0].transpose(1,2,0)/2+0.5,0,1)*255).astype(np.uint8))
W=Image.new('RGB',(1032,512),'white'); W.paste(img(yf),(0,0)); W.paste(img(yq),(520,0)); W.save('out/int8_compare.png')
