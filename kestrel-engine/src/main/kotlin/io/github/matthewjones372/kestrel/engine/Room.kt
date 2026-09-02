package io.github.matthewjones372.kestrel.engine

import com.sun.management.OperatingSystemMXBean
import com.sun.management.UnixOperatingSystemMXBean
import io.github.matthewjones372.kestrel.Headroom
import io.github.matthewjones372.kestrel.Limits
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How close this process came to its own ceilings while the run was measuring
 * the target.
 *
 * Sampled rather than counted per request, on the pattern [Hiccups] uses and
 * for its reason: a syscall on the departure path would be a cost the report
 * then reads as the target's latency. A second between reads, because these
 * are file reads and a limit is approached over seconds rather than
 * microseconds.
 */
internal class Room(private val sources: List<Source>) {

    private val peaks = sources.map { AtomicLong(0L) }

    /** Reads every source once, keeping the highest each has reached. */
    fun observe() {
        sources.forEachIndexed { index, source ->
            source.read()?.let { peaks[index].accumulateAndGet(it, ::maxOf) }
        }
    }

    fun frozen(): Limits {
        val readings = sources
            .mapIndexed { index, source -> source.name to source.headroom(peaks[index].get()) }
            .toMap()
        return Limits(
            openFiles = readings[OPEN_FILES] ?: notThere(OPEN_FILES),
            ports = readings[PORTS] ?: notThere(PORTS),
            cpu = readings[CPU] ?: notThere(CPU),
        )
    }

    private fun notThere(what: String): Headroom = Headroom.Absent("this platform exposes no $what")

    /** One thing that can be read repeatedly and has a ceiling to be read against. */
    internal interface Source {

        val name: String

        /** What it stands at now, or nothing where this read failed. */
        fun read(): Long?

        /** The peak against the ceiling, or why there is no reading. */
        fun headroom(peak: Long): Headroom
    }

    companion object {
        const val OPEN_FILES: String = "open files"
        const val PORTS: String = "ephemeral ports"
        const val CPU: String = "CPU"
    }
}

/** A room sampler that is running, and the executor it runs on. */
internal class RoomWatch(private val executor: ScheduledExecutorService, private val room: Room) {

    fun stop(): Limits {
        executor.shutdownNow()
        // The edge that publishes what the sampling thread wrote, as the
        // hiccup watch does.
        executor.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS)
        return room.frozen()
    }
}

/**
 * Starts sampling this process's own ceilings until the returned watch is
 * stopped.
 *
 * Nothing on the timed path touches any of it: the reads run on an executor of
 * their own, no departure or step is submitted to it, and the peaks are read
 * only once it has terminated.
 */
internal fun watchForRoom(
    interval: Duration = ROOM_TICK,
    sources: List<Room.Source> = available(),
    executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(::roomThread),
): RoomWatch {
    val room = Room(sources)
    // Once immediately, so a run shorter than the interval still reports
    // something rather than a peak of zero it never looked for.
    room.observe()
    executor.scheduleAtFixedRate(
        { room.observe() },
        interval.inWholeNanoseconds,
        interval.inWholeNanoseconds,
        TimeUnit.NANOSECONDS,
    )
    return RoomWatch(executor, room)
}

/** The sources this platform actually has, asked once at the start of a run. */
internal fun available(): List<Room.Source> = listOfNotNull(descriptors(), ports(), cpu())

/**
 * Open descriptors against this JVM's own limit, from the bean the JDK exposes
 * only on Unix. An `is` check rather than a class name, so a JVM without it
 * simply has no source.
 */
private fun descriptors(): Room.Source? {
    val bean = ManagementFactory.getOperatingSystemMXBean() as? UnixOperatingSystemMXBean ?: return null
    return object : Room.Source {
        override val name: String get() = Room.OPEN_FILES
        override fun read(): Long? = bean.openFileDescriptorCount.takeIf { it >= 0 }
        override fun headroom(peak: Long): Headroom {
            val limit = bean.maxFileDescriptorCount
            return if (limit <= 0L) Headroom.Absent("this JVM reports no descriptor limit")
            else Headroom.Measured(peak, limit)
        }
    }
}

/**
 * Sockets in TIME_WAIT against the ephemeral port range, both read from
 * `/proc`, so Linux only.
 *
 * Machine-wide rather than this process's: `tw` counts every socket on the
 * host, and a neighbour's TIME_WAIT lands here. The alternative is matching
 * `/proc/self/fd` inodes against `/proc/net/tcp`, which is tens of thousands
 * of lines a second on the path this exists to stay off. The range is
 * machine-wide too, so the reading and the ceiling at least describe the same
 * thing — and the report says which.
 */
private fun ports(): Room.Source? {
    val range = read(PORT_RANGE)?.trim()?.split(Regex("\\s+"))?.mapNotNull(String::toLongOrNull) ?: return null
    if (range.size != 2) return null
    val available = range[1] - range[0] + 1
    if (available <= 0L) return null
    return object : Room.Source {
        override val name: String get() = Room.PORTS
        override fun read(): Long? = read(SOCKSTAT)
            ?.lineSequence()
            ?.firstOrNull { it.startsWith("TCP:") }
            ?.let { TIME_WAIT.find(it)?.groupValues?.get(1)?.toLongOrNull() }

        override fun headroom(peak: Long): Headroom = Headroom.Measured(peak, available)
    }
}

/**
 * This process's share of the machine, in hundredths of a core, against every
 * core it could have.
 *
 * Reported and never gated on: a generator sharing its cores with the target
 * is the ordinary case on a laptop and on a single CI runner.
 */
private fun cpu(): Room.Source? {
    val bean = ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean ?: return null
    val cores = Runtime.getRuntime().availableProcessors().toLong()
    return object : Room.Source {
        override val name: String get() = Room.CPU
        override fun read(): Long? =
            bean.processCpuLoad.takeIf { it >= 0.0 }?.let { (it * cores * HUNDREDTHS).toLong() }

        override fun headroom(peak: Long): Headroom = Headroom.Measured(peak, cores * HUNDREDTHS_PER_CORE)
    }
}

private fun read(path: String): String? =
    runCatching { Files.readString(Path.of(path)) }.getOrNull()

private fun roomThread(runnable: Runnable): Thread =
    Thread(runnable, "kestrel-room").apply { isDaemon = true }

private const val PORT_RANGE = "/proc/sys/net/ipv4/ip_local_port_range"

private const val SOCKSTAT = "/proc/net/sockstat"

private val TIME_WAIT = Regex("""\btw (\d+)""")

/** Hundredths of a core, so one core fully used reads 100 rather than 1.0. */
private const val HUNDREDTHS = 100.0

private const val HUNDREDTHS_PER_CORE = 100L

/** A second: these are file reads, and a ceiling is approached over seconds. */
private val ROOM_TICK = 1.seconds

private const val SHUTDOWN_SECONDS = 5L
