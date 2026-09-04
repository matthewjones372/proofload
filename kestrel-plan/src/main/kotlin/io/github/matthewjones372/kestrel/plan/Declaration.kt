package io.github.matthewjones372.kestrel.plan

import io.github.matthewjones372.kestrel.Goal
import io.github.matthewjones372.kestrel.InjectionProfile
import io.github.matthewjones372.kestrel.Rate
import io.github.matthewjones372.kestrel.Scenario
import io.github.matthewjones372.kestrel.Simulation
import io.github.matthewjones372.kestrel.Step
import io.github.matthewjones372.kestrel.StepName
import io.github.matthewjones372.kestrel.ThinkTime
import io.github.matthewjones372.kestrel.failureRate
import io.github.matthewjones372.kestrel.http.Http
import io.github.matthewjones372.kestrel.http.HttpAction
import io.github.matthewjones372.kestrel.http.http
import io.github.matthewjones372.kestrel.p50
import io.github.matthewjones372.kestrel.p95
import io.github.matthewjones372.kestrel.p99
import io.github.matthewjones372.kestrel.p999
import io.github.matthewjones372.kestrel.percent
import kotlin.time.Duration

/**
 * A plan somebody wrote down, before it is anything Kestrel can run.
 *
 * Deliberately a strict subset of what the DSL builds: everything here lowers
 * to the same values `scenario { }` produces, and anything needing a lambda —
 * a capture, a condition, a body computed per user — is absent rather than
 * spelled with a string key. A file that grew those would be a worse language
 * for the same job.
 */
data class Declaration(
    val version: String,
    val baseUrl: String,
    val scenario: String,
    val steps: List<DeclaredStep>,
    val load: DeclaredLoad,
    val goals: List<DeclaredGoal> = emptyList(),
) {

    companion object {

        /** What a file must say it is. Kept as the written form, so a reader sees what it typed. */
        const val VERSION: String = "plan/1"
    }
}

/** One request. `expecting` defaults to 200 because most steps say so and none should have to. */
data class DeclaredStep(
    val name: String,
    val method: String,
    val path: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val expecting: Int = OK,
    /** A wait after this step, which records nothing and is not latency. */
    val pauseAfter: Duration? = null,
)

/** The shape of the load, in the three forms a file can state without a lambda. */
sealed interface DeclaredLoad {

    data class Constant(val rate: Rate, val over: Duration) : DeclaredLoad

    data class Ramp(val from: Rate, val to: Rate, val over: Duration) : DeclaredLoad

    data class Staged(val stages: List<DeclaredLoad>) : DeclaredLoad
}

/** A goal, in the two kinds a file can state. */
sealed interface DeclaredGoal {

    /** Which step this is about, or null where a goal is about the whole run. */
    val step: String?

    data class Percentile(override val step: String, val percentile: String, val under: Duration) : DeclaredGoal

    data class FailureRate(override val step: String?, val under: Double) : DeclaredGoal
}

/**
 * The simulation this declaration describes.
 *
 * Everything a file can get wrong is refused here rather than at the first
 * departure: an unknown version, no steps, a verb nobody serves, a goal naming
 * a step that was never declared.
 */
fun Declaration.asSimulation(): Simulation {
    require(version == Declaration.VERSION) {
        "this is a ${Declaration.VERSION} reader and the plan says `$version`"
    }
    require(steps.isNotEmpty()) { "a plan sends at least one step, and `$scenario` declares none" }

    val declared = steps.map { it.name }
    goals.forEach { goal ->
        val named = goal.step
        require(named == null || named in declared) {
            "a goal names the step `$named`, which is not one of ${declared.joinToString { "`$it`" }}"
        }
    }

    val api = http.baseUrl(baseUrl)
    return Simulation(
        scenario = Scenario(scenario, steps.flatMap { it.asSteps(api) }),
        profile = load.asProfile(),
        goals = goals.map { it.asGoal() },
    )
}

private fun DeclaredStep.asSteps(api: Http): List<Step> =
    listOfNotNull(
        Step.Exec(name, asAction(api)),
        pauseAfter?.let { Step.Pause(ThinkTime.Constant(it)) },
    )

private fun DeclaredStep.asAction(api: Http): HttpAction {
    val request = when (method.uppercase()) {
        "GET" -> api.get(path)
        "POST" -> api.post(path)
        "PUT" -> api.put(path)
        "PATCH" -> api.patch(path)
        "DELETE" -> api.delete(path)
        "HEAD" -> api.head(path)
        else -> throw IllegalArgumentException("step `$name` names the method `$method`, which is not one this reads")
    }

    val withHeaders = headers.entries.fold(request) { action, (key, value) -> action.header(key, value) }
    return (body?.let { withHeaders.body(it) } ?: withHeaders).expecting(expecting)
}

private fun DeclaredLoad.asProfile(): InjectionProfile = when (this) {
    is DeclaredLoad.Constant -> InjectionProfile.ConstantRate(rate.perSecond, over)
    is DeclaredLoad.Ramp -> InjectionProfile.RampRate(from.perSecond, to.perSecond, over)
    is DeclaredLoad.Staged -> InjectionProfile.Stages(stages.map { it.asProfile() })
}

private fun DeclaredGoal.asGoal(): Goal = when (this) {
    is DeclaredGoal.Percentile -> when (percentile) {
        "p50" -> p50(StepName(step)) under under
        "p95" -> p95(StepName(step)) under under
        "p99" -> p99(StepName(step)) under under
        "p999" -> p999(StepName(step)) under under
        else -> throw IllegalArgumentException("a goal names `$percentile`, which is not p50, p95, p99 or p999")
    }

    is DeclaredGoal.FailureRate ->
        step
            ?.let { failureRate(StepName(it)) under under.percent }
            ?: (failureRate under under.percent)
}

private const val OK = 200
