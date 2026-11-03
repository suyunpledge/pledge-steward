#!/usr/bin/env bash
# Run as root inside a dedicated Ubuntu WSL distribution; never on /mnt/c.
set -euo pipefail
STEWARD=$(cd "$(dirname "$0")/.." && pwd)
SRC=${FORGE_SRC:-$STEWARD/build/forge-src}   # forge 内核源码（scripts/fetch-forge.py 产物）
BUILD=${FORGE_ROOTFS_BUILD:-/root/forge-arm64-build}
ROOT="$BUILD/rootfs"
OUT="$STEWARD/app/src/main/assets/forge-rootfs-arm64.tar"
[[ $EUID == 0 ]] || { echo 'Run this script as root in WSL' >&2; exit 1; }
[[ "$BUILD" == /root/forge-arm64-build ]] || { echo 'Unsupported build directory' >&2; exit 1; }
if [[ "${1:-}" == --repack ]]; then
  [[ $# == 1 ]] || { echo 'Unexpected arguments' >&2; exit 1; }
  [[ -f "$ROOT/usr/bin/python3.12" && -d "$ROOT/var/lib/dpkg" ]] || { echo 'No completed rootfs to repack' >&2; exit 1; }
elif [[ $# != 0 || -e "$ROOT" ]]; then
  echo 'Use a fresh /root/forge-arm64-build, or --repack for a completed image; preserve failed builds' >&2; exit 1
fi
export DEBIAN_FRONTEND=noninteractive
if [[ "${1:-}" != --repack ]]; then
  apt-get -o APT::Update::Error-Mode=any update
  apt-get install -y --no-install-recommends debootstrap qemu-user-static binfmt-support ubuntu-keyring ca-certificates git
fi
mkdir -p "$BUILD"
mountpoint -q /proc/sys/fs/binfmt_misc || mount -t binfmt_misc binfmt_misc /proc/sys/fs/binfmt_misc
if [[ ! -e /proc/sys/fs/binfmt_misc/qemu-aarch64 ]]; then
  if [[ -f /usr/lib/binfmt.d/qemu-aarch64.conf ]]; then
    grep '^:' /usr/lib/binfmt.d/qemu-aarch64.conf > /proc/sys/fs/binfmt_misc/register
  else
    update-binfmts --enable qemu-aarch64
  fi
fi
if [[ "${1:-}" != --repack ]]; then
debootstrap --variant=minbase --arch=arm64 --foreign --components=main,universe \
  --include=python3,ca-certificates \
  noble "$ROOT" "${FORGE_UBUNTU_PORTS:-http://ports.ubuntu.com/ubuntu-ports}"
cp /usr/bin/qemu-aarch64-static "$ROOT/usr/bin/"
chroot "$ROOT" /debootstrap/debootstrap --second-stage
fi
mkdir -p "$ROOT/opt/forge" "$ROOT/opt/forge/workspace" "$ROOT/workspace" "$ROOT/forge-home" "$ROOT/tmp"
chmod 1777 "$ROOT/tmp"
# Stage the pinned forge kernel tree (scripts/fetch-forge.py output; no monorepo
# checkout needed), excluding .git and the superseded forge-android directory.
# Then overlay only the Python worker and its Forge tool adapter.  HTTP,
# onboarding, catalog, MCP state and remote state are owned by Kotlin.
[[ -f "$SRC/forge/loop.py" && -d "$SRC/bundles" ]] || {
  echo "forge kernel source missing under $SRC — run: python scripts/fetch-forge.py" >&2
  exit 1
}
[[ ! -e "$ROOT/opt/forge.new" && ! -e "$ROOT/opt/forge.old" ]] || { echo 'Inspect interrupted source staging first' >&2; exit 1; }
mkdir "$ROOT/opt/forge.new"
tar -C "$SRC" --exclude='./.git' --exclude='./forge-android' -cf - . |
  tar -xf - -C "$ROOT/opt/forge.new"
cp "$STEWARD"/bridge/forge_worker.py "$STEWARD"/bridge/bridge_tools.py "$ROOT/opt/forge.new/"
mv "$ROOT/opt/forge" "$ROOT/opt/forge.old"
mv "$ROOT/opt/forge.new" "$ROOT/opt/forge"
# Only disposable source in this fixed, resolved build root is replaced.
[[ "$(readlink -f "$ROOT/opt/forge.old")" == /root/forge-arm64-build/rootfs/opt/forge.old ]] || exit 1
rm -rf -- "$ROOT/opt/forge.old"
printf 'nameserver 1.1.1.1\n' > "$ROOT/etc/resolv.conf"
chroot "$ROOT" /usr/bin/python3 -c '
import sys
sys.path.insert(0,"/opt/forge")
import forge, forge_worker
from forge.loop import build_agent
print("arm64 runtime + forge worker module passed")
'
chroot "$ROOT" /usr/bin/python3 - <<'PY'
import os, subprocess, json
os.chdir('/opt/forge')
process=subprocess.Popen(['/usr/bin/python3','/opt/forge/forge_worker.py'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True)
try:
    event=json.loads(process.stdout.readline())
    assert event.get('type') == 'worker_ready', event
    print('arm64 Forge worker stdio ready passed',flush=True)
finally:
    process.stdin.write(json.dumps({'type':'shutdown'})+'\n'); process.stdin.flush()
    process.terminate()
    try: process.wait(timeout=5)
    except subprocess.TimeoutExpired: process.kill();process.wait()
PY
chroot "$ROOT" dpkg-query -W > "$BUILD/packages.txt"
find "$ROOT" -xdev -type f -perm /6000 -exec chmod a-s {} +
rm -f "$ROOT/usr/bin/qemu-aarch64-static"
# Keep package metadata, strip only disposable downloads and logs in this build.
find "$ROOT/var/cache/apt/archives" -maxdepth 1 -type f -name '*.deb' -delete
find "$ROOT/var/lib/apt/lists" -maxdepth 1 -type f -delete
find "$ROOT/var/log" -type f -exec truncate -s 0 {} +
mkdir -p "$(dirname "$OUT")"
tar --exclude='./dev/*' --exclude='./proc/*' --exclude='./sys/*' --exclude='./run/*' \
  --numeric-owner -C "$ROOT" -cpf "$OUT.partial" .
mv "$OUT.partial" "$OUT"
sha256sum "$OUT" | tee "$OUT.sha256"
stat -c '%s bytes %n' "$OUT"
