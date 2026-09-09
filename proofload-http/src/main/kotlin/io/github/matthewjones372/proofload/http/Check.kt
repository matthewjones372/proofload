package io.github.matthewjones372.proofload.http

/**
 * One named question asked of a response. The name is the failure reason, so a
 * report names the check that went rather than saying that one did.
 */
internal class Check(
    val name: String,
    private val holds: (Response) -> Boolean,
) {

    fun rejects(response: Response): Boolean = !holds(response)
}
