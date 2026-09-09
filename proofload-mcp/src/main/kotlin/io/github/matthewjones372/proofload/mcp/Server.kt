package io.github.matthewjones372.proofload.mcp

import io.github.matthewjones372.proofload.Allowance
import java.io.BufferedReader
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.io.Writer
import java.nio.file.Path

/**
 * The server, as a function over lines.
 *
 * Reading and writing are the caller's, so a test drives it with two strings
 * and no process. A server that could only be exercised by spawning one is a
 * server whose framing is tested by hand.
 */
internal fun serve(input: BufferedReader, output: Writer, answer: (Call) -> String) {
    input.lineSequence().filter { it.isNotBlank() }.forEach { line ->
        val call = parse(line)
        if (call == null) {
            output.write(failTo(null, METHOD_NOT_FOUND, "not a call this server can read") + "\n")
            output.flush()
            return@forEach
        }

        // A notification wants no answer, and sending one is what makes a
        // client hang up on the next request it makes.
        if (call.notification) return@forEach

        output.write(answer(call) + "\n")
        output.flush()
    }
}

/** What this server is, in the shape `initialize` asks for. */
internal fun initialised(): String =
    """{"protocolVersion":"2024-11-05","capabilities":{"tools":{}},""" +
        """"serverInfo":{"name":"proofload","version":${describedBuild().asJsonString()}}}"""

fun main() {
    // stdout is the protocol. Anything in the library that prints — a trace, a
    // progress line, a stack trace somebody added later — would land in the
    // middle of a JSON-RPC message and end the session, so the real stdout is
    // taken for the protocol and everything else is pointed at stderr before a
    // single tool runs. Cheaper than auditing every call for prints, and it
    // stays true for calls nobody has written yet.
    val protocol = PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8)
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8))

    serve(System.`in`.bufferedReader(), protocol.writer()) { call -> answer(call) }
}

/** The runs this process has started. One server, one registry, lost with the process. */
internal val RUNS = Registry()

/** Where a page a person opens is written. Beside the build, like every other report. */
internal val REPORTS: Path = Path.of("build", "reports", "proofload", "mcp")

internal fun answer(call: Call): String = when (call.method) {
    "initialize" -> replyTo(call.id, initialised())
    "tools/list" -> replyTo(call.id, toolsAsJson())
    "tools/call" -> replyTo(call.id, called(call))
    else -> failTo(call.id, METHOD_NOT_FOUND, "no method `${call.method}`")
}

/**
 * The tool, and a tool result whatever it does.
 *
 * A throw used to leave `main` and end the session, taking every run this
 * process was holding with it — and not only for a bug: a YAML parse failure
 * is not an `IllegalArgumentException`, so a document that was not one killed
 * the server from six of the thirteen tools. stdout is the protocol here, and
 * the same argument that points prints at stderr applies to a stack trace.
 *
 * `Exception` rather than `Throwable`: an `OutOfMemoryError` is not a tool
 * result and pretending otherwise would answer a client with a lie about a
 * process that is no longer working.
 */

/**
 * Whether this server has been asked to refuse load that nothing bounds.
 *
 * `docs/allowance.md` is explicit that no file means no limits, and for someone
 * who installed a load generator on purpose that is the right default. For an
 * image a model drives it is the wrong one: a container started with nothing
 * mounted would point anywhere, at any rate, for as long as it was asked to. The
 * container sets this and a local install does not, so running it here is
 * unchanged.
 *
 * Only `run` consults it. `benchmark`, `smoke` and `trace` are bounded by the
 * plan's shape rather than its rate — one request per step, one journey — and
 * refusing those would leave the image unable to do the safe half of its job.
 */
internal fun refusesUnfenced(asked: String? = System.getenv(REQUIRE_ALLOWANCE)): Boolean = !asked.isNullOrBlank()

/** Whether an allowance bounds anything at all. A file that sets no key bounds nothing. */
internal fun bounds(allowance: Allowance): Boolean = allowance != Allowance.none

private fun unfenced(): String = content(
    "refused: this server will not send load with nothing bounding it. $REQUIRE_ALLOWANCE is set " +
        "and no allowance was found in ${Path.of("").toAbsolutePath()} — mount or write one, and " +
        "docs/allowance.md says what it is called and what goes in it. " +
        "`preview`, `smoke` and `trace` need none of this.",
    failed = true,
)

/** Set by the container image; absent everywhere else. */
private const val REQUIRE_ALLOWANCE = "PROOFLOAD_REQUIRE_ALLOWANCE"

@Suppress("TooGenericExceptionCaught") // The point: a server boundary that only caught what it predicted
private fun called(call: Call): String = try {
    calling(call)
} catch (thrown: Exception) {
    // Named by its type, as `Threw` names a step's: a message alone leaves a
    // caller unable to tell a plan they can fix from a bug they cannot.
    content("`${call.tool}` failed: ${thrown.javaClass.simpleName}: ${thrown.message}", failed = true)
}

private fun calling(call: Call): String = when (call.tool) {
    "benchmark" -> benchmark(call.arguments, Allowance.fromFile())

    "plan_schema" -> content(PLAN_SCHEMA)

    "validate" -> validate(call.arguments)

    "preview" -> preview(call.arguments, Allowance.fromFile())

    "smoke" -> smoke(call.arguments, Allowance.fromFile())

    "trace" -> trace(call.arguments, Allowance.fromFile())

    "run" -> onThePlan(call.arguments) { plan ->
        val allowance = Allowance.fromFile()
        if (refusesUnfenced() && bounds(allowance).not()) unfenced() else RUNS.start(plan, allowance)
    }

    "status" -> RUNS.status(call.arguments["runId"] as? String)

    "explain" -> explain(RUNS, call.arguments["runId"] as? String)

    "report" -> report(RUNS, call.arguments["runId"] as? String, REPORTS)

    "summary" -> summarised(RUNS, call.arguments["runId"] as? String)

    "list_runs" -> listRuns(RUNS)

    "write_spec" -> writeSpec(
        RUNS,
        call.arguments["runId"] as? String,
        call.arguments["into"] as? String,
        call.arguments["why"] as? String,
    )

    "compare" -> compare(RUNS, call.arguments["runId"] as? String, call.arguments["against"] as? String)

    "from_openapi" -> fromOpenApi(call.arguments)

    else -> content("no tool `${call.tool}`", failed = true)
}
