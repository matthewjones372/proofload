package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.Arm
import io.github.matthewjones372.kestrel.Shard
import io.github.matthewjones372.kestrel.departures
import kotlin.time.Duration

/**
 * One user leaving: the arm that sends it, which of that arm's users it is, and
 * how long after the run's start it goes.
 */
internal data class Departure(val arm: Arm, val user: Long, val offset: Duration)

/**
 * Every arm's departures as one schedule, in the order they leave.
 *
 * Merged rather than concatenated. Booking one arm's whole run before the
 * next's hands the arrival recorder gaps that run backwards, so the spacing it
 * reports is one nobody offered — and the target meets three runs that overlap
 * rather than the mix that was asked for.
 *
 * Lazy the whole way down, because an arm's own departures are: a ten-minute
 * mix at half a million a second is not a list.
 */
internal fun List<Arm>.schedule(): Sequence<Departure> = if (size == 1) first().departures() else merged()

/**
 * The departures this injector owns, filtered per arm so every injector sends
 * the same mix.
 *
 * The whole schedule is walked and most of it dropped, rather than each
 * injector computing a quarter of a rate. 0004 computes every offset from its
 * own index, so the union over all injectors is every user exactly once, at
 * exactly the offsets one JVM would have used — which is what makes a
 * distributed run checkable against a local one.
 *
 * The cost is that each injector iterates the whole sequence. It is lazy and
 * allocates nothing per skipped departure, and the alternative gives up the
 * property above.
 */
internal fun Sequence<Departure>.ownedBy(shard: Shard?): Sequence<Departure> =
    if (shard == null) this else filter { shard.sends(it.user) }

/** An arm's own users, numbered from zero whatever the other arms are sending. */
private fun Arm.departures(): Sequence<Departure> =
    profile.departures().mapIndexed { user, offset -> Departure(this, user.toLong(), offset) }

private fun List<Arm>.merged(): Sequence<Departure> = sequence {
    val fronts = map { arm -> arm.departures().iterator() }
    // The state a merge is: one departure held per arm, the earliest of them
    // handed out and only that arm advanced. A sequence cannot be un-consumed,
    // and the alternative is holding every arm's whole run to sort it.
    val held = MutableList(fronts.size) { arm -> fronts[arm].nextOrNull() }
    while (true) {
        val (arm, departure) = held.earliest() ?: break
        yield(departure)
        held[arm] = fronts[arm].nextOrNull()
    }
}

/**
 * The arm due next, and what it is sending. Arms that are due together leave in
 * the order the mix names them, so a run is a function of the value rather than
 * of which sequence answered first.
 */
private fun List<Departure?>.earliest(): IndexedValue<Departure>? =
    asSequence()
        .withIndex()
        .mapNotNull { (arm, departure) -> departure?.let { IndexedValue(arm, it) } }
        .minByOrNull { (_, departure) -> departure.offset }

private fun <T> Iterator<T>.nextOrNull(): T? = if (hasNext()) next() else null
