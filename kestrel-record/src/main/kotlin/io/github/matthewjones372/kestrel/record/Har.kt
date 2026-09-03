package io.github.matthewjones372.kestrel.record

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A HAR read as the requests it recorded.
 *
 * Chrome, Firefox, Charles, mitmproxy and every API client export this format,
 * and they disagree about everything optional in it — a proxy leaves out the
 * response of a request that was cut off, a browser writes one with a status of
 * zero; one writes `postData.text` and another `postData.params`. What they
 * agree on is `log.entries[].request` and `.response`, which is what this reads.
 *
 * Lenient rather than strict: a field this does not understand is a field
 * somebody's tool added, and refusing a recording over one is refusing the whole
 * point of reading a file everybody already exports.
 */
fun readHar(text: String): List<Recorded> {
    val root = runCatching { lenient.parseToJsonElement(text) }.getOrNull() as? JsonObject
        ?: throw IllegalArgumentException("this is not JSON, so it is not a HAR")
    val entries = (root["log"] as? JsonObject)?.get("entries") as? JsonArray
        ?: throw IllegalArgumentException("no log.entries here, so this is JSON but not a HAR")

    val recorded = entries.mapNotNull { entry -> (entry as? JsonObject)?.let { one(it) } }
    val first = recorded.minOfOrNull { it.at } ?: Duration.ZERO
    return recorded.map { it.copy(at = it.at - first) }
}

private fun one(entry: JsonObject): Recorded? {
    val request = entry["request"] as? JsonObject ?: return null
    val url = request.text("url") ?: return null
    return Recorded(
        method = request.text("method")?.uppercase() ?: "GET",
        url = url,
        headers = (request["headers"] as? JsonArray).headers(),
        body = (request["postData"] as? JsonObject)?.text("text"),
        answer = (entry["response"] as? JsonObject)?.let { answer(it) },
        at = entry.text("startedDateTime")?.asOffset() ?: Duration.ZERO,
    )
}

/**
 * A status of zero is a browser's way of saying the request got no answer, and
 * a proxy says the same thing by writing no response object at all. Both read
 * as a request that got none rather than as a response with a strange status.
 */
private fun answer(response: JsonObject): Answer? {
    val status = response.number("status")?.toInt() ?: return null
    if (status <= 0) return null
    return Answer(
        status = status,
        headers = (response["headers"] as? JsonArray).headers(),
        body = (response["content"] as? JsonObject)?.text("text"),
    )
}

private fun JsonArray?.headers(): List<Header> =
    orEmpty().mapNotNull { it as? JsonObject }
        .mapNotNull { header ->
            val name = header.text("name") ?: return@mapNotNull null
            Header(name, header.text("value").orEmpty())
        }

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.number(name: String): Double? = (this[name] as? JsonPrimitive)?.content?.toDoubleOrNull()

/** Epoch millis, so the gaps survive whatever offset the recorder wrote them in. */
private fun String.asOffset(): Duration? =
    try {
        Instant.parse(this).toEpochMilli().milliseconds
    } catch (unparseable: DateTimeParseException) {
        null
    }

/**
 * Unknown keys ignored, because every exporter adds its own: Chrome writes
 * `_priority` and `_resourceType`, and refusing a recording over a field nobody
 * here reads would refuse the whole point of reading a file everybody exports.
 */
private val lenient = Json { ignoreUnknownKeys = true }
