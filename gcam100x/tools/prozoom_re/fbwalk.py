"""Schema-less flatbuffer walker: prints the table tree with best-guess field types."""
import sys,struct
def u32(b,o): return struct.unpack_from('<I',b,o)[0]
def i32(b,o): return struct.unpack_from('<i',b,o)[0]
def u16(b,o): return struct.unpack_from('<H',b,o)[0]
def is_table(b,o):
    if o<0 or o+4>len(b): return False
    vt=o-i32(b,o)
    if vt<0 or vt+4>len(b): return False
    vlen=u16(b,vt); tlen=u16(b,vt+2)
    return 4<=vlen<=256 and vlen%2==0 and 4<=tlen<=65536 and vt+vlen<=len(b)
def as_string(b,o):
    if o+4>len(b): return None
    n=u32(b,o)
    if 0<n<4096 and o+4+n<len(b) and b[o+4+n]==0:
        s=b[o+4:o+4+n]
        if all(32<=c<127 for c in s): return s.decode()
    return None
def walk(b,o,depth,maxdepth,path,out,seen):
    if depth>maxdepth or o in seen: return
    seen.add(o)
    vt=o-i32(b,o); vlen=u16(b,vt); nf=(vlen-4)//2
    for f in range(nf):
        fo=u16(b,vt+4+2*f)
        if fo==0: continue
        a=o+fo
        p=f"{path}.{f}"
        # try offset-based interpretations
        if a+4<=len(b):
            tgt=a+u32(b,a)
            s=as_string(b,tgt) if tgt<len(b) else None
            if s is not None:
                out.append(f"{'  '*depth}{p}: string {s!r}"); continue
            if tgt<len(b)-4 and tgt>a:
                n=u32(b,tgt)
                if is_table(b,tgt) and depth<maxdepth:
                    out.append(f"{'  '*depth}{p}: table @{tgt}")
                    walk(b,tgt,depth+1,maxdepth,p,out,seen); continue
                if 0<n and tgt+4+n<=len(b):
                    # vector: offsets to tables/strings, or raw bytes
                    first=tgt+4
                    if n<100000 and first+4<=len(b) and is_table(b,first+u32(b,first)):
                        out.append(f"{'  '*depth}{p}: vector[{n}] of tables")
                        for k in range(min(n,3)):
                            e=first+4*k; walk(b,e+u32(b,e),depth+1,maxdepth,f"{p}[{k}]",out,seen)
                        continue
                    if n<100000 and as_string(b,first+u32(b,first)) is not None:
                        strs=[as_string(b,first+4*k+u32(b,first+4*k)) for k in range(min(n,8))]
                        out.append(f"{'  '*depth}{p}: vector[{n}] of strings {strs}"); continue
                    if n>=16:
                        out.append(f"{'  '*depth}{p}: bytes/vector len={n} head={b[first:first+16].hex()}"); continue
        # scalar
        out.append(f"{'  '*depth}{p}: scalar u32={u32(b,a) if a+4<=len(b) else '?'} u16={u16(b,a)}")
if __name__=='__main__':
    b=open(sys.argv[1],'rb').read()
    root=u32(b,0)
    out=[]
    walk(b,root,0,int(sys.argv[2]) if len(sys.argv)>2 else 4,'root',out,set())
    print('\n'.join(out[:400]))
