package io.github.matthewjones372.proofload.examples

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.run
import io.github.matthewjones372.proofload.expecting
import io.github.matthewjones372.proofload.failureRate
import io.github.matthewjones372.proofload.fedBy
import io.github.matthewjones372.proofload.feed
import io.github.matthewjones372.proofload.goodput
import io.github.matthewjones372.proofload.http.http
import io.github.matthewjones372.proofload.keptSchedule
import io.github.matthewjones372.proofload.p99
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.percent
import io.github.matthewjones372.proofload.report.writeHtmlReport
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.sessionKey
import io.github.matthewjones372.proofload.step
import io.github.matthewjones372.proofload.warmingUp
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val shopper = sessionKey<String>("shopper")

/**
 * Not a test — a demo that writes a real report. Tagged `timing` so it runs
 * alone and never inside `build`; the numbers on the page are a real run's.
 */
@Tag("timing")
class ReportShowcase {

    @Test
    fun `a checkout run, written as a report`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.executor = Executors.newVirtualThreadPerTaskExecutor()
        val jitter = Random(20260901)

        // A target that answers fast at first and gets slower as the run's
        // concurrency climbs, so the timeline has something to show and the
        // pay step's tail is a real tail rather than flat noise.
        fun context(path: String, base: Long, spread: Long, failIn: Int) =
            server.createContext(path) { exchange ->
                Thread.sleep(base + jitter.nextLong(spread))
                val failed = failIn > 0 && jitter.nextInt(failIn) == 0
                val body = "{}".toByteArray()
                exchange.sendResponseHeaders(if (failed) 503 else 200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }

        context("/products", base = 5L, spread = 6L, failIn = 0)
        context("/cart", base = 14L, spread = 16L, failIn = 0)
        context("/pay", base = 120L, spread = 250L, failIn = 70)
        server.start()

        val api = http.baseUrl("http://localhost:${server.address.port}")
        val products = step("GET /products")
        val cart = step("GET /cart")
        val pay = step("POST /pay")

        val checkout = scenario("checkout") {
            exec(products, api.get("/products/{shopper}"))
            exec(cart, api.get("/cart"))
            exec(pay, api.post("/pay").body("""{"total":"1 anvil"}""").expecting(200))
        }

        // Declared on the run rather than sent by hand before it. The first
        // departures of a JVM pay for class loading, the JIT and opening
        // connections, and they pay for it as lateness the target never
        // caused; the page below says how long was warmed and that none of it
        // was counted.
        val result = checkout.at(30.perSecond, over = 30.seconds)
            .warmingUp(8.seconds)
            .fedBy(feed(shopper) { user -> "shopper-$user" })
            .expecting(
                p99(pay) under 300.milliseconds,
                goodput(pay, under = 300.milliseconds) atLeast 99.percent,
                failureRate under 1.percent,
                keptSchedule,
            )
            .run()

        val out = Path.of(System.getProperty("proofload.showcase.out", "build/reports/showcase.html"))
        result.writeHtmlReport(out)
    }
}
