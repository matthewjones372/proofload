package io.github.matthewjones372.kestrel.cli

import java.nio.file.Path

/**
 * What the command line was asked for.
 *
 * A value, so the parsing is testable without a process and the defaults are
 * readable in one place — the same shape `kestrel-record`'s entry point uses.
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
        else -> null
    }
}

/** What to print when nobody named a command this understands. */
val usage: String = """
    kestrel validate <plan>          parse it, resolve it, send nothing
    kestrel preview  <plan>          say what it would send, and send none of it
    kestrel run      <plan> [--json] run it; the exit code is the verdict
    kestrel emit     <plan> [--package p]  print it as the Kotlin it was equivalent to

    exit codes: 0 met, 1 missed a goal, 2 the generator fell behind, 3 refused, 4 unusable
""".trimIndent()
