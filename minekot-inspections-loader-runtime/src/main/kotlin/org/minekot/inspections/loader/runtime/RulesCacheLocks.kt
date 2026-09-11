package org.minekot.inspections.loader.runtime

import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.createDirectories

/** Coordinates cache mutations across threads, cache instances, and processes. */
internal object RulesCacheLocks {
    private val processLocks = ConcurrentHashMap<Path, ReentrantLock>()

    /** Runs one mutation under process-local and operating-system file locks. */
    fun <T> withLock(root: Path, action: () -> T): T {
        val normalizedRoot = root.toAbsolutePath().normalize()
        normalizedRoot.createDirectories()
        return processLocks.computeIfAbsent(normalizedRoot) { ReentrantLock() }.withLock {
            FileChannel.open(
                normalizedRoot.resolve("cache.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            ).use { channel ->
                channel.lock().use { action() }
            }
        }
    }
}
