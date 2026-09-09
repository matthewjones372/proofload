package io.github.matthewjones372.proofload.engine

import io.github.matthewjones372.proofload.Action
import io.github.matthewjones372.proofload.Arm
import io.github.matthewjones372.proofload.ArrivalRecorder
import io.github.matthewjones372.proofload.Capacity
import io.github.matthewjones372.proofload.Completing
import io.github.matthewjones372.proofload.Engine
import io.github.matthewjones372.proofload.InjectionProfile
import io.github.matthewjones372.proofload.Pending
import io.github.matthewjones372.proofload.Progress
import io.github.matthewjones372.proofload.Reason
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.SampleSink
import io.github.matthewjones372.proofload.Scenario
import io.github.matthewjones372.proofload.Search
import io.github.matthewjones372.proofload.Session
import io.github.matthewjones372.proofload.Shard
import io.github.matthewjones372.proofload.Simulation
import io.github.matthewjones372.proofload.Step
import io.github.matthewjones372.proofload.StepResult
import io.github.matthewjones372.proofload.StepScope
import io.github.matthewjones372.proofload.Threw
import io.github.matthewjones372.proofload.WarmUp
import io.github.matthewjones372.proofload.closed
import io.github.matthewjones372.proofload.hold
import io.github.matthewjones372.proofload.plan
import io.github.matthewjones372.proofload.requireSeededThinking
import io.github.matthewjones372.proofload.startRate
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
    // Before the warm-up and before the recorder: a run that cannot reproduce
    // itself should not have spent the window first.
    requireSeededThinking()
    progress.starting(plan())
    // Before the recorder exists, so the run's own startedAt, counts, timeline
    // and lateness cannot hold any of it: excluded means never recorded, not
    // recorded and filtered out afterwards.
    warmUp?.let { warming -> warmingUpRun(warming).departAll(unrecorded, Departures(), Departed(), null, null) }
    // After the warm-up and after 0050's lock, and before the recorder: every
    // injector should be warm when the clocks line up, and a run that waited
    // for the machine inside the alignment window would start late by however
    // long it waited. Nothing else is coordinated — a sample is two readings
    // of this injector's own monotonic clock.
    val held = shard?.let { waitFor(it, progress) }
    val recorders = Recorders(Instant.now(), keepingSchedule = !closed)
    val watch = watchForHiccups()
    val room = watchForRoom()
    // The recorder's origin rather than a second reading of the clock: a
    // transport that asks the recorder where it is in the run must land on the
    // same timeline as the steps the engine times itself.
    val runStart = System.nanoTime() - recorders.sinceStart().inWholeNanoseconds
    val users = Departures()
    val departed = Departed()
    val watching = watchProgress(progress, runStart) { ended ->
        // The whole run's window, which is the longest arm's: a mix is over
        // when its last arm is.
        val (requests, failed) = recorders.counts()
        departed.snapshot(users.inFlight(), ended, scheduled = over, requests = requests, failed = failed)
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
    val sampled = watching.usersInFlight()
    return recorders.freeze(plan(), arrivals.freeze())
        .copy(
            hiccups = watch.stop(),
            limits = room.stop(),
            usersInFlight = sampled,
            // The shard the caller declared, plus the hold this host actually
            // computed: the only thing a merge can read a disagreeing clock off.
            shard = shard?.copy(heldFor = held),
        )
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
    // A fixed population is not a schedule and has no pump: its users start
    // once each and then go round again whenever the target lets them.
    if (closed) return departPopulation(sink, users, departed, drain, runStart)

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
                BookingWindow(arms.schedule().ownedBy(shard).iterator(), BOOKING_WINDOW),
                runStart,
                allBooked = users::allScheduled,
            ) { departure ->
                arrivals?.record(departure.offset)
                users.booked()
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
                            departure.arm.thinkingFor(departure.user),
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
 * A fixed population, each user restarting the scenario until the window ends.
 *
 * No scheduler and no booking window: nothing here decides when a user goes,
 * because after its first journey the target does. Every journey is recorded
 * at the moment it actually started, with no lateness — the recorder was told
 * this run keeps no schedule, so `behind` stays empty rather than filling with
 * zeros, and response time collapses onto service time. That collapse is the
 * finding, not a gap: it is coordinated omission, which is what a closed model
 * buys and what the page has to say.
 *
 * A user that is still mid-journey when the window ends finishes it. Cutting
 * one off would record a step that never happened and leave a session half
 * written.
 */
private fun Simulation.departPopulation(
    sink: StepSink,
    users: Departures,
    departed: Departed,
    drain: Drain?,
    runStart: Long,
) {
    val closesAt = over.inWholeNanoseconds
    arms.forEach { arm ->
        val population = (arm.profile as InjectionProfile.ClosedUsers).count
        repeat(population) { index ->
            users.booked()
            Thread.ofVirtual().start {
                users.departed()
                try {
                    goRound(arm, index, population, sink, runStart, closesAt, drain, departed)
                } finally {
                    users.finished()
                }
            }
        }
    }
    // The population is the whole of what this run books, and it is booked by
    // the time this line is reached. Without it the latch keeps the pump's
    // standing count of one — which a closed run has no pump to clear.
    users.allScheduled()
    users.awaitAll()
}

/**
 * One of the population, until the window ends.
 *
 * Each journey gets its own user number — the population's size times the lap,
 * plus this user's index — so a feeder gives a looping user new data each time
 * round rather than the same row for ten minutes.
 */
@Suppress("LongParameterList")
private fun goRound(
    arm: Arm,
    index: Int,
    population: Int,
    sink: StepSink,
    runStart: Long,
    closesAt: Long,
    drain: Drain?,
    departed: Departed,
) {
    var lap = 0L
    while (System.nanoTime() - runStart < closesAt) {
        val at = (System.nanoTime() - runStart).nanoseconds
        // No arrivals. `ArrivalRecorder` folds a running mean and variance
        // into plain fields and is documented as seeing departures in order —
        // in an open run the pump is the one thread that does. A population
        // has no such thread, and fifty of them writing to it is a race whose
        // symptom is a spacing figure quietly wrong rather than a crash.
        departed.left(0L)
        arm.scenario.runOneUser(
            sink,
            runStart,
            // Nothing promised this journey a departure, so there is no
            // lateness to carry. The recorder drops it rather than counting a
            // zero, and the two clocks become one.
            schedulingDelay = Duration.ZERO,
            departure = at,
            started = arm.feeder.forUser(lap * population + index),
            drain = drain,
            thinking = arm.thinkingFor(lap * population + index),
        )
        lap++
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
    Simulation(
        arms.map { arm ->
            // A closed arm warms as itself for the declared length: its
            // population is the shape, and there is no rate to hold it at.
            val warming = (arm.profile as? InjectionProfile.ClosedUsers)
                ?.copy(over = warmUp.over)
                ?: hold(arm.profile.startRate, over = warmUp.over)
            arm.copy(profile = warming)
        },
    )

/**
 * Holds this injector until the instant every injector was given.
 *
 * The only thing shared between them, and only the start: what alignment buys
 * is a timeline whose second thirty is the same second thirty everywhere,
 * because a merge superimposes them. 0025 fixes the timeline's resolution at
 * one second, so a hundred milliseconds of skew is a tenth of a bucket and
 * NTP beats that comfortably — sub-second alignment buys the picture, not the
 * honesty.
 *
 * An instant already past is refused rather than started late: a run that
 * began after the others measured a different window, and pooling it with
 * theirs would report a shape none of them saw.
 */
private fun waitFor(shard: Shard, progress: Progress): Duration {
    val until = shard.startingAt.toEpochMilli() - System.currentTimeMillis()
    require(until > 0L) {
        "injector ${shard.index} of ${shard.of} was told to start at ${shard.startingAt}, which was " +
            "${-until}ms ago; give every injector an instant far enough ahead to reach"
    }
    progress.aligning(shard, until.milliseconds)
    // A latch nothing counts down, rather than a sleep: this is a platform
    // thread and the ban is against parking a carrier, but the intent here is
    // "wait until" and a latch says so.
    CountDownLatch(1).await(until, TimeUnit.MILLISECONDS)
    // Returned rather than thrown away: this is what the host's own clock said
    // about the instant every injector was given, and a merge has nothing else
    // to read a disagreement off.
    return until.milliseconds
}

/** Where a warm-up's samples go: nowhere. */
private val unrecorded = StepSink { _, _, _, _, _, _, _, _, _ -> }

/**
 * This user's own source of waits, seeded from the arm's seed and the user's
 * own number, or null where this arm draws nothing.
 *
 * Mixed the way a staged profile mixes a stage's seed with a window's, so user
 * 4,001 parks the same tomorrow whatever the target did today — the same thing
 * `Feeder` already gives for what a user sends.
 */
private fun Arm.thinkingFor(user: Long): Random? =
    thinkSeed?.let { Random(it * MIXED_WITH_USER + user) }

private fun Scenario.depart(
    sink: StepSink,
    runStart: Long,
    departure: Duration,
    users: Departures,
    session: Session,
    drain: Drain?,
    departed: Departed,
    thinking: Random? = null,
) {
    // The scheduler counting itself, on the scheduler's own thread. What the
    // report calls lateness is still read below, where the user starts: one
    // says when this tool fired, the other when a request left.
    departed.left(lateness(runStart, departure).inWholeNanoseconds)
    Thread.ofVirtual().start {
        // Counted here rather than where it was booked: until this thread is
        // mounted, nothing has left.
        users.departed()
        try {
            // Read here rather than on the scheduler: what the report calls
            // lateness is how late the user's first request left, and until the
            // virtual thread is mounted nothing has left.
            runOneUser(
                sink,
                runStart,
                lateness(runStart, departure),
                departure,
                session,
                drain,
                thinking = thinking,
            )
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
    Thread(runnable, "proofload-scheduler").apply { isDaemon = true }

/**
 * How many users are still to finish, and a gate that opens when none are.
 * The pump counts as one of them until the last departure is booked, so a run
 * whose early users finish before its late ones are even scheduled cannot
 * declare itself over.
 */
private class Departures {

    // Two counts, because they answer two questions. `outstanding` is what the
    // run waits on: a user is counted the moment it is booked, so the latch
    // cannot fire while a booked user has yet to start. `running` is what a
    // watcher reads: a user is counted when its thread actually departs.
    //
    // One counter served both and was wrong for the second. The pump books a
    // window ahead — BOOKING_WINDOW of departures — so at fifty a second it
    // counted two hundred and fifty users as in flight that had not left, and
    // any reading of concurrency off it was that window rather than the
    // target's latency.
    private val outstanding = AtomicLong(1)
    private val running = AtomicLong()
    private val allFinished = CountDownLatch(1)

    /** Booked, and owed a departure: the run cannot end until this user has run. */
    fun booked() {
        outstanding.incrementAndGet()
    }

    /** Left: its thread is mounted and its first step is about to run. */
    fun departed() {
        running.incrementAndGet()
    }

    fun finished() {
        running.decrementAndGet()
        release()
    }

    fun allScheduled() = release()

    private fun release() {
        if (outstanding.decrementAndGet() == 0L) allFinished.countDown()
    }

    fun awaitAll() = allFinished.await()

    /** Users whose journey has started and not yet ended. */
    fun inFlight(): Long = running.get().coerceAtLeast(0)
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
    narrating: ((String, List<String>) -> Unit)? = null,
    thinking: Random? = null,
) {
    UserWalk(sink, runStart, schedulingDelay, departure, drain, narrating, thinking).walk(steps, started)
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
private fun Step.Pause.thoughtAbout(session: Session, random: Random?): Session {
    // A constant needs no draw and no seed, which is why an unseeded scenario
    // of constants still runs. A distribution without a random here cannot
    // happen: the simulation refuses one before anything departs.
    val waiting = if (random == null) think.mean else think.drawnFrom(random)
    Thread.sleep(waiting.toJavaDuration())
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
    // This user's own source of waits, or null where nothing here draws. One
    // per user rather than one shared: a shared generator is contention on the
    // path whose delay is reported as latency, and it would make what user
    // 4,001 waited depend on how fast the target answered every user before it.
    // Null for a run and a list for a trace: what a step did is collected here
    // and handed to whoever asked, so a measuring run builds no lines and a
    // diagnostic gets them without a route of its own.
    private val narrating: ((String, List<String>) -> Unit)? = null,
    // This user's own source of waits, or null where nothing here draws. One
    // per user rather than one shared: a shared generator is contention on the
    // path whose delay is reported as latency, and it would make what user
    // 4,001 waited depend on how fast the target answered every user before it.
    private val thinking: Random? = null,
) : SampleSink {

    // The one mutable thing a walk keeps, and one per user rather than per
    // request: `reached` counts users, so the first time this user records
    // under a name has to be told from every later time, and only the walk
    // knows which user it is on.
    private val met = HashSet<String>()

    // Which step is running and whether this user had reached it, so a body's
    // own samples land under the right name. Fields on the walk rather than a
    // sampler allocated per step: a walk is one user on one virtual thread and
    // runs one step at a time, and an allocation per step is one the report
    // would read as the target's latency. Reset by `runOn` before each step.
    private var sampling: String? = null
    private var samplingReached = false
    private var samplingVisit = false

    /**
     * A sample the body observed, recorded as it observed it rather than
     * buffered: a buffer would flatten every sample into the second the body
     * started, and hold a step's worth of them for nothing.
     *
     * `reached` rides the first only — a user that received a hundred messages
     * reached the step once — and so does `visit`, for the same reason read the
     * other way: those hundred messages are one run of the body.
     */
    override fun sample(took: Duration, at: Duration?, reason: Reason?) {
        val step = sampling ?: return
        sink.record(
            step,
            reason,
            took,
            schedulingDelay,
            at ?: (System.nanoTime() - runStart).nanoseconds,
            samplingReached,
            samplingVisit,
            attempts = 1,
            trace = null,
        )
        samplingReached = false
        samplingVisit = false
    }

    // A failed step abandons the user. A null session carries that decision
    // through the walk and out of any loop it was inside: the steps after it
    // are not run, and are not counted as anything. Counting a payment that
    // never had a cart as a success reports a service that answered nobody.
    fun walk(steps: List<Step>, from: Session?): Session? =
        steps.fold(from) { session, step -> session?.let { run(step, it) } }

    private fun run(step: Step, session: Session): Session? = when (step) {
        is Step.Exec -> runOn(step.action, step.name, session, met.add(step.name))

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

        is Step.Pause -> step.thoughtAbout(session, thinking)
    }

    /**
     * One step, timed and recorded.
     *
     * On the walk rather than beside it, because the scope a body reports
     * through is this walk's: the samples it writes have to land under the
     * step being run, and only the walk knows which that is.
     */
    private fun runOn(action: Action, name: String, session: Session, reached: Boolean): Session? {
        sampling = name
        samplingReached = reached
        samplingVisit = true
        val notes = if (narrating == null) null else mutableListOf<String>()
        val scope = StepScope(session, samples = this, notes = notes)
        val startedAt = System.nanoTime()
        val result = action.attempt(session, scope)
        val serviceTime = (System.nanoTime() - startedAt).nanoseconds
        val reason = result.reason()
        // Only where the body reported none of its own: a step that measured a
        // hundred messages has said what it saw, and one more sample over the
        // whole body would be a latency nobody experienced.
        if (!result.sampled) {
            sink.record(
                name,
                reason,
                serviceTime,
                schedulingDelay,
                (startedAt - runStart).nanoseconds,
                reached,
                samplingVisit,
                result.attempts,
                result.trace,
            )
        }
        // Beside the sample rather than in it, and only where a body had
        // something to say: a step that waited for a connection out of a pool,
        // or one whose answer was rows.
        if (result.queued > Duration.ZERO || result.produced > 0L) {
            sink.aside(name, result.queued, result.produced)
        }
        sampling = null
        samplingVisit = false
        notes?.let { narrating?.invoke(name, it) }
        return if (reason == null) result.session else null
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
        val published = runOn(step.action, step.name, session, reached)
            ?: return null
        drain?.departed(step.correlation.of(published), departure)
        return published
    }
}

// The second a request is counted in is the one it left in, not the one it came
// back in: a step that takes six seconds belongs on the timeline where the load
// was offered, beside the profile that offered it.

// The one place in the library allowed to catch a throwable. An action is code
// the caller wrote against a target the caller does not control, so a throw out
// of it is a request that failed, to be measured and named — not a bug in the
// engine and not a reason to lose the rest of the run.
private fun Action.attempt(session: Session, scope: StepScope): StepResult =
    try {
        run(scope)
        scope.result()
    } catch (throwable: Throwable) {
        // The session the step started with, not what it had written before it
        // threw: a half-written session is a failure that reappears as a
        // stranger several steps later.
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

/**
 * An odd multiplier, so one arm's seed and its neighbour's cannot land on the
 * same stream by being one apart: seed 38 user 1 and seed 39 user 0 are two
 * different users, and would otherwise wait the same.
 */
private const val MIXED_WITH_USER = -0x61C8864680B583EBL
