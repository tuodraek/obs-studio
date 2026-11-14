import sys,re
p=sys.argv[1]
d=open(p,'rb').read(2_000_000)
pats=[rb'main_STAND_ALONE_params_Group_[0-9_]+', rb'TPUOffloadFunc_STAND_ALONE_inst_Group_[0-9_]+',
      rb'scratch_[0-9]+', rb'dive_[a-z_0-9]+', rb'[a-z]+_b0', rb'[a-z]+_a0', rb'main_STAND_ALONE_cluster[0-9]+',
      rb'serving_default[a-z_0-9:]*', rb'StatefulPartitionedCall[:0-9]*']
found=[]
for pat in pats:
    for m in re.finditer(pat,d): found.append((m.start(),m.group().decode('latin1')))
found=sorted(set(found))
print(f"== {p.split('/')[-2]}: {len(found)} structural strings in first 2MB ==")
for off,s in found[:90]:
    tail=d[off+len(s):off+len(s)+16]
    print(f" {off:7d}  {s:<46}  next: "+" ".join(f'{b:02x}' for b in tail))
