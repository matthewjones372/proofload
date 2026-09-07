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
 * The query string with every credential parameter taken out, and the names of
 * the ones taken.
 *
 * A recording carries tokens in the URL as readily as in a header —
 * `?access_token=...` is how half of OAuth is done — and a header is the only
 * place this used to look. A query parameter cannot hold a `TODO` the way a
 * header can, so the value does not survive at all: the name is reported and
 * the parameter is gone. A generated file that would not run is a smaller
 * problem than one carrying a live token into a repository.
 */
fun String.withoutCredentialParameters(): Pair<String, List<String>> {
    val query = substringAfter('?', "")
    if (query.isEmpty()) return this to emptyList()

    val (kept, dropped) = query.split("&")
        .filter { it.isNotEmpty() }
        .partition { !it.isCredentialParameter() }

    val path = substringBefore('?')
    val rebuilt = if (kept.isEmpty()) path else "$path?${kept.joinToString("&")}"
    return rebuilt to dropped.map { it.substringBefore('=') }.distinct()
}

private fun String.isCredentialParameter(): Boolean {
    val name = substringBefore('=').lowercase()
    val value = substringAfter('=', "")
    return name in credentialParameters || value.decoded().looksLikeAJwt()
}

/**
 * The body with anything that looks like a secret replaced.
 *
 * Bodies are the caller's own format, so this is deliberately shape-based and
 * deliberately eager: a JSON string under a password-ish name, and any
 * JWT-shaped run of characters wherever it sits. Over-redacting a recording
 * costs somebody a `TODO`; under-redacting it puts a live credential in a
 * repository, and only one of those is recoverable.
 */
fun String.withoutSecrets(): String =
    replace(SECRET_FIELD) { match -> """${match.groupValues[1]}"$REDACTED"""" }
        .split(JWT_BOUNDARY)
        .joinToString("") { if (it.looksLikeAJwt()) REDACTED else it }

/** `"password": "..."` and its relatives, whatever whitespace is around them. */
private val SECRET_FIELD = Regex(
    """("(?:password|passwd|secret|token|api_?key|access_?token|refresh_?token|client_?secret)"\s*:\s*)"[^"]*"""",
    RegexOption.IGNORE_CASE,
)

/** What separates one candidate token from the text around it. */
private val JWT_BOUNDARY = Regex("""(?<=[^A-Za-z0-9._-])|(?=[^A-Za-z0-9._-])""")

private fun String.decoded(): String = runCatching {
    java.net.URLDecoder.decode(this, Charsets.UTF_8)
}.getOrDefault(this)

/** What a dropped value is replaced by, so a reader sees the shape of what was there. */
const val REDACTED: String = "REDACTED"

/** Query parameter names that carry a credential whatever their value looks like. */
private val credentialParameters = setOf(
    "access_token",
    "api_key",
    "apikey",
    "auth",
    "auth_token",
    "client_secret",
    "id_token",
    "key",
    "password",
    "refresh_token",
    "secret",
    "session",
    "sig",
    "signature",
    "token",
)

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
