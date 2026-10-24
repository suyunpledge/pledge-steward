# Android arm64 宿主依赖

`scripts/fetch-host-bins.py` 从 Termux 官方 HTTPS 仓库读取 Packages.gz，首次生成版本锁；
之后默认严格使用 `scripts/native-host-lock.json` 中的版本、包路径和 SHA-256。
只有显式 `--refresh` 才更新锁。下载缓存被 Git 忽略。

来自 6 个软件包的 7 个文件，加一个 NDK 编译的最小启动器：

| APK 文件 | 来源 |
|---|---|
| libproot.so / libproot-loader.so | proot 5.1.107.100，bin/proot 和 libexec/proot/loader |
| libbusybox-impl.so | busybox 1.38.0-1，lib/libbusybox.so.*；ELF 入口为0，不能直接执行 |
| libbusybox.so | 本仓 app/src/main/cpp/busybox-launcher.c，通过 dlopen/dlsym 调用 lbb_main，显式设置 argv[0]=busybox |
| libtalloc.so | libtalloc 2.5.0 |
| libandroid-shmem.so | libandroid-shmem 0.7 |
| libandroid-selinux.so | libandroid-selinux 14.0.0.11-1 |
| libpcre2-8.so | pcre2 10.49 |

每个成员记录原始与最终 SHA-256、ELF DT_NEEDED/SONAME。解析器拒绝非 AArch64 ELF64。
为适应 APK 只提取 lib*.so，在 .dynstr 中把 libtalloc.so.2、libpcre2-8.so.0、libbusybox.so.1.38.0 原位缩短为上述文件名；
不改变段偏移和指令，SONAME 与引用同时调整。不是原样上游二进制。
检查 DT_NEEDED 闭包，只有 libc/libm/libdl/liblog 允许来自系统；三个可执行文件额外要求非零入口。
启动器的动态加载目标也必须在打包清单中，源文件哈希和 NDK 版本写入锁文件。
当前使用 NDK 26.1.10909125，API26，PIE；fetch 脚本要求 --ndk 或 ANDROID_NDK_HOME。
函数原型遵循 [BusyBox appletlib.c](https://github.com/mirror/busybox/blob/master/libbb/appletlib.c) 的 `int lbb_main(char **argv)`，并核对当前共享库导出的 AArch64 指令参数使用。

实际 proot PT_INTERP 为 `/system/bin/linker64`；旧灰度报告所述 Termux 路径不符合当前二进制。
启动显式使用系统 linker64，LD_LIBRARY_PATH 指向 nativeLibraryDir，PROOT_LOADER 指向打包的独立 loader。
LD_PRELOAD 清空，进入 Linux guest 后清除宿主库路径。没有引入 libtermux-exec。

来源与构建配方：

- [Termux 主仓](https://packages.termux.dev/apt/termux-main/)
- [Termux packages / proot](https://github.com/termux/termux-packages/tree/master/packages/proot)
- [Termux packages / busybox](https://github.com/termux/termux-packages/tree/master/packages/busybox)
- [Termux packages](https://github.com/termux/termux-packages/tree/master/packages) 中其余同名包配方。
- [Termux 执行环境说明](https://github.com/termux/termux-packages/wiki/Termux-execution-environment)

锁文件校验不等于 Android 真机验证，也不等于所有原生依赖已满足商店分发要求。
正式分发前须按各上游许可证整理版权声明与对应源码材料，并验证 Android 最低系统版本、16KB 页和 SELinux 兼容性。
当前仅制作本地开发 debug 构建。
