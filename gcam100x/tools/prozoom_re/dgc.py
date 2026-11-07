import sys,struct,re
from tensorflow.lite.python import schema_py_generated as SC
def blob_of(path):
    b=open(path,'rb').read(); m=SC.Model.GetRootAsModel(b,0); op=m.Subgraphs(0).Operators(0)
    # custom options as a memoryview without copying huge arrays twice
    return op.CustomOptionsAsNumpy().tobytes()
for path in sys.argv[1:]:
    blob=blob_of(path); p=blob.find(b'DGC0'); d=blob[p-4:]
    print('=='*5, path.rsplit('/',1)[-1], 'blob',len(blob),'DGC0@',p)
    print(' pre-magic u32 =',struct.unpack_from('<I',blob,p-4)[0])
    words=struct.unpack_from('<32I',d,4)
    print(' header u32[0..31]:',words)
    # find printable strings with offsets in the first 64 KB of the DGC0 payload
    ss=[(m.start(),m.group().decode()) for m in re.finditer(rb'[\x20-\x7e]{6,}',d[:65536])]
    print(' strings:',ss[:25])
