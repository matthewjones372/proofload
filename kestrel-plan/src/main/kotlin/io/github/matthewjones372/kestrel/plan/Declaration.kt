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
    /** Where HTTP steps send, and null in a plan that declares none. */
    val baseUrl: String? = null,
    /** The cluster produce steps send to, and null in a plan that declares none. */
    val brokers: String? = null,
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

/**
 * One thing a plan sends, in the protocols a file can name without a lambda.
 *
 * Sealed rather than one shape with a nullable half, so a step carries the keys
 * its own protocol has and no others: a produce step has no status to expect
 * and a request has no topic.
 */
sealed interface DeclaredStep {

    val name: String

    /** A wait after this step, which records nothing and is not latency. */
    val pauseAfter: Duration?

    /** One request. `expecting` defaults to 200 because most steps say so and none should have to. */
    data class Request(
        override val name: String,
        val method: String,
        val path: String,
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
        val expecting: Int = OK,
        /**
         * Statuses this endpoint is documented to answer with.
         *
         * They fail the step like any other unasked-for status, under their own
         * reason, so a report can separate a service working as written from one
         * doing something nobody wrote down.
         */
        val declared: List<Int> = emptyList(),
        override val pauseAfter: Duration? = null,
    ) : DeclaredStep

    /**
     * One record produced to a topic, timed as deep as `acks` makes it.
     *
     * [settings] are Kafka's own producer settings by their own names, because
     * a producer's configuration is already a map of string to string and one
     * given a name here is one a reader cannot look up in Kafka's own
     * documentation.
     */
    data class Produce(
        override val name: String,
        val topic: String,
        val body: String,
        /** The partition key, as written. A record with none is placed round-robin. */
        val key: String? = null,
        val settings: Map<String, String> = emptyMap(),
        override val pauseAfter: Duration? = null,
    ) : DeclaredStep
}

/**
 * A declared step this module cannot lower on its own.
 *
 * `kestrel-plan` carries the JDK's HTTP client and lowers a request itself.
 * Anything else needs the module that carries its client, and that module
 * supplies one of these rather than being depended on from here — which is what
 * keeps a plan of nothing but requests off a Kafka classpath.
 */
fun interface Lowering {

    /** The steps [step] becomes, or null where this does not know that step. */
    fun lower(step: DeclaredStep, plan: Declaration): List<Step>?
}

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
 * The request steps this plan declares — all of them, in a plan that names no
 * other protocol.
 *
 * Derived rather than a second list on [Declaration]: two lists that have to be
 * kept in the order they were written is the arrangement a reader gets wrong.
 */
fun Declaration.requests(): List<DeclaredStep.Request> = steps.filterIsInstance<DeclaredStep.Request>()

/**
 * The simulation this declaration describes.
 *
 * Everything a file can get wrong is refused here rather than at the first
 * departure: an unknown version, no steps, a verb nobody serves, a goal naming
 * a step that was never declared.
 */
fun Declaration.asSimulation(lowerings: List<Lowering> = emptyList()): Simulation {
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

    val api = baseUrl?.let { http.baseUrl(it) }
    return Simulation(
        scenario = Scenario(scenario, steps.flatMap { it.asSteps(this, api, lowerings) }),
        profile = load.asProfile(),
        goals = goals.map { it.asGoal() },
    )
}

/**
 * The steps one declared step becomes: the one this module knows, or whatever a
 * caller's [Lowering] makes of it.
 *
 * The lowerings are asked in the order they were given, and the first that
 * answers wins, so a caller can replace a lowering by putting theirs in front
 * rather than by there being a registry to unregister from.
 */
private fun DeclaredStep.asSteps(plan: Declaration, api: Http?, lowerings: List<Lowering>): List<Step> {
    val sent = when (this) {
        is DeclaredStep.Request -> {
            requireNotNull(api) {
                "the step `$name` sends `${method.lowercase()}: $path`, and the plan names no baseUrl"
            }
            listOf(Step.Exec(name, asAction(api)))
        }

        is DeclaredStep.Produce -> {
            requireNotNull(plan.brokers) { "the step `$name` produces to `$topic`, and the plan names no brokers" }
            lowerings.firstNotNullOfOrNull { it.lower(this, plan) }
                ?: throw IllegalArgumentException(
                    "nothing here lowers the produce step `$name`: " +
                        "add `kestrel-plan-kafka` and pass its lowering to asSimulation",
                )
        }
    }

    return sent + listOfNotNull(pauseAfter?.let { Step.Pause(ThinkTime.Constant(it)) })
}

private fun DeclaredStep.Request.asAction(api: Http): HttpAction {
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
    val withBody = (body?.let { withHeaders.body(it) } ?: withHeaders).expecting(expecting)
    // Folded rather than spread: `declaring` adds to the set it already holds,
    // so one at a time is the same action without the array copy.
    return declared.fold(withBody) { action, code -> action.declaring(code) }
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
