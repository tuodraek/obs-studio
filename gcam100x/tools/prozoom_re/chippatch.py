#!/usr/bin/env python3
# Reversible chip-identifier patcher for the Pro Zoom denoiser (interoperability test).
# Edits ONLY chip-identity metadata in the DGC0 container, offset-preserving. Restores exactly.
# Usage:
#   chippatch.py verify   <file>
#   chippatch.py save     <file> <journal>          # record original bytes for rollback
#   chippatch.py apply    <file> <variant>          # variant: string|wrapper|both
#   chippatch.py restore  <file> <journal>
import sys,struct,os,json
def sites(d):
    dgc=d.find(b'DGC0')
    assert dgc>=0 and d.count(b'DGC0')==1, "expected exactly one DGC0"
    fam_off=dgc-16
    assert struct.unpack_from('<I',d,fam_off)[0]==25, "wrapper chip_family != 25 (unexpected file)"
    strs=[]; o=0
    while True:
        j=d.find(b'buenos_b0',o)
        if j<0: break
        assert struct.unpack_from('<I',d,j-4)[0]==9, "buenos_b0 length prefix != 9"
        strs.append(j); o=j+1
    assert len(strs)==2, f"expected 2 buenos_b0, found {len(strs)}"
    return fam_off,strs
def read_head(path,n=200000):
    with open(path,'rb') as f: return bytearray(f.read(n))
def write_at(path,off,data):
    with open(path,'r+b') as f: f.seek(off); f.write(data)
def main():
    cmd=sys.argv[1]; path=sys.argv[2]
    if cmd=='restore':
        j=json.load(open(sys.argv[3]))
        write_at(path,j['fam_off'],bytes(j['fam_orig']))
        for s in j['strs']:
            write_at(path,s['off']-4,bytes(s['lenpref'])); write_at(path,s['off'],bytes(s['bytes']))
        print("restored original bytes"); return
    d=read_head(path); fam_off,strs=sites(d)
    if cmd=='verify':
        print(f"OK file={path} chip_family@{fam_off}=25 buenos_b0@{strs}"); return
    if cmd=='save':
        j={'fam_off':fam_off,'fam_orig':list(d[fam_off:fam_off+4]),
           'strs':[{'off':s,'lenpref':list(d[s-4:s]),'bytes':list(d[s:s+10])} for s in strs]}
        open(sys.argv[3],'w').write(json.dumps(j)); print("journal saved"); return
    if cmd=='apply':
        variant=sys.argv[3]
        if variant in ('string','both'):
            for s in strs:
                write_at(path,s-4,struct.pack('<I',6))      # len 9->6
                write_at(path,s,b'rio_a0\x00')               # 'rio_a0' + null; leftover 'b0\0' unread
        if variant in ('wrapper','both'):
            write_at(path,fam_off,struct.pack('<I',16))      # 25->16
        print(f"applied variant={variant}"); return
    sys.exit("unknown cmd")
main()
