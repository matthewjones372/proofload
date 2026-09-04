package io.github.matthewjones372.kestrel.mcp

import io.github.matthewjones372.kestrel.Allowance
import io.github.matthewjones372.kestrel.Preview
import io.github.matthewjones372.kestrel.Progress
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.engine.Kestrel
import io.github.matthewjones372.kestrel.http.DeclaredStatus
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.plan.Declaration
import io.github.matthewjones372.kestrel.plan.asSimulation
import io.github.matthewjones372.kestrel.preview
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.time.Duration.Companion.seconds

/**
 * The two tools that send something, and send only what a plan describes rather
 * than what its rate asks for.
 *
 * Between them they are the difference between a caller that iterates and one
 * that guesses: a plan answering 400s produces a run full of them and the run
 * says only that they were 400s. Firing three thousand requests to find a typo
 * in a path is the other half of the same mistake.
 */
internal fun smoke(arguments: Map<String, Any?>, allowance: Allowance): String =
    sending(arguments, allowance) { plan ->
        val result = onceThrough(plan)
        val undeclared = result.undeclared()
        content(
            buildString {
                appendLine("${result.count} requests, ${result.ok} ok, ${result.failed} failed")
                result.steps.values.forEach { step ->
                    val why = step.failed.reasons.keys.joinToString { it.described }
                    appendLine("  ${step.name}: ${step.count} sent${if (why.isEmpty()) "" else ", $why"}")
                }
                if (result.failed > undeclared) {
                    appendLine(
                        "${result.failed - undeclared} of those the contract declares, " +
                            "which is the service working.",
                    )
                }
            }.trimEnd(),
            // Undeclared only. A documented 401 is the service behaving as
            // written, and reporting it as something gone wrong throws away the
            // distinction the declared list exists to make.
            failed = undeclared > 0,
        )
    }

internal fun trace(arguments: Map<String, Any?>, allowance: Allowance): String =
    sending(arguments, allowance) { plan ->
        // `trace` narrates to stdout, which this process has already pointed at
        // stderr so it cannot corrupt the protocol. Captured here instead,
        // because the narration *is* the answer to this tool.
        content(capturing { Kestrel(progress = Progress.silent).trace(plan.asSimulation().arms.single().scenario) })
    }

/**
 * One user through every step, whatever the plan's rate says.
 *
 * A smoke bounded by the plan's own shape rather than by its load: the point is
 * to find a path that 404s before three thousand requests do.
 */
private fun onceThrough(plan: Declaration): RunResult =
    Kestrel(progress = Progress.silent)
        .run(plan.asSimulation().arms.single().scenario.at(1.perSecond, over = 1.seconds))

/**
 * Reads the plan, asks the allowance about the hosts it would reach, and only
 * then sends.
 *
 * The rate is not the allowance's business here — one request per step is
 * inside any ceiling — but the host is: a plan pointed somewhere this machine
 * does not permit is refused whether it would send one request or a million.
 */
private fun sending(
    arguments: Map<String, Any?>,
    allowance: Allowance,
    send: (Declaration) -> String,
): String = onThePlan(arguments) { plan ->
    when (val asked = plan.asSimulation().preview(allowance.forOneRequest())) {
        is Preview.Refused -> content("refused: ${asked.reason.described}", failed = true)
        is Preview.Allowed -> send(plan)
    }
}

/** The same fence with its rate, window and count set aside: this sends one of each. */
private fun Allowance.forOneRequest(): Allowance =
    copy(maxRate = null, maxDuration = null, maxRequests = null)

private fun capturing(walk: () -> Unit): String {
    val caught = ByteArrayOutputStream()
    val was = System.out
    System.setOut(PrintStream(caught, true, Charsets.UTF_8))
    try {
        walk()
    } finally {
        System.setOut(was)
    }
    return caught.toString(Charsets.UTF_8).trimEnd()
}

/**
 * Failures the contract did not declare.
 *
 * A declared status is still a failed step — it did not do what was asked — and
 * is still not a defect. Everything that decides whether something is *wrong*
 * counts these rather than [RunResult.failed].
 */
internal fun RunResult.undeclared(): Long = steps.values.sumOf { step ->
    step.failed.reasons.entries.filterNot { it.key is DeclaredStatus }.sumOf { it.value }
}
