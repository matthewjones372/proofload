package io.github.matthewjones372.kestrel.mcp

import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.fellBehind
import io.github.matthewjones372.kestrel.lostGround
import io.github.matthewjones372.kestrel.plan.Declaration
import io.github.matthewjones372.kestrel.plan.DeclaredLoad
import io.github.matthewjones372.kestrel.plan.asYaml
import io.github.matthewjones372.kestrel.scheduleRemedy
import java.nio.file.Files
import java.nio.file.Path

/**
 * A benchmark somebody can commit, argue with, and run again.
 *
 * Everything else here is ephemeral: a plan written into a conversation, run,
 * and lost, so the next person asks the same questions, answers them slightly
 * differently, and gets a number nobody can compare to the last one.
 *
 * The decisions are the expensive part. The plan is four lines of YAML; that
 * one endpoint is the one worth testing, that some rate is what it sees at
 * peak, that some figure is the p99 anyone cares about — those took a
 * conversation. This is where they stop living only in one.
 */
internal fun writeSpec(registry: Registry, id: String?, into: String?, why: String?): String {
    val ran = id?.let { registry.ran(it) }
        ?: return content("no finished run `$id`; `status` says whether one is still sending", failed = true)

    // Only where the load that left was not the load the profile named: the
    // plan itself is wrong then, and there is nothing worth recording. A run
    // merely behind is written with the caveat above the table, which is what
    // Tail.Absent and Tell.CannotTell already do with a number they distrust.
    if (ran.result.lostGround()) {
        return content(
            "that run did not send the load its plan asked for, so there is no benchmark here to record. " +
                "${ran.result.scheduleRemedy}",
            failed = true,
        )
    }

    val where = into ?: return content("write_spec wants an `into` path to write to", failed = true)
    val document = ran.result.asBenchmark(ran.plan, why)

    val path = Path.of(where)
    path.parent?.let { Files.createDirectories(it) }
    Files.writeString(path, document, Charsets.UTF_8)

    return content("wrote ${path.toAbsolutePath()}\n\n$document")
}

private fun RunResult.asBenchmark(plan: Declaration, why: String?): String = buildString {
    appendLine("# Benchmark: ${plan.scenario}")
    appendLine()
    appendLine("## What this measures")
    appendLine()
    appendLine("${plan.steps.joinToString { "`${it.method} ${it.path}`" }} against `${plan.baseUrl}`,")
    appendLine("at ${plan.load.described()}.")
    appendLine()
    appendLine("## Why these numbers")
    appendLine()
    if (why.isNullOrBlank()) {
        // An unanswered question is the useful half of a first draft: it says
        // where somebody has to decide, rather than inventing a reason nobody
        // gave.
        appendLine("**Nobody has answered this yet.** Open, and worth answering before anyone trusts the table below:")
        appendLine()
        appendLine("- Why these endpoints, and not the others?")
        appendLine("- Is ${plan.load.described()} what this sees at peak?")
        appendLine("- What p99 counts as too slow, and for whom?")
    } else {
        appendLine(why.trim())
    }
    appendLine()
    appendLine("## The baseline, measured ${startedAt.toString().take(DATE)}")
    appendLine()
    appendLine("| step | p50 | p99 | requests | failed |")
    appendLine("|---|---|---|---|---|")
    steps.values.forEach {
        appendLine(
            "| ${it.name} | ${it.responseTime.p50} | ${it.responseTime.p99} | ${it.count} | ${it.failed.count} |",
        )
    }
    appendLine()
    if (fellBehind()) {
        appendLine("> **Read these with care.** $scheduleRemedy")
        appendLine()
        appendLine("$count requests, $failed failed.")
    } else {
        appendLine("$count requests, $failed failed. The generator kept its own schedule, so these are the target's.")
    }
    appendLine("Measured on ${machine.cores} cores, ${machine.jdk}, ${machine.os} ${machine.arch}.")
    appendLine()
    appendLine("## The plan")
    appendLine()
    appendLine("```yaml")
    append(plan.asYaml())
    appendLine("```")
    appendLine()
    appendLine("Run it again with `kestrel run`, or hand the block above to the `run` tool.")
    appendLine()
    appendLine("## What this does not cover")
    appendLine()
    plan.notCovered().forEach { appendLine("- $it") }
}

/** Said plainly, because a benchmark read as covering more than it does is worse than a narrow one. */
private fun Declaration.notCovered(): List<String> = buildList {
    add("Anything that writes — every step here is a read.")
    if (steps.any { it.declared.contains(UNAUTHORISED) }) {
        add("Authentication: a step declares 401 and no credential was supplied.")
    }
    add("Any journey. These steps run in the order written, and nothing here says that is the order users take.")
}

private fun DeclaredLoad.described(): String = when (this) {
    is DeclaredLoad.Constant -> "${rate.perSecond}/s for $over"
    is DeclaredLoad.Ramp -> "${from.perSecond}/s ramping to ${to.perSecond}/s over $over"
    is DeclaredLoad.Staged -> "${stages.size} stages"
}

private const val UNAUTHORISED = 401
private const val DATE = 10
