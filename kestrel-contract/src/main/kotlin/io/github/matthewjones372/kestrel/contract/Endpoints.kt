package io.github.matthewjones372.kestrel.contract

import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.plan.Declaration
import io.github.matthewjones372.kestrel.plan.DeclaredGoal
import io.github.matthewjones372.kestrel.plan.DeclaredLoad
import io.github.matthewjones372.kestrel.plan.DeclaredStep
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.Method
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A first draft of a load test, from the endpoint values a service is built
 * from.
 *
 * A draft, not a test. A document says what endpoints exist and never that
 * browsing precedes checkout, so the steps come out in the order they were
 * declared and the journey is the caller's to write. 0080 derives order from
 * traffic, which is the other question.
 */
fun planFrom(
    endpoints: List<Endpoint<*, *>>,
    baseUrl: String,
    scenario: String = "smoke",
    methods: Set<Method> = setOf(Method.GET),
    seed: Long = 0L,
): Declaration {
    val taken = endpoints.filter { it.method in methods }
    require(taken.isNotEmpty()) {
        "no endpoint uses ${methods.joinToString { it.name }}; the ones given are " +
            endpoints.joinToString { "${it.method} ${it.pathSpec.template}" }
    }

    val steps = taken.mapIndexed { index, endpoint -> endpoint.asStep(seed + index) }
    return Declaration(
        version = Declaration.VERSION,
        baseUrl = baseUrl,
        scenario = scenario,
        steps = steps,
        // Deliberately tiny. A generated artefact is never the thing that hurt
        // something: the caller raises this on purpose, under 0088's ceiling.
        load = DeclaredLoad.Constant(SMOKE_RATE.perSecond, SMOKE_WINDOW),
        // One goal per step against a placeholder, so a generated plan does not
        // validate green while asserting nothing.
        goals = steps.map { DeclaredGoal.Percentile(it.name, "p99", PLACEHOLDER_LIMIT) },
    )
}

/**
 * Named by the operation the contract named, because that is what its own
 * document calls the row and what a reader will search for; falling back to the
 * template, never to a substituted URL, so `/orders/{id}` is one row rather
 * than one per id.
 *
 * The path itself is filled, though. `{id}` in a path is read from the session
 * key of that name and fails the step when nothing is there, and a plan has no
 * feeder to put one there — so a generated step whose path kept its braces
 * would fail every request it made. One value the contract already calls legal,
 * the same one every user sends: this is a smoke at one a second, and per-user
 * variety is what `kestrel emit` and a feeder are for.
 */
private fun Endpoint<*, *>.asStep(seed: Long): DeclaredStep.Request = DeclaredStep.Request(
    name = operationId?.takeIf { it.isNotBlank() } ?: "${method.name.lowercase()} ${pathSpec.template}",
    method = method.name,
    path = filledPath(seed),
    expecting = output.status,
    // The failures the contract puts in the endpoint's own type. A load test
    // otherwise has to be told these by hand, per step, and mostly is not — so
    // a declared 404 is counted beside an undeclared 500 and the run reports a
    // failure rate that describes two different things.
    declared = errors.mapNotNull { it.status }.distinct().sorted(),
)

private fun Endpoint<*, *>.filledPath(seed: Long): String =
    pathSpec.captures.foldIndexed(pathSpec.template) { index, path, param ->
        path.replace("{${param.name}}", param.legalValue(seed + index))
    }

/** One a second for ten seconds: enough to see it answer, not enough to be an event. */
private const val SMOKE_RATE = 1
private val SMOKE_WINDOW = 10.seconds
private val PLACEHOLDER_LIMIT = 1_000.milliseconds
