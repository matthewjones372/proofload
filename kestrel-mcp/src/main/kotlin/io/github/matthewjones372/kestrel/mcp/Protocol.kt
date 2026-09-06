package io.github.matthewjones372.kestrel.mcp

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * One JSON-RPC call, as far as this server cares about it.
 *
 * [id] is absent for a notification, which is the difference between a message
 * that wants an answer and one that does not — answering a notification is the
 * mistake that makes a client hang up.
 */
internal data class Call(
    val id: Any?,
    val method: String,
    /** Which tool, where the method is `tools/call`. MCP puts it beside the arguments, not inside them. */
    val tool: String?,
    val arguments: Map<String, Any?>,
    val notification: Boolean,
)

/**
 * Reads one line of JSON-RPC, or null where the line is not a call.
 *
 * Read with the YAML parser already here rather than a JSON library: YAML 1.2
 * is a superset of JSON, the module carries one parser for plans, and a second
 * one to read the envelope around them would be a second thing to keep current.
 */
internal fun parse(line: String): Call? {
    val message = runCatching { Load(LoadSettings.builder().build()).loadFromString(line) }
        .getOrNull()
        .asMap() ?: return null

    val method = message["method"] as? String ?: return null
    val parameters = message["params"].asMap().orEmpty()
    return Call(
        id = message["id"],
        method = method,
        tool = parameters["name"] as? String,
        arguments = parameters["arguments"].asMap().orEmpty(),
        notification = "id" !in message,
    )
}

/** A successful reply, whose payload is already JSON. */
internal fun replyTo(id: Any?, payload: String): String =
    """{"jsonrpc":"2.0","id":${id.asJson()},"result":$payload}"""

/**
 * A failed reply.
 *
 * MCP separates a call that could not be made from a tool that ran and said no:
 * this is the first, and a tool refusing a plan is an ordinary result with
 * `isError` on it. Confusing the two makes a client retry something that will
 * never work.
 */
internal fun failTo(id: Any?, code: Int, message: String): String =
    """{"jsonrpc":"2.0","id":${id.asJson()},"error":{"code":$code,"message":${message.asJsonString()}}}"""

/** A tool's answer, as MCP wants it: content the caller shows, and whether it went wrong. */
internal fun content(text: String, failed: Boolean = false): String =
    """{"content":[{"type":"text","text":${text.asJsonString()}}],"isError":$failed}"""

internal fun Any?.asJson(): String = when (this) {
    null -> "null"
    is Number, is Boolean -> toString()
    else -> toString().asJsonString()
}

internal fun String.asJsonString(): String = buildString {
    append('"')
    this@asJsonString.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character < ' ') append(character.escaped()) else append(character)
        }
    }
    append('"')
}

private fun Char.escaped(): String = "\\u" + code.toString(HEX).padStart(ESCAPE_DIGITS, '0')

@Suppress("UNCHECKED_CAST")
internal fun Any?.asMap(): Map<String, Any?>? = (this as? Map<*, *>)?.entries
    ?.mapNotNull { (key, value) -> (key as? String)?.let { it to value } }
    ?.toMap()

internal const val METHOD_NOT_FOUND = -32601
private const val HEX = 16
private const val ESCAPE_DIGITS = 4
