package com.forge.android.shizuku

/**
 * IForgeTool — UserService AIDL 接口（编译期由 aidl 生成 Stub/Proxy；
 * 此处给出接口定义源，构建时放 aidl/ 目录同名文件：shizuku 的
 * UserService 走标准 AIDL 跨进程）。
 *
 * 文件：app/src/main/aidl/com/forge/android/shizuku/IForgeTool.aidl
 * 内容与本桩一致（aidl 语法）。
 */
interface IForgeTool {
    String shell(String command, int timeoutMs);
    String screencap(int maxSide);
    String uiDump();
    String packageList();
    boolean launchApp(String pkg);
    boolean inputTap(int x, int y);
    boolean inputSwipe(int x1, int y1, int x2, int y2, int durMs);
    boolean inputText(String text);
}
