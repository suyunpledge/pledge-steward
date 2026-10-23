package com.forge.android.shizuku;
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
