/* Android host launcher for Termux's libbusybox. No shell command parsing. */
#include <dlfcn.h>
#include <stdio.h>

int main(int argc, char **argv) {
    void *library = dlopen("libbusybox-impl.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) { fprintf(stderr, "busybox load failed: %s\n", dlerror()); return 127; }
    /* libbusybox's public entry takes argv only, unlike a C main(argc, argv). */
    int (*entry)(char **) = (int (*)(char **))dlsym(library, "lbb_main");
    if (!entry) { fprintf(stderr, "busybox entry missing\n"); return 127; }
    /* BusyBox selects an applet from argv[0]; the APK filename has a lib prefix. */
    argv[0] = "busybox";
    (void)argc;
    return entry(argv);
}
