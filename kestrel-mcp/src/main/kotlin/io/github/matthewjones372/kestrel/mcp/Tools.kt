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

private val PLAN = Argument("plan", "The plan, as a plan/1 document. Ask plan_schema what one looks like.", true)

internal val TOOLS: List<Tool> = listOf(
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
