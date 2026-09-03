package io.github.matthewjones372.kestrel.record

import io.github.matthewjones372.kestrel.http.http
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.sessionKey
import io.github.matthewjones372.kestrel.step
import io.github.matthewjones372.kestrel.stepNames
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * What a recording is for: the first hour of adopting a load tool is reading a
 * checkout flow back out of somebody's own code and typing it in. This asks
 * whether the typing was worth skipping — the generated scenario's step names
 * against the hand-written one's.
 *
 * The names are read out of the source rather than off the generated value:
 * the generated file carries a `TODO` where its credential was, which is
 * exactly what stops it running until somebody has decided what belongs there.
 */
class ScenarioShapeTest {

    private val api = http.baseUrl("https://api.example.com")

    private val orderId = sessionKey<String>("orderId")

    /** The cookbook's own checkout, as somebody would write it by hand. */
    private val handWritten = scenario("checkout") {
        exec(
            step("POST /orders"),
            api.post("/orders")
                .body("""{"cart":"1 anvil"}""")
                .expecting(201)
                .capture(orderId) { it.body },
        )
        exec(step("GET /orders/{orderId}/confirmation"), api.get("/orders/{orderId}/confirmation"))
    }

    private fun generated(): String =
        draft(readHar(Files.readString(Path.of(checkNotNull(System.getProperty("kestrel.record.checkoutHar"))))))
            .asKotlin("load", "checkout", "checkout.har")

    private val named = Regex("""step\("([^"]+)"\)""")

    @Test
    fun `the recording of the cookbook's checkout has the step names it was written with`() {
        val source = generated()

        val recorded = named.findAll(source).map { it.groupValues[1] }.toList()
        withClue(source) {
            recorded shouldContainExactly handWritten.stepNames
        }
    }

    @Test
    fun `the static asset in the recording is not one of them`() {
        withClue("a page load is forty requests to a CDN and one to the API") {
            generated() shouldNotContain "app.css"
        }
    }
}
