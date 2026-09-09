package io.github.matthewjones372.proofload

import kotlin.time.DurationUnit

/**
 * The users a run had in flight, beside the number its own throughput and
 * latency say it should have had.
 *
 * Little's law — L = λW — is arithmetic rather than a model: over a window
 * that starts and ends empty it holds for any arrival process and any service
 * distribution. So the two sides disagreeing is not a fact about the target,
 * it is a fact about the measurement, and every other number on the page is
 * suspect until it is explained.
 *
 * Absent carries the reason, as [Tail] and [Headroom] do. A run that cannot be
 * checked says so rather than reporting a ratio nobody can read.
 */
sealed interface Concurrency {

    data class Measured(
        /** Users running, averaged over the samples taken in the segment. */
        val observed: Double,
        /** Throughput times mean service time: what was outstanding on the wire. */
        val fromServiceTime: Double,
        /** The same against the clock that counts from the promised departure. */
        val fromResponseTime: Double,
        /** How many samples the observed side rests on. */
        val samples: Int,
    ) : Concurrency {

        /** What was measured over what was predicted: one where the law holds. */
        val ratio: Double get() = if (fromServiceTime == 0.0) 0.0 else observed / fromServiceTime

        /**
         * The queue the generator itself was holding, in requests.
         *
         * `responseTime = serviceTime + schedulingDelay` exactly, so the
         * difference between the two predictions is throughput times the mean
         * lateness — the backlog, counted the way the users are.
         */
        val backlog: Double get() = fromResponseTime - fromServiceTime

        /** Whether the two sides agree to within what this measurement can resolve. */
        val agrees: Boolean get() = ratio >= 1.0 - LAW_TOLERANCE && ratio <= 1.0 + LAW_TOLERANCE
    }

    data class Absent(val because: String) : Concurrency
}

/**
 * Little's law over the segment this run settled into, or why it cannot be
 * asked.
 *
 * Judged over the steady segment rather than the whole run: the identity is
 * exact over a window that starts and ends empty, and a run's opening seconds
 * are neither.
 */
val RunResult.concurrency: Concurrency
    get() {
        if (plan.pauses) {
            return Concurrency.Absent(
                "this scenario parks its users between steps, so a user in flight is not a request in flight",
            )
        }
        val settled = steady
        val window = settled.timeline.size.toDouble()
        if (window <= 0.0) return Concurrency.Absent("nothing was recorded second by second, so there is no window")

        val readings = usersInFlight.drop(usersInFlight.size - settled.usersInFlight.size).filterNotNull()
        if (readings.isEmpty()) return Concurrency.Absent("no user count was sampled, so there is nothing to check")

        val throughput = settled.count / window
        val service = settled.steps.values.map { it.serviceTime }.merged()
        val response = settled.steps.values.map { it.responseTime }.merged()
        if (service.count == 0L) return Concurrency.Absent("nothing was measured in the segment this run settled into")

        return Concurrency.Measured(
            observed = readings.average(),
            fromServiceTime = throughput * service.mean.toDouble(DurationUnit.SECONDS),
            fromResponseTime = throughput * response.mean.toDouble(DurationUnit.SECONDS),
            samples = readings.size,
        )
    }

/**
 * How far the two sides may differ and still be called agreement.
 *
 * The same figure [SteadyState.TOLERANCE] uses, and for the same reason: it is
 * what the coarse timeline can resolve. Symmetric, though the derived side is
 * biased high — every mean is read off bucket tops — because the measured side
 * is a one-hertz sample of a count that moves, and that error runs both ways.
 * A one-sided gate would fire on sampling noise and call it a bug in the tool.
 */
const val LAW_TOLERANCE: Double = SteadyState.TOLERANCE
