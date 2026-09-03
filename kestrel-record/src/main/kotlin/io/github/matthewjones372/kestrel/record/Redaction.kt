package io.github.matthewjones372.kestrel.record

/**
 * Whether this header is a credential, and so is dropped from the output.
 *
 * There is no flag to turn this off. A switch somebody sets once and forgets is
 * a token in a public repository, and a recording is full of live ones: the
 * generated source names what was dropped and where to put it back, which is a
 * `TODO` a compiler will not let anybody ignore.
 */
fun Header.isCredential(): Boolean = name.lowercase() in credentialHeaders || value.looksLikeAJwt()

/**
 * Three dot-separated base64url segments whose first decodes to a JSON object.
 *
 * Shape rather than a header name, because a token turns up under whatever
 * name the service that issued it chose — `x-session`, `access-token`, a query
 * parameter somebody moved into a header — and none of those is on a list.
 */
fun String.looksLikeAJwt(): Boolean {
    val parts = removePrefix("Bearer ").trim().split(".")
    if (parts.size != SEGMENTS || parts.any { it.isEmpty() }) return false
    if (!parts.all { it.all { char -> char.isLetterOrDigit() || char == '-' || char == '_' } }) return false
    val header = runCatching { java.util.Base64.getUrlDecoder().decode(parts[0].padded()) }.getOrNull()
        ?: return false
    return header.decodeToString().trimStart().startsWith("{")
}

/** Base64url in a JWT carries no padding, and the JDK's decoder insists on it. */
private fun String.padded(): String = this + "=".repeat((PADDING - length % PADDING) % PADDING)

/** A JWT is a header, a payload and a signature, and nothing else is. */
private const val SEGMENTS = 3

private const val PADDING = 4

/**
 * The names that are a credential whatever their value looks like.
 *
 * `x-csrf-token` is here for a different reason from the others: it is not
 * usually secret, but it is bound to a session that has expired by the time
 * anybody runs the generated file, and a run that sends a stale one measures a
 * target rejecting every request.
 */
private val credentialHeaders = setOf(
    "authorization",
    "cookie",
    "set-cookie",
    "proxy-authorization",
    "x-api-key",
    "x-auth-token",
    "x-csrf-token",
    "x-xsrf-token",
)
