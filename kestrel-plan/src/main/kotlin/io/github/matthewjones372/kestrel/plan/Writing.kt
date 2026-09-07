package io.github.matthewjones372.kestrel.plan

import kotlin.time.Duration

/**
 * A declaration as the file somebody edits.
 *
 * Written by hand rather than through the parser's emitter: what a generated
 * plan looks like on the page is half of whether anyone keeps it, and a dumper
 * decides quoting and ordering for itself. `readPlan(plan.asYaml())` is the
 * same declaration, which is the test that keeps this honest.
 */
fun Declaration.asYaml(): String = buildString {
    appendLine("# Read from a contract. Edit it, commit it, raise the rate on purpose.")
    appendLine("kestrel:  $version")
    baseUrl?.let { appendLine("baseUrl:  $it") }
    brokers?.let { appendLine("brokers:  $it") }
    appendLine("scenario: $scenario")
    appendLine("steps:")
    steps.forEach { appendLine(it.written()) }
    appendLine("load:")
    append(load.written())
    if (goals.isNotEmpty()) {
        appendLine("goals:")
        goals.forEach { appendLine(it.written()) }
    }
}

private fun DeclaredStep.written(): String = buildString {
    appendLine("  - name: ${name.quoted()}")
    when (val step = this@written) {
        is DeclaredStep.Request -> append(step.body())
        is DeclaredStep.Produce -> append(step.body())
        is DeclaredStep.Completes -> append(step.body())
    }
    pauseAfter?.let { appendLine("    pauseAfter: ${it.written()}") }
}.trimEnd()

private fun DeclaredStep.Request.body(): String = buildString {
    appendLine("    ${method.lowercase()}: ${path.quoted()}")
    if (headers.isNotEmpty()) {
        appendLine("    headers:")
        headers.forEach { (key, value) -> appendLine("      ${key.quoted()}: ${value.quoted()}") }
    }
    body?.let { appendLine("    body: ${it.quoted()}") }
    if (expecting != OK) appendLine("    expecting: $expecting")
    if (declared.isNotEmpty()) appendLine("    declared: [${declared.joinToString()}]")
}

private fun DeclaredStep.Produce.body(): String = buildString {
    appendLine("    produce: ${topic.quoted()}")
    key?.let { appendLine("    key: ${it.quoted()}") }
    appendLine("    body: ${body.quoted()}")
    if (settings.isNotEmpty()) {
        appendLine("    settings:")
        settings.forEach { (key, value) -> appendLine("      ${key.quoted()}: ${value.quoted()}") }
    }
}

private fun DeclaredStep.Completes.body(): String = buildString {
    appendLine("    completes: ${completes.quoted()}")
    appendLine("    on: ${on.quoted()}")
    appendLine("    by: ${by.quoted()}")
    group?.let { appendLine("    group: ${it.quoted()}") }
    appendLine("    within: ${within.written()}")
}

private fun DeclaredLoad.written(indent: String = "  "): String = when (this) {
    is DeclaredLoad.Constant -> "${indent}rate: ${rate.perSecond.trimmed()}/s\n${indent}over: ${over.written()}\n"

    is DeclaredLoad.Ramp ->
        "${indent}from: ${from.perSecond.trimmed()}/s\n${indent}to: ${to.perSecond.trimmed()}/s\n" +
            "${indent}over: ${over.written()}\n"

    is DeclaredLoad.Staged ->
        "${indent}stages:\n" +
            stages.joinToString("") { stage ->
                stage.written("$indent    ").replaceFirst("$indent    ", "$indent  - ")
            }
}

private fun DeclaredGoal.written(): String = when (this) {
    is DeclaredGoal.Percentile -> "  - step: ${step.quoted()}\n    $percentile: ${under.written()}"

    is DeclaredGoal.FailureRate ->
        step
            ?.let { "  - step: ${it.quoted()}\n    failureRate: \"$under%\"" }
            ?: "  - failureRate: \"$under%\""
}

/**
 * Quoted where YAML would otherwise read it as something else, and left plain
 * where it would not — a file nobody wants to edit is one nobody keeps.
 */
private fun String.quoted(): String = if (plainEnough()) this else "'" + replace("'", "''") + "'"

private fun String.plainEnough(): Boolean {
    if (isEmpty() || first().isWhitespace() || last().isWhitespace()) return false
    return none { it in AWKWARD }
}

/** The characters that would make YAML read a bare scalar as something structural. */
private const val AWKWARD = ":#{}[],&*!|>'\"%@`"

private fun Duration.written(): String = toString()

private fun Double.trimmed(): String = if (this == toLong().toDouble()) toLong().toString() else toString()

private const val OK = 200
