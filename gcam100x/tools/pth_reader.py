# Safe .pth reader: a tiny pickle stack machine over pickletools ops; no imports/calls from the file.
import zipfile, pickletools, numpy as np, io, sys
MARK=object()
def load_pth(path):
    z=zipfile.ZipFile(path); names=z.namelist(); root=names[0].split('/')[0]
    pk=z.read([n for n in names if n.endswith('data.pkl')][0])
    st=[]; memo={}
    def poptomark():
        i=len(st)-1
        while st[i] is not MARK: i-=1
        items=st[i+1:]; del st[i:]; return items
    for op,a,_ in pickletools.genops(io.BytesIO(pk)):
        n=op.name
        if n in('PROTO','FRAME'): continue
        elif n=='STOP': break
        elif n=='MARK': st.append(MARK)
        elif n in('EMPTY_DICT',): st.append({})
        elif n=='EMPTY_TUPLE': st.append(())
        elif n in('BININT','BININT1','BININT2','LONG1','BINFLOAT'): st.append(a)
        elif n in('BINUNICODE','SHORT_BINUNICODE','UNICODE'): st.append(a)
        elif n=='NONE': st.append(None)
        elif n=='NEWFALSE': st.append(False)
        elif n=='NEWTRUE': st.append(True)
        elif n in('BINPUT','LONG_BINPUT','PUT'): memo[a]=st[-1]
        elif n in('BINGET','LONG_BINGET','GET'): st.append(memo[a])
        elif n=='GLOBAL': st.append(('G',a))
        elif n=='TUPLE': st.append(tuple(poptomark()))
        elif n=='TUPLE1': st[-1:]=[(st[-1],)]
        elif n=='TUPLE2': st[-2:]=[tuple(st[-2:])]
        elif n=='TUPLE3': st[-3:]=[tuple(st[-3:])]
        elif n=='BINPERSID': st.append(('PID',st.pop()))
        elif n=='REDUCE':
            args=st.pop(); fn=st.pop()
            g=fn[1] if isinstance(fn,tuple) and fn[0]=='G' else None
            if g=='torch._utils _rebuild_tensor_v2':
                pid,off,size,stride=args[0][1],args[1],args[2],args[3]
                key=pid[2]
                buf=np.frombuffer(z.read(f'{root}/data/{key}'),dtype=np.float32)
                cnt=int(np.prod(size)) if size else 1
                st.append(buf[off:off+cnt].reshape(size).copy())
            elif g=='collections OrderedDict': st.append({})
            else: raise ValueError('unexpected callable %r'%(fn,))
        elif n=='SETITEMS':
            it=poptomark(); d=st[-1]
            for i in range(0,len(it),2): d[it[i]]=it[i+1]
        elif n=='SETITEM':
            v=st.pop(); k=st.pop(); st[-1][k]=v
        elif n=='BUILD': st.pop()
        else: raise ValueError('unsupported op '+n)
    d=st[-1]
    return d.get('params', d.get('params_ema', d))
if __name__=='__main__':
    d=load_pth(sys.argv[1]); print(len(d))
    for k in list(d)[:3]+list(d)[-3:]: print(k,d[k].shape)
