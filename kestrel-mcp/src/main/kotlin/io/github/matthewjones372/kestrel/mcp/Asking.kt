package io.github.matthewjones372.kestrel.mcp

import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.plan.Declaration
import io.github.matthewjones372.kestrel.plan.DeclaredGoal
import io.github.matthewjones372.kestrel.plan.DeclaredLoad
import io.github.matthewjones372.kestrel.plan.DeclaredStep
import io.github.matthewjones372.kestrel.plan.requests

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

    addAll(topicQuestions())
    addAll(literalQuestions())
}

/**
 * A path carrying an id nobody drew.
 *
 * Asked because this plan has one, not because plans often do — a plan that
 * draws is asked nothing. One id repeated is a measurement of one row and one
 * cache line, and where that is deliberate it is worth saying so.
 */
private fun Declaration.literalQuestions(): List<String> = buildList {
    val fixed = requests()
        .filter { step -> step.path.split('/').any { it.isNotEmpty() && it.none(Char::isLetter) } }
        .filter { step -> step.path.none { it == '{' } }
    if (fixed.isEmpty()) return@buildList

    add(
        "${fixed.joinToString { "`${it.name}`" }} sends the same id every time, so every user asks for one " +
            "row and one cache line. Is that on purpose, or should it draw — and over how many keys?",
    )
}

/**
 * What a plan with topics in it is guessing, which is not what an HTTP plan
 * guesses.
 *
 * A produce step is a write to somebody's cluster, and one nobody answers for
 * measures the publish rather than the work — both are worth saying out loud
 * before a rate is raised.
 */
private fun Declaration.topicQuestions(): List<String> = buildList {
    val produced = steps.filterIsInstance<DeclaredStep.Produce>()
    if (produced.isEmpty()) return@buildList

    add(
        "This produces to ${produced.joinToString { "`${it.topic}`" }} on `$brokers`. " +
            "Is that a cluster this may write to, and is anything downstream going to act on those records?",
    )

    val unanswered = produced.filter { step ->
        steps.filterIsInstance<DeclaredStep.Completes>().none { it.completes == step.name }
    }
    if (unanswered.isNotEmpty()) {
        add(
            "${unanswered.joinToString { "`${it.name}`" }} measures the publish, which is `acks` deep and not " +
                "a consumer having done the work. Which topic carries the answer, and in what window?",
        )
    }

    val same = produced.filter { it.key != null }
    if (same.isNotEmpty()) {
        add(
            "Every record from ${same.joinToString { "`${it.name}`" }} carries the same key, so they all land on " +
                "one partition. Is that the distribution this sees, or should the key vary per user?",
        )
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

/**
 * Which step is worth the load, said rather than asked.
 *
 * A caller that has been asked four questions and given no opinion has been
 * handed the work back. The smoke already measured something — one request per
 * step — and the slowest of them is where a percentile will say anything at
 * all: a percentile over a constant is the constant, and reporting it as a tail
 * dresses one number up as a distribution.
 *
 * One request is a hint and not a measurement, and this says so. A
 * recommendation that overstated its evidence would be the thing this tool
 * exists not to do.
 */
internal fun RunResult.recommendation(): String? {
    val ran = steps.values.filter { it.count > 0 }
    if (ran.size < 2) return null

    val slowest = ran.maxBy { it.responseTime.p99 }
    val rest = ran.filter { it.name != slowest.name }.maxOfOrNull { it.responseTime.p99 } ?: return null
    if (slowest.responseTime.p99 <= rest * CLEARLY) return null

    return "`${slowest.name}` took ${slowest.responseTime.p99} against $rest for the next slowest, so it is " +
        "where a percentile will say something — a percentile over a constant is the constant. That is one " +
        "request each, so it is a hint about where to point the load, not a measurement."
}

/** How much slower the slowest has to be before naming it is worth more than the noise in one request. */
private const val CLEARLY = 2.0
