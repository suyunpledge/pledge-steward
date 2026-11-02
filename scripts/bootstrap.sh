#!/usr/bin/env bash
# bootstrap.sh — PRoot Ubuntu 内装配 forge 桥（主路径 APK 与 Termux 路径共用）
set -euo pipefail

FORGE_DEST=/opt/forge
SRC=${1:-/sdcard/forge-src}             # 宿主侧 forge 内核源码（scripts/fetch-forge.py 产物；PRoot 里经 /sdcard 绑定可见）

echo "[1/4] 系统依赖"
apt update
apt install -y --no-install-recommends python3 python3-pip ca-certificates

echo "[2/4] Python 依赖（forge 本体零依赖；仅桥需要）"
pip3 install --break-system-packages fastapi uvicorn

echo "[3/4] 部署 forge + 桥"
mkdir -p "$FORGE_DEST"
cp -r "$SRC/forge" "$SRC/bundles" "$SRC/run.py" "$FORGE_DEST/"
# 全量桥模块铺到 /opt/forge 顶层（forge_bridge 按顶层平铺 import 兄弟模块）
cp "$(dirname "$0")"/../bridge/*.py "$FORGE_DEST/"
cd "$FORGE_DEST"
python3 - <<'EOF'
import sys
sys.path.insert(0, "/opt/forge")
import forge, forge_bridge
from forge.loop import build_agent  # 公开 API 可导入即部署 OK
for m in ("assistant_mode", "onboarding", "mcp_connectors", "remote_client"):
    assert m in sys.modules, m + " 未导入——桥模块铺设不完整"
print("forge + bridge ok:", forge.__file__)
EOF

echo "[4/4] 启动桥（127.0.0.1:8000）"
cd "$FORGE_DEST" && exec python3 -m uvicorn forge_bridge:app \
    --host 127.0.0.1 --port 8000 --app-dir /opt/forge
