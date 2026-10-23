# rootfs 镜像制作（PC / WSL，一次性）

目标：arm64 Ubuntu 24.04 用户态 tar，内含 python3 + uvicorn/fastapi + forge 全仓，
直接可被 PRoot 挂载（root 设备可选真 chroot，同一套镜像）。

## 自动构建（优先）

在独立 Ubuntu WSL 中以 root 执行本工作树的 `forge-android/scripts/build-rootfs.sh`。
使用 Linux 文件系统的 `/root/forge-arm64-build`，不要在 `/mnt/c` 解包 Linux rootfs。
脚本执行 debootstrap foreign/second-stage、安装 Python/FastAPI/Uvicorn/WebSockets，
复制完整的 Git 管理源码及本工作树修复（不含 `.git`、忽略的凭据和构建产物），
验证 arm64 Python 导入后写入 `app/src/main/assets/forge-rootfs-arm64.tar`，附 SHA-256。

首次构建目录必须为空；失败时保留目录及日志排查，不自动删除。Node/npm、Git/SSH
不是本次最小镜像的必需依赖，未放入运行镜像；需要时另行安装。tar 体积由实际
软件包和全仓源码决定，不能把下表估算当作验收结果。

**APK 运行还需 Android arm64 原生 PRoot 和 busybox**，分别以
`app/src/main/jniLibs/arm64-v8a/libproot.so`、`libbusybox.so` 打包，
安装后从 `nativeLibraryDir` 执行。Ubuntu 的 aarch64 Linux 二进制不能替代
Android 的这些宿主程序；仅有 rootfs tar 不代表免 root 启动链路已完成。

## 1) debootstrap

```bash
# Debian/Ubuntu/WSL（x86 主机跑 arm64 需 qemu-user-static；原生 arm 机不需要）
sudo apt install debootstrap qemu-user-static
sudo debootstrap --arch=arm64 --foreign noble rootfs http://ports.ubuntu.com/ubuntu-ports
# x86 主机：第二阶段进 qemu
sudo cp /usr/bin/qemu-aarch64-static rootfs/usr/bin/
sudo chroot rootfs /debootstrap/debootstrap --second-stage
```

## 2) 精简 + 装运行时（chroot 内）

```bash
sudo chroot rootfs /bin/bash -c '
apt update &&
apt install -y --no-install-recommends python3 python3-pip python3-venv \
    git openssh-client nodejs npm ca-certificates &&
pip3 install --break-system-packages fastapi uvicorn &&
rm -rf /var/lib/apt/lists/* /tmp/*'
```

## 3) 放入 forge + 桥

```bash
sudo mkdir -p rootfs/opt/forge
sudo cp -r ../pledge-evolving/forge  rootfs/opt/forge/forge
sudo cp -r ../pledge-evolving/bundles rootfs/opt/forge/bundles
sudo cp -r ../pledge-evolving/run.py  rootfs/opt/forge/
sudo cp bridge/forge_bridge.py bridge/bridge_tools.py rootfs/opt/forge/
sudo chroot rootfs python3 -c "import sys; sys.path.insert(0,\"/opt/forge\"); import forge; print(\"forge ok\")"
```

## 4) PRoot 适配检查

- `/etc/resolv.conf` 可写（PRoot 下由宿主网络决定；离线场景不需要）
- 不含 setuid 二件套（PRoot 不支持 setuid；apt 在 PRoot 内装包正常但慢）
- 镜像内路径**无中文**（forge 已全相对路径化，2026-10-23 审计过）

## 5) 打 tar

```bash
sudo tar -C rootfs -cpf forge-rootfs-arm64.tar .
ls -lh forge-rootfs-arm64.tar    # 预期 110~160MB（含 python3）
```

放 `app/src/main/assets/forge-rootfs-arm64.tar`。
**>100MB 时**：Gradle `noCompress tar` 已配；若仍超限，改首启从局域网/USB 下载到 `filesDir`（代码路径不变，ChrootManager/ProotManager 都支持 filesDir 镜像）。

## 6) 可选：llama.cpp 本地推理

```bash
sudo chroot rootfs /bin/bash -c '
apt install -y --no-install-recommends build-essential cmake &&
git clone --depth 1 https://github.com/ggml-org/llama.cpp /tmp/llama &&
cmake -S /tmp/llama -B /tmp/llama/build -DLLAMA_CUDA=OFF -DLLAMA_METAL=OFF \
      -DGGML_NATIVE=ON && cmake --build /tmp/llama/build -j$(nproc) &&
cp /tmp/llama/build/bin/llama-server /usr/local/bin/ && rm -rf /tmp/llama'
```

模型 GGUF 放 `/opt/models/`（APK 外置，用户 adb push 或首启下载）。
启动：`llama-server -m /opt/models/<model>.gguf --port 8101 --host 127.0.0.1`，
forge provider `service: ollama` 分支同款接法（baseURL 指 8101）。

## 体积预期

| 内容 | 体积 |
|---|---|
| Ubuntu noble 最小 + python3/pip/git | ~95MB |
| fastapi/uvicorn（wheel 纯 py） | ~8MB |
| forge 全仓 | ~2MB |
| nodejs/npm（可选，Agent 要写前端时才装） | +45MB |
| llama.cpp（可选） | +6MB（二进制） |
