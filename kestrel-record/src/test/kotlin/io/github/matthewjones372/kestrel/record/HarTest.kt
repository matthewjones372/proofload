package io.github.matthewjones372.kestrel.record

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Every tool that records traffic exports a HAR and they disagree about
 * everything optional in it. What they agree on is what this reads.
 */
class HarTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "no $name on the test classpath" }
            .use { it.readBytes().decodeToString() }

    private val chrome: List<Recorded> get() = readHar(fixture("chrome.har"))

    private val mitmproxy: List<Recorded> get() = readHar(fixture("mitmproxy.har"))

    @Test
    fun `a Chrome export and a mitmproxy export of the same flow read to the same shape`() {
        withClue("one writes _priority and _resourceType, the other params and compression") {
            chrome shouldContainExactly mitmproxy
        }
    }

    @Test
    fun `a request reads with its method, its url, its headers and its body`() {
        val posted = chrome.first()

        posted.method shouldBe "POST"
        posted.url shouldBe "https://api.example.com/orders"
        posted.path shouldBe "/orders"
        posted.origin shouldBe "https://api.example.com"
        posted.body shouldBe """{"cart":"1 anvil"}"""
        posted.headers shouldContainExactly listOf(
            Header("content-type", "application/json"),
            Header("authorization", "Bearer abc.def.ghi"),
        )
    }

    @Test
    fun `an answer reads with its status and its body`() {
        val answered = chrome.first().answer.shouldNotBeNull()

        answered.status shouldBe 201
        answered.body shouldBe """{"id":"7f3a91c2b8","status":"placed"}"""
    }

    @Test
    fun `an entry with no response reads as a request that got none`() {
        withClue("a browser writes a status of zero and a proxy leaves the response out; both mean this") {
            chrome.last().answer shouldBe null
            mitmproxy.last().answer shouldBe null
        }
    }

    @Test
    fun `the gaps are kept from the first request, so nothing depends on when the recording was made`() {
        chrome.map { it.at } shouldContainExactly listOf(Duration.ZERO, 2.seconds, 3.seconds)
    }

    @Test
    fun `something that is not a HAR is refused by name rather than read as an empty one`() {
        shouldThrow<IllegalArgumentException> { readHar("not json at all") }
            .message.orEmpty() shouldContain "not a HAR"

        shouldThrow<IllegalArgumentException> { readHar("""{"log":{"version":"1.2"}}""") }
            .message.orEmpty() shouldContain "not a HAR"
    }
}
