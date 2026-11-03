# arm64 rootfs 构建（历史兼容路径）

Android 主路径已改为 Kotlin NativeAgentEngine，不再解包或启动此 rootfs。
本文件仅供桌面/Termux 兼容路径维护旧 Forge Python 内核。

以 `scripts/build-rootfs.sh` 为可执行依据。在独立 Ubuntu WSL 发行版中，以 root 运行：

```bash
bash /mnt/c/Users/<user>/pledge-steward/scripts/build-rootfs.sh
```

固定构建目录 `/root/forge-arm64-build/rootfs`；首次要求目录不存在，失败保留现场。
脚本安装 debootstrap/QEMU，执行 arm64 noble foreign + second stage，安装 python3 与 CA 证书。
HTTP/WebSocket 不再依赖 Python 包，由 APK 内的 Kotlin NativeBridge 提供。
复制 `forge-pin.json` 固定的 forge 内核源码树（`scripts/fetch-forge.py` 产物），排除 `.git` 与旧 `forge-android` 目录，再铺入 `forge_worker.py` 与 `bridge_tools.py`。其余 HTTP、目录、引导和遥控逻辑均由 Kotlin 实现。

已完成 debootstrap 的镜像只刷新源码时：

```bash
bash /mnt/c/Users/<user>/pledge-steward/scripts/build-rootfs.sh --repack
```

该路径验证已有 Python/dpkg，重新注册 binfmt、以 staging 替换镜像内全仓源码，避免删除文件残留。
不会更新已有软件包，也不能用于恢复未完成的 second stage。
每次打包执行 arm64 Python 实际导入与 Forge Worker stdio 就绪检查。
运行镜像中不包含宿主 QEMU 二进制。tar 排除 dev/proc/sys/run 内容，清理 apt 下载和日志。

输出 `app/src/main/assets/forge-rootfs-arm64.tar` 和 `.sha256`。
内核源码与 Ubuntu 软件包使镜像约 200MB；不能沿用早期 120MB 估算。
APK 的 tar 使用 ZIP_STORED，便于流式解包。完整体积和哈希以本次审查记录为准。

镜像内 `/workspace` 与 `/forge-home` 是绑定点；安装后分别绑定到 App 私有 filesDir 的独立目录。
替换 rootfs 不清空项目、会话和用户配置。镜像本身不携带桌面模型密钥。
当前精简镜像未安装 Git/SSH/Node/npm/llama.cpp；需要这些能力时应单独添加并验证。

Android 宿主运行还需要 [8 个原生文件](NATIVE-RUNTIME.md)。Ubuntu 的 Linux 可执行文件不能代替 Android bionic 宿主文件。
WSL/QEMU 中通过检查，只代表 Linux arm64 用户态验证通过；Android PRoot/SELinux 行为须另做真机验收。
