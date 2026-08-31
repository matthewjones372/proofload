package io.github.matthewjones372.kestrel.http

import io.github.matthewjones372.kestrel.SessionKey
import io.github.matthewjones372.kestrel.StepScope
import io.github.matthewjones372.kestrel.sessionKey

/**
 * The cookies one virtual user holds.
 *
 * `java.net.CookieHandler` hangs off the `HttpClient`, and there is one client
 * for the whole run so that TLS handshakes are not measured; a jar there would
 * be one jar for every user, and fifty thousand users taking turns being one
 * logged-in person measures something nobody asked for. So the jar lives in the
 * session, which is already per user, already immutable, and already threaded
 * through every step.
 *
 * Name and value only: no domain, path or `Secure`, because a load test sends
 * to one base URL, and no expiry, because a run shorter than a session cookie's
 * life does not need it and a longer one needs a credential that refreshes.
 */
internal class CookieJar private constructor(private val values: Map<String, String>) {

    /** What a request sends back, or null when there is nothing to send. */
    val header: String? =
        values.entries.joinToString("; ") { (name, value) -> "$name=$value" }.ifEmpty { null }

    fun updatedBy(setCookie: List<String>): CookieJar = CookieJar(values + setCookie.mapNotNull(::parse))

    internal companion object {
        val empty: CookieJar = CookieJar(emptyMap())

        /** The pair before the first `;`; the attributes after it are the ones above. */
        private fun parse(setCookie: String): Pair<String, String>? {
            val pair = setCookie.substringBefore(';')
            val name = pair.substringBefore('=').trim()
            return if ('=' !in pair || name.isEmpty()) null else name to pair.substringAfter('=').trim()
        }
    }
}

/** Reserved by this module, and named so a caller's own key cannot collide with it. */
private val jar: SessionKey<CookieJar> = sessionKey("kestrel-http.cookies")

internal fun StepScope.cookieHeader(): String? = this[jar]?.header

internal fun StepScope.rememberCookies(response: Response) {
    val setCookie = response.setCookie
    if (setCookie.isNotEmpty()) set(jar, (this[jar] ?: CookieJar.empty).updatedBy(setCookie))
}
