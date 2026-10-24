#!/usr/bin/env python3
"""Fetch pinned Termux arm64 host binaries, verify package SHA256 and ELF closure."""
import argparse,gzip,hashlib,io,json,tarfile,time,urllib.request,os,subprocess,sys,tempfile
from pathlib import Path
from native_elf import elf,alias
BASE='https://packages.termux.dev/apt/termux-main/'
HERE=Path(__file__).resolve().parent
DEST=HERE.parent/'app/src/main/jniLibs/arm64-v8a'
CACHE=HERE/'.host-bins-cache'
LOCK=HERE/'native-host-lock.json'
PACKAGES=['proot','busybox','libtalloc','libandroid-shmem','libandroid-selinux','pcre2']
ALIASES={'libtalloc.so.2':'libtalloc.so','libpcre2-8.so.0':'libpcre2-8.so','libbusybox.so.1.38.0':'libbusybox-impl.so'}
SYSTEM={'libc.so','libm.so','libdl.so','liblog.so'}

def fetch(url):
    for i in range(5):
        try:
            with urllib.request.urlopen(url,timeout=30) as r:return r.read()
        except Exception:
            if i==4:raise
            time.sleep(min(2**i,8))

def payload(data):
    if data[:8]!=b'!<arch>\n':raise ValueError('Invalid deb')
    pos=8
    while pos<len(data):
        size=int(data[pos+48:pos+58]);name=data[pos:pos+16].decode().strip().rstrip('/')
        if name.startswith('data.tar'):return tarfile.open(fileobj=io.BytesIO(data[pos+60:pos+60+size]),mode='r:*')
        pos+=60+size+(size%2)
    raise ValueError('Missing deb data')

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--refresh',action='store_true')
    parser.add_argument('--ndk',default=os.environ.get('ANDROID_NDK_HOME'));args=parser.parse_args()
    if not args.ndk:parser.error('--ndk or ANDROID_NDK_HOME is required to compile the Android BusyBox launcher')
    if LOCK.exists() and not args.refresh:lock=json.loads(LOCK.read_text())
    else:
        blocks=gzip.decompress(fetch(BASE+'dists/stable/main/binary-aarch64/Packages.gz')).decode().split('\n\n')
        records=[dict(line.split(': ',1) for line in b.splitlines() if ': ' in line and not line.startswith(' ')) for b in blocks]
        lock={'packages':{p:next({k:r[k] for k in ('Version','Filename','SHA256')} for r in records if r.get('Package')==p) for p in PACKAGES}}
    CACHE.mkdir(exist_ok=True);DEST.mkdir(parents=True,exist_ok=True)
    outputs={};files={}
    for package,row in lock['packages'].items():
        cached=CACHE/Path(row['Filename']).name
        if not cached.exists() or hashlib.sha256(cached.read_bytes()).hexdigest()!=row['SHA256']:
            data=fetch(BASE+row['Filename'])
            if hashlib.sha256(data).hexdigest()!=row['SHA256']:raise ValueError(f'Package SHA256 mismatch: {package}')
            cached.write_bytes(data)
        with payload(cached.read_bytes()) as archive:
            for member in archive:
                if not member.isfile():continue
                name=Path(member.name).name;dest=None
                if package=='proot' and name=='proot':dest='libproot.so'
                elif package=='proot' and member.name.endswith('/libexec/proot/loader'):dest='libproot-loader.so'
                elif package=='busybox' and name.startswith('libbusybox.so.'):dest='libbusybox-impl.so'
                elif name in ('libtalloc.so.2.5.0','libandroid-shmem.so','libandroid-selinux.so') or name=='libpcre2-8.so' or name.startswith('libpcre2-8.so.0.'):
                    dest='libtalloc.so' if name.startswith('libtalloc') else 'libpcre2-8.so' if name.startswith('libpcre2') else name
                if dest:
                    original=archive.extractfile(member).read();data=alias(original,ALIASES)
                    files[dest]=data
                    outputs[dest]={'package':package,'member':member.name,'original_sha256':hashlib.sha256(original).hexdigest(),
                                  'sha256':hashlib.sha256(data).hexdigest(),**{k:v for k,v in elf(data).items() if k!='tables'}}
    source=HERE.parent/'app/src/main/cpp/busybox-launcher.c'
    host='windows-x86_64' if sys.platform=='win32' else 'darwin-x86_64' if sys.platform=='darwin' else 'linux-x86_64'
    clang=Path(args.ndk)/'toolchains/llvm/prebuilt'/host/'bin'/('clang.exe' if sys.platform=='win32' else 'clang')
    with tempfile.TemporaryDirectory() as tmp:
        output=Path(tmp)/'launcher.so'
        flags=['--target=aarch64-linux-android26','-O2','-fPIE','-pie','-Wl,--build-id=sha1','-Wl,-z,max-page-size=16384','-o',str(output),str(source),'-ldl']
        subprocess.run([str(clang),*flags],check=True)
        strip=clang.parent/('llvm-strip.exe' if sys.platform=='win32' else 'llvm-strip')
        subprocess.run([str(strip),'--strip-unneeded',str(output)],check=True)
        data=output.read_bytes();files['libbusybox.so']=data
        outputs['libbusybox.so']={'source':'app/src/main/cpp/busybox-launcher.c','source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
            'ndk':(Path(args.ndk)/'source.properties').read_text().strip(), 'stripped':True,'sha256':hashlib.sha256(data).hexdigest(),
            **{k:v for k,v in elf(data).items() if k!='tables'}}
    required={'libproot.so','libproot-loader.so','libbusybox.so','libbusybox-impl.so','libtalloc.so','libandroid-shmem.so','libandroid-selinux.so','libpcre2-8.so'}
    if set(outputs)!=required:raise ValueError(f'Incomplete native set: {required-set(outputs)}')
    for name,row in outputs.items():
        missing=set(row['needed'])-SYSTEM-set(outputs)
        if missing:raise ValueError(f'{name} missing {missing}')
    for name in ('libproot.so','libproot-loader.so','libbusybox.so'):
        if not outputs[name]['entry']:raise ValueError(f'No executable entry: {name}')
    for name,data in files.items():
        temporary=DEST/(name+'.partial');temporary.write_bytes(data);temporary.replace(DEST/name)
    lock.update(binaries=outputs,aliases=ALIASES,verification='HTTPS index + package SHA256; ELF AArch64 + packaged DT_NEEDED closure')
    LOCK.write_text(json.dumps(lock,indent=2)+'\n')
    print(json.dumps(outputs,indent=2))
if __name__=='__main__':main()
