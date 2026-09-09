package io.github.matthewjones372.proofload.examples

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.run
import io.github.matthewjones372.proofload.expecting
import io.github.matthewjones372.proofload.failureRate
import io.github.matthewjones372.proofload.fedBy
import io.github.matthewjones372.proofload.feed
import io.github.matthewjones372.proofload.http.exec
import io.github.matthewjones372.proofload.http.http
import io.github.matthewjones372.proofload.keptSchedule
import io.github.matthewjones372.proofload.p99
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.percent
import io.github.matthewjones372.proofload.report.markdown
import io.github.matthewjones372.proofload.report.writeHtmlReport
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.sessionKey
import io.github.matthewjones372.proofload.step
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val shopper = sessionKey<String>("shopper")

class ReportSpike {

    @Test
    fun `write a report from a real run`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.executor = Executors.newVirtualThreadPerTaskExecutor()
        val jitter = Random(7)

        fun context(path: String, base: Long, spread: Long, failEvery: Int) =
            server.createContext(path) { exchange ->
                Thread.sleep(base + jitter.nextLong(spread))
                val failed = failEvery > 0 && jitter.nextInt(failEvery) == 0
                val body = "{}".toByteArray()
                exchange.sendResponseHeaders(if (failed) 503 else 200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }

        context("/products", base = 8L, spread = 6L, failEvery = 0)
        context("/cart", base = 25L, spread = 20L, failEvery = 0)
        context("/pay", base = 90L, spread = 120L, failEvery = 12)
        server.start()

        val api = http.baseUrl("http://localhost:${server.address.port}")
        val checkout = scenario("checkout") {
            exec(api.get("/products/{shopper}"))
            exec(api.get("/cart"))
            exec(api.get("/pay"))
        }

        val result = checkout.at(120.perSecond, over = 4.seconds)
            .fedBy(feed(shopper) { user -> "shopper-$user" })
            .expecting(
                p99(step("/pay")) under 400.milliseconds,
                failureRate under 1.percent,
                keptSchedule,
            )
            .run()

        println(result.markdown())
        result.writeHtmlReport(Path.of("/tmp/claude-501/proofload-report.html"))
    }
}
