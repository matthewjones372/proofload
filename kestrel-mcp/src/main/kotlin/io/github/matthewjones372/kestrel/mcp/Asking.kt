package io.github.matthewjones372.kestrel.mcp

import io.github.matthewjones372.kestrel.plan.Declaration
import io.github.matthewjones372.kestrel.plan.DeclaredGoal
import io.github.matthewjones372.kestrel.plan.DeclaredLoad

/**
 * What this plan is guessing, put as questions somebody can answer.
 *
 * A generated plan is a draft with defaults in it: one a second because nothing
 * said otherwise, a goal of a second because a plan that asserts nothing passes
 * for free. Handing that over as though it were a benchmark is the failure mode
 * — a number nobody chose, reported as though somebody had.
 *
 * Every question here is derived from this plan and this smoke rather than
 * asked from a list. A question about a credential is asked because the target
 * said 401, not because targets often need one; a question about paths is asked
 * because the caller gave a bare URL and got `GET /`. A fixed list would ask
 * about auth on a plan that authenticated fine, and get ignored on the next
 * plan for it.
 */
internal fun Declaration.questions(from: Source, smoked: String): List<String> = buildList {
    when (from) {
        Source.BareUrl -> add(
            "This only sends `GET /`, because a base URL is all it was given. " +
                "Which paths actually matter — a journey, a hot endpoint, a slow one?",
        )

        Source.Document -> add(
            "This took the ${steps.size} read operation(s) the document declares, in the order it declares them. " +
                "Which of them matter under load, and is any write worth including?",
        )

        Source.Written -> Unit
    }

    if (load.isDefaulted()) {
        add(
            "The rate is a placeholder — one a second, because nothing said otherwise. " +
                "What does this see at peak, and over how long?",
        )
    }

    if (goals.all { it.isPlaceholder() } && goals.isNotEmpty()) {
        add(
            "Every goal is a placeholder of one second, which almost anything passes. " +
                "What p99 counts as too slow here?",
        )
    }

    // Asked because the target said so, not because targets often do.
    if (smoked.contains("401") || smoked.contains("403")) {
        add("The target refused the smoke as unauthorised. What credential should these steps carry?")
    }

    if (smoked.contains("404")) {
        add("The target answered 404 for at least one step. Are those paths right, or does one need a real id?")
    }

    if (smoked.contains("status 5")) {
        add("The target answered 5xx to a single request. Worth fixing before load rather than measuring under it.")
    }
}

/** Where the plan came from, which decides what it is most likely to be guessing. */
internal enum class Source {
    BareUrl,
    Document,
    Written,
}

/** The rate `planFrom` writes when nobody has said what the real one is. */
private fun DeclaredLoad.isDefaulted(): Boolean =
    this is DeclaredLoad.Constant && rate.perSecond <= 1.0

private fun DeclaredGoal.isPlaceholder(): Boolean =
    this is DeclaredGoal.Percentile && under.inWholeMilliseconds >= PLACEHOLDER_MILLIS

private const val PLACEHOLDER_MILLIS = 1_000L
