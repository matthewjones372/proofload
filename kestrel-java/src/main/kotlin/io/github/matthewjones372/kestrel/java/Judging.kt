package io.github.matthewjones372.kestrel.java

import io.github.matthewjones372.kestrel.Clock
import io.github.matthewjones372.kestrel.Goal
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.Simulation
import io.github.matthewjones372.kestrel.StepName
import io.github.matthewjones372.kestrel.Verdict
import io.github.matthewjones372.kestrel.expecting
import io.github.matthewjones372.kestrel.failureRate
import io.github.matthewjones372.kestrel.goodput
import io.github.matthewjones372.kestrel.p50
import io.github.matthewjones372.kestrel.p95
import io.github.matthewjones372.kestrel.p99
import io.github.matthewjones372.kestrel.p999
import io.github.matthewjones372.kestrel.percent
import kotlin.time.toKotlinDuration
import java.time.Duration as JavaDuration

/**
 * Goals, built where the value classes are nameable.
 *
 * `Goal` itself crosses a Kotlin signature intact — it is an interface, not a
 * `@JvmInline` value — but every call that makes one takes a `StepName` or a
 * `Share`, which mangles the name it compiles to. So the arguments arrive here
 * as plain `String` and `double` and [Goals] is the Java source that holds the
 * types a caller reads.
 */
internal object Judgements {

    @JvmStatic
    @JvmName("percentileUnder")
    fun percentileUnder(step: String, percentile: String, limit: JavaDuration, clock: Clock): Goal {
        val under = limit.toKotlinDuration()
        val of = StepName(step)
        return when (percentile) {
            "p50" -> p50(of, clock) under under
            "p95" -> p95(of, clock) under under
            "p99" -> p99(of, clock) under under
            else -> p999(of, clock) under under
        }
    }

    @JvmStatic
    @JvmName("failureRateUnder")
    fun failureRateUnder(step: String?, share: Double): Goal = step
        ?.let { failureRate(StepName(it)) under share.percent }
        ?: (failureRate under share.percent)

    @JvmStatic
    @JvmName("goodputAtLeast")
    fun goodputAtLeast(step: String, under: JavaDuration, share: Double, clock: Clock): Goal =
        goodput(StepName(step), under = under.toKotlinDuration(), of = clock) atLeast share.percent

    @JvmStatic
    @JvmName("expecting")
    fun expecting(simulation: Simulation, goals: Array<Goal>): Simulation =
        // Folded rather than spread: `expecting` appends and re-checks on every
        // call, so one at a time is the same simulation without the array copy
        // a spread makes.
        goals.fold(simulation) { built, goal -> built.expecting(goal) }

    /** Every verdict the run's own goals produce, which is what the report prints. */
    @JvmStatic
    @JvmName("verdicts")
    fun verdicts(result: RunResult): List<Verdict> = result.plan.goals.flatMap { it.judgeAll(result) }
}
