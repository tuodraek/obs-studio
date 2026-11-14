import sys,os,re,struct
from tensorflow.lite.python import schema_py_generated as SC
TYPES={0:'f32',1:'f16',2:'i32',3:'u8',4:'i64',9:'i8',7:'i16',10:'f64'}
BUILTIN={v:k for k,v in vars(SC.BuiltinOperator).items() if not k.startswith('_')}
def survey(path):
    b=open(path,'rb').read()
    name=os.path.basename(path)
    if b[4:8]!=b'TFL3':
        print(f'== {name} ({len(b)} B): not TFLite, head={b[:48]!r}'); return
    m=SC.Model.GetRootAsModel(b,0)
    md={m.Metadata(i).Name().decode():m.Buffers(m.Metadata(i).Buffer()).DataAsNumpy() for i in range(m.MetadataLength())}
    mrv=md.get('min_runtime_version'); mrv=bytes(mrv).split(b'\0')[0].decode() if mrv is not None and not isinstance(mrv,int) else None
    print(f'== {name} ({len(b)/1e6:.1f} MB) TFLite, subgraphs={m.SubgraphsLength()}, min_runtime={mrv}, metadata={list(md)}')
    ops={}
    for i in range(m.OperatorCodesLength()):
        oc=m.OperatorCodes(i); code=max(oc.BuiltinCode(),oc.DeprecatedBuiltinCode())
        ops[i]=oc.CustomCode().decode() if oc.CustomCode() else BUILTIN.get(code,str(code))
    for si in range(m.SubgraphsLength()):
        sg=m.Subgraphs(si)
        hist={}
        for k in range(sg.OperatorsLength()):
            n=ops[sg.Operators(k).OpcodeIndex()]; hist[n]=hist.get(n,0)+1
        def tdesc(ti):
            t=sg.Tensors(ti); return f"{t.Name().decode()}{[t.Shape(j) for j in range(t.ShapeLength())]}:{TYPES.get(t.Type(),t.Type())}"
        print(f'  subgraph {si} {sg.Name()}: ops={dict(sorted(hist.items(),key=lambda x:-x[1]))}')
        print('   inputs:',[tdesc(sg.Inputs(j)) for j in range(sg.InputsLength())])
        print('   outputs:',[tdesc(sg.Outputs(j)) for j in range(sg.OutputsLength())])
        for k in range(sg.OperatorsLength()):
            op=sg.Operators(k)
            if ops[op.OpcodeIndex()].startswith('edgetpu'):
                blob=op.CustomOptionsAsNumpy().tobytes()
                p=blob.find(b'DGC0')
                ss=[s.decode() for s in re.findall(rb'[\x20-\x7e]{6,}',blob[:2_000_000])]
                chips=sorted(set(s for s in ss if re.fullmatch(r'[a-z]+_[ab][0-9]',s)))
                print(f'   TPU op #{k}: blob {len(blob)/1e6:.1f} MB, DGC0 at {p}, chip strings {chips}')
for f in sys.argv[1:]: survey(f)
