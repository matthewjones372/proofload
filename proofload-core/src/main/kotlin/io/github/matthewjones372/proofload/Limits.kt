package io.github.matthewjones372.proofload

/**
 * How close a run came to one of the injector's own ceilings.
 *
 * Absent carries the reason, as [Tail] and [Met] do: a platform that does not
 * expose a limit reports that it did not, never a zero. A Windows runner
 * reporting no open files would be a lie in the exact shape this exists to
 * stop.
 */
sealed interface Headroom {

    data class Measured(val peak: Long, val limit: Long) : Headroom {

        init {
            require(limit > 0L) { "a limit is a ceiling above zero, but was $limit" }
            require(peak >= 0L) { "a peak cannot be negative, but was $peak" }
        }

        /** How much of the ceiling the run reached, as a share of one. */
        val used: Double get() = peak.toDouble() / limit
    }

    data class Absent(val because: String) : Headroom
}

/**
 * What the injector itself ran up against while it was measuring the target.
 *
 * `docs/what-it-costs.md` publishes a row it cannot explain: at ten thousand a
 * second, 29,568 failures of 50,000 requests, every one of them counted against
 * the target because that is where a failed request comes from. The likelier
 * reading is that this process ran out of descriptors or of ephemeral ports —
 * and nothing here measured either, so the page cannot say.
 *
 * A peak rather than a distribution: a stall's size is a shape, which is why
 * `hiccups` is a `Timing`, but a limit approached is a maximum.
 */
data class Limits(
    val openFiles: Headroom = Headroom.Absent(NOT_SAMPLED),
    val ports: Headroom = Headroom.Absent(NOT_SAMPLED),
    val cpu: Headroom = Headroom.Absent(NOT_SAMPLED),
) {

    /** The three of them, for a reader that wants to say the same thing about each. */
    val all: List<Pair<String, Headroom>>
        get() = listOf("open files" to openFiles, "ephemeral ports" to ports, "CPU" to cpu)

    companion object {
        /** For a result built from samples rather than run, which sampled nothing. */
        val none: Limits = Limits()

        internal const val NOT_SAMPLED: String = "nothing sampled this run"
    }
}

/**
 * Whether the injector came close enough to one of its own ceilings to have
 * changed what the run measured.
 *
 * The gate is [TIGHT], stated here so the page, the markdown and a void rung
 * ask the same question. CPU is deliberately not asked: `docs/what-it-costs.md`
 * says the generator and the target share all four cores on a laptop and a
 * single CI runner, so a share near saturation is the ordinary case rather
 * than a fault, and gating on it would void most honest runs. Descriptors and
 * ports are hard ceilings, and those count.
 */
fun RunResult.ranOutOfRoom(): Boolean = listOf(limits.openFiles, limits.ports).any { it.tight() }

private fun Headroom.tight(): Boolean = when (this) {
    is Headroom.Measured -> used >= TIGHT
    is Headroom.Absent -> false
}

/**
 * Nine tenths of a ceiling.
 *
 * Not the ceiling itself: a run that touched its descriptor limit has already
 * been failing requests for some time, and by the time the peak equals the
 * limit the numbers are long since about this process. The point is to say so
 * before the reader has to work it out from the failure counts.
 */
const val TIGHT: Double = 0.9
