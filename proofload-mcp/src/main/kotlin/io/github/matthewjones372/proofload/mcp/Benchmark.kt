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
 * The tool a request actually arrives as.
 *
 * Everything else here is a verb on Proofload's own model, which is the shape a
 * library has and not the shape a question has. Nobody asks to validate a plan;
 * they ask whether their service holds up. Answering that through the
 * primitives takes seven calls in an order the caller has to infer, and
 * inferring it wrongly is how a run gets fired before anyone previewed it.
 *
 * So this does the whole safe half in one call — plan, validate, preview,
 * smoke — and stops. It sends one request per step and no load. `run` still
 * sends the load, which makes the gate the allowance exists to create the only
 * way through rather than a step a caller has to remember.
 */
internal fun benchmark(arguments: Map<String, Any?>, allowance: Allowance): String {
    val (plan, from) = try {
        arguments.asPlan() ?: return content(WANTED, failed = true)
    } catch (unreadable: IllegalArgumentException) {
        return content(unreadable.message.orEmpty(), failed = true)
    }

    val asked = try {
        plan.asSimulation(kafkaLowerings).preview(allowance)
    } catch (unusable: IllegalArgumentException) {
        return content(unusable.message.orEmpty(), failed = true)
    }

    val written = plan.asYaml()
    if (asked is Preview.Refused) {
        // The plan is still worth handing back: a caller told only that a host
        // is not allowed cannot see what it was about to send there.
        return content("$written\nrefused: ${asked.reason.described}", failed = true)
    }

    val allowed = asked as Preview.Allowed
    val smoked = smoke(mapOf("plan" to written), allowance)

    return content(
        buildString {
            appendLine(written)
            appendLine("Running this would send ${allowed.requestsAtLeast} requests over ${allowed.over}")
            allowed.peakRate?.let { appendLine("peaking at ${it.perSecond}/s") }
            appendLine("to ${allowed.hosts.joinToString().ifEmpty { "a host this cannot read" }}.")
            appendLine()
            appendLine("One request per step, already sent:")
            appendLine(smoked.textOnly())
            appendLine()

            // Before the call to action rather than after it: a caller already
            // told what to do next has stopped reading.
            LAST_SMOKE.get()?.recommendation()?.let {
                appendLine("Worth pointing the load at:")
                appendLine("  $it")
                appendLine()
            }

            val asking = plan.questions(from, smoked)
            if (asking.isNotEmpty()) {
                appendLine("This plan is guessing. Ask whoever wants the benchmark:")
                asking.forEach { appendLine("  - $it") }
                appendLine()
            }

            appendLine("Nothing above sent load. Edit the plan with the answers, then call `run` with it.")
            // Where somebody looking for a tool that is not here will actually
            // be looking. A launcher over built jars is stale the moment the
            // source moves, and nothing else says so.
            appendLine("(proofload-mcp ${describedBuild()} — rebuild with `./gradlew :proofload-mcp:installDist`)")
        }.trimEnd(),
        // A smoke that failed is the answer, not a footnote: a plan whose steps
        // 404 once will 404 three thousand times.
        failed = smoked.contains(""""isError":true"""),
    )
}

/**
 * A target, in the three shapes a caller has one.
 *
 * A document is the common case, a plan is for a caller that already has one,
 * and a bare URL is the smallest thing somebody can type — it makes a plan with
 * a single `GET /`, which is a real answer to "is anything there".
 */
private fun Map<String, Any?>.asPlan(): Pair<Declaration, Source>? {
    (this["plan"] as? String)?.let { return readPlan(it) to Source.Written }
    (this["document"] as? String)?.let {
        return planFromDocument(it, this["baseUrl"] as? String) to Source.Document
    }

    return (this["baseUrl"] as? String)?.let { url ->
        readPlan(
            """
            proofload:  plan/1
            baseUrl:  $url
            scenario: smoke
            steps:
              - name: root
                get:  /
            load:
              rate: 1/s
              over: 10s
            """.trimIndent(),
        ) to Source.BareUrl
    }
}

/** The text out of an MCP envelope, so one tool can report what another found. */
private fun String.textOnly(): String = substringAfter(""""text":"""")
    .substringBeforeLast(""""}]""")
    .replace("\\n", "\n")
    .replace("\\\"", "\"")
    .replace("\\\\", "\\")

private const val WANTED =
    "benchmark wants one of: `document` (an OpenAPI document), `plan` (a plan/1 document), or `baseUrl`"
