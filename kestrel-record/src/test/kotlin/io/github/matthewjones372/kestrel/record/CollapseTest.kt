package io.github.matthewjones372.kestrel.record

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private const val OK = 200

/**
 * A page load is forty requests to a CDN and one to the API, and a browsing
 * session is forty requests to one endpoint. Neither is forty rows in a report
 * anybody reads.
 */
class CollapseTest {

    private fun got(path: String, at: Long = 0): Recorded = Recorded(
        method = "GET",
        url = "https://shop.example.com$path",
        headers = emptyList(),
        body = null,
        answer = Answer(OK, emptyList(), null),
        at = at.milliseconds,
    )

    @Test
    fun `forty products are one step, and the count is kept`() {
        val drafted = draft((1..40).map { got("/products/$it", at = it * 100L) })

        drafted.steps.size shouldBe 1
        drafted.steps.single().path shouldBe "/products/{id}"
        drafted.steps.single().stoodFor shouldBe 40

        val source = drafted.asKotlin("load", "browsing", "one.har")
        withClue(source) {
            source shouldContain "// Stood for 40 requests differing only by a segment."
            source shouldContain """step("GET /products/{id}")"""
        }
    }

    @Test
    fun `a uuid segment is a segment too`() {
        val drafted = draft(
            listOf(
                got("/orders/1b4e28ba-2fa1-11d2-883f-0016d3cca427"),
                got("/orders/2c5f39cb-3fb2-22e3-994f-1127e4ddb538", at = 100),
            ),
        )

        drafted.steps.single().path shouldBe "/orders/{id}"
    }

    @Test
    fun `a segment a capture already named is left alone`() {
        val posted = Recorded(
            method = "POST",
            url = "https://shop.example.com/orders",
            headers = emptyList(),
            body = null,
            answer = Answer(OK, emptyList(), """{"id":"7f3a91c2b8"}"""),
            at = Duration.ZERO,
        )
        val drafted = draft(listOf(posted, got("/orders/7f3a91c2b8", at = 100)))

        withClue("a chain somebody can run beats a segment named twice") {
            drafted.steps.map { it.path } shouldContainExactly listOf("/orders", "/orders/{orderId}")
        }
    }

    @Test
    fun `two endpoints that only look alike are still two steps`() {
        val drafted = draft(listOf(got("/products/17"), got("/baskets/17", at = 100)))

        drafted.steps.map { it.path } shouldContainExactly listOf("/products/17", "/baskets/17")
    }

    @Test
    fun `static assets are excluded whatever else is asked for`() {
        val drafted = draft(listOf(got("/assets/app.css"), got("/logo.png", at = 10), got("/orders", at = 20)))

        withClue("a page load is forty requests to a CDN, and including them measures somebody else's cache") {
            drafted.steps.map { it.path } shouldContainExactly listOf("/orders")
        }
    }

    @Test
    fun `include and exclude are the caller's own rules, over the path`() {
        val recorded = listOf(got("/orders"), got("/admin/flush", at = 10))

        draft(recorded, include = Regex("^/orders")).steps.map { it.path } shouldContainExactly listOf("/orders")
        draft(recorded, exclude = Regex("^/admin")).steps.map { it.path } shouldContainExactly listOf("/orders")
    }
}
