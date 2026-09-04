package io.github.matthewjones372.kestrel.mcp

import io.kotest.assertions.withClue
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * A launcher over built jars is stale the moment the source moves, and nothing
 * else in the protocol says so.
 */
class BuiltTest {

    @Test
    fun `it says when it was built`() {
        withClue(describedBuild()) {
            describedBuild() shouldContain "0.1.0"
        }
    }

    @Test
    fun `initialize carries it, because that is what a client shows`() {
        withClue("somebody wondering whether they rebuilt is looking at the server, not the source") {
            initialised() shouldContain "built"
        }
    }
}
