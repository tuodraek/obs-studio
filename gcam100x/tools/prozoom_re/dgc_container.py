#!/usr/bin/env python3
"""DGC0 container manifest (format-level). TFLite-wrapped DarWINN graph container.
Emits a JSON manifest: chip_family, DGC0 offset, I/O tensor names, and the section
directory (param groups, instruction groups, scratch/DIVE) with sizes. Does NOT
decode weight values or reconstruct any model. Usage: dgc_container.py <file> [...]
"""
import sys,struct,re,json,os
def analyze(path):
    sz=os.path.getsize(path); head=open(path,'rb').read(min(sz,4_000_000))
    i=head.find(b'DGC0')
    m={'file':os.path.basename(path),'size':sz,'tflite':head[4:8]==b'TFL3' or b'TFL3' in head[:64],
       'dgc0_off':i,'chip_family_u32':(struct.unpack_from('<I',head,i-16)[0] if i>=16 else None)}
    m['chip_strings']=[c.decode() for c in (b'rio_a0',b'buenos_b0',b'santafe_a0') if c in head]
    m['io_tensors']=sorted(set(re.findall(r'serving_default[A-Za-z0-9_:]*', head.decode('latin1'))+
                                re.findall(r'StatefulPartitionedCall:\d+', head.decode('latin1'))))
    def groups(pat):
        out={}
        for mm in re.finditer(pat.encode(), head):
            idx=mm.group(1).decode(); w=head[mm.end():mm.end()+20]; j=w.find(b'\x01\x00\x00\x00')
            if j>=0: out[idx]=struct.unpack_from('<I',w,j+4)[0]
        return out
    pg=groups(r'main_STAND_ALONE_params_Group_0_(\d+)_[0-9]_0_0')
    ig=groups(r'TPUOffloadFunc_STAND_ALONE_inst_Group_0_(\d+)_[0-9]_0_0')
    m['param_groups']={'count':len(pg),'total_bytes':sum(pg.values()),'sizes':pg}
    m['inst_groups']={'count':len(ig),'total_bytes':sum(ig.values())}
    m['scratch']=[s.decode() for s in set(re.findall(rb'(?:dive_)?scratch_\d+', head))]
    return m
if __name__=='__main__':
    res=[analyze(p) for p in sys.argv[1:]]
    print(json.dumps(res if len(res)>1 else res[0], indent=2))
