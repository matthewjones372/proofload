package io.github.matthewjones372.kestrel.smoke

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.engine.Kestrel
import io.github.matthewjones372.kestrel.http.http
import io.github.matthewjones372.kestrel.junit5.LoadTest
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.report.writeHtmlReport
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.step
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
class PublishedKestrelTest {

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
    fun `a scenario built from the published modules runs and reports`(kestrel: Kestrel) {
        val api = http.baseUrl("http://localhost:${server.address.port}")
        val browsing = scenario("browsing") {
            exec(browse, api.get("/products").expecting(200))
        }

        val result = kestrel.run(browsing.at(10.perSecond, over = 1.seconds))

        result[browse].count shouldBe 10L
        result.failed shouldBe 0L

        val report = Files.createTempDirectory("kestrel-smoke").resolve("browsing.html")
        result.writeHtmlReport(report)
        Files.readString(report) shouldContain "browse"
    }
}
