package io.github.matthewjones372.kestrel.examples

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.kestrel.Change
import io.github.matthewjones372.kestrel.against
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.baseline.readBaseline
import io.github.matthewjones372.kestrel.baseline.writeBaseline
import io.github.matthewjones372.kestrel.engine.Kestrel
import io.github.matthewjones372.kestrel.http.exec
import io.github.matthewjones372.kestrel.http.http
import io.github.matthewjones372.kestrel.junit5.LoadTest
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/**
 * The loop a team actually runs: measure, keep the numbers, change the service,
 * measure again, and ask whether anything got worse.
 *
 * The target here gets slower on purpose between the two runs, so the answer is
 * known before the tool is asked.
 */
class RegressionTest {

    private lateinit var server: HttpServer
    private val latency = AtomicLong(20L)

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.executor = Executors.newVirtualThreadPerTaskExecutor()
        server.createContext("/pay") { exchange ->
            Thread.sleep(latency.get())
            val body = "{}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun measure(kestrel: Kestrel) = kestrel.run(
        scenario("paying") { exec(http.baseUrl("http://localhost:${server.address.port}").get("/pay")) }
            .at(100.perSecond, over = 2.seconds),
    )

    @LoadTest
    fun `a service that got slower is reported as worse, and one that did not is not`(
        kestrel: Kestrel,
        @TempDir dir: Path,
    ) {
        val baseline = dir.resolve("baseline.kestrel")

        // Thrown away. The first run of a JVM pays for class loading, JIT and
        // opening connections, and it is measurably slower than every run
        // after it — so a baseline taken from a cold process would report the
        // next release as an improvement.
        measure(kestrel)

        measure(kestrel).writeBaseline(baseline)

        val unchanged = measure(kestrel).against(readBaseline(baseline))
        withClue("same target twice: $unchanged") {
            unchanged.single().shouldBeInstanceOf<Change.Indistinguishable>()
        }

        // The deploy that made it worse.
        latency.set(200L)

        val slower = measure(kestrel).against(readBaseline(baseline))
        withClue("target ten times slower: $slower") {
            val worse = slower.single().shouldBeInstanceOf<Change.Worse>()
            (worse.now > worse.before) shouldBe true
        }
    }
}
