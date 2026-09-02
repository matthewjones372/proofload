package io.github.matthewjones372.kestrel.http

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.kestrel.SampleSink
import io.github.matthewjones372.kestrel.Session
import io.github.matthewjones372.kestrel.StepResult
import io.github.matthewjones372.kestrel.StepScope
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A retry that is folded into one measurement reports the target as slower
 * than it is and hides that it answered wrongly first. Two counters keep both
 * facts, and the sample is the attempt that decided the step.
 */
class RetryingTest {

    private lateinit var server: HttpServer

    private val hits = AtomicInteger()

    private val failFirst = AtomicInteger(0)

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/pay") { exchange ->
            val status = if (hits.incrementAndGet() <= failFirst.get()) 503 else 200
            // The failing answers are slow and the good one is quick, so a
            // sample covering every attempt could not pass for the last one.
            if (status == 503) Thread.sleep(120)
            val body = "{}".toByteArray()
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun api() = http.baseUrl("http://localhost:${server.address.port}")

    private fun run(action: HttpAction, samples: SampleSink? = null): StepResult =
        StepScope(Session.empty, samples).also(action::run).result()

    @Test
    fun `a step retried once is one request and two attempts`() {
        failFirst.set(1)

        val result = run(api().get("/pay").retrying(times = 2, on = { it.status == 503 }, backingOff = 1.milliseconds))

        result.shouldBeInstanceOf<StepResult.Ok>().attempts shouldBe 2
        hits.get() shouldBe 2
    }

    @Test
    fun `the sample is the last attempt, not the attempts added up`() {
        failFirst.set(1)
        val took = mutableListOf<Duration>()

        run(
            api().get("/pay").retrying(times = 2, on = { it.status == 503 }, backingOff = 50.milliseconds),
            samples = { each, _, _ -> took += each },
        )

        withClue("one sample, and it is the quick attempt that succeeded: $took") {
            took.size shouldBe 1
            (took.single() < 100.milliseconds) shouldBe true
        }
    }

    @Test
    fun `a step retries only on the condition it was given`() {
        failFirst.set(1)

        val result = run(api().get("/pay").retrying(times = 2, on = { it.status == 429 }, backingOff = 1.milliseconds))

        withClue("503 is not 429, so it sent once and failed on the status") {
            result.shouldBeInstanceOf<StepResult.Failed>().attempts shouldBe 1
            hits.get() shouldBe 1
        }
    }

    @Test
    fun `a retry gives up after the number of tries it was allowed`() {
        failFirst.set(10)

        val result = run(api().get("/pay").retrying(times = 2, on = { it.status == 503 }, backingOff = 1.milliseconds))

        result.shouldBeInstanceOf<StepResult.Failed>().reason shouldBe HttpStatus(503)
        withClue("the first send and two retries") { hits.get() shouldBe 3 }
    }
}
