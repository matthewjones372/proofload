package io.github.matthewjones372.proofload.smoke

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.Proofload
import io.github.matthewjones372.proofload.http.http
import io.github.matthewjones372.proofload.junit5.LoadTest
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.report.writeHtmlReport
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.step
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

private val browse = step("browse")

/**
 * One load test written the way the README says to write one, compiled against
 * the published jars rather than the projects that produce them.
 *
 * The target is a JDK `HttpServer` in the same JVM: what is under test here is
 * the artifacts, not a network and not a latency number.
 */
class PublishedProofloadTest {

    private lateinit var server: HttpServer

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/products") { exchange -> exchange.respond("""{"items":2}""") }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun HttpExchange.respond(body: String) {
        val bytes = body.toByteArray()
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    @LoadTest
    fun `a scenario built from the published modules runs and reports`(proofload: Proofload) {
        val api = http.baseUrl("http://localhost:${server.address.port}")
        val browsing = scenario("browsing") {
            exec(browse, api.get("/products").expecting(200))
        }

        val result = proofload.run(browsing.at(10.perSecond, over = 1.seconds))

        result[browse].count shouldBe 10L
        result.failed shouldBe 0L

        val report = Files.createTempDirectory("proofload-smoke").resolve("browsing.html")
        result.writeHtmlReport(report)
        Files.readString(report) shouldContain "browse"
    }
}
