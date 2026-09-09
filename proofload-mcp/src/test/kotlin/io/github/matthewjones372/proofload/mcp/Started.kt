package io.github.matthewjones372.proofload.mcp

/**
 * The id a start answered with.
 *
 * Read rather than assumed: ids stopped being `r-1` when two replicas serving
 * one caller each began handing out the same one, and a test that knows the id
 * before it asks is a test that only passes against a counter.
 *
 * Only from a start that succeeded. A refusal names the run already sending,
 * so an id read out of one belongs to somebody else and waiting for it never
 * ends.
 */
internal fun idIn(started: String): String {
    check("runId" in started) { "no run was started: $started" }
    return requireNotNull(Regex("""r-[0-9a-f]{8}""").find(started)) { started }.value
}
