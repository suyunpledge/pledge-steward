package com.forge.android.bridge

import java.io.File
import java.nio.file.Files
import java.util.Comparator

/** Commit a validated image while preserving the previous image on failure. */
class RootfsStore(private val parent: File,
    private val rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) }) {
    val root = File(parent, "rootfs")
    private val backup = File(parent, "rootfs.previous")
    private val staging = File(parent, "rootfs.staging")

    companion object {
        private val processLock=java.util.concurrent.locks.ReentrantLock()
        fun deleteTree(file: File) {
            if (!Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
            Files.walk(file.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            }
        }
    }

    @Synchronized fun install(digest: String, unpack: (File) -> Unit): Boolean {
        require(digest.matches(Regex("[0-9a-f]{64}")))
        parent.mkdirs()
        processLock.lock()
        try {
        java.io.RandomAccessFile(File(parent, ".rootfs-install.lock"), "rw").use { lock ->
            lock.channel.lock().use {
                if (!root.exists() && backup.exists()) check(rename(backup, root)) { "Cannot recover previous image" }
                if (valid(root) && File(root, ".forge-extracted").takeIf { it.exists() }?.readText()==digest) {
                    runCatching { deleteTree(backup) }
                    return true
                }
                deleteTree(staging); staging.mkdirs()
                try {
                    unpack(staging)
                    check(valid(staging)) { "Image does not contain Python and Forge bridge" }
                    File(staging, ".forge-extracted").writeText(digest)
                    deleteTree(backup)
                    if (root.exists()) check(rename(root, backup)) { "Cannot preserve previous image" }
                    if (!rename(staging, root)) {
                        if (backup.exists()) check(rename(backup, root)) { "Recovery required: rootfs.previous retained" }
                        error("Image promotion failed; previous image restored")
                    }
                    // Image is committed; failed backup cleanup must not invalidate it.
                    runCatching { deleteTree(backup) }
                    return true
                } finally { deleteTree(staging) }
            }
        }
        } finally { processLock.unlock() }
    }

    private fun valid(dir: File) = File(dir,"usr/bin/python3.12").isFile && File(dir,"opt/forge/forge_bridge.py").isFile
}
