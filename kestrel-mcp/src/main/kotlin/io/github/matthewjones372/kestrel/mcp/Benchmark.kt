package io.github.matthewjones372.kestrel.mcp

import io.github.matthewjones372.kestrel.Allowance
import io.github.matthewjones372.kestrel.Preview
import io.github.matthewjones372.kestrel.openapi.planFromDocument
import io.github.matthewjones372.kestrel.plan.Declaration
import io.github.matthewjones372.kestrel.plan.asSimulation
import io.github.matthewjones372.kestrel.plan.asYaml
import io.github.matthewjones372.kestrel.plan.readPlan
import io.github.matthewjones372.kestrel.preview

/**
 * The tool a request actually arrives as.
 *
 * Everything else here is a verb on Kestrel's own model, which is the shape a
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
    val plan = try {
        arguments.asPlan() ?: return content(WANTED, failed = true)
    } catch (unreadable: IllegalArgumentException) {
        return content(unreadable.message.orEmpty(), failed = true)
    }

    val asked = try {
        plan.asSimulation().preview(allowance)
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
            appendLine("Nothing above sent load. Call `run` with this plan to do that.")
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
private fun Map<String, Any?>.asPlan(): Declaration? {
    (this["plan"] as? String)?.let { return readPlan(it) }
    (this["document"] as? String)?.let { return planFromDocument(it, this["baseUrl"] as? String) }

    return (this["baseUrl"] as? String)?.let { url ->
        readPlan(
            """
            kestrel:  plan/1
            baseUrl:  $url
            scenario: smoke
            steps:
              - name: root
                get:  /
            load:
              rate: 1/s
              over: 10s
            """.trimIndent(),
        )
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
