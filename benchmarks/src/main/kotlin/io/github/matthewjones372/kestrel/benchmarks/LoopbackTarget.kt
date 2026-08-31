package io.github.matthewjones372.kestrel.benchmarks

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.Timing
import io.github.matthewjones372.kestrel.timing
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The target an over-the-socket sweep points at: in this process, answering
 * from memory, so what a run adds over the null-action sweep is the client, the
 * loopback stack and this handler.
 *
 * It times itself, because the generator's numbers cannot say whether the
 * server was the bottleneck. What [served] measures starts when the server
 * handed the exchange to a handler, so time an accepted connection spent
 * queued ahead of that is not in it — a handler time that stays flat while the
 * generator's lateness climbs is evidence the client gave up first, not proof.
 */
class LoopbackTarget internal constructor(private val server: HttpServer) {

    // Striped and shared rather than one table per handler thread: the server
    // runs a new virtual thread for every exchange, so a thread-local table is
    // a forty-kilobyte allocation per request and a list that grows with the
    // run, which at a sweep's rates is the harness measuring itself.
    private val stripes = List(STRIPES) { Tally() }

    val baseUrl: String get() = "http://localhost:${server.address.port}/"

    /** How many counter tables the target holds, which a sweep's length must not move. */
    internal val tables: Int get() = stripes.size

    /** What the target itself took, added up across the stripes it was counted in. */
    fun served(): Timing = stripes
        .fold(Histogram()) { all, stripe -> all.also(stripe::mergeInto) }
        .timing()

    internal fun answer(exchange: HttpExchange) {
        val started = TimeSource.Monotonic.markNow()
        // Drained rather than ignored: an unread request body leaves the
        // connection unusable, and the sweep measures a pooled client reusing
        // its connections.
        exchange.requestBody.use { it.readAllBytes() }
        exchange.sendResponseHeaders(OK, BODY.size.toLong())
        exchange.responseBody.use { it.write(BODY) }
        // Read before the stripe is claimed, so waiting for one is not counted
        // as time the target took to answer.
        val took = started.elapsedNow()
        stripes[(Thread.currentThread().threadId() % STRIPES).toInt()].record(took)
    }
}

/**
 * One counter table and the lock several handler threads take to share it.
 * `ReentrantLock` rather than `synchronized`, which pins the carrier the
 * virtual thread running a handler is mounted on.
 */
private class Tally {
    private val lock = ReentrantLock()
    private val counted = Histogram()

    fun record(took: Duration) = lock.withLock { counted.record(took) }

    fun mergeInto(all: Histogram) = lock.withLock { all.merge(counted) }
}

/**
 * Starts a target, hands it to [block], and stops it however [block] ends, so a
 * sweep that throws does not leave a port bound for the next one.
 */
fun <T> loopback(block: (LoopbackTarget) -> T): T {
    // A backlog wide enough that a rate the sweep reaches cannot be refused at
    // the accept queue, and a virtual thread per exchange so the target is not
    // a fixed pool the sweep is really measuring.
    val server = HttpServer.create(InetSocketAddress("localhost", 0), BACKLOG)
    server.executor = Executors.newVirtualThreadPerTaskExecutor()
    val target = LoopbackTarget(server)
    server.createContext("/", target::answer)
    server.start()
    return try {
        block(target)
    } finally {
        server.stop(0)
    }
}

/**
 * Whether the target answered inside [budget], read at p99 rather than the
 * median: a tail the server produced lands in the client's tail, so a median
 * inside the budget says nothing about the row the sweep would publish.
 */
fun Timing.answeredWithin(budget: Duration = TARGET_BUDGET): Boolean = p99 <= budget

/**
 * How long the target may take before a row measures the server rather than the
 * client. Sized against 0011's one-millisecond budget for the generator: an
 * order of magnitude above it, because a handler slower than that is a
 * service time large enough to be what a rate is bounded by.
 */
val TARGET_BUDGET: Duration = 10.milliseconds

private const val OK = 200

private const val BACKLOG = 4_096

private val BODY = "{}".encodeToByteArray()

private const val PER_PROCESSOR = 8

/**
 * Enough stripes that two handlers rarely want the same one, and few enough
 * that they are a fixed cost of the target rather than a cost of the run.
 */
private val STRIPES: Int = Runtime.getRuntime().availableProcessors() * PER_PROCESSOR
