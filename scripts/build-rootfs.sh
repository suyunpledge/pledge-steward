#!/usr/bin/env bash
# Run as root inside a dedicated Ubuntu WSL distribution; never on /mnt/c.
set -euo pipefail
REPO=$(cd "$(dirname "$0")/../.." && pwd)
BUILD=${FORGE_ROOTFS_BUILD:-/root/forge-arm64-build}
ROOT="$BUILD/rootfs"
OUT="$REPO/forge-android/app/src/main/assets/forge-rootfs-arm64.tar"
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
  --include=python3,python3-fastapi,python3-uvicorn,python3-websockets,ca-certificates \
  noble "$ROOT" "${FORGE_UBUNTU_PORTS:-http://ports.ubuntu.com/ubuntu-ports}"
cp /usr/bin/qemu-aarch64-static "$ROOT/usr/bin/"
chroot "$ROOT" /debootstrap/debootstrap --second-stage
fi
mkdir -p "$ROOT/opt/forge" "$ROOT/opt/forge/workspace" "$ROOT/workspace" "$ROOT/forge-home" "$ROOT/tmp"
chmod 1777 "$ROOT/tmp"
# Copy the complete public source tree, including this worktree's fixes. Exclude
# ignored credentials, .git, host build outputs and the generated rootfs itself.
cd "$REPO"
GIT=(git -C "$REPO")
if [[ -f "$REPO/.git" ]]; then
  gitdir=$(sed -n 's/^gitdir: //p' "$REPO/.git" | tr -d '\r')
  if [[ "$gitdir" =~ ^[A-Za-z]: ]]; then
    GIT=(git "--git-dir=$(wslpath -u "$gitdir")" "--work-tree=$REPO")
  fi
fi
[[ ! -e "$ROOT/opt/forge.new" && ! -e "$ROOT/opt/forge.old" ]] || { echo 'Inspect interrupted source staging first' >&2; exit 1; }
mkdir "$ROOT/opt/forge.new"
"${GIT[@]}" ls-files -z --cached --others --exclude-standard |
  tar --null -T - -cf - | tar -xf - -C "$ROOT/opt/forge.new"
cp forge-android/bridge/forge_bridge.py forge-android/bridge/bridge_tools.py "$ROOT/opt/forge.new/"
mv "$ROOT/opt/forge" "$ROOT/opt/forge.old"
mv "$ROOT/opt/forge.new" "$ROOT/opt/forge"
# Only disposable source in this fixed, resolved build root is replaced.
[[ "$(readlink -f "$ROOT/opt/forge.old")" == /root/forge-arm64-build/rootfs/opt/forge.old ]] || exit 1
rm -rf -- "$ROOT/opt/forge.old"
printf 'nameserver 1.1.1.1\n' > "$ROOT/etc/resolv.conf"
chroot "$ROOT" /usr/bin/python3 -c '
import sys
sys.path.insert(0,"/opt/forge")
import forge, fastapi, uvicorn, websockets, forge_bridge
from forge.loop import build_agent
print("arm64 runtime and bridge imports passed")
'
chroot "$ROOT" /usr/bin/python3 - <<'PY'
import os, socket, subprocess, time, urllib.request, json, asyncio, secrets
import websockets
os.chdir('/opt/forge')
token=secrets.token_hex(32)
os.environ['FORGE_BRIDGE_TOKEN']=token
with socket.socket() as sock:
    sock.bind(('127.0.0.1',0))
    port=sock.getsockname()[1]
process=subprocess.Popen(['/usr/bin/python3','-m','uvicorn','forge_bridge:app',
    '--host','127.0.0.1','--port',str(port),'--loop','asyncio'],stdout=subprocess.DEVNULL)
try:
    for attempt in range(120):
        if process.poll() is not None: raise RuntimeError('arm64 bridge exited')
        try:
            with urllib.request.urlopen(f'http://127.0.0.1:{port}/health',timeout=1) as response:
                assert json.load(response)['status']=='ok'
            print('arm64 uvicorn real HTTP health passed',flush=True)
            break
        except OSError: time.sleep(.5)
    else: raise RuntimeError('arm64 bridge health timeout')
    async def check_session():
        async with websockets.connect(f'ws://127.0.0.1:{port}/agent/ws?session=build-check',
                extra_headers={'Authorization':'Bearer '+token},open_timeout=60) as ws:
            assert json.loads(await asyncio.wait_for(ws.recv(),60))['type']=='ready'
            await ws.send(json.dumps({'type':'ping'}))
            assert json.loads(await asyncio.wait_for(ws.recv(),10))['type']=='pong'
            print('arm64 real Agent construction + authenticated WS ready/ping passed',flush=True)
    asyncio.run(check_session())
finally:
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
