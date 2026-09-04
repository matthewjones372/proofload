package io.github.matthewjones372.kestrel.plan

import io.github.matthewjones372.kestrel.Rate
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.composer.Composer
import org.snakeyaml.engine.v2.nodes.MappingNode
import org.snakeyaml.engine.v2.nodes.Node
import org.snakeyaml.engine.v2.nodes.ScalarNode
import org.snakeyaml.engine.v2.nodes.SequenceNode
import org.snakeyaml.engine.v2.parser.ParserImpl
import org.snakeyaml.engine.v2.scanner.StreamReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration

/**
 * A plan read from what somebody wrote.
 *
 * Composed to nodes rather than loaded to maps, because every node carries the
 * line it came from and the line is the feature: a caller iterating against
 * this reads its mistake and fixes it, where a reader that said only "expected
 * a string" sends them looking.
 */
fun readPlan(text: String): Declaration {
    val settings = LoadSettings.builder().build()
    val root = Composer(settings, ParserImpl(settings, StreamReader(settings, text)))
        .singleNode
        .orElseThrow { IllegalArgumentException("the plan is empty") }

    val plan = root.mapping("the plan")
    plan.only(PLAN_KEYS)

    val steps = plan.required("steps").sequence("steps").value.map { it.step() }
    return Declaration(
        version = plan.required("kestrel").text(),
        baseUrl = plan.required("baseUrl").text(),
        scenario = plan.required("scenario").text(),
        steps = steps,
        load = plan.required("load").load(),
        goals = plan.optional("goals")?.sequence("goals")?.value.orEmpty().map { it.goal() },
    )
}

/** The same, from a file. */
fun readPlan(path: Path): Declaration = readPlan(Files.readString(path, Charsets.UTF_8))

private fun Node.step(): DeclaredStep {
    val step = mapping("a step")
    step.only(STEP_KEYS)

    val verb = VERBS.firstOrNull { step.optional(it) != null }
        ?: step.fail("a step names one of ${VERBS.joinToString()} and a path")

    return DeclaredStep(
        name = step.required("name").text(),
        method = verb.uppercase(),
        path = step.required(verb).text(),
        headers = step.optional("headers")?.mapping("headers")?.pairs().orEmpty(),
        body = step.optional("body")?.text(),
        expecting = step.optional("expecting")?.number("expecting")?.toInt() ?: OK,
        pauseAfter = step.optional("pauseAfter")?.duration(),
    )
}

private fun Node.load(): DeclaredLoad {
    val load = mapping("load")
    load.only(LOAD_KEYS)

    val stages = load.optional("stages")
    if (stages != null) return DeclaredLoad.Staged(stages.sequence("stages").value.map { it.load() })

    val over = load.required("over").duration()
    val from = load.optional("from")
    return if (from == null) {
        DeclaredLoad.Constant(load.required("rate").rate(), over)
    } else {
        DeclaredLoad.Ramp(from.rate(), load.required("to").rate(), over)
    }
}

private fun Node.goal(): DeclaredGoal {
    val goal = mapping("a goal")
    goal.only(GOAL_KEYS)

    val failure = goal.optional("failureRate")
    if (failure != null) return DeclaredGoal.FailureRate(goal.optional("step")?.text(), failure.share())

    val percentile = PERCENTILES.firstOrNull { goal.optional(it) != null }
        ?: goal.fail("a goal names ${PERCENTILES.joinToString()} or failureRate")

    return DeclaredGoal.Percentile(
        step = goal.required("step").text(),
        percentile = percentile,
        under = goal.required(percentile).duration(),
    )
}

private fun Node.mapping(what: String): MappingNode =
    this as? MappingNode ?: fail("$what is a block of `key: value`")

private fun Node.sequence(what: String): SequenceNode =
    this as? SequenceNode ?: fail("$what is a list")

private fun Node.text(): String = (this as? ScalarNode)?.value ?: fail("this is a single value, not a block")

private fun Node.number(what: String): Long =
    text().toLongOrNull() ?: fail("$what is a whole number")

private fun Node.rate(): Rate = Rate.parse(text()) ?: fail("`${text()}` is not a rate like \"50/s\" or \"30/m\"")

private fun Node.duration(): Duration =
    Duration.parseOrNull(text()) ?: fail("`${text()}` is not a duration like \"200ms\", \"30s\" or \"1m\"")

/** A share as a person writes one: `"1%"`, or the bare number that means the same. */
private fun Node.share(): Double {
    val written = text().removeSuffix("%").trim()
    return written.toDoubleOrNull() ?: fail("`${text()}` is not a share like \"1%\"")
}

private fun MappingNode.pairs(): Map<String, String> =
    value.associate { it.keyNode.text() to it.valueNode.text() }

private fun MappingNode.optional(key: String): Node? =
    value.firstOrNull { it.keyNode.text() == key }?.valueNode

private fun MappingNode.required(key: String): Node =
    optional(key) ?: fail("`$key` is missing")

/**
 * Refuses a key nobody declared, naming it and every key that was allowed.
 *
 * A plan is often written by a program working from the schema, and the fastest
 * way to correct one is to be told what it should have said.
 */
private fun MappingNode.only(keys: List<String>) {
    value.forEach { entry ->
        val key = entry.keyNode.text()
        if (key !in keys) entry.keyNode.fail("`$key` is not one of ${keys.joinToString { "`$it`" }}")
    }
}

/** Every failure comes through here, so every failure names its line. */
private fun Node.fail(said: String): Nothing {
    val line = startMark.map { it.line + 1 }.orElse(0)
    throw IllegalArgumentException("line $line: $said")
}

private val PLAN_KEYS = listOf("kestrel", "baseUrl", "scenario", "steps", "load", "goals")
private val VERBS = listOf("get", "post", "put", "patch", "delete", "head")
private val STEP_KEYS = listOf("name", "headers", "body", "expecting", "pauseAfter") + VERBS
private val LOAD_KEYS = listOf("rate", "over", "from", "to", "stages")
private val PERCENTILES = listOf("p50", "p95", "p99", "p999")
private val GOAL_KEYS = listOf("step", "failureRate") + PERCENTILES
private const val OK = 200
