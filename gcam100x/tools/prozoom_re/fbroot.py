import sys,struct
path=sys.argv[1]; magic_off=int(sys.argv[2])  # file offset of 'DGC0' magic
d=open(path,'rb').read(1_000_000)
base0=magic_off-4   # flatbuffer buffer start (uoffset location)
root_off=struct.unpack_from('<I',d,base0)[0]
base=base0+root_off
vt=base-struct.unpack_from('<i',d,base)[0]
vt_size,tbl_size=struct.unpack_from('<HH',d,vt)
nf=(vt_size-4)//2
print(f"{path.split('/')[-1][:12]} magic@{magic_off} bufstart@{base0} root@{base} fields={nf}")
for i in range(nf):
    foff=struct.unpack_from('<H',d,vt+4+i*2)[0]
    if foff==0: print(f"  f[{i}]: (absent)"); continue
    pos=base+foff
    u8=d[pos]; u16=struct.unpack_from('<H',d,pos)[0]; u32=struct.unpack_from('<I',d,pos)[0]
    print(f"  f[{i}] @{foff}: u8={u8:<4} u16={u16:<7} u32={u32}(0x{u32:x})")
