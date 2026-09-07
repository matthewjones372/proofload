package io.github.matthewjones372.kestrel.plan

import kotlin.time.Duration

/**
 * A declaration as the Kotlin somebody would have written.
 *
 * The graduation path, and what stops `plan/1` becoming a worse DSL: the moment
 * a plan needs a capture, a condition or a body per user, this prints the
 * source it was equivalent to and the caller carries on in the language rather
 * than asking for another key in the file.
 *
 * `kestrel-record` emits Kotlin too and is not reused. Its output is a scenario
 * where this is a whole load test, profile and goals included, and its header
 * describes a browser recording with the credentials stripped out — a false
 * account of where a plan came from. What the two share is the shape of one
 * HTTP line.
 */
fun Declaration.asKotlin(packageName: String, from: String): String {
    val handles = steps.associate { it.name to it.name.identifier() }

    return (
        header(from) +
            "package $packageName" +
            "" +
            imports().sorted() +
            "" +
            steps.map { "val ${handles.getValue(it.name)} = step(\"${it.name}\")" } +
            listOfNotNull(baseUrl?.let { "val api = http.baseUrl(\"$it\")" }) +
            topicLines() +
            "" +
            "val ${scenario.identifier()}: Scenario = scenario(\"$scenario\") {" +
            steps.flatMap { lines(it, handles.getValue(it.name)) }.map { "    $it" } +
            "}" +
            "" +
            "val simulation = ${scenario.identifier()}" +
            load.lines().map { "    $it" } +
            completionLines(handles) +
            goalLines(handles)
        ).joinToString(separator = "\n", postfix = "\n")
}

private fun header(from: String): List<String> = listOf(
    "// Written from $from by `kestrel emit`.",
    "//",
    "// A plan file says what it can without a lambda. This is the same run in the",
    "// language, which is where a capture, a condition or a body per user goes —",
    "// none of which a plan can state, and none of which should be added to one.",
    "",
)

private fun Declaration.goalLines(handles: Map<String, String>): List<String> =
    if (goals.isEmpty()) {
        emptyList()
    } else {
        listOf("    .expecting(") + goals.map { "        ${it.line(handles)}," } + listOf("    )")
    }

/**
 * One `Topic` value per topic a plan produces to, named once above the
 * scenario: two steps on one topic are two sends through the same producer,
 * which is what the Kotlin would have written and what the run then measures.
 */
private fun Declaration.topicLines(): List<String> {
    val produced = steps.filterIsInstance<DeclaredStep.Produce>()
    if (produced.isEmpty()) return emptyList()

    val answers = steps.filterIsInstance<DeclaredStep.Completes>()
    return listOf("val cluster = kafka.brokers(\"$brokers\")") +
        produced.distinctBy { it.topic }.map { step ->
            buildString {
                append("val ${step.topic.identifier()}Topic = cluster")
                step.settings.forEach { (key, value) -> append(".setting(\"$key\", \"$value\")") }
                append(".topic(\"${step.topic}\")")
                answerTo(step)?.let { append(".correlatedBy(Header(\"${it.by}\"))") }
            }
        } +
        answers.map { answer ->
            "val ${answer.on.identifier()}Topic = cluster" +
                answer.group?.let { ".setting(\"group.id\", \"$it\")" }.orEmpty() +
                ".topic(\"${answer.on}\").correlatedBy(Header(\"${answer.by}\"))"
        } +
        if (answers.isEmpty()) {
            emptyList()
        } else {
            // The id is the user's number, which is unique per departure in an
            // open model and is the only value a plan has without a lambda.
            listOf(
                "val user = sessionKey<Long>(\"user\")",
                "val byUser = Correlation { session -> session[user] ?: -1L }",
            )
        }
}

/** The declared answer to [step], where the plan declares one. */
private fun Declaration.answerTo(step: DeclaredStep.Produce): DeclaredStep.Completes? =
    steps.filterIsInstance<DeclaredStep.Completes>().firstOrNull { it.completes == step.name }

/**
 * The sink the run is drained into, and the feeder the correlation reads from.
 *
 * Beside the rate rather than in the scenario, because that is where they live
 * in the language: a sink belongs to the run, not to a position in a journey.
 */
private fun Declaration.completionLines(handles: Map<String, String>): List<String> =
    steps.filterIsInstance<DeclaredStep.Completes>().flatMap { answer ->
        listOf(
            "    .fedBy(feed(user) { it })",
            "    .completing(" +
                "${handles.getValue(answer.name)}, " +
                "from = ${answer.on.identifier()}Topic.completions(), " +
                "drainingFor = ${answer.within.written()})",
        )
    }

/**
 * Sorted by the caller, because emitted source has to satisfy the same import
 * rule as hand-written source: unordered imports are a formatter's diff waiting
 * to happen in whatever project this is pasted into.
 */
private fun Declaration.imports(): List<String> = buildList {
    add("import io.github.matthewjones372.kestrel.Scenario")
    add("import io.github.matthewjones372.kestrel.at")
    if (goals.isNotEmpty()) add("import io.github.matthewjones372.kestrel.expecting")
    if (goals.any { it is DeclaredGoal.FailureRate }) add("import io.github.matthewjones372.kestrel.failureRate")
    if (load !is DeclaredLoad.Constant) add("import io.github.matthewjones372.kestrel.injecting")
    goals.filterIsInstance<DeclaredGoal.Percentile>()
        .map { it.percentile }
        .distinct()
        .sorted()
        .forEach { add("import io.github.matthewjones372.kestrel.$it") }
    if (steps.any { it.pauseAfter != null }) add("import io.github.matthewjones372.kestrel.pause")
    if (goals.any { it is DeclaredGoal.FailureRate }) add("import io.github.matthewjones372.kestrel.percent")
    add("import io.github.matthewjones372.kestrel.perSecond")
    if (load is DeclaredLoad.Ramp) add("import io.github.matthewjones372.kestrel.rampRate")
    add("import io.github.matthewjones372.kestrel.scenario")
    add("import io.github.matthewjones372.kestrel.step")
    if (steps.any { it is DeclaredStep.Request }) add("import io.github.matthewjones372.kestrel.http.http")
    addAll(kafkaImports())
    units().forEach { add("import kotlin.time.Duration.Companion.$it") }
}

/** What the Kafka half of an emitted plan names, and nothing when it names none. */
private fun Declaration.kafkaImports(): List<String> = buildList {
    val produced = steps.filterIsInstance<DeclaredStep.Produce>()
    if (produced.isEmpty()) return@buildList

    add("import io.github.matthewjones372.kestrel.kafka.kafka")
    if (produced.any { answerTo(it) == null }) add("import io.github.matthewjones372.kestrel.kafka.produce")
    if (produced.none { answerTo(it) != null }) return@buildList

    add("import io.github.matthewjones372.kestrel.Correlation")
    add("import io.github.matthewjones372.kestrel.completing")
    add("import io.github.matthewjones372.kestrel.fedBy")
    add("import io.github.matthewjones372.kestrel.feed")
    add("import io.github.matthewjones372.kestrel.sessionKey")
    add("import io.github.matthewjones372.kestrel.kafka.Header")
    add("import io.github.matthewjones372.kestrel.kafka.completions")
    add("import io.github.matthewjones372.kestrel.kafka.emit")
}

/** Only the duration units the emitted source actually names, so no import is unused. */
private fun Declaration.units(): List<String> {
    val durations = buildList {
        addAll(steps.mapNotNull { it.pauseAfter })
        addAll(steps.filterIsInstance<DeclaredStep.Completes>().map { it.within })
        addAll(goals.filterIsInstance<DeclaredGoal.Percentile>().map { it.under })
        addAll(load.durations())
    }
    return durations.map { it.unit() }.distinct().sorted()
}

private fun DeclaredLoad.durations(): List<Duration> = when (this) {
    is DeclaredLoad.Constant -> listOf(over)
    is DeclaredLoad.Ramp -> listOf(over)
    is DeclaredLoad.Staged -> stages.flatMap { it.durations() }
}

private fun Declaration.lines(step: DeclaredStep, handle: String): List<String> = buildList {
    when (step) {
        is DeclaredStep.Request -> add(
            buildString {
                append("exec($handle, api.${step.method.lowercase()}(\"${step.path}\")")
                step.headers.forEach { (key, value) -> append(".header(\"$key\", \"$value\")") }
                step.body?.let { append(".body(\"\"\"$it\"\"\")") }
                if (step.expecting != OK) append(".expecting(${step.expecting})")
                if (step.declared.isNotEmpty()) append(".declaring(${step.declared.joinToString()})")
                append(")")
            },
        )

        is DeclaredStep.Produce -> add(
            buildString {
                val answered = answerTo(step) != null
                append(if (answered) "emit" else "produce")
                append("($handle, ${step.topic.identifier()}Topic")
                step.key?.let { append(".keyed { \"\"\"$it\"\"\".toByteArray() }") }
                append(".value { \"\"\"${step.body}\"\"\".toByteArray() }")
                if (answered) append(", keyedBy = byUser")
                append(")")
            },
        )

        // Drained by the run rather than sent from inside the scenario, so it
        // is a line under the rate and nothing here.
        is DeclaredStep.Completes -> Unit
    }
    step.pauseAfter?.let { add("pause(${it.written()})") }
}

private fun DeclaredLoad.lines(): List<String> = when (this) {
    is DeclaredLoad.Constant -> listOf(".at(${rate.perSecond}.perSecond, over = ${over.written()})")

    is DeclaredLoad.Ramp -> listOf(
        ".injecting(rampRate(from = ${from.perSecond}.perSecond, " +
            "to = ${to.perSecond}.perSecond, over = ${over.written()}))",
    )

    // A staged plan has no single rate, so the source names the stages rather
    // than pretending one of them is the run.
    is DeclaredLoad.Staged -> listOf("    // stages, in order:") +
        stages.flatMap { it.lines() }.map { "    // $it" } +
        listOf(".at(${stages.firstConstantRate()}.perSecond, over = ${stages.totalOver().written()})")
}

private fun List<DeclaredLoad>.firstConstantRate(): Double =
    filterIsInstance<DeclaredLoad.Constant>().firstOrNull()?.rate?.perSecond ?: 1.0

private fun List<DeclaredLoad>.totalOver(): Duration =
    fold(Duration.ZERO) { total, stage -> total + stage.durations().fold(Duration.ZERO) { a, b -> a + b } }

private fun DeclaredGoal.line(handles: Map<String, String>): String = when (this) {
    is DeclaredGoal.Percentile -> "$percentile(${handles.getValue(step)}) under ${under.written()}"

    is DeclaredGoal.FailureRate ->
        step
            ?.let { "failureRate(${handles.getValue(it)}) under $under.percent" }
            ?: "failureRate under $under.percent"
}

/**
 * A duration as Kotlin source rather than as [Duration.toString], which prints
 * `1m` — a thing this library reads and the compiler does not.
 */
private fun Duration.written(): String = when (unit()) {
    "minutes" -> "$inWholeMinutes.minutes"
    "seconds" -> "$inWholeSeconds.seconds"
    else -> "$inWholeMilliseconds.milliseconds"
}

private fun Duration.unit(): String = when {
    inWholeMilliseconds % MILLIS_A_MINUTE == 0L && this >= Duration.ZERO -> "minutes"
    inWholeMilliseconds % MILLIS_A_SECOND == 0L -> "seconds"
    else -> "milliseconds"
}

/** `place order` is a step name and `placeOrder` is what the source calls it. */
private fun String.identifier(): String = split(' ', '-', '_', '/')
    .filter { it.isNotEmpty() }
    .mapIndexed { index, word ->
        val cleaned = word.filter { it.isLetterOrDigit() }
        if (index == 0) cleaned.replaceFirstChar { it.lowercase() } else cleaned.replaceFirstChar { it.uppercase() }
    }
    .joinToString("")

private const val OK = 200
private const val MILLIS_A_SECOND = 1_000L
private const val MILLIS_A_MINUTE = 60_000L
