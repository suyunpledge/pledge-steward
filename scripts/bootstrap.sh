#!/usr/bin/env bash
# bootstrap.sh — PRoot Ubuntu 内装配 forge 桥（主路径 APK 与 Termux 路径共用）
set -euo pipefail

FORGE_DEST=/opt/forge
SRC=${1:-/sdcard/pledge-evolving}      # 宿主侧 forge 仓路径（PRoot 里经 /sdcard 绑定可见）

echo "[1/4] 系统依赖"
apt update
apt install -y --no-install-recommends python3 python3-pip ca-certificates

echo "[2/4] Python 依赖（forge 本体零依赖；仅桥需要）"
pip3 install --break-system-packages fastapi uvicorn

echo "[3/4] 部署 forge + 桥"
mkdir -p "$FORGE_DEST"
cp -r "$SRC/forge" "$SRC/bundles" "$SRC/run.py" "$FORGE_DEST/"
cp "$(dirname "$0")/../bridge/forge_bridge.py" \
   "$(dirname "$0")/../bridge/bridge_tools.py" "$FORGE_DEST/"
cd "$FORGE_DEST"
python3 - <<'EOF'
import sys
sys.path.insert(0, "/opt/forge")
import forge
from forge.loop import build_agent  # 公开 API 可导入即部署 OK
print("forge import ok:", forge.__file__)
EOF

echo "[4/4] 启动桥（127.0.0.1:8000）"
cd "$FORGE_DEST" && exec python3 -m uvicorn forge_bridge:app \
    --host 127.0.0.1 --port 8000 --app-dir /opt/forge
