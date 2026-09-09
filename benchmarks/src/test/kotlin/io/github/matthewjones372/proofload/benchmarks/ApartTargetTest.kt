package io.github.matthewjones372.proofload.benchmarks

import io.github.matthewjones372.proofload.Histogram
import io.github.matthewjones372.proofload.timing
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

/**
 * The target in a JVM of its own, and the counts it hands back over a file.
 *
 * Nothing here asserts a duration: a figure taken on a shared build machine is
 * not a measurement, which is the same reason [LoopbackTargetTest] asserts
 * counts and statuses only.
 */
class ApartTargetTest {

    private val client: HttpClient = HttpClient.newHttpClient()

    @Test
    fun `the buckets survive the trip through a file`() {
        val counted = Histogram().apply {
            repeat(SAMPLES) { record(200.microseconds) }
            repeat(SAMPLES / 50) { record(20.milliseconds) }
        }.timing()

        val read = parseServed(servedLines(counted))

        withClue("wrote ${servedLines(counted).size} lines") {
            read.count shouldBe counted.count
            read.p50 shouldBe counted.p50
            read.p99 shouldBe counted.p99
            read.max shouldBe counted.max
            read.precision shouldBe counted.precision
        }
    }

    @Test
    fun `a target that answered nothing reads back as a timing that counted nothing`() {
        val read = parseServed(servedLines(Histogram().timing()))

        read.count shouldBe 0L
    }

    @Test
    fun `the target in its own JVM answers, and reports what it served`() {
        val run = apart { target ->
            (1..ASKED).map { get(target.baseUrl).statusCode() }
        }

        withClue("statuses: ${run.answered.distinct()}") {
            run.answered.all { it == OK } shouldBe true
        }
        withClue("the target reported ${run.served.count} service times for $ASKED requests") {
            run.served.count shouldBe ASKED.toLong()
        }
    }

    private fun get(baseUrl: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create(baseUrl)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private companion object {
        const val ASKED = 25
        const val OK = 200
        const val SAMPLES = 1_000
    }
}
