package io.github.matthewjones372.kestrel.mcp

/**
 * What a caller can ask for, and what it costs to ask.
 *
 * [sends] is the column that matters. Every tool states what it will send
 * before it sends it, and only `run` sends load: `smoke` sends one request per
 * step and `trace` walks one user, both bounded by the plan rather than by its
 * rate. Everything else is free to call and free to get wrong, which is what
 * lets a caller iterate instead of guess.
 */
internal data class Tool(
    val name: String,
    val describes: String,
    val sends: String,
    val arguments: List<Argument>,
) {

    fun asJson(): String {
        val required = arguments.filter { it.required }.joinToString(",") { it.name.asJsonString() }
        val properties = arguments.joinToString(",") { it.asJson() }
        return """{"name":${name.asJsonString()},"description":${"$describes Sends: $sends.".asJsonString()},""" +
            """"inputSchema":{"type":"object","properties":{$properties},"required":[$required]}}"""
    }
}

internal data class Argument(
    val name: String,
    val describes: String,
    val required: Boolean = false,
    val type: String = "string",
) {

    fun asJson(): String =
        """${name.asJsonString()}:{"type":${type.asJsonString()},"description":${describes.asJsonString()}}"""
}

private val RUN_ID = Argument("runId", "The id `run` returned.", required = true)

private val PLAN = Argument("plan", "The plan, as a plan/1 document. Ask plan_schema what one looks like.", true)

internal val TOOLS: List<Tool> = listOf(
    Tool(
        name = "benchmark",
        describes = "Start here. Takes an OpenAPI document, a plan, or just a baseUrl, and hands back a plan " +
            "with what running it would send and what one request per step already found. Then call `run`.",
        sends = "one request per step, and no load",
        arguments = listOf(
            Argument("document", "An OpenAPI document, as YAML or JSON."),
            Argument("plan", "A plan/1 document, if you already have one."),
            Argument("baseUrl", "Where to send it. On its own, benchmarks GET /."),
        ),
    ),
    Tool(
        name = "plan_schema",
        describes = "The shape of a plan/1 document, with two worked examples. Read this before writing one.",
        sends = "nothing",
        arguments = emptyList(),
    ),
    Tool(
        name = "validate",
        describes = "Parses a plan, resolves its steps and goals, and names the line of anything wrong.",
        sends = "nothing",
        arguments = listOf(PLAN),
    ),
    Tool(
        name = "preview",
        describes = "Says what the plan would send — users, requests, window, peak rate, hosts — and sends none of it.",
        sends = "nothing",
        arguments = listOf(PLAN),
    ),
    Tool(
        name = "smoke",
        describes = "One request per step, so a typo in a path is found here rather than " +
            "at three thousand a second.",
        sends = "one request per step",
        arguments = listOf(PLAN),
    ),
    Tool(
        name = "trace",
        describes = "Walks one user through the plan and prints what each step sent and what came back.",
        sends = "one journey",
        arguments = listOf(PLAN),
    ),
    Tool(
        name = "run",
        describes = "Starts the run and returns a runId. Poll `status` with it; it does not wait.",
        sends = "**the load the plan asks for**",
        arguments = listOf(PLAN),
    ),
    Tool(
        name = "status",
        describes = "What a run is doing, or the verdict and remedy of a finished one.",
        sends = "nothing",
        arguments = listOf(Argument("runId", "The id `run` returned.", required = true)),
    ),
    Tool(
        name = "explain",
        describes = "The full document for a finished run: every step, the timeline, the lateness.",
        sends = "nothing",
        arguments = listOf(RUN_ID),
    ),
    Tool(
        name = "report",
        describes = "Writes the run's self-contained HTML page and returns its path, for a person to open.",
        sends = "nothing",
        arguments = listOf(RUN_ID),
    ),
    Tool(
        name = "list_runs",
        describes = "Every run this server has started, newest first.",
        sends = "nothing",
        arguments = emptyList(),
    ),
    Tool(
        name = "write_spec",
        describes = "Writes a benchmark somebody can commit and argue with: the reasoning, the measured " +
            "baseline, and the plan, as one markdown file.",
        sends = "nothing",
        arguments = listOf(
            RUN_ID,
            Argument("into", "Where to write it, e.g. benchmarks/search.md.", required = true),
            Argument("why", "Why these endpoints, this rate, this target — the answers somebody gave."),
        ),
    ),
    Tool(
        name = "compare",
        describes = "One finished run against another: better, worse, or cannot tell.",
        sends = "nothing",
        arguments = listOf(RUN_ID, Argument("against", "The runId to compare against.", required = true)),
    ),
    Tool(
        name = "from_openapi",
        describes = "Reads an OpenAPI document and writes the plan it describes.",
        sends = "nothing",
        arguments = listOf(
            Argument("document", "The OpenAPI document, as YAML or JSON.", required = true),
            Argument("baseUrl", "Where to send it, if the document names no server."),
        ),
    ),
)

internal fun toolsAsJson(): String = """{"tools":[${TOOLS.joinToString(",") { it.asJson() }}]}"""
