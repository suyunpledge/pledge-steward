# forge-android 一键部署脚本（Termux 免 root 路径 · 备选安装方式）

> 主路径是 APK（见 ../README.md）。本脚本是免安装的快速验证路径：
> 手机已有 Termux 时，一条命令把 PRoot Ubuntu + forge + 桥拉起来，
> 与 APK 内 ProotManager 用同一套 rootfs 与同一个 forge_bridge。

用法（Termux 内）：
  pkg install wget proot-distro -y
  proot-distro install ubuntu           # 或导入自建 tar
  proot-distro login ubuntu -- bash /sdcard/forge-android/scripts/bootstrap.sh
