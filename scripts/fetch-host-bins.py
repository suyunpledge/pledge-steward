#!/usr/bin/env python3
"""fetch-host-bins.py — 一键补齐 PRoot 宿主侧二进制（Android bionic, arm64）。

从 Termux 官方 APT 源拉 deb，解出可执行与 so，改名 lib*.so 落 jniLibs
（AGP useLegacyPackaging 提取后带执行位）。

已就位：libproot.so（proot 5.1.107）、libbusybox.so（busybox 1.38.0 真身
libbusybox.so.1.38.0）。本脚本幂等：缺什么补什么。

依赖（proot 的 DT_NEEDED）：
  libtalloc.so.2 / libandroid-shmem.so / libtermux-exec.so

注意：Termux 二进制 INTERP 指向 /data/data/com.termux/... 的 loader，
App 进程内直接 exec 不了——真机联调时用 Termux 自带 linker 方案
（见 docs/GRAY-RELEASE.md 待办）。

用法：python fetch-host-bins.py [--proxy http://127.0.0.1:7897]
"""
import argparse
import os
import re
import sys
import tarfile
import time
import urllib.request
import lzma
from pathlib import Path

HERE = Path(__file__).resolve().parent
JNILIBS = HERE.parent / "app/src/main/jniLibs/arm64-v8a"
TMP = HERE / ".host-bins-cache"

WANT = [
    ("proot", "p/proot", "bin/proot", "libproot.so"),
    ("busybox", "b/busybox", "lib/libbusybox.so.", "libbusybox.so"),
    ("libtalloc", "t/talloc", "libtalloc.so.2", "libtalloc.so.2"),
    ("libandroid-shmem", "a/android-shmem", "libandroid-shmem.so", "libandroid-shmem.so"),
    ("termux-exec", "t/termux-exec", "libtermux-exec.so", "libtermux-exec.so"),
]

BASE = "https://packages.termux.dev/apt/termux-main/pool/main/"
HDR = {"User-Agent": "Mozilla/5.0"}


def http_get(url, timeout=90, tries=5):
    for i in range(tries):
        try:
            return urllib.request.urlopen(
                urllib.request.Request(url, headers=HDR), timeout=timeout).read()
        except Exception as e:
            print(f"  retry {i + 1}: {type(e).__name__}: {e}")
            time.sleep(4 * (i + 1))
    raise SystemExit(f"download failed: {url}")


def list_debs(sub):
    html = http_get(BASE + sub + "/").decode()
    return sorted(d for d in re.findall(r'href="([^"]+\.deb)"', html) if "aarch64" in d)


def data_tar(deb: Path):
    data = deb.read_bytes()
    if data[:8] != b"!<arch>\n":
        raise SystemExit(f"not a deb: {deb}")
    pos = 8
    while pos < len(data):
        hdr = data[pos:pos + 60]
        name = hdr[:16].decode().strip().rstrip("/")
        size = int(hdr[48:58].decode().strip())
        body = data[pos + 60:pos + 60 + size]
        if name.startswith("data.tar"):
            tf_ = TMP / name
            tf_.write_bytes(body)
            op = lzma.open if name.endswith(".xz") else open
            with op(tf_) as f:
                return tarfile.open(fileobj=f)
        pos += 60 + size + (size % 2)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--proxy", default="http://127.0.0.1:7897")
    args = ap.parse_args()
    if args.proxy:
        os.environ["http_proxy"] = args.proxy
        os.environ["https_proxy"] = args.proxy
    TMP.mkdir(parents=True, exist_ok=True)
    JNILIBS.mkdir(parents=True, exist_ok=True)

    for pkg, sub, suffix, soname in WANT:
        dest = JNILIBS / soname
        if dest.exists() and dest.stat().st_size > 100_000:
            print(f"[skip] {soname} already present")
            continue
        try:
            debs = list_debs(sub)
            if not debs:
                print(f"[miss] no aarch64 deb listed for {pkg}")
                continue
            deb = TMP / debs[-1]
            print(f"[{pkg}] {debs[-1]}")
            if not deb.exists():
                deb.write_bytes(http_get(BASE + sub + "/" + debs[-1]))
            tf = data_tar(deb)
            found = False
            for m in (tf.getmembers() if tf else []):
                n = m.name.split("/")[-1]
                if m.isfile() and (n == suffix or (suffix.endswith(".") and n.startswith(suffix))):
                    src = tf.extractfile(m).read()
                    dest.write_bytes(src)
                    os.chmod(dest, 0o755)
                    print(f"  -> {soname} ({len(src) // 1024} KB)")
                    found = True
                    break
            if not found:
                print(f"[miss] {suffix} not in {pkg} data.tar")
        except SystemExit:
            raise
        except Exception as e:
            print(f"[warn] {pkg}: {e}（网络恢复后重跑本脚本即可）")

    print()
    print("jniLibs 现状：")
    for f in sorted(JNILIBS.glob("*.so")):
        print(f"  {f.name}: {f.stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()
