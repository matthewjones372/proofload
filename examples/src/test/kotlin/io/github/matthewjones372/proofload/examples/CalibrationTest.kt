package io.github.matthewjones372.proofload.examples

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.Proofload
import io.github.matthewjones372.proofload.http.exec
import io.github.matthewjones372.proofload.http.http
import io.github.matthewjones372.proofload.junit5.LoadTest
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.step
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val slow = step("/slow")

/**
 * Does a known latency come back as that latency?
 *
 * Every other test here asserts on numbers Proofload produced from samples it was
 * handed. This one gives the target a latency nobody has to trust — the handler
 * sleeps for a fixed time — and checks that the report says so. A load tool
 * whose numbers are self-consistent but wrong is the failure mode that matters.
 */
@Tag("timing")
class CalibrationTest {

    private lateinit var server: HttpServer

    private val targetLatency = 40.milliseconds

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        // A thread pool wide enough that the target is not the queue: the point
        // is to measure a known service time, not to build a bottleneck.
        server.executor = Executors.newVirtualThreadPerTaskExecutor()
        server.createContext("/slow") { exchange ->
            Thread.sleep(targetLatency.inWholeMilliseconds)
            val body = "{}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @LoadTest
    fun `a target that takes forty milliseconds is reported as taking forty milliseconds`(proofload: Proofload) {
        val api = http.baseUrl("http://localhost:${server.address.port}")
        val hitting = io.github.matthewjones372.proofload.scenario("calibration") { exec(api.get("/slow")) }

        val result = proofload.run(hitting.at(20.perSecond, over = 2.seconds))

        val service = result[slow].serviceTime
        withClue("p50 ${service.p50}, p99 ${service.p99}, max ${service.max}") {
            (service.p50 >= targetLatency) shouldBe true
            (service.p50 < targetLatency + 15.milliseconds) shouldBe true
        }

        withClue("the run sent what the profile said: ${result[slow].count}") {
            result[slow].count shouldBe 40L
        }

        withClue("nothing failed: ${result[slow].failed.reasons}") {
            result[slow].failed.count shouldBe 0L
        }

        // Response time carries the generator's own lateness on top of service
        // time, so it can never be the smaller of the two.
        withClue("service ${service.p50}, response ${result[slow].responseTime.p50}") {
            (result[slow].responseTime.p50 >= service.p50) shouldBe true
        }
    }
}
