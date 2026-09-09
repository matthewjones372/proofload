package io.github.matthewjones372.proofload.record

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration

private const val JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl"

/**
 * A recording is full of live tokens, and a generator that wrote them into
 * source would put them in somebody's git history. There is no flag to turn
 * this off: a switch somebody sets once and forgets is a token in a public
 * repository.
 */
class RedactionTest {

    private fun sending(vararg headers: Pair<String, String>): List<Recorded> = listOf(
        Recorded(
            method = "GET",
            url = "https://api.example.com/orders",
            headers = headers.map { (name, value) -> Header(name, value) },
            body = null,
            answer = Answer(200, emptyList(), null),
            at = Duration.ZERO,
        ),
    )

    private fun generated(vararg headers: Pair<String, String>): String =
        draft(sending(*headers)).asKotlin("load", "recorded", "one.har")

    @Test
    fun `every header a name says is a credential is dropped, and named where it was`() {
        listOf("authorization", "cookie", "set-cookie", "x-api-key").forEach { name ->
            val source = generated(name to "the-secret-value")

            withClue(source) {
                source shouldNotContain "the-secret-value"
                source shouldContain "TODO(\"the $name header was dropped"
                source shouldContain "// The recording's $name header was dropped"
            }
        }
    }

    @Test
    fun `a header whose value parses as a JWT is dropped whatever it is called`() {
        val source = generated("x-session" to JWT)

        withClue(source) {
            source shouldNotContain JWT
            source shouldContain "TODO(\"the x-session header was dropped"
        }
    }

    @Test
    fun `a bearer prefix does not hide a token from the shape test`() {
        Header("x-thing", "Bearer $JWT").isCredential() shouldBe true
    }

    @Test
    fun `three dot-separated words are not a JWT, and an ordinary header survives`() {
        val source = generated("accept" to "application/json", "x-trace" to "a.b.c")

        withClue(source) {
            source shouldContain """.header("accept", "application/json")"""
            source shouldContain """.header("x-trace", "a.b.c")"""
        }
    }

    @Test
    fun `a client's own headers are left out rather than written into a request nobody wrote`() {
        val source = generated("host" to "api.example.com", "content-length" to "18")

        withClue(source) {
            source shouldNotContain "\"host\""
            source shouldNotContain "content-length"
        }
    }
}
