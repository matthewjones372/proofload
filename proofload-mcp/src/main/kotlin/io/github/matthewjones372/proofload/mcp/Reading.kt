package io.github.matthewjones372.proofload.mcp

import io.github.matthewjones372.proofload.Allowance
import io.github.matthewjones372.proofload.Preview
import io.github.matthewjones372.proofload.openapi.planFromDocument
import io.github.matthewjones372.proofload.plan.Declaration
import io.github.matthewjones372.proofload.plan.asSimulation
import io.github.matthewjones372.proofload.plan.asYaml
import io.github.matthewjones372.proofload.plan.kafka.kafkaLowerings
import io.github.matthewjones372.proofload.plan.readPlan
import io.github.matthewjones372.proofload.preview

/**
 * The tools that send nothing.
 *
 * Free to call and free to get wrong, which is what lets a caller iterate
 * against a parser instead of guessing. Each one refuses with the sentence the
 * library already writes — the line and what was allowed — rather than a stack
 * trace, because that sentence is the thing a caller acts on.
 */
internal fun validate(arguments: Map<String, Any?>): String = onThePlan(arguments) { plan ->
    plan.asSimulation(kafkaLowerings)
    content("the plan reads, and every goal names a step it declares")
}

internal fun preview(arguments: Map<String, Any?>, allowance: Allowance): String = onThePlan(arguments) { plan ->
    when (val asked = plan.asSimulation(kafkaLowerings).preview(allowance)) {
        is Preview.Refused -> content("refused: ${asked.reason.described}", failed = true)

        is Preview.Allowed -> content(
            buildString {
                appendLine("${asked.users} users over ${asked.over}")
                appendLine("${if (asked.requestsBounded) "" else "at least "}${asked.requestsAtLeast} requests")
                asked.peakRate?.let { appendLine("peaking at ${it.perSecond}/s") }
                appendLine(
                    if (asked.hosts.isEmpty()) "to no host this can read" else "to ${asked.hosts.joinToString()}",
                )
                if (asked.untargeted > 0) {
                    appendLine("${asked.untargeted} step(s) named no host, so nothing here bounds them")
                }
            }.trimEnd(),
        )
    }
}

internal fun fromOpenApi(arguments: Map<String, Any?>): String {
    val document = arguments["document"] as? String
        ?: return content("from_openapi wants a `document`", failed = true)

    return try {
        content(planFromDocument(document, arguments["baseUrl"] as? String).asYaml())
    } catch (unreadable: IllegalArgumentException) {
        content(unreadable.message.orEmpty(), failed = true)
    }
}

/**
 * Reads the plan, or answers with why it could not be read.
 *
 * Shared by every tool that takes one, so a caller gets the same sentence
 * whichever tool it was reaching for.
 */
internal fun onThePlan(arguments: Map<String, Any?>, read: (Declaration) -> String): String {
    val text = arguments["plan"] as? String ?: return content("this tool wants a `plan`", failed = true)

    return try {
        read(readPlan(text))
    } catch (unreadable: IllegalArgumentException) {
        // The parser's own sentence names the line and the keys that were
        // allowed, which is what a caller correcting itself needs. A stack
        // trace buries it.
        content(unreadable.message.orEmpty(), failed = true)
    }
}
