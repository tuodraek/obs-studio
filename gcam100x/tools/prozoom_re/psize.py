import sys,re,struct,collections
p=sys.argv[1]; d=open(p,'rb').read(3_000_000)
sizes={}
for m in re.finditer(rb'main_STAND_ALONE_params_Group_0_(\d+)_2_0_0', d):
    idx=int(m.group(1)); s=m.end()
    win=d[s:s+20]
    j=win.find(b'\x01\x00\x00\x00')
    if j>=0 and j+8<=len(win):
        sz=struct.unpack_from('<I',win,j+4)[0]
        # keep the one from the directory that has 01000000 marker (second table)
        sizes.setdefault(idx,set()).add(sz)
print(f"== {p.split('/')[-2]} ==  groups found: {len(sizes)}")
tot=0; out=[]
for idx in sorted(sizes):
    cand=sorted(sizes[idx])
    out.append((idx,cand))
for idx,cand in out:
    print(f"  group {idx:2d}: sizes {[hex(c) for c in cand]}  ({[c for c in cand]})")
