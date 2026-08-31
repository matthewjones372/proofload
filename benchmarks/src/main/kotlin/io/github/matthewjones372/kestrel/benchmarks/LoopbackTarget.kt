package io.github.matthewjones372.kestrel.benchmarks

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.Timing
import io.github.matthewjones372.kestrel.timing
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
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

    // One histogram per handler thread, merged on read: a shared one would put
    // a lock on the path being timed, which is the service time being reported.
    private val everyThread = CopyOnWriteArrayList<Histogram>()
    private val mine = ThreadLocal.withInitial { Histogram().also(everyThread::add) }

    val baseUrl: String get() = "http://localhost:${server.address.port}/"

    /**
     * What the target itself took, read once the traffic has stopped: the
     * per-thread tables are merged without locking them, so a read taken while
     * handlers are still writing can miss a sample they are recording.
     */
    fun served(): Timing = everyThread
        .fold(Histogram()) { all, thread -> all.also { it.merge(thread) } }
        .timing()

    internal fun answer(exchange: HttpExchange) {
        val started = TimeSource.Monotonic.markNow()
        // Drained rather than ignored: an unread request body leaves the
        // connection unusable, and the sweep measures a pooled client reusing
        // its connections.
        exchange.requestBody.use { it.readAllBytes() }
        exchange.sendResponseHeaders(OK, BODY.size.toLong())
        exchange.responseBody.use { it.write(BODY) }
        mine.get().record(started.elapsedNow())
    }
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
