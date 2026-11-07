import sys,re,struct
from tensorflow.lite.python import schema_py_generated as S
def custom_blob(path):
    b=open(path,'rb').read()
    m=S.Model.GetRootAsModel(b,0)
    sg=m.Subgraphs(0); op=sg.Operators(0)
    # custom options as bytes, plus their absolute offset in the file
    n=op.CustomOptionsLength()
    blob=op.CustomOptionsAsNumpy().tobytes()
    off=b.find(blob[:64])
    return b,blob,off
if __name__=='__main__':
    b,blob,off=custom_blob(sys.argv[1])
    open(sys.argv[2],'wb').write(blob)
    print('blob',len(blob),'at file offset',off)
    print('first 64 bytes:',blob[:64].hex())
    # flatbuffer: root offset + file identifier
    root=struct.unpack('<I',blob[:4])[0]; ident=blob[4:8]
    print('root offset',root,'ident',ident)
