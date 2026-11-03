#!/usr/bin/env bash
# bootstrap.sh — PRoot Ubuntu 内装配 forge 桥（主路径 APK 与 Termux 路径共用）
set -euo pipefail

FORGE_DEST=/opt/forge
SRC=${1:-/sdcard/forge-src}             # 宿主侧 forge 内核源码（scripts/fetch-forge.py 产物；PRoot 里经 /sdcard 绑定可见）

echo "[1/4] 系统依赖"
apt update
apt install -y --no-install-recommends python3 python3-pip ca-certificates

echo "[2/4] Python 依赖（Kotlin 原生桥；worker 仅使用 forge 本体）"

echo "[3/4] 部署 forge + 桥"
mkdir -p "$FORGE_DEST"
cp -r "$SRC/forge" "$SRC/bundles" "$SRC/run.py" "$FORGE_DEST/"
# 仅铺设 Forge Worker 与设备工具适配器；HTTP/WS 由 Kotlin NativeBridge 提供。
cp "$(dirname "$0")"/../bridge/forge_worker.py "$(dirname "$0")"/../bridge/bridge_tools.py "$FORGE_DEST/"
cd "$FORGE_DEST"
python3 - <<'EOF'
import sys
sys.path.insert(0, "/opt/forge")
import forge, forge_worker
from forge.loop import build_agent  # 公开 API 可导入即部署 OK
print("forge + worker ok:", forge.__file__)
EOF

echo "[4/4] 启动 Forge worker（HTTP/WebSocket 由 Android Kotlin 提供）"
cd "$FORGE_DEST" && exec python3 /opt/forge/forge_worker.py
