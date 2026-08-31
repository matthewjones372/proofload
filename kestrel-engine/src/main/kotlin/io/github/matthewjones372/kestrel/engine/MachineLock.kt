package io.github.matthewjones372.kestrel.engine

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * The machine, held against every other process on this host until [close].
 *
 * [waited] says whether anybody else had it, so a run that walked straight in
 * is not reported as having queued.
 */
internal class MachineLock(private val channel: FileChannel, private val lock: FileLock, val waited: Boolean) :
    AutoCloseable {

    override fun close() {
        lock.release()
        channel.close()
    }
}

/**
 * The machine, or `null` where this host will not lend a lock for it.
 *
 * A `null` is a downgrade rather than a failure: a read-only temp directory, a
 * filesystem that does not implement locking and a file another user owns all
 * leave the run exclusive within this JVM, and say so once. A load test that
 * died because it could not create a lock file is a tool people stop using,
 * which is the whole of why this answers with nothing instead of throwing.
 */
internal fun takeTheMachine(): MachineLock? {
    val file = lockFile()
    val channel = try {
        FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    } catch (unusable: IOException) {
        return degraded(file, unusable)
    }
    return try {
        channel.take(file)
    } catch (unusable: IOException) {
        channel.close()
        degraded(file, unusable)
    }
}

/**
 * The lock on this channel, waiting for whoever has it.
 *
 * The holder's process id goes into the file once it is held, so the next run
 * to give up waiting can say what it was waiting for.
 */
private fun FileChannel.take(file: Path): MachineLock {
    val free = tryLock()
    val held = free ?: waitFor(file)
    truncate(0)
    write(ByteBuffer.wrap("${ProcessHandle.current().pid()}\n".toByteArray(StandardCharsets.UTF_8)), 0)
    return MachineLock(this, held, waited = free == null)
}

/**
 * The lock, once whoever holds it is done, or a failure naming them once the
 * ceiling is up.
 *
 * The wait is on a platform thread of its own for both halves of that: a
 * blocked `FileLock` pins the carrier under a virtual thread, and handing it
 * away is the only way to bound a call the JDK gives no timeout for. An hour of
 * queued runs is indistinguishable from a lock nobody will ever release, so the
 * wait ends by saying who has it rather than going on in silence.
 */
private fun FileChannel.waitFor(file: Path): FileLock {
    val ceiling = ceiling()
    val taken = CompletableFuture.supplyAsync({ lock() }, waiters)
    return try {
        taken.get(ceiling.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    } catch (queuedTooLong: TimeoutException) {
        // Closing calls the wait off: the thread blocked in `lock()` fails
        // rather than being granted a lock nobody is left to release.
        close()
        throw IllegalStateException("waited $ceiling for the machine, held by ${holderOf(file)}", queuedTooLong)
    } catch (failed: ExecutionException) {
        close()
        throw failed.cause ?: failed
    }
}

/** Whoever wrote their process id into [file], where the platform lets a locked file be read. */
private fun holderOf(file: Path): String = try {
    Files.readString(file).trim().ifEmpty { "a process that had not named itself in $file" }
} catch (unreadable: IOException) {
    "a process $file could not be read to name ($unreadable)"
}

/**
 * A fixed name under `java.io.tmpdir`, unless `kestrel.exclusive.file` names
 * another path.
 *
 * Every JDK names a temp directory on Linux, macOS and Windows and it is
 * writable by whoever is running, so the lock exists without anybody
 * configuring it — which is the setup this design spends a file to avoid. One
 * name for the host means two people load-testing it queue for each other; two
 * containers have a temp directory each and so cannot serialise, which nothing
 * in a file here can fix.
 */
private fun lockFile(): Path =
    System.getProperty(FILE)?.let(Path::of) ?: Path.of(System.getProperty("java.io.tmpdir"), NAME)

/**
 * How long a run queues before it gives up, from `kestrel.exclusive.timeout` in
 * seconds.
 *
 * An hour by default: long enough to wait out a soak that is ahead in the
 * queue, short enough that a lock nobody will release is named rather than
 * waited on until somebody kills the build.
 */
private fun ceiling(): Duration = System.getProperty(TIMEOUT)?.toLongOrNull()?.seconds ?: 1.hours

/**
 * Says, once per lock file, that runs are exclusive within this JVM only.
 *
 * Once, because a host that will not lend a lock will not lend one for the next
 * run either; per file, because a path that changed is a different answer.
 */
private fun degraded(file: Path, why: IOException): MachineLock? {
    if (mentioned.add(file)) {
        println(
            "kestrel: could not lock $file ($why), so runs are one at a time in this JVM " +
                "and not across this machine",
        )
    }
    return null
}

private val mentioned = ConcurrentHashMap.newKeySet<Path>()

/**
 * Where a wait for another process happens: platform threads, since a blocked
 * `FileLock` pins the carrier under a virtual one, and daemon threads, so a run
 * that gave up waiting cannot hold the JVM open behind it.
 */
private val waiters = Executors.newCachedThreadPool { waiting ->
    Thread.ofPlatform().daemon().name("kestrel-machine-", 0).unstarted(waiting)
}

private const val FILE = "kestrel.exclusive.file"

private const val TIMEOUT = "kestrel.exclusive.timeout"

private const val NAME = "kestrel-machine.lock"
