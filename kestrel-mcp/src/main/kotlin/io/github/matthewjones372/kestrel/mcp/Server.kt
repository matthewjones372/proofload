package io.github.matthewjones372.kestrel.mcp

import io.github.matthewjones372.kestrel.Allowance
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
        """"serverInfo":{"name":"kestrel","version":"0.1.0"}}"""

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
internal val REPORTS: Path = Path.of("build", "reports", "kestrel", "mcp")

internal fun answer(call: Call): String = when (call.method) {
    "initialize" -> replyTo(call.id, initialised())
    "tools/list" -> replyTo(call.id, toolsAsJson())
    "tools/call" -> replyTo(call.id, called(call))
    else -> failTo(call.id, METHOD_NOT_FOUND, "no method `${call.method}`")
}

private fun called(call: Call): String = when (call.tool) {
    "plan_schema" -> content(PLAN_SCHEMA)
    "validate" -> validate(call.arguments)
    "preview" -> preview(call.arguments, Allowance.fromFile())
    "smoke" -> smoke(call.arguments, Allowance.fromFile())
    "trace" -> trace(call.arguments, Allowance.fromFile())
    "run" -> onThePlan(call.arguments) { plan -> RUNS.start(plan, Allowance.fromFile()) }
    "status" -> RUNS.status(call.arguments["runId"] as? String)
    "explain" -> explain(RUNS, call.arguments["runId"] as? String)
    "report" -> report(RUNS, call.arguments["runId"] as? String, REPORTS)
    "list_runs" -> listRuns(RUNS)
    "compare" -> compare(RUNS, call.arguments["runId"] as? String, call.arguments["against"] as? String)
    "from_openapi" -> fromOpenApi(call.arguments)
    else -> content("no tool `${call.tool}`", failed = true)
}
