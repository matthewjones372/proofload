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
        // What it took, or that it took nothing — the second is the line a
        // reader running a trace is usually looking for.
        if (scope.narrating) {
            scope.note(if (value == null) "captured nothing for ${key.name}" else "captured ${key.name} = $value")
        }
    }
}

private val placeholder = Regex("""\{([^{}]+)}""")

/**
 * The same idea, tightened to identifiers, for a body.
 *
 * A path rarely contains a brace and a body almost always does: `{"cart":"1
 * anvil"}` is one match under the rule above, named `"cart":"1 anvil"`, and
 * every JSON body would fail as a placeholder the session had nothing under.
 * A name here is what a session key is called — a letter or underscore and
 * then letters, digits or underscores — which no JSON document opens with.
 *
 * The looser rule is left alone where it is: a path template has never needed
 * this and narrowing it would be a break for nobody.
 */
private val named = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)}""")

/**
 * Fills `{name}` from the session key of that name, failing [scope] and
 * returning null when the session has nothing under it.
 *
 * The keys are read as `String`: a path segment is text by the time it is one.
 * A key of the same name holding another type is the mistake `Session` already
 * throws on, and it stays a throw here — nobody declared it.
 */
internal fun String.fill(scope: StepScope): String? = filledBy(placeholder, scope)

/**
 * The same, for a request body: `{name}` from the session key of that name,
 * and every other brace left where it is.
 *
 * A missing key fails the step rather than sending the placeholder on. A body
 * that quietly carries `{cart}` to the target is a request nobody wrote,
 * answered for by a service that had no part in the mistake.
 */
internal fun String.fillBody(scope: StepScope): String? = filledBy(named, scope)

private fun String.filledBy(pattern: Regex, scope: StepScope): String? {
    // Most paths are not templates, and this runs once per request per user.
    if ('{' !in this) return this

    val holes = pattern.findAll(this).map { it.groupValues[1] }.toList()
    val found = holes.mapNotNull { name -> scope[sessionKey<String>(name)]?.let { name to it } }.toMap()

    val missing = holes.firstOrNull { it !in found }
    if (missing != null) {
        scope.fail(UnfilledPath(missing))
        return null
    }
    return pattern.replace(this) { match -> found.getValue(match.groupValues[1]) }
}
