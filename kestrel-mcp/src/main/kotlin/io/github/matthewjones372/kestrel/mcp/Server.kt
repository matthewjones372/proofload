package io.github.matthewjones372.kestrel.mcp

import java.io.BufferedReader
import java.io.Writer

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
    serve(System.`in`.bufferedReader(), System.out.writer()) { call -> answer(call) }
}

internal fun answer(call: Call): String = when (call.method) {
    "initialize" -> replyTo(call.id, initialised())
    "tools/list" -> replyTo(call.id, toolsAsJson())
    "tools/call" -> replyTo(call.id, called(call))
    else -> failTo(call.id, METHOD_NOT_FOUND, "no method `${call.method}`")
}

private fun called(call: Call): String = when (call.tool) {
    "plan_schema" -> content(PLAN_SCHEMA)
    else -> content("no tool `${call.tool}`", failed = true)
}
