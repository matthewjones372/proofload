package io.github.matthewjones372.proofload.cli

import java.nio.file.Path

/**
 * What the command line was asked for.
 *
 * A value, so the parsing is testable without a process and the defaults are
 * readable in one place — the same shape `proofload-record`'s entry point uses.
 */
sealed interface Command {

    val plan: Path

    /** Parses and resolves, and sends nothing. */
    data class Validate(override val plan: Path) : Command

    /** Says what the plan would send, and sends none of it. */
    data class Preview(override val plan: Path) : Command

    data class Run(override val plan: Path, val json: Boolean) : Command

    /** Prints the plan as the Kotlin it was equivalent to, for a caller who has outgrown the subset. */
    data class Emit(override val plan: Path, val packageName: String) : Command

    /**
     * Reads an OpenAPI document and writes the plan it describes.
     *
     * [plan] is the document here rather than a plan, which is the one command
     * where that word means the input.
     */
    data class FromOpenApi(override val plan: Path, val baseUrl: String?) : Command
}

/**
 * How a run ended, as a number a shell can branch on.
 *
 * The order is 0087's verdict order: a generator that lost its schedule
 * outranks a missed goal, because its numbers are not the target's.
 */
enum class Code {
    Met,
    Missed,
    Behind,
    Refused,
    Unusable,
    ;

    /**
     * Declaration order, so the list above is the contract rather than a second
     * copy of it. `CodeTest` pins the numbers, because a shell script written
     * against them does not recompile when somebody reorders an enum.
     */
    val number: Int get() = ordinal
}

private fun List<String>.after(flag: String): String? =
    indexOf(flag).takeIf { it >= 0 }?.let { getOrNull(it + 1) }

/** The command these arguments name, or null where they name none. */
fun parse(args: List<String>): Command? {
    val plan = args.getOrNull(1)?.let(Path::of) ?: return null
    return when (args.firstOrNull()) {
        "validate" -> Command.Validate(plan)
        "preview" -> Command.Preview(plan)
        "run" -> Command.Run(plan, json = "--json" in args)
        "emit" -> Command.Emit(plan, packageName = args.after("--package") ?: "load")
        "from-openapi" -> Command.FromOpenApi(plan, baseUrl = args.after("--base-url"))
        else -> null
    }
}

/** What to print when nobody named a command this understands. */
val usage: String = """
    proofload validate <plan>          parse it, resolve it, send nothing
    proofload preview  <plan>          say what it would send, and send none of it
    proofload run      <plan> [--json] run it; the exit code is the verdict
    proofload emit     <plan> [--package p]  print it as the Kotlin it was equivalent to
    proofload from-openapi <doc> [--base-url u]  read a document and write the plan it describes

    exit codes: 0 met, 1 missed a goal, 2 the generator fell behind, 3 refused, 4 unusable
""".trimIndent()
