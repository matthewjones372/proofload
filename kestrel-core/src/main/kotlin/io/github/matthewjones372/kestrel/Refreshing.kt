package io.github.matthewjones372.kestrel

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * A credential the whole run shares, replaced by something that is not a step.
 *
 * [current] is a volatile read of a value already in hand, so a step that
 * authenticates neither waits for the identity provider nor times it. Fetching
 * inside a step instead puts that round trip in one request out of a few
 * hundred, and the p99 becomes a measurement of the auth server.
 */
class Refreshing<T : Any> internal constructor(initial: T, private val stopping: () -> Unit) {

    // An AtomicReference rather than a `var`: the same volatile read, no
    // mutable field, and `get` allocates nothing on the path a step takes.
    private val value = AtomicReference(initial)

    private val failed = AtomicLong()

    private val why = AtomicReference<Reason?>(null)

    /** The one in force now. */
    val current: T get() = value.get()

    /**
     * How many refreshes threw.
     *
     * A credential that stopped refreshing is a run whose target starts
     * answering 401 and a report that says the target began failing — the
     * exact misreading this whole value exists to prevent. Counted rather than
     * thrown, because the thread that fetches is not the thread that could
     * catch it, and a run that loses one refresh of twelve is still a run.
     */
    val failures: Long get() = failed.get()

    /** What the last failed refresh threw, or nothing where none has. */
    val lastFailure: Reason? get() = why.get()

    /** Ends the schedule. What was last fetched stays readable. */
    fun stop() = stopping()

    internal fun replaceWith(next: T) = value.set(next)

    internal fun failedWith(thrown: Throwable) {
        why.set(Threw(thrown::class.simpleName ?: thrown.javaClass.name))
        failed.incrementAndGet()
    }

    companion object {
        /** One that never changes: a token a test already holds, with no scheduler under it. */
        fun <T : Any> fixed(value: T): Refreshing<T> = Refreshing(value) {}
    }
}

/**
 * Fetches once now, and again every [every] until the result is stopped.
 *
 * Cold rather than lazy: the first fetch happens here, before the run, so it
 * cannot land inside whichever virtual user reads the value first — that user
 * would carry the fetch in its step's latency. Every later fetch runs on a
 * daemon thread no departure and no step is submitted to, which is what keeps
 * a refresh off the path being measured.
 */
fun <T : Any> refreshing(every: Duration, fetch: () -> T): Refreshing<T> =
    refreshing(every, fetch, Executors.newSingleThreadScheduledExecutor(::refreshThread))

internal fun <T : Any> refreshing(
    every: Duration,
    fetch: () -> T,
    executor: ScheduledExecutorService,
): Refreshing<T> {
    val refreshing = Refreshing(fetch()) { executor.shutdownNow() }
    executor.scheduleAtFixedRate(
        {
            // Caught rather than allowed out: a task that throws out of
            // scheduleAtFixedRate cancels its own schedule, so one failed
            // refresh would leave every later one unscheduled and the run
            // reading a credential that expired an hour ago. The last good
            // value stays in force, and the failure is counted rather than
            // swallowed.
            try {
                refreshing.replaceWith(fetch())
            } catch (thrown: Throwable) {
                refreshing.failedWith(thrown)
            }
        },
        every.inWholeNanoseconds,
        every.inWholeNanoseconds,
        TimeUnit.NANOSECONDS,
    )
    return refreshing
}

// Daemon, so a `main` that forgets to stop the refresher still exits.
private fun refreshThread(runnable: Runnable): Thread =
    Thread(runnable, "kestrel-refresh").apply { isDaemon = true }
