package io.github.matthewjones372.kestrel.plan

import io.github.matthewjones372.kestrel.Rate
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.composer.Composer
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
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
    // A parse failure is the parser's own exception type, and every caller of
    // this catches `IllegalArgumentException` — so without this a document
    // that is not YAML at all goes past all of them and out of the process.
    // It already knows the line; that is the half worth keeping.
    val root = try {
        Composer(settings, ParserImpl(settings, StreamReader(settings, text)))
            .singleNode
            .orElseThrow { IllegalArgumentException("the plan is empty") }
    } catch (unreadable: YamlEngineException) {
        throw IllegalArgumentException(unreadable.said(), unreadable)
    }

    val plan = root.mapping("the plan")
    plan.only(PLAN_KEYS)

    val steps = plan.required("steps").sequence("steps").value.map { it.step() }
    return Declaration(
        version = plan.required("kestrel").text(),
        baseUrl = plan.optional("baseUrl")?.text(),
        brokers = plan.optional("brokers")?.text(),
        scenario = plan.required("scenario").text(),
        steps = steps,
        load = plan.required("load").load(),
        goals = plan.optional("goals")?.sequence("goals")?.value.orEmpty().map { it.goal() },
        draw = plan.optional("draw")?.mapping("draw")?.draws().orEmpty(),
        seed = plan.optional("seed")?.number("seed") ?: 0L,
    )
}

/** The same, from a file. */
fun readPlan(path: Path): Declaration = readPlan(Files.readString(path, Charsets.UTF_8))

private fun Node.step(): DeclaredStep {
    val step = mapping("a step")
    // Every key a step of any kind may carry, checked before the kind is
    // decided, so a misspelled verb is named as the key it is rather than as a
    // step that named no verb.
    step.only(REQUEST_KEYS + PRODUCE_KEYS + COMPLETES_KEYS)

    val verb = VERBS.firstOrNull { step.optional(it) != null }

    return when {
        verb != null -> step.request(verb)
        step.optional("produce") != null -> step.produce()
        step.optional("completes") != null -> step.completes()
        else -> step.fail("a step names one of ${VERBS.joinToString()}, or `produce`, or `completes`")
    }
}

private fun MappingNode.request(verb: String): DeclaredStep.Request {
    only(REQUEST_KEYS)
    return DeclaredStep.Request(
        name = required("name").text(),
        method = verb.uppercase(),
        path = required(verb).text(),
        headers = optional("headers")?.mapping("headers")?.pairs().orEmpty(),
        body = optional("body")?.text(),
        expecting = optional("expecting")?.number("expecting")?.toInt() ?: OK,
        declared = optional("declared")?.sequence("declared")?.value.orEmpty()
            .map { it.number("a declared status").toInt() },
        pauseAfter = optional("pauseAfter")?.duration(),
    )
}

private fun MappingNode.produce(): DeclaredStep.Produce {
    only(PRODUCE_KEYS)
    return DeclaredStep.Produce(
        name = required("name").text(),
        topic = required("produce").text(),
        body = required("body").text(),
        key = optional("key")?.text(),
        settings = optional("settings")?.mapping("settings")?.pairs().orEmpty(),
        pauseAfter = optional("pauseAfter")?.duration(),
    )
}

private fun MappingNode.completes(): DeclaredStep.Completes {
    only(COMPLETES_KEYS)
    return DeclaredStep.Completes(
        name = required("name").text(),
        completes = required("completes").text(),
        on = required("on").text(),
        by = required("by").text(),
        // Required, and said here rather than defaulted: a wait chosen for the
        // caller turns a record the run lost into one it merely did not wait
        // for, or the other way about.
        within = required("within").duration(),
        group = optional("group")?.text(),
        pauseAfter = optional("pauseAfter")?.duration(),
    )
}

/**
 * The generators a plan names, by the session key each fills.
 *
 * One key names the generator and carries its arguments, which is the idiom a
 * step already uses for its verb — and unlike a call written as text it is
 * YAML, which `uniform(keys: 500)` is not.
 */
private fun MappingNode.draws(): Map<String, DeclaredDraw> =
    value.associate { entry -> entry.keyNode.text() to entry.valueNode.draw() }

private fun Node.draw(): DeclaredDraw {
    val drawn = mapping("a draw")
    drawn.only(DRAWS)

    val named = DRAWS.firstOrNull { drawn.optional(it) != null }
        ?: drawn.fail("a draw names one of ${DRAWS.joinToString()}")

    val argument = drawn.required(named)
    return when (named) {
        "uniform" -> DeclaredDraw.Uniform(argument.number("uniform"))

        "zipf" -> argument.mapping("zipf").let {
            it.only(ZIPF_KEYS)
            DeclaredDraw.Zipf(it.required("keys").number("keys"), it.required("skew").decimal("skew"))
        }

        "oneOf" -> DeclaredDraw.OneOf(argument.sequence("oneOf").value.map { it.text() })

        "digits" -> DeclaredDraw.Digits(argument.number("digits").toInt())

        else -> DeclaredDraw.Uuids
    }
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

private fun Node.decimal(what: String): Double =
    text().toDoubleOrNull() ?: fail("$what is a number")

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

/**
 * A parse failure in the vocabulary every other refusal here uses: the line
 * first, then what was wrong with it.
 */
private fun YamlEngineException.said(): String {
    val where = (this as? MarkedYamlEngineException)?.problemMark?.map { it.line + 1 }?.orElse(null)
    val what = (this as? MarkedYamlEngineException)?.problem ?: message.orEmpty()
    return if (where == null) "this is not YAML: ${message.orEmpty()}" else "line $where: $what"
}

/** Every failure comes through here, so every failure names its line. */
private fun Node.fail(said: String): Nothing {
    val line = startMark.map { it.line + 1 }.orElse(0)
    throw IllegalArgumentException("line $line: $said")
}

private val PLAN_KEYS =
    listOf("kestrel", "baseUrl", "brokers", "scenario", "steps", "load", "goals", "draw", "seed")
private val DRAWS = listOf("uniform", "zipf", "oneOf", "digits", "uuids")
private val ZIPF_KEYS = listOf("keys", "skew")
private val VERBS = listOf("get", "post", "put", "patch", "delete", "head")
private val REQUEST_KEYS = listOf("name", "headers", "body", "expecting", "declared", "pauseAfter") + VERBS
private val PRODUCE_KEYS = listOf("name", "produce", "body", "key", "settings", "pauseAfter")
private val COMPLETES_KEYS = listOf("name", "completes", "on", "by", "within", "group", "pauseAfter")
private val LOAD_KEYS = listOf("rate", "over", "from", "to", "stages")
private val PERCENTILES = listOf("p50", "p95", "p99", "p999")
private val GOAL_KEYS = listOf("step", "failureRate") + PERCENTILES
private const val OK = 200
