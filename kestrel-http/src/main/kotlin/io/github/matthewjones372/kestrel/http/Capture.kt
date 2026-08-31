package io.github.matthewjones372.kestrel.http

import io.github.matthewjones372.kestrel.SessionKey
import io.github.matthewjones372.kestrel.StepScope
import io.github.matthewjones372.kestrel.sessionKey

/**
 * One value taken out of a response and put into the session. The key's type
 * parameter is held here, so nothing downstream casts.
 */
internal class Capture<T : Any>(
    private val key: SessionKey<T>,
    private val extract: (Response) -> T?,
) {

    fun applyTo(scope: StepScope, response: Response) {
        val value = extract(response)
        // A capture that quietly does nothing surfaces three steps later as an
        // unfilled path; failing here names the step that actually broke.
        if (value == null) scope.fail(NothingCaptured(key.name)) else scope.set(key, value)
    }
}

private val placeholder = Regex("""\{([^{}]+)}""")

/**
 * Fills `{name}` from the session key of that name, failing [scope] and
 * returning null when the session has nothing under it.
 *
 * The keys are read as `String`: a path segment is text by the time it is one.
 * A key of the same name holding another type is the mistake `Session` already
 * throws on, and it stays a throw here — nobody declared it.
 */
internal fun String.fill(scope: StepScope): String? {
    // Most paths are not templates, and this runs once per request per user.
    if ('{' !in this) return this

    val holes = placeholder.findAll(this).map { it.groupValues[1] }.toList()
    val found = holes.mapNotNull { name -> scope[sessionKey<String>(name)]?.let { name to it } }.toMap()

    val missing = holes.firstOrNull { it !in found }
    if (missing != null) {
        scope.fail(UnfilledPath(missing))
        return null
    }
    return placeholder.replace(this) { match -> found.getValue(match.groupValues[1]) }
}
