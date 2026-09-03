package io.github.matthewjones372.kestrel.record

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The chain a hand-written scenario gets wrong. A transcription hard-codes the
 * id that should have been captured, and nothing fails — the run measures a
 * lighter experiment than the one somebody meant.
 */
class CorrelationTest {

    private fun request(
        method: String,
        path: String,
        answer: String? = null,
        body: String? = null,
        at: Long = 0,
    ): Recorded = Recorded(
        method = method,
        url = "https://api.example.com$path",
        headers = emptyList(),
        body = body,
        answer = Answer(if (method == "POST") CREATED else OK, emptyList(), answer),
        at = at.seconds,
    )

    private fun drafted(vararg recorded: Recorded): Draft = draft(recorded.toList())

    @Test
    fun `an order created and then fetched by id is a capture and a templated path`() {
        val drafted = drafted(
            request("POST", "/orders", answer = """{"id":"7f3a91c2b8"}""", body = """{"cart":"1 anvil"}"""),
            request("GET", "/orders/7f3a91c2b8/confirmation", at = 1),
        )

        drafted.captures shouldContainExactly listOf(Capture("orderId", "id"))
        withClue("the key is named for the path that produced it, not for the word `id`") {
            drafted.steps[1].path shouldBe "/orders/{orderId}/confirmation"
        }
    }

    @Test
    fun `a value in a body is templated too, not only one in a path`() {
        val drafted = drafted(
            request("POST", "/orders", answer = """{"id":"7f3a91c2b8"}"""),
            request("POST", "/payments", body = """{"order":"7f3a91c2b8"}""", at = 1),
        )

        drafted.steps[1].body shouldBe """{"order":"{orderId}"}"""
    }

    @Test
    fun `a value in two answers before it is used belongs to the nearer one`() {
        val drafted = drafted(
            request("POST", "/quotes", answer = """{"id":"7f3a91c2b8"}"""),
            request("POST", "/orders", answer = """{"id":"7f3a91c2b8"}""", at = 1),
            request("GET", "/orders/7f3a91c2b8", at = 2),
        )

        withClue("read backwards from the request that used it, the first answer carrying it put it there") {
            drafted.steps[1].captures shouldContainExactly listOf(Capture("orderId", "id"))
            drafted.steps[0].captures shouldContainExactly emptyList()
        }
    }

    @Test
    fun `a value the request itself sent is not a capture, because nothing produced it`() {
        val drafted = drafted(
            request("POST", "/orders/7f3a91c2b8", answer = """{"id":"7f3a91c2b8"}"""),
            request("GET", "/orders/7f3a91c2b8", at = 1),
        )

        withClue("a service echoing back what it was sent has produced nothing") {
            drafted.captures shouldContainExactly emptyList()
        }
    }

    @Test
    fun `a short value is a comment rather than a capture, because it may be a coincidence`() {
        val drafted = drafted(
            request("POST", "/orders", answer = """{"status":"open"}"""),
            request("GET", "/orders?status=open", at = 1),
        )
        val source = drafted.asKotlin("load", "recorded", "one.har")

        withClue(source) {
            drafted.captures shouldContainExactly emptyList()
            source shouldContain "may want a capture; it is too short to be sure"
            source shouldNotContain "{status}"
        }
    }

    @Test
    fun `a gap between requests is a comment rather than a rate line`() {
        val source = drafted(
            request("POST", "/orders", answer = """{"id":"7f3a91c2b8"}"""),
            request("GET", "/orders/7f3a91c2b8", at = 4),
        ).asKotlin("load", "recorded", "one.har")

        withClue(source) {
            source shouldContain "The recorded gap before this was 4s — one person's think time, not a rate line."
        }
    }

    private companion object {
        const val OK = 200
        const val CREATED = 201
    }
}
