#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""fetch-forge — 按 forge-pin.json 拉取 forge 内核源码树到 build/forge-src。

pledge-steward 只含手机侧（壳/桥/构建脚本/测试）；Agent 内核 forge/ 与 bundles/
单一来源在上游 pledge-evolving，由 forge-pin.json 固定版本。rootfs 打包
（scripts/build-rootfs.sh）与全部冒烟测试共用 build/forge-src 这一份。

用法：
  python scripts/fetch-forge.py                      # GitHub 按 pin 拉（默认）
  python scripts/fetch-forge.py --ref <sha|tag|分支>   # 临时用其他 ref
  python scripts/fetch-forge.py --local <pledge-evolving 检出路径>   # 离线：本地 git archive
  python scripts/fetch-forge.py --force              # 忽略 stamp 强制重拉

幂等：build/forge-src/.forge-pin-stamp.json 记录已拉取 ref，一致且校验通过则跳过。
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
PIN_FILE = REPO / "forge-pin.json"
DEST = REPO / "build" / "forge-src"
STAMP = DEST / ".forge-pin-stamp.json"
DOWNLOADS = REPO / ".downloads"


def load_pin() -> dict:
    return json.loads(PIN_FILE.read_text(encoding="utf-8"))


def verify(dest: Path) -> None:
    for rel in ("forge/loop.py", "forge/config.py", "forge/model.py", "bundles"):
        if not (dest / rel).exists():
            raise SystemExit(f"[fetch-forge] 校验失败：{dest} 缺少 {rel}")


def extract_stream(fileobj, dest: Path, strip: int = 0) -> int:
    dest.mkdir(parents=True, exist_ok=True)
    count = 0
    with tarfile.open(fileobj=fileobj, mode="r|*") as tf:
        for member in tf:
            name = member.name
            parts = name.split("/")
            if strip:
                if len(parts) <= strip:
                    continue
                name = "/".join(parts[strip:])
            if not name or name.startswith("/") or ".." in Path(name).parts:
                continue
            member.name = name
            tf.extract(member, dest, filter="data")
            count += 1
    return count


def fetch_github(repo: str, ref: str) -> None:
    url = f"https://codeload.github.com/{repo}/tar.gz/{ref}"
    print(f"[fetch-forge] GitHub: {url}")
    DOWNLOADS.mkdir(exist_ok=True)
    req = urllib.request.Request(url, headers={"User-Agent": "pledge-steward-fetch-forge"})
    with urllib.request.urlopen(req, timeout=120) as resp, tempfile.NamedTemporaryFile(
            dir=DOWNLOADS, suffix=".tar.gz", delete=False) as tmp:
        shutil.copyfileobj(resp, tmp)
        archive = Path(tmp.name)
    try:
        with open(archive, "rb") as f:
            n = extract_stream(f, DEST, strip=1)
    finally:
        archive.unlink(missing_ok=True)
    print(f"[fetch-forge] 解包 {n} 个条目 -> {DEST}")


def fetch_local(local_repo: str, ref: str) -> None:
    local_repo = Path(local_repo)
    print(f"[fetch-forge] 本地 git archive: {local_repo} @ {ref}")
    if not (local_repo / ".git").exists():
        raise SystemExit(f"[fetch-forge] 不是 git 检出：{local_repo}")
    proc = subprocess.Popen(
        ["git", "-C", str(local_repo), "archive", "--format=tar", ref],
        stdout=subprocess.PIPE)
    n = extract_stream(proc.stdout, DEST, strip=0)
    if proc.wait() != 0:
        raise SystemExit("[fetch-forge] git archive 失败（ref 不存在？）")
    print(f"[fetch-forge] 解包 {n} 个条目 -> {DEST}")


def main() -> int:
    ap = argparse.ArgumentParser(description="按 forge-pin.json 拉取 forge 内核源码树")
    ap.add_argument("--ref", help="覆盖 pin 的 ref（sha/tag/分支）")
    ap.add_argument("--local", metavar="PATH", help="离线模式：从本地 pledge-evolving 检出 git archive")
    ap.add_argument("--force", action="store_true", help="忽略 stamp 强制重拉")
    args = ap.parse_args()

    pin = load_pin()
    ref = args.ref or pin["ref"]

    if not args.force and STAMP.exists():
        try:
            stamp = json.loads(STAMP.read_text(encoding="utf-8"))
            if stamp.get("ref") == ref:
                try:
                    verify(DEST)
                    print(f"[fetch-forge] 已是目标 ref，跳过：{ref}")
                    return 0
                except SystemExit:
                    print("[fetch-forge] stamp 命中但校验失败，重拉")
        except Exception:
            pass

    if DEST.exists():
        shutil.rmtree(DEST)

    if args.local:
        fetch_local(args.local, ref)
    else:
        fetch_github(pin["repo"], ref)

    verify(DEST)
    STAMP.write_text(json.dumps({
        "ref": ref,
        "repo": pin["repo"],
        "source": "local" if args.local else "github",
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[fetch-forge] 完成：{ref}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
