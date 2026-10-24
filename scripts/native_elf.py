"""Small ELF64 reader used for Android native dependency closure and SONAME aliases."""
import struct

def elf(data):
    if data[:6] != b'\x7fELF\x02\x01': raise ValueError('Expected little-endian ELF64')
    h=struct.unpack_from('<16sHHIQQQIHHHHHH',data)
    if h[2]!=183:raise ValueError('Expected AArch64')
    sections=[struct.unpack_from('<IIQQQQIIQQ',data,h[6]+i*h[11]) for i in range(h[12])]
    needed=[];soname=None;tables=[]
    for s in sections:
        if s[1]!=6:continue
        strings=sections[s[6]];start=strings[4];tables.append((start,strings[5]))
        for off in range(s[4],s[4]+s[5],16):
            tag,value=struct.unpack_from('<qQ',data,off)
            if tag in (1,14):
                pos=start+value;text=data[pos:data.index(b'\0',pos)].decode()
                if tag==1:needed.append(text)
                else:soname=text
    return {'needed':needed,'soname':soname,'entry':h[4],'tables':tables}

def alias(data, names):
    """Shorten names in .dynstr without moving offsets or changing instructions."""
    out=bytearray(data)
    for start,size in elf(data)['tables']:
        for old,new in names.items():
            if len(new)>len(old):raise ValueError('Alias must fit original ELF string')
            needle=old.encode()+b'\0';offset=start
            while True:
                offset=data.find(needle,offset,start+size)
                if offset<0:break
                if offset==start or data[offset-1]==0:
                    out[offset:offset+len(needle)]=new.encode()+b'\0'*(len(needle)-len(new))
                offset+=len(needle)
    return bytes(out)
