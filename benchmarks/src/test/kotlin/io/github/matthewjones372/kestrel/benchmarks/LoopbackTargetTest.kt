package io.github.matthewjones372.kestrel.benchmarks

import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.timing
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

/**
 * The target the sweep points at, not the sweep: nothing here asserts how long
 * anything took, because a duration taken on a shared build machine would not
 * be a measurement of anything.
 */
class LoopbackTargetTest {

    private val client: HttpClient = HttpClient.newHttpClient()

    @Test
    fun `the target answers, and counts one service time per answer`() {
        val statuses = loopback { target ->
            val codes = (1..ASKED).map { get(target.baseUrl).statusCode() }

            withClue("service times recorded: ${target.served().count}") {
                target.served().count shouldBe ASKED.toLong()
            }
            codes
        }

        withClue("statuses: ${statuses.distinct()}") {
            statuses.all { it == OK } shouldBe true
        }
    }

    @Test
    fun `the counter tables do not grow with the number of requests answered`() {
        loopback { target ->
            repeat(FEW) { get(target.baseUrl) }
            val afterAFew = target.tables

            repeat(MANY - FEW) { get(target.baseUrl) }

            withClue("$afterAFew tables after $FEW requests, ${target.tables} after $MANY") {
                target.tables shouldBe afterAFew
            }
        }
    }

    @Test
    fun `the target is gone once the harness has stopped it`() {
        val url = loopback { it.baseUrl }

        assertFailsWith<IOException> { get(url) }
    }

    @Test
    fun `a target answering inside the budget is not the sweep's ceiling`() {
        val quick = Histogram().apply { repeat(SAMPLES) { record(200.microseconds) } }

        quick.timing().answeredWithin(1.milliseconds) shouldBe true
    }

    @Test
    fun `a tail past the budget is enough to say the server may have been the ceiling`() {
        val mostlyQuick = Histogram().apply {
            repeat(SAMPLES) { record(200.microseconds) }
            repeat(SAMPLES / 50) { record(20.milliseconds) }
        }

        withClue("p99 was ${mostlyQuick.timing().p99}") {
            mostlyQuick.timing().answeredWithin(1.milliseconds) shouldBe false
        }
    }

    private fun get(baseUrl: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private companion object {
        const val ASKED = 25
        const val FEW = 50
        const val MANY = 500
        const val OK = 200
        const val SAMPLES = 1_000
    }
}
