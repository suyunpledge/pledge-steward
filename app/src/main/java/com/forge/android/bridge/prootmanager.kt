package com.forge.android.bridge

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ProotManager(private val context: Context) {
    private val store = RootfsStore(context.filesDir)
    @Volatile private var stopped=false
    private val extraction=AtomicReference<Process?>(null)
    val rootfsDir: File get() = store.root
    private val nativeDir get() = File(context.applicationInfo.nativeLibraryDir)
    private val assetsTar = "forge-rootfs-arm64.tar"
    private val required = listOf("libproot.so", "libproot-loader.so", "libbusybox.so", "libbusybox-impl.so", "libtalloc.so",
        "libandroid-shmem.so", "libandroid-selinux.so", "libpcre2-8.so")
    private val temp get() = File(context.cacheDir,"proot-tmp").apply { mkdirs() }

    private fun host(binary: String, args: List<String>): ProcessBuilder {
        check(!stopped) {"Runtime has been stopped"}
        check(required.all { File(nativeDir,it).isFile }) { "Native host libraries are incomplete; rebuild APK" }
        // Explicit system linker also supports BusyBox's executable shared object.
        return ProcessBuilder(listOf("/system/bin/linker64",File(nativeDir,binary).absolutePath)+args).apply {
            environment()["LD_LIBRARY_PATH"] = nativeDir.absolutePath
            environment().remove("LD_PRELOAD")
            environment()["PROOT_LOADER"] = File(nativeDir,"libproot-loader.so").absolutePath
            environment()["PROOT_TMP_DIR"] = temp.absolutePath
            environment()["TMPDIR"] = temp.absolutePath
        }
    }

    fun extractIfNeeded(onProgress: (Int) -> Unit = {}): Boolean = try {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        context.assets.open(assetsTar).use { input ->
            val bytes=ByteArray(65536)
            while (true) { check(!stopped);val n=input.read(bytes);if(n<0)break;digest.update(bytes,0,n) }
        }
        val hash=digest.digest().joinToString("") { "%02x".format(it) }
        migrateLegacy("workspace", "workspace")
        migrateLegacy(".forge-home", "forge-home")
        store.install(hash) { destination ->
            val process=host("libbusybox.so",listOf("tar","-xf","-","-C",destination.absolutePath))
                .redirectErrorStream(true).start()
            extraction.set(process)
            if(stopped) process.destroyForcibly()
            val error=AtomicReference<Throwable?>(null)
            val diagnostic=StringBuilder()
            val drain=Thread {
                process.inputStream.bufferedReader().useLines { lines -> lines.forEach {
                    synchronized(diagnostic) { if(diagnostic.length<4096) diagnostic.append(it.take(500)).append('\n') }
                } }
            }.apply { start() }
            val feeder=Thread {
                try {
                    val size=context.assets.openFd(assetsTar).use { it.length }.coerceAtLeast(1)
                    context.assets.open(assetsTar).use { input -> process.outputStream.use { output ->
                        val bytes=ByteArray(65536);var total=0L
                        while(true) {check(!stopped);val n=input.read(bytes);if(n<0)break;output.write(bytes,0,n);total+=n
                            onProgress((total*100/size).toInt().coerceIn(0,99))}
                    } }
                } catch(e:Throwable) {error.set(e);runCatching {process.outputStream.close()}}
            }.apply { start() }
            try {
                check(process.waitFor(600,TimeUnit.SECONDS)) {"Image extraction timed out"}
                feeder.join(2000);drain.join(2000)
                check(!feeder.isAlive && error.get()==null && process.exitValue()==0) {
                    "Image extraction failed: ${error.get()?.message ?: diagnostic.toString()}"
                }
            } finally {
                extraction.compareAndSet(process,null)
                if(process.isAlive) process.destroyForcibly()
                runCatching {process.outputStream.close()};runCatching {process.inputStream.close()}
                feeder.join(2000);drain.join(2000)
            }
        }.also { if(it)onProgress(100) }
    } catch(e:Exception) {Log.e("ProotManager","Image setup failed",e);false}

    private fun migrateLegacy(oldName: String, newName: String) {
        val old=File(rootfsDir,"opt/forge/$oldName")
        val target=File(context.filesDir,newName)
        if(old.exists() && old.listFiles()?.isNotEmpty()==true) {
            check(old.canonicalPath.startsWith(rootfsDir.canonicalPath+File.separator)) {"Unsafe legacy state path"}
            check(!target.exists() || target.listFiles()?.isEmpty()==true) {"Both legacy and external state contain files; migration required"}
            if(target.exists()) check(target.delete())
            check(old.renameTo(target)) {"Cannot preserve legacy state"}
        }
        target.mkdirs()
    }

    fun exec(command: List<String>): Process = host("libproot.so",listOf(
        "-0","-r",rootfsDir.absolutePath,"-b","/proc","-b","/sys","-b","/dev",
        "-b","${File(context.filesDir,"workspace").absolutePath}:/workspace",
        "-b","${File(context.filesDir,"forge-home").absolutePath}:/forge-home",
        "-w","/opt/forge","/usr/bin/env","-u","LD_LIBRARY_PATH","-u","LD_PRELOAD",
        "HOME=/root","PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin","TMPDIR=/tmp",
        "FORGE_ROOT=/opt/forge","FORGE_HOME=/forge-home","FORGE_WORKSPACE=/workspace")+command).apply {
            environment()["FORGE_BRIDGE_TOKEN"] = BridgeToken.get(context)
        }.start()

    fun startBridge(): Process = exec(listOf("/usr/bin/python3","-m","uvicorn","forge_bridge:app",
        "--host","127.0.0.1","--port","8000","--app-dir","/opt/forge","--loop","asyncio"))
    fun stop() { stopped=true;extraction.getAndSet(null)?.destroyForcibly() }
}
