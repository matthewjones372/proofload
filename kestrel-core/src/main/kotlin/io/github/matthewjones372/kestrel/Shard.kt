package io.github.matthewjones372.kestrel

import java.time.Instant

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
