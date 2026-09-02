package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.Action
import io.github.matthewjones372.kestrel.ArrivalRecorder
import io.github.matthewjones372.kestrel.Capacity
import io.github.matthewjones372.kestrel.Completing
import io.github.matthewjones372.kestrel.Engine
import io.github.matthewjones372.kestrel.Pending
import io.github.matthewjones372.kestrel.Progress
import io.github.matthewjones372.kestrel.Reason
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.Scenario
import io.github.matthewjones372.kestrel.Search
import io.github.matthewjones372.kestrel.Session
import io.github.matthewjones372.kestrel.Simulation
import io.github.matthewjones372.kestrel.Step
import io.github.matthewjones372.kestrel.StepResult
import io.github.matthewjones372.kestrel.Threw
import io.github.matthewjones372.kestrel.WarmUp
import io.github.matthewjones372.kestrel.hold
import io.github.matthewjones372.kestrel.plan
import io.github.matthewjones372.kestrel.startRate
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Sends a simulation on virtual threads, one per user, departing on a schedule
 * a platform thread of its own keeps.
 *
 * It blocks until the last user it started has finished and until the sink the
 * simulation named has had the wait it declared. Blocking a virtual thread is
 * what Loom is for, so the caller's thread is where a run is waited on.
 */
class VirtualThreads(private val progress: Progress = Progress.lines()) : Engine {

    override fun run(simulation: Simulation): RunResult = simulation.send(progress)
}

/**
 * Runs the search a rung at a time, and blocks for as long as it takes —
 * `worstCase` says how long that can be before anybody starts one.
 */
fun Search.run(progress: Progress = Progress.lines()): Capacity =
    reported(progress) { rung -> rung.run(progress) }

/** The same run through [VirtualThreads], for a caller with no reason to name an engine. */
fun Simulation.run(progress: Progress = Progress.lines()): RunResult = VirtualThreads(progress).run(this)

/**
 * [progress] says what the run is doing while it does it, and defaults to
 * saying it: a `main` that prints nothing for ten minutes is a run somebody
 * kills. A caller whose stdout belongs to something else passes
 * [Progress.silent].
 */
private fun Simulation.send(progress: Progress): RunResult {
    progress.starting(plan())
    // Before the recorder exists, so the run's own startedAt, counts, timeline
    // and lateness cannot hold any of it: excluded means never recorded, not
    // recorded and filtered out afterwards.
    warmUp?.let { warming -> warmingUpRun(warming).departAll(unrecorded, Departures(), Departed(), null, null) }
    val recorders = Recorders(Instant.now())
    val watch = watchForHiccups()
    // The recorder's origin rather than a second reading of the clock: a
    // transport that asks the recorder where it is in the run must land on the
    // same timeline as the steps the engine times itself.
    val runStart = System.nanoTime() - recorders.sinceStart().inWholeNanoseconds
    val users = Departures()
    val departed = Departed()
    val watching = watchProgress(progress, runStart) { ended ->
        // The whole run's window, which is the longest arm's: a mix is over
        // when its last arm is.
        departed.snapshot(users.inFlight(), ended, scheduled = over)
    }
    // Read where the offsets are consumed rather than off the profile: what the
    // report names is the spacing that was produced, and a profile asked the
    // same question would answer with its own intention. The pump is the one
    // thread that sees every departure exactly once and in order, and folding
    // three numbers there costs no allocation on the path whose delay is
    // measured as latency.
    val arrivals = ArrivalRecorder()
    val drain = completing?.let { Drain(it, recorders, runStart, closesAt = over + it.drainingFor) }
    drain?.start()
    departAll(recorders, users, departed, arrivals, drain, runStart)
    drain?.close()
    watching.stop()
    return recorders.freeze(plan(), arrivals.freeze()).copy(hiccups = watch.stop())
}

/**
 * Books every arm's departures and waits for the users they start.
 *
 * Shared by the measured run and the warm-up before it, so the load a warm-up
 * offers is the load a run offers and not a second implementation of it. What
 * differs is where the samples go: a warm-up hands in a sink that drops them.
 *
 * [runStart] defaults to now, which is what a warm-up wants — it is its own
 * clock's zero — while the measured run passes the origin its recorder owns.
 */
private fun Simulation.departAll(
    sink: StepSink,
    users: Departures,
    departed: Departed,
    arrivals: ArrivalRecorder?,
    drain: Drain?,
    runStart: Long = System.nanoTime(),
) {
    // One platform thread. Its only job is to start virtual threads at the
    // offsets the profile named; a step never runs on it, so a slow target
    // cannot push a departure back.
    val scheduler = Executors.newSingleThreadScheduledExecutor(::schedulerThread)
    try {
        // The pump is the scheduler thread's own work rather than a second
        // thread's: two threads deciding when a user leaves is two chances to
        // disagree about it.
        scheduler.execute(
            Pump(
                scheduler,
                BookingWindow(arms.schedule().iterator(), BOOKING_WINDOW),
                runStart,
                allBooked = users::allScheduled,
            ) { departure ->
                arrivals?.record(departure.offset)
                users.starting()
                scheduler.schedule(
                    {
                        // Each arm's own feeder, asked for its own user number:
                        // an arm's data is then reproducible whatever rate the
                        // arms beside it are being sent at.
                        departure.arm.scenario.depart(
                            sink,
                            runStart,
                            departure.offset,
                            users,
                            departure.arm.feeder.forUser(departure.user),
                            drain,
                            departed,
                        )
                    },
                    // Relative to now, but the offset is from the run's start
                    // and a pump books partway through it. Subtracting what has
                    // already elapsed is what stops every departure inheriting
                    // the time its window waited to be booked.
                    departure.offset.inWholeNanoseconds - (System.nanoTime() - runStart),
                    TimeUnit.NANOSECONDS,
                )
            },
        )
        users.awaitAll()
    } finally {
        scheduler.shutdownNow()
    }
}

/**
 * The run a warm-up sends: the same arms and the same data, each held at the
 * rate its own shape opens at for the length the caller declared.
 *
 * Goals and a drained sink are dropped, because nothing judges a warm-up and
 * nothing may be recorded from one.
 */
private fun Simulation.warmingUpRun(warmUp: WarmUp): Simulation =
    Simulation(arms.map { arm -> arm.copy(profile = hold(arm.profile.startRate, over = warmUp.over)) })

/** Where a warm-up's samples go: nowhere. */
private val unrecorded = StepSink { _, _, _, _, _, _, _ -> }

private fun Scenario.depart(
    sink: StepSink,
    runStart: Long,
    departure: Duration,
    users: Departures,
    session: Session,
    drain: Drain?,
    departed: Departed,
) {
    // The scheduler counting itself, on the scheduler's own thread. What the
    // report calls lateness is still read below, where the user starts: one
    // says when this tool fired, the other when a request left.
    departed.left(lateness(runStart, departure).inWholeNanoseconds)
    Thread.ofVirtual().start {
        try {
            // Read here rather than on the scheduler: what the report calls
            // lateness is how late the user's first request left, and until the
            // virtual thread is mounted nothing has left.
            runOneUser(sink, runStart, lateness(runStart, departure), departure, session, drain)
        } finally {
            users.finished()
        }
    }
}

/**
 * Drains the sink for the wait the simulation declared, on a thread of its own
 * so an answer that is slow to arrive never holds a departure up.
 *
 * The wait closes at the run's scheduled end plus that window, which is a bound
 * known before anything is sent. A run whose own injector is behind does not get
 * to extend it: that would turn records it lost into records it merely did not
 * wait for, which are the two things being told apart here.
 */
internal class Drain(
    private val completing: Completing,
    private val recorders: Recorders,
    private val runStart: Long,
    private val closesAt: Duration,
) {

    private val pending = Pending()
    private val closed = CountDownLatch(1)

    fun start() {
        Thread.ofVirtual().start {
            try {
                poll()
            } finally {
                closed.countDown()
            }
        }
    }

    fun departed(id: Long, intended: Duration) {
        pending.departed(id, intended, at = elapsed())
    }

    /** What never came, once nothing more will be drained. */
    fun close() {
        closed.await()
        // Every user has finished by now, so an answer still unpaired here has
        // a departure registered if it is ever going to have one.
        record(pending.matched(), at = elapsed())
        recorders.outstanding(completing.step, pending.close(closesAt, completing.drainingFor))
    }

    private tailrec fun poll() {
        val left = closesAt - elapsed()
        if (left <= Duration.ZERO) return
        val observed = completing.from.poll(left)
        val at = elapsed()
        record(observed.mapNotNull { id -> pending.observed(id, at) } + pending.matched(), at)
        poll()
    }

    private fun record(latencies: List<Duration>, at: Duration) =
        latencies.forEach { latency -> recorders.arrived(completing.step, latency, at) }

    private fun elapsed(): Duration = (System.nanoTime() - runStart).nanoseconds
}

/**
 * Books the departures that are due within a window, then books itself to run
 * again when the next one comes into view.
 *
 * Scheduled rather than looped: a thread that wakes, tops the queue up and
 * sleeps again is a parked thread in library code, and a stall there is
 * measured as the target's latency.
 */
private class Pump(
    private val scheduler: ScheduledExecutorService,
    private val booking: BookingWindow,
    private val runStart: Long,
    private val allBooked: () -> Unit,
    private val book: (Departure) -> Unit,
) : Runnable {

    override fun run() {
        val again = booking.fill((System.nanoTime() - runStart).nanoseconds, book)
        if (again == null) allBooked() else scheduler.schedule(this, again.inWholeNanoseconds, TimeUnit.NANOSECONDS)
    }
}

private fun schedulerThread(runnable: Runnable): Thread =
    Thread(runnable, "kestrel-scheduler").apply { isDaemon = true }

/**
 * How many users are still to finish, and a gate that opens when none are.
 * The pump counts as one of them until the last departure is booked, so a run
 * whose early users finish before its late ones are even scheduled cannot
 * declare itself over.
 */
private class Departures {

    private val outstanding = AtomicLong(1)
    private val allFinished = CountDownLatch(1)
    private val booking = CountDownLatch(1)

    fun starting() {
        outstanding.incrementAndGet()
    }

    fun finished() {
        if (outstanding.decrementAndGet() == 0L) allFinished.countDown()
    }

    fun allScheduled() {
        // Released before the token it stands for, so a watcher between the two
        // reads one user too few rather than the loop as a user.
        booking.countDown()
        finished()
    }

    fun awaitAll() = allFinished.await()

    /** Users still running, which is everything outstanding but the scheduling loop's own token. */
    fun inFlight(): Long = (outstanding.get() - booking.count).coerceAtLeast(0)
}

/**
 * How late a user left against the departure its profile named. Floored at
 * zero: a scheduler cannot fire early, and a user that started before its
 * offset is not a backlog anybody can act on.
 */
private fun lateness(runStart: Long, departure: Duration): Duration =
    ((System.nanoTime() - runStart).nanoseconds - departure).coerceAtLeast(Duration.ZERO)

internal fun Scenario.runOneUser(
    sink: StepSink,
    runStart: Long,
    schedulingDelay: Duration,
    departure: Duration,
    started: Session,
    drain: Drain?,
) {
    UserWalk(sink, runStart, schedulingDelay, departure, drain).walk(steps, started)
}

/**
 * The one `Thread.sleep` the library allows, and the reason the ban exists is
 * the reason this is exempt from it: the ban is against a parked *carrier*, and
 * on a virtual thread `sleep` unmounts rather than holding one, so the platform
 * threads stay free to keep the schedule and no other user departs late for it.
 *
 * Nothing is recorded. The session passes through untouched, so the next step's
 * service time starts when it starts; response time is that plus how late the
 * user departed, so a pause cannot leak into either, and no sample reaches
 * `behind` — the generator is not late for a departure that was meant to wait.
 */
@Suppress("ForbiddenMethodCall")
private fun Step.Pause.thoughtAbout(session: Session): Session {
    Thread.sleep(duration.toJavaDuration())
    return session
}

/**
 * One user's way through the scenario tree. What a step is run against is held
 * here rather than threaded through the walk, because a nested step hands all
 * of it down unchanged.
 */
private class UserWalk(
    private val sink: StepSink,
    private val runStart: Long,
    private val schedulingDelay: Duration,
    private val departure: Duration,
    private val drain: Drain?,
) {

    // The one mutable thing a walk keeps, and one per user rather than per
    // request: `reached` counts users, so the first time this user records
    // under a name has to be told from every later time, and only the walk
    // knows which user it is on.
    private val met = HashSet<String>()

    // A failed step abandons the user. A null session carries that decision
    // through the walk and out of any loop it was inside: the steps after it
    // are not run, and are not counted as anything. Counting a payment that
    // never had a cart as a success reports a service that answered nobody.
    fun walk(steps: List<Step>, from: Session?): Session? =
        steps.fold(from) { session, step -> session?.let { run(step, it) } }

    private fun run(step: Step, session: Session): Session? = when (step) {
        is Step.Exec -> step.action.runOn(step.name, session, sink, runStart, schedulingDelay, met.add(step.name))

        is Step.Emit -> emit(step, session)

        // Iterations, not steps: the body is one subtree walked again, so three
        // times round records three requests under the one name the tree
        // declared, and the plan can still name it before the run starts.
        is Step.Repeat -> (1..step.times).fold<Int, Session?>(session) { each, _ -> walk(step.steps, each) }

        // A clock of its own, read between iterations rather than waited on:
        // nothing here parks a carrier, and a loop that outlives the profile's
        // window extends the run rather than being cut off mid-journey.
        is Step.During -> looping(step, session, startedAt = System.nanoTime())

        is Step.When -> if (step.predicate(session)) walk(step.steps, session) else session

        is Step.Pause -> step.thoughtAbout(session)
    }

    private tailrec fun looping(step: Step.During, session: Session?, startedAt: Long): Session? {
        if (session == null || (System.nanoTime() - startedAt).nanoseconds >= step.duration) return session
        return looping(step, walk(step.steps, session), startedAt)
    }

    /**
     * The publish is timed like any other step, because it is all that leaves
     * here. What the record answers with is registered against the departure the
     * profile promised, so the sink's observation has an honest thing to be
     * subtracted from.
     */
    private fun emit(step: Step.Emit, session: Session): Session? {
        val reached = met.add(step.name)
        val published = step.action.runOn(step.name, session, sink, runStart, schedulingDelay, reached)
            ?: return null
        drain?.departed(step.correlation.of(published), departure)
        return published
    }
}

// The second a request is counted in is the one it left in, not the one it came
// back in: a step that takes six seconds belongs on the timeline where the load
// was offered, beside the profile that offered it.
private fun Action.runOn(
    name: String,
    session: Session,
    sink: StepSink,
    runStart: Long,
    schedulingDelay: Duration,
    reached: Boolean,
): Session? {
    val startedAt = System.nanoTime()
    val result = attempt(session)
    val serviceTime = (System.nanoTime() - startedAt).nanoseconds
    val reason = result.reason()
    sink.record(
        name,
        reason,
        serviceTime,
        schedulingDelay,
        (startedAt - runStart).nanoseconds,
        reached,
        result.attempts,
    )
    return if (reason == null) result.session else null
}

// The one place in the library allowed to catch a throwable. An action is code
// the caller wrote against a target the caller does not control, so a throw out
// of it is a request that failed, to be measured and named — not a bug in the
// engine and not a reason to lose the rest of the run.
private fun Action.attempt(session: Session): StepResult = try {
    run(session)
} catch (throwable: Throwable) {
    StepResult.Failed(session, Threw(throwable.javaClass.name))
}

private fun StepResult.reason(): Reason? = when (this) {
    is StepResult.Ok -> null
    is StepResult.Failed -> reason
}

/**
 * How far ahead of itself a run books departures.
 *
 * Time rather than a count of tasks: five seconds is the same slack at every
 * rate, where a thousand tasks is minutes of run at one rate and milliseconds
 * at another.
 */
private val BOOKING_WINDOW: Duration = 5.seconds
