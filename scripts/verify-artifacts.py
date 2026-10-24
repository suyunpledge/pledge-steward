#!/usr/bin/env python3
"""Offline verification of the APK, rootfs source snapshot and native lock."""
import hashlib,json,subprocess,tarfile,zipfile
from datetime import datetime,timezone
from pathlib import Path
from native_elf import elf

ANDROID=Path(__file__).resolve().parents[1]
REPO=ANDROID.parent
APK=ANDROID/'app/build/outputs/apk/debug/app-debug.apk'
ROOTFS=ANDROID/'app/src/main/assets/forge-rootfs-arm64.tar'

def digest(stream):
    result=hashlib.sha256()
    while chunk:=stream.read(1024*1024):result.update(chunk)
    return result.hexdigest()

def require(condition,message):
    if not condition:raise ValueError(message)

def main():
    with ROOTFS.open('rb') as stream:root_hash=digest(stream)
    lock=json.loads((ANDROID/'scripts/native-host-lock.json').read_text())
    with zipfile.ZipFile(APK) as apk:
        require(apk.testzip() is None,'APK CRC failure')
        member=apk.getinfo('assets/forge-rootfs-arm64.tar')
        require(member.compress_type==zipfile.ZIP_STORED,'rootfs must be stored without ZIP compression')
        with apk.open(member) as stream:require(digest(stream)==root_hash,'APK rootfs SHA256 mismatch')
        for name,record in lock['binaries'].items():
            data=apk.read('lib/arm64-v8a/'+name)
            require(hashlib.sha256(data).hexdigest()==record['sha256'],'APK native SHA256 mismatch: '+name)
            needed=set(elf(data)['needed'])-set(lock['binaries'])-{'libc.so','libm.so','libdl.so','liblog.so'}
            require(not needed,'Missing native dependency: '+name)
    sources=subprocess.check_output(['git','ls-files','-z','--cached','--others','--exclude-standard'],cwd=REPO).decode('utf-8').split('\0')
    count=0
    with tarfile.open(ROOTFS,'r:') as rootfs:
        python=rootfs.extractfile('./usr/bin/python3.12')
        require(python is not None,'Missing arm64 Python')
        require(python.read(20)[18:20]==b'\xb7\x00','Python is not arm64')
        for name in filter(None,sources):
            path=REPO/name
            if not path.is_file():continue
            source=rootfs.extractfile('./opt/forge/'+name)
            require(source is not None,'Missing source: '+name)
            with source,path.open('rb') as original:
                require(digest(source)==digest(original),'Stale rootfs source: '+name)
            count+=1
        for name in ('forge_bridge.py','bridge_tools.py'):
            with rootfs.extractfile('./opt/forge/'+name) as stream,(ANDROID/'bridge'/name).open('rb') as original:
                require(digest(stream)==digest(original),'Stale active bridge: '+name)
    with APK.open('rb') as stream:apk_hash=digest(stream)
    result={'verified_at':datetime.now(timezone.utc).isoformat(),'apk':str(APK),'apk_bytes':APK.stat().st_size,
        'apk_sha256':apk_hash,'rootfs_bytes':ROOTFS.stat().st_size,'rootfs_sha256':root_hash,
        'rootfs_zip_stored':True,'native_files_verified':len(lock['binaries']),'source_files_verified':count,
        'apk_crc_passed':True,'android_device_execution_verified':False}
    output=APK.parent/'artifact-verification.json'
    output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False,indent=2))

if __name__=='__main__':main()
