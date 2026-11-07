"""Find ARM64 code that references a string (ADRP+ADD / ADRP+LDR pairs)."""
import sys,subprocess,re
import numpy as np
lib=sys.argv[1]; needles=sys.argv[2:]
data=open(lib,'rb').read()
# section table
out=subprocess.run(['readelf','-SW',lib],capture_output=True,text=True).stdout
secs=[]
for l in out.splitlines():
    m=re.match(r'\s*\[\s*\d+\]\s+(\S+)\s+\S+\s+([0-9a-f]+)\s+([0-9a-f]+)\s+([0-9a-f]+)',l)
    if m: secs.append((m.group(1),int(m.group(2),16),int(m.group(3),16),int(m.group(4),16)))
def off2va(o):
    for n,va,off,sz in secs:
        if off<=o<off+sz and va: return va+(o-off)
text=[s for s in secs if s[0]=='.text'][0]
_,tva,toff,tsz=text
ins=np.frombuffer(data,dtype='<u4',count=tsz//4,offset=toff)
pcs=tva+np.arange(len(ins),dtype=np.int64)*4
adrp=(ins & 0x9F000000)==0x90000000
immlo=(ins>>29)&3; immhi=(ins>>5)&0x7FFFF
imm=((immhi<<2)|immlo).astype(np.int64); imm=np.where(imm&(1<<20),imm-(1<<21),imm)
page=(pcs & ~0xFFF)+(imm<<12)
rd=ins&31
nxt=np.roll(ins,-1)
# ADD Xd, Xn, #imm12 (64-bit, shift 0)
addmask=(nxt & 0xFFC00000)==0x91000000
add_imm=((nxt>>10)&0xFFF).astype(np.int64)
add_rn=(nxt>>5)&31
tgt=page+add_imm
for nd in needles:
    o=data.find(nd.encode())
    if o<0: print('not found',nd); continue
    va=off2va(o)
    hit=np.where(adrp & addmask & (add_rn==rd) & (tgt==va))[0]
    print(f'{nd!r}: string at file 0x{o:x} va 0x{va:x}; referenced from', [hex(int(pcs[h])) for h in hit[:10]])
