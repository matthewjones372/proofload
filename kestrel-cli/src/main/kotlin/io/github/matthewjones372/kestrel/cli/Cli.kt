package io.github.matthewjones372.kestrel.cli

import io.github.matthewjones372.kestrel.Allowance
import io.github.matthewjones372.kestrel.Preview
import io.github.matthewjones372.kestrel.Progress
import io.github.matthewjones372.kestrel.Ran
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.engine.Kestrel
import io.github.matthewjones372.kestrel.engine.runWithin
import io.github.matthewjones372.kestrel.export.Density
import io.github.matthewjones372.kestrel.export.json
import io.github.matthewjones372.kestrel.fellBehind
import io.github.matthewjones372.kestrel.lostGround
import io.github.matthewjones372.kestrel.openapi.planFromDocument
import io.github.matthewjones372.kestrel.plan.asKotlin
import io.github.matthewjones372.kestrel.plan.asSimulation
import io.github.matthewjones372.kestrel.plan.asYaml
import io.github.matthewjones372.kestrel.plan.kafka.kafkaLowerings
import io.github.matthewjones372.kestrel.plan.readPlan
import io.github.matthewjones372.kestrel.preview
import io.github.matthewjones372.kestrel.remedy
import io.github.matthewjones372.kestrel.scheduleRemedy
import java.nio.file.Files
import kotlin.system.exitProcess

/** What a command printed and what it exited with, so a test can read both without a process. */
data class Finished(val out: String, val error: String = "", val code: Code = Code.Met)

/**
 * Does what [command] asked.
 *
 * Returns rather than prints, so the interesting half of this file is testable
 * and `main` is four lines that cannot be got wrong.
 */
fun obey(
    command: Command,
    allowance: Allowance = Allowance.fromFile(),
    kestrel: (Progress) -> Kestrel = { Kestrel(progress = it) },
): Finished {
    // Read before anything else: this one takes a document, not a plan, so the
    // plan reader below has nothing to read yet.
    if (command is Command.FromOpenApi) {
        return try {
            Finished(out = planFromDocument(Files.readString(command.plan, Charsets.UTF_8), command.baseUrl).asYaml())
        } catch (unusable: IllegalArgumentException) {
            Finished(out = "", error = unusable.message.orEmpty(), code = Code.Unusable)
        }
    }

    val simulation = try {
        readPlan(command.plan).asSimulation(kafkaLowerings)
    } catch (unusable: IllegalArgumentException) {
        // The message already names the line and what was allowed; wrapping it
        // in a stack trace would bury the one sentence a caller needs.
        return Finished(out = "", error = unusable.message.orEmpty(), code = Code.Unusable)
    }

    return when (command) {
        is Command.Emit -> Finished(
            out = readPlan(command.plan).asKotlin(command.packageName, command.plan.fileName.toString()),
        )

        is Command.Validate -> Finished(out = "${command.plan} is a plan this reads")

        is Command.Preview -> when (val asked = simulation.preview(allowance)) {
            is Preview.Refused -> Finished(out = "", error = asked.reason.described, code = Code.Refused)
            is Preview.Allowed -> Finished(out = asked.described)
        }

        // Silent under `--json`, which is the caller `Progress.silent` names in
        // its own KDoc: the document goes to stdout, so a run's commentary on
        // the same stream is a document nothing can parse.
        is Command.Run -> when (
            val ran = kestrel(if (command.json) Progress.silent else Progress.lines())
                .runWithin(allowance, simulation)
        ) {
            is Ran.Refused -> Finished(out = "", error = ran.reason.described, code = Code.Refused)

            is Ran.Result -> Finished(
                out = if (command.json) ran.result.json(Density.Summary) else ran.result.told(),
                code = ran.result.code(),
            )
        }
    }
}

/**
 * A run's verdict as an exit code.
 *
 * `behind` outranks a missed goal here for the same reason it does on the page:
 * a generator that lost its own schedule measured a queue it built, so a shell
 * branching on this should not be told the target was slow.
 */
internal fun RunResult.code(): Code = when {
    fellBehind() || lostGround() -> Code.Behind
    plan.goals.flatMap { it.judgeAll(this) }.any { !it.met } -> Code.Missed
    else -> Code.Met
}

/**
 * The same verdict as the JSON, for somebody reading a terminal.
 *
 * Not a second judgement: the words come off the same goals and the same
 * remedy the document carries, so a caller cannot be told two things.
 */
private fun RunResult.told(): String = buildString {
    val judged = plan.goals.flatMap { it.judgeAll(this@told) }
    appendLine("$count requests, $ok ok, $failed failed")
    judged.forEach { appendLine("${if (it.met) "met" else "missed"}  ${it.goal.described}") }
    scheduleRemedy?.let { appendLine(it) }
    judged.firstOrNull { !it.met }?.remedy?.let { appendLine(it) }
}.trimEnd()

private val Preview.Allowed.described: String
    get() = buildString {
        appendLine("$users users over $over")
        appendLine("${if (requestsBounded) "" else "at least "}$requestsAtLeast requests")
        peakRate?.let { appendLine("peaking at ${it.perSecond}/s") }
        appendLine(if (hosts.isEmpty()) "to no host this can read" else "to ${hosts.joinToString()}")
        if (untargeted > 0) appendLine("$untargeted step(s) named no host, so nothing here bounds them")
    }.trimEnd()

fun main(args: Array<String>) {
    val command = parse(args.toList()) ?: run {
        System.err.println(usage)
        exitProcess(Code.Unusable.number)
    }

    val finished = obey(command)
    if (finished.out.isNotEmpty()) println(finished.out)
    if (finished.error.isNotEmpty()) System.err.println(finished.error)
    exitProcess(finished.code.number)
}
