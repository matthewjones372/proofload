package io.github.matthewjones372.kestrel.http

import io.github.matthewjones372.kestrel.Action
import io.github.matthewjones372.kestrel.ScenarioBuilder
import io.github.matthewjones372.kestrel.Session
import io.github.matthewjones372.kestrel.StepResult
import io.github.matthewjones372.kestrel.StepScope
import io.github.matthewjones372.kestrel.action
import java.net.URI
import java.net.http.HttpRequest

private const val OK = 200

/**
 * One request, as a value: built up by copying rather than by mutating, so an
 * action can be shared between scenarios and inspected before anything is sent.
 */
class HttpAction internal constructor(
    private val method: String,
    private val path: String,
) : Action {

    /**
     * The path template as written. A report keyed on the substituted URL grows
     * a row per user; keyed on `/orders/{id}` it has one row per endpoint.
     */
    val name: String get() = path

    override fun run(session: Session): StepResult = action { send(this) }.run(session)

    /** Sends, and records what happened on [scope]. For use inside a step body. */
    fun send(scope: StepScope) {
        val response = exchange(request(), scope) ?: return
        if (response.status != OK) scope.fail("status ${response.status}")
    }

    private fun request(): HttpRequest = HttpRequest.newBuilder(URI.create(path))
        .timeout(requestTimeout)
        .method(method, HttpRequest.BodyPublishers.noBody())
        .build()
}

/** Names the step for the path template, which is the row a report wants. */
fun ScenarioBuilder.exec(request: HttpAction) {
    exec(request.name, request)
}
