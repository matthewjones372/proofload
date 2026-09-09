package io.github.matthewjones372.proofload

import java.time.Instant
import kotlin.time.Duration

/**
 * Which slice of a run this process is sending.
 *
 * One JVM is the largest limit this tool has. `docs/what-it-costs.md` shows the
 * shipped path saturating somewhere between ten and twenty-five thousand a
 * second on four shared cores — and whichever resource it runs out of, a second
 * host brings its own ports, descriptors and cores.
 *
 * Four injectors by hand is four plans nobody proved alike, four sets of
 * percentiles that cannot honestly be pooled, and four timelines whose second
 * thirty is not the same second thirty. This is the value that makes them one
 * experiment.
 */
data class Shard(
    /** Which injector this is, counted from zero. */
    val index: Int,
    /** How many there are. */
    val of: Int,
    /**
     * When every injector starts, on each one's own wall clock.
     *
     * The only thing coordinated, and only the start: a sample is two readings
     * of the injector's own monotonic clock, which is honest with no shared
     * clock at all. What alignment buys is a timeline whose second thirty is
     * the same second thirty everywhere, since a merge superimposes them.
     */
    val startingAt: Instant,

    /**
     * How long this injector actually held before [startingAt], read off its
     * own wall clock.
     *
     * Measured rather than declared: the value a caller builds carries none,
     * and the one on a result carries what the injector saw. It is the only
     * thing in a set of shards that can see a clock that disagrees — every
     * injector waits until *its own* clock says [startingAt], so a host running
     * three seconds fast starts three seconds early and writes the same instant
     * as everybody else. What it cannot write the same is the hold: told to go
     * at the same moment, it computes three seconds less of one.
     *
     * Null where nothing recorded it, which is a run nobody sharded and every
     * baseline written before version 8.
     */
    val heldFor: Duration? = null,
) {

    init {
        require(of > 0) { "a run is split across at least one injector, but of was $of" }
        require(index in 0 until of) { "injector $index of $of is not one of them" }
    }

    /**
     * Whether this injector sends the user numbered [user] of its arm.
     *
     * Public because an engine asks it, and [Engine] is core's to declare: an
     * engine in another module has to be able to send its own share.
     */
    fun sends(user: Long): Boolean = (user % of).toInt() == index
}

/**
 * The same run, with this process sending only its own share of it.
 *
 * Every injector is given the whole profile and filters it, rather than being
 * given a slice of the rate. A scaled-down profile per injector is easier and
 * wrong three ways: the arrival sequence is no longer the one a single JVM
 * produces, so a distributed run cannot be checked against a local one; a
 * randomised profile becomes N unrelated draws; and each injector's plan
 * differs from the whole, which forces open the unlike-plan refusal that is
 * the only thing between a user and N pooled experiments.
 *
 * The plan is unchanged, so every injector agrees about what was asked for and
 * `Plan.unlike` passes by construction.
 */
fun Simulation.sharded(index: Int, of: Int, startingAt: Instant): Simulation =
    copy(shard = Shard(index, of, startingAt))
