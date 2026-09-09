package io.github.matthewjones372.proofload.http

import io.github.matthewjones372.proofload.Reason
import io.github.matthewjones372.proofload.ScenarioBuilder
import io.github.matthewjones372.proofload.SessionKey
import io.github.matthewjones372.proofload.StepName
import io.github.matthewjones372.proofload.StepScope
import io.github.matthewjones372.proofload.Threw
import io.github.matthewjones372.proofload.TimedOut
import io.github.matthewjones372.proofload.sessionKey
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.toJavaDuration

/**
 * A step that needed a stream nothing had opened: a `firstEvent` with no `open`
 * before it, or one whose `open` failed and left the user without a feed.
 */
data object NotStreaming : Reason {
    override val described: String get() = "not streaming"
}

/** The far end completed the response before the events a step was waiting for arrived. */
data object StreamEnded : Reason {
    override val described: String get() = "stream ended"
}

/**
 * A `cadence` before any `firstEvent` took the round trip.
 *
 * The one mistake the split exists to prevent: the first event of a feed is
 * measured from the request that opened it, and handed out as a gap it would be
 * a number no reader of the report could catch.
 */
data object NoFirstEvent : Reason {
    override val described: String get() = "no first event"
}

/** Where [open] leaves the stream and the reading verbs go looking for it. */
val eventStream: SessionKey<EventStream> = sessionKey("proofload.http.eventStream")

/**
 * One user's open feed.
 *
 * Per user rather than pooled, which is the opposite of this module's shared
 * client and for the same reason `proofload-websocket` opens one socket each: a
 * feed test is about how many streams a target holds open, so sharing one would
 * remove what is being measured.
 */
class EventStream internal constructor(internal val events: Events) {

    /** Events this feed has delivered, comments not among them. */
    val delivered: Long get() = events.delivered

    /**
     * Comment lines the feed sent, counted and never timed.
     *
     * A heartbeat is how a feed keeps a connection alive while it has nothing
     * to say. Counted as an event it would let a silent target report as a
     * busy one, so it satisfies no wait and lands in no histogram.
     */
    val heartbeats: Long get() = events.heartbeats

    internal fun stop() = events.cancel()
}

/**
 * The request that opens a feed, as one timed step.
 *
 * The sample is the exchange up to the response head: it starts when the
 * request leaves and ends when the target agrees to stream. It is not a first
 * event and not a first byte of one — nothing has been asked for at this point,
 * and a feed that opens instantly and then says nothing is exactly the run this
 * step cannot tell you about. [firstEvent] is the one that can.
 */
fun ScenarioBuilder.open(name: StepName, target: SseTarget) {
    exec(name) { openTo(target) }
}

private fun StepScope.openTo(target: SseTarget) {
    val events = Events()
    val request = HttpRequest.newBuilder(URI.create(target.url))
        .header("accept", "text/event-stream")
        // A feed is long-lived by definition, so the request timeout that
        // bounds a round trip would end every stream that worked. What bounds
        // a wait here is the `within` on the step doing the waiting.
        .timeout(streamTimeout.toJavaDuration())
        .apply { tracing(target.traced, this@openTo).forEach { (name, value) -> header(name, value) } }
        .GET()
        .build()

    // The type is the JDK's `HttpResponse<Void>`, left inferred: a body
    // handler that hands every line to a subscriber answers with no body.
    val sending = sharedClient.sendAsync(request) { head ->
        events.opened(head.statusCode())
        HttpResponse.BodySubscribers.fromLineSubscriber(events)
    }
    // Whatever ends the exchange ends the wait: a body that completed, a far
    // end that let go, a connection nobody accepted.
    sending.whenComplete { _, failure -> events.finished(failure) }

    when (val why = events.awaitOpen(handshakeTimeout)) {
        null -> set(eventStream, EventStream(events))
        else -> fail(why)
    }
}

/**
 * The first event of a feed, as one sample measured from the request that
 * opened it.
 *
 * A round trip, and named as one: it is comparable with a plain request's
 * latency. The gaps after it are [cadence]'s, and are a different number.
 */
fun ScenarioBuilder.firstEvent(name: StepName, within: Duration) {
    exec(name) { firstEventOn(within) }
}

private fun StepScope.firstEventOn(within: Duration) {
    val open = this[eventStream] ?: return fail(NotStreaming)
    val why = open.events.awaitDelivered(1L, within)
    open.events.tookFirst()
    open.events.take(1L).forEach { sample(it) }
    why?.let { reason ->
        // The failure is a sample of its own, timed from the request: how long
        // the feed had been open with nothing said on it. Without it the
        // failure would go unrecorded wherever an event did arrive, the engine
        // recording nothing for a body that reported its own samples.
        sample(open.events.sinceLastEvent(), reason = reason)
        fail(reason)
    }
}

/**
 * [count] more events, each measured from the event before it.
 *
 * The cadence the feed delivered at, which is what a stream is for. `count` is
 * events, so a hundred-event read is [firstEvent] and `cadence(count = 99)`.
 *
 * Refused before any [firstEvent], rather than quietly handing out the round
 * trip as the first gap.
 */
fun ScenarioBuilder.cadence(name: StepName, count: Int, within: Duration) {
    require(count > 0) { "a step cannot wait for $count events" }
    exec(name) { cadenceOn(count.toLong(), within) }
}

private fun StepScope.cadenceOn(count: Long, within: Duration) {
    val open = this[eventStream] ?: return fail(NotStreaming)
    if (!open.events.firstTaken) return fail(NoFirstEvent)
    val why = open.events.awaitDelivered(count, within)
    open.events.take(count).forEach { sample(it) }
    why?.let { reason ->
        sample(open.events.sinceLastEvent(), reason = reason)
        fail(reason)
    }
}

/**
 * Stops reading, and drops the connection under it.
 *
 * Not `close`: SSE negotiates no closing handshake, so there is nothing to send
 * and nothing to wait for. The sample is the cancel, which happens in this
 * process and takes about as long as a method call — a number about the
 * generator rather than about the target, and here so that the users who
 * finished a feed are counted somewhere.
 */
fun ScenarioBuilder.stopReading(name: StepName) {
    exec(name) { stopReadingOn() }
}

private fun StepScope.stopReadingOn() {
    val open = this[eventStream] ?: return fail(NotStreaming)
    open.stop()
}

/**
 * How long the request that opens a feed is given before it is a failure rather
 * than a slow success.
 */
internal val handshakeTimeout: Duration = requestTimeout.toKotlinDuration()

/**
 * The bound on the whole exchange, which for a feed is a bound on how long it
 * may stream rather than on how long it may take to answer.
 *
 * A day rather than none at all: the JDK's default is no timeout, and a run
 * that leaks a stream would hold the connection for the life of the process.
 */
private val streamTimeout: Duration = kotlin.time.Duration.parse("PT24H")

private fun java.time.Duration.toKotlinDuration(): Duration = toNanos().nanoseconds

/**
 * The lines of one feed, as they arrive.
 *
 * Shared between the user that waits and the client's thread that reads the
 * body, so it is the one structure here that is thread-safe. Nothing on the
 * client's thread touches a recorder — the gaps are computed here and drained
 * on the user's own thread — because the recorder is sharded per user rather
 * than locked.
 *
 * The frame fields are read only far enough to find where one event ends and
 * the next begins. Nothing keeps them: an event value per frame is an
 * allocation per event for something no step here reads.
 */
internal class Events : Flow.Subscriber<String> {

    private val origin = System.nanoTime()

    private val ended = CountDownLatch(1)

    private val began = CountDownLatch(1)

    private val status = AtomicLong(NOTHING_YET)

    private val failure = AtomicReference<Reason?>()

    private val subscription = AtomicReference<Flow.Subscription?>()

    // The gaps, oldest first: the first is measured from the request and every
    // one after it from the event before. Drained on the user's own thread.
    private val gaps = ConcurrentLinkedQueue<Duration>()

    private val seen = AtomicLong()

    private val comments = AtomicLong()

    // When the last event arrived, on this stream's own clock. Zero until one
    // does, and zero is when the request went out.
    private val lastEvent = AtomicLong()

    // What a step has already waited for, touched by that user's thread alone:
    // two reading steps in one scenario wait for their own events rather than
    // both being satisfied by the first one's.
    private val awaited = AtomicLong()

    private val first = AtomicBoolean()

    // The frame being accumulated. Touched only by the client's thread, which
    // the Flow contract says is one thread at a time, so no lock guards it.
    private var data = false

    private val gate = ReentrantLock()

    private val arrived = gate.newCondition()

    internal val delivered: Long get() = seen.get()

    internal val heartbeats: Long get() = comments.get()

    internal val firstTaken: Boolean get() = first.get()

    internal fun tookFirst() {
        first.set(true)
    }

    /** Called on the client's thread the moment the response head is in. */
    internal fun opened(code: Int) {
        status.set(code.toLong())
        began.countDown()
    }

    /** Called when the exchange ends, however it ended. */
    internal fun finished(why: Throwable?) {
        why?.let { failure.compareAndSet(null, it.unwrapped()) }
        ended.countDown()
        began.countDown()
        signal()
    }

    /**
     * Why the feed did not open, or null where it did.
     *
     * A status the caller did not ask for is a failure here rather than a
     * stream nobody can read: a 404 answered with an HTML page is not a feed,
     * and reading it as one would report a timeout for a mistake in a path.
     */
    internal fun awaitOpen(within: Duration): Reason? {
        if (!began.await(within.inWholeMilliseconds, TimeUnit.MILLISECONDS)) return TimedOut
        failure.get()?.let { return it }
        val code = status.get()
        if (code == NOTHING_YET) return TimedOut
        return if (code in OK_FROM until OK_UNTIL) null else HttpStatus(code.toInt())
    }

    /** [count] events beyond the ones a step has already waited for, or why they did not arrive. */
    internal fun awaitDelivered(count: Long, within: Duration): Reason? {
        val target = awaited.get() + count
        try {
            waitFor(target, System.nanoTime() + within.inWholeNanoseconds)
        } catch (interrupted: InterruptedException) {
            // A run being torn down under a user, not a feed that went quiet,
            // so the flag goes back for whoever is doing the tearing.
            Thread.currentThread().interrupt()
            return Threw(interrupted.javaClass.simpleName)
        }
        if (seen.get() < target) return if (ended.count == 0L) StreamEnded else TimedOut
        awaited.set(target)
        return null
    }

    /**
     * The gaps of up to [atMost] events that have arrived and not been reported,
     * oldest first, taken out as they are read.
     *
     * Bounded rather than draining: a step that waited for one event takes one,
     * however many the feed had already pushed behind it.
     */
    internal fun take(atMost: Long): List<Duration> =
        generateSequence { gaps.poll() }.take(atMost.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).toList()

    /**
     * How long since the last event, or since the request where none has come.
     *
     * What an event that never came had been outstanding for, which is the only
     * duration a failed wait can honestly be given: from the step's start it
     * would be the whole batch.
     */
    internal fun sinceLastEvent(): Duration = elapsed() - lastEvent.get().nanoseconds

    internal fun cancel() {
        subscription.getAndSet(null)?.cancel()
        ended.countDown()
        signal()
    }

    override fun onSubscribe(subscription: Flow.Subscription) {
        this.subscription.set(subscription)
        // Unbounded rather than one at a time. Asking for a line only once the
        // last is handled would make this generator's own draining the thing
        // that paces the feed, and the report would call that the target's
        // cadence.
        subscription.request(Long.MAX_VALUE)
    }

    override fun onNext(line: String) {
        when {
            // A comment, which is how a feed keeps a connection alive with
            // nothing to say. Counted, and an event it is not.
            line.startsWith(":") -> comments.incrementAndGet()

            // The blank line ends a frame. Only one carrying data is an event:
            // a frame of `id:` alone moves a cursor and delivers nothing.
            line.isEmpty() -> if (data) dispatch()

            line.startsWith("data") -> data = true

            // `event:`, `id:`, `retry:` and anything unknown: read far enough
            // to know they are not data, and dropped.
            else -> Unit
        }
    }

    override fun onError(error: Throwable) = finished(error)

    override fun onComplete() = finished(null)

    private fun dispatch() {
        val at = elapsed()
        gaps.add(at - lastEvent.get().nanoseconds)
        lastEvent.set(at.inWholeNanoseconds)
        data = false
        seen.incrementAndGet()
        signal()
    }

    private fun signal() = gate.withLock { arrived.signalAll() }

    private fun waitFor(target: Long, deadline: Long) = gate.withLock {
        while (seen.get() < target && ended.count > 0L) {
            if (!arrived.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) return@withLock
        }
    }

    private fun elapsed(): Duration = (System.nanoTime() - origin).nanoseconds
}

/** What a failed exchange failed with, in this repository's terms. */
private fun Throwable.unwrapped(): Reason {
    val cause = if (this is java.util.concurrent.CompletionException) cause ?: this else this
    return if (cause is java.net.http.HttpTimeoutException) TimedOut else Threw(cause.javaClass.name)
}

private const val NOTHING_YET = -1L

private const val OK_FROM = 200L

private const val OK_UNTIL = 300L
