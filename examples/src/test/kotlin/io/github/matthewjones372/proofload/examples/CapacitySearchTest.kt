package io.github.matthewjones372.proofload.examples

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.proofload.Capacity
import io.github.matthewjones372.proofload.Rung
import io.github.matthewjones372.proofload.engine.Proofload
import io.github.matthewjones372.proofload.http.http
import io.github.matthewjones372.proofload.junit5.LoadTest
import io.github.matthewjones372.proofload.p99
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.step
import io.github.matthewjones372.proofload.sustainable
import io.github.matthewjones372.proofload.warmingUp
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

private val serve = step("/serve")

/**
 * Does a search arrive at a rate a real target really does sustain?
 *
 * The target answers one request at a time and holds each for [SERVICE], so it
 * cannot serve more than one every sixty milliseconds however it is asked —
 * a ceiling of the handler's own, not a number this tool produced. Every other
 * proof of the search judges a function that was told which rate to fail above;
 * this one has to find the knee from the outside, on the engine, over sockets.
 */
@Tag("timing")
class CapacitySearchTest {

    private lateinit var server: HttpServer

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        // The bottleneck, and the only one: one handler held for the service
        // time is a target whose ceiling can be worked out on paper.
        server.executor = Executors.newFixedThreadPool(1)
        server.createContext("/serve") { exchange ->
            Thread.sleep(SERVICE.inWholeMilliseconds)
            val body = "{}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @LoadTest
    fun `a search finds the rate a real target sustains, and names the goal that stopped it`(proofload: Proofload) {
        val api = http.baseUrl("http://localhost:${server.address.port}")
        val fastEnough = p99(serve) under 200.milliseconds
        val hitting = scenario("serve") { exec(serve, api.get("/serve")) }
        // Warmed per rung, at that rung's own rate: the first rung decides
        // whether the ladder climbs at all, so it is the one that must not be
        // measuring the JIT and the first connection to a socket that has
        // never been opened. The search counts the warm-ups in what it quotes.
        val search = hitting
            .sustainable(upTo = 40.perSecond, holding = 2.seconds, expecting = listOf(fastEnough))
            .warmingUp(2.seconds)

        val capacity = proofload.run(search)

        withClue(capacity.curveAsText()) {
            // The low rungs only. A rung high enough for its departure interval
            // to be within reach of a scheduler stall can void on one, and that
            // rung really was a departure behind at the tail.
            withClue("a departure every 250 ms and every 125 ms, against an injector late by milliseconds") {
                capacity.curve[0].outcome shouldBe Rung.Outcome.Passed
                capacity.curve[1].outcome shouldBe Rung.Outcome.Passed
            }
            capacity.limitedBy shouldBe fastEnough

            val found = capacity.rate.shouldNotBeNull().perSecond
            // Half again, not the ceiling itself: the goal tolerates a p99 of
            // 200 ms against a 60 ms answer, so the rate that still meets it
            // carries a request or two of standing queue.
            withClue("one request every 60 ms is ${CEILING.roundedRate()}, and the ladder climbs to 40/s") {
                found shouldBeLessThanOrEqual CEILING * ALLOWED_QUEUE
            }
            // Against what the target was measured doing rather than a number
            // written here: one server at the service time it showed cannot go
            // faster than this, and a machine that halves under load takes the
            // target's ceiling down with it.
            val served = 1.0 / capacity.curve.first().result[serve].serviceTime.p50.toDouble(DurationUnit.SECONDS)
            withClue("one server at the service time this run measured tops out near ${served.roundedRate()}") {
                found shouldBeGreaterThanOrEqual served / 2.0
            }
        }
    }

    private fun Double.roundedRate(): String = String.format(Locale.ROOT, "%.1f/s", this)

    /** Every rung, for a failure that has to say which one went wrong. */
    private fun Capacity.curveAsText(): String = curve.joinToString(separator = "\n") { rung ->
        "${rung.rate.perSecond}/s asked, ${rung.offered.perSecond}/s offered: ${rung.outcome}, " +
            "p99 ${rung.result[serve].responseTime.p99}, behind p99 ${rung.result.behind.p99}"
    }

    private companion object {
        val SERVICE = 60.milliseconds

        /** One request every sixty milliseconds, which is all a single handler can be asked for. */
        const val CEILING = 16.7

        /** How far past that ceiling the queue the goal tolerates can carry the reported rate. */
        const val ALLOWED_QUEUE = 1.5
    }
}
