package com.forge.android.bridge

import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import java.io.File
import java.nio.file.Files

class RootfsStoreTest {
    private val old="a".repeat(64)
    private val fresh="b".repeat(64)
    private fun image(dir:File) {
        File(dir,"usr/bin").mkdirs();File(dir,"usr/bin/python3.12").writeText("python")
        File(dir,"opt/forge").mkdirs();File(dir,"opt/forge/forge_bridge.py").writeText("bridge")
    }
    private fun test(body:(File)->Unit) {
        val parent=Files.createTempDirectory("forge-rootfs-test").toFile()
        try { body(parent) } finally { RootfsStore.deleteTree(parent) }
    }
    @Test fun sameDigestDoesNotUnpackAgain()=test { p ->
        val store=RootfsStore(p);store.install(old,::image)
        assertTrue(store.install(old) {error("should not unpack")})
    }
    @Test fun failedUnpackPreservesOldImageAndUserData()=test { p ->
        val store=RootfsStore(p);store.install(old,::image)
        File(p,"workspace").mkdirs();File(p,"workspace/user.txt").writeText("keep")
        runCatching {store.install(fresh) {image(it);error("broken archive")}}
        assertEquals(old,File(store.root,".forge-extracted").readText())
        assertEquals("keep",File(p,"workspace/user.txt").readText())
        assertFalse(File(p,"rootfs.staging").exists())
    }
    @Test fun failedPromotionRestoresPreviousImage()=test {p ->
        RootfsStore(p).install(old,::image)
        val store=RootfsStore(p) {from,to -> if(from.name=="rootfs.staging") false else from.renameTo(to)}
        assertTrue(runCatching {store.install(fresh,::image)}.isFailure)
        assertEquals(old,File(store.root,".forge-extracted").readText())
    }
    @Test fun interruptedRenameRecoversPreviousImage()=test {p ->
        val store=RootfsStore(p);store.install(old,::image)
        assertTrue(store.root.renameTo(File(p,"rootfs.previous")))
        store.install(old) {error("must recover")}
        assertEquals(old,File(store.root,".forge-extracted").readText())
    }
    @Test fun invalidImageCannotReplaceGoodImage()=test {p ->
        val store=RootfsStore(p);store.install(old,::image)
        assertTrue(runCatching {store.install(fresh) {}}.isFailure)
        assertEquals(old,File(store.root,".forge-extracted").readText())
    }
    @Test fun cleanupDoesNotFollowSymlinks()=test {p ->
        val outside=File(p,"outside").apply {mkdirs()};File(outside,"keep").writeText("keep")
        val tree=File(p,"tree").apply {mkdirs()}
        try { Files.createSymbolicLink(File(tree,"link").toPath(),outside.toPath()) }
        catch(e:Exception) {assumeNoException(e)}
        RootfsStore.deleteTree(tree)
        assertEquals("keep",File(outside,"keep").readText())
    }
    @Test fun twoInstancesDoNotOverlapUnpack()=test {p ->
        val executor=java.util.concurrent.Executors.newFixedThreadPool(2)
        val start=java.util.concurrent.CountDownLatch(1)
        val unpacked=java.util.concurrent.atomic.AtomicInteger()
        try {
            val futures=(0..1).map {executor.submit<Boolean> {
                start.await()
                RootfsStore(p).install(old) {dir -> unpacked.incrementAndGet();Thread.sleep(50);image(dir)}
            }}
            start.countDown()
            futures.forEach { assertTrue(it.get(5,java.util.concurrent.TimeUnit.SECONDS)) }
            assertEquals(1,unpacked.get())
        } finally {executor.shutdownNow()}
    }
}
