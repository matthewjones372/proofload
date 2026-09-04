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
}

/**
 * How a run ended, as a number a shell can branch on.
 *
 * The order is 0087's verdict order: a generator that lost its schedule
 * outranks a missed goal, because its numbers are not the target's.
 */
enum class Code(val number: Int) {
    Met(0),
    Missed(1),
    Behind(2),
    Refused(3),
    Unusable(4),
}

/** The command these arguments name, or null where they name none. */
fun parse(args: List<String>): Command? {
    val plan = args.getOrNull(1)?.let(Path::of) ?: return null
    return when (args.firstOrNull()) {
        "validate" -> Command.Validate(plan)
        "preview" -> Command.Preview(plan)
        "run" -> Command.Run(plan, json = "--json" in args)
        else -> null
    }
}

/** What to print when nobody named a command this understands. */
val usage: String = """
    kestrel validate <plan>          parse it, resolve it, send nothing
    kestrel preview  <plan>          say what it would send, and send none of it
    kestrel run      <plan> [--json] run it; the exit code is the verdict

    exit codes: 0 met, 1 missed a goal, 2 the generator fell behind, 3 refused, 4 unusable
""".trimIndent()
