package io.github.matthewjones372.proofload.examples

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.Proofload
import io.github.matthewjones372.proofload.fedBy
import io.github.matthewjones372.proofload.feed
import io.github.matthewjones372.proofload.http.HttpStatus
import io.github.matthewjones372.proofload.http.http
import io.github.matthewjones372.proofload.http.send
import io.github.matthewjones372.proofload.junit5.LoadTest
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.report.appendToStepSummary
import io.github.matthewjones372.proofload.report.markdown
import io.github.matthewjones372.proofload.report.writeHtmlReport
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.sessionKey
import io.github.matthewjones372.proofload.step
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val orderId = sessionKey<String>("orderId")
private val customer = sessionKey<String>("customer")

private val browse = step("browse")
private val placeOrder = step("place order")
private val pay = step("pay")
private val confirm = step("confirm")

/**
 * Every module at once: a scenario of HTTP steps, run by the engine on virtual
 * threads, asserted like any other test, and written out as a report.
 *
 * The target is a JDK `HttpServer` in the same JVM, so this measures the tool
 * rather than a network — which is the only honest thing an example can claim.
 */
class CheckoutLoadTest {

    private lateinit var server: HttpServer

    /** What the target was actually asked for, so "every user is different" is checked rather than claimed. */
    private val seenCustomers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/products") { exchange ->
            seenCustomers.add(exchange.requestURI.path)
            exchange.respond(status = 200, body = """{"items":2}""")
        }
        server.createContext("/orders") { exchange ->
            exchange.responseHeaders.add("location", "/orders/1")
            exchange.respond(status = 201, body = """{"id":1}""")
        }
        // Every fourth payment is refused, so the report has failures to show.
        server.createContext("/pay") { exchange ->
            val refused = exchange.requestURI.query?.endsWith("4") == true
            exchange.respond(status = if (refused) 503 else 200, body = "{}")
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun com.sun.net.httpserver.HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun endpoint() = http.baseUrl("http://localhost:${server.address.port}")

    @LoadTest
    fun `checkout holds up at fifty a second`(proofload: Proofload) {
        val api = endpoint()

        val checkout = scenario("checkout") {
            // A step that is one request is that request.
            // The template is filled from the session the feeder seeded, so
            // every user asks for its own page and a cache in front of the
            // target cannot answer for all of them.
            exec(browse, api.get("/products/{customer}"))
            exec(
                placeOrder,
                api.post("/orders")
                    .header("content-type", "application/json")
                    .body("""{"cart":"1 anvil"}""")
                    .expecting(201)
                    .capture(orderId) { response -> response.header("location") },
            )
            // A step that does more than send gets a body, and `send` there
            // reads as a verb taking the request.
            exec(pay) {
                val order = get(orderId)
                send(api.get("/pay").header("x-order", order.orEmpty()))
            }
        }

        val result = proofload.run(
            checkout.at(50.perSecond, over = 1.seconds)
                .fedBy(feed(customer) { user -> "customer-$user" }),
        )

        result[browse].count shouldBe 50L
        seenCustomers.size shouldBe 50
        result[placeOrder].failed.count shouldBe 0L
        result[pay].serviceTime.p99 shouldBeLessThan 500.milliseconds

        val report = Files.createTempDirectory("proofload").resolve("checkout.html")
        result.writeHtmlReport(report)
        Files.readString(report) shouldContain "place order"
        result.markdown() shouldContain "place order"
        result.appendToStepSummary { null }
    }

    @LoadTest
    fun `a step that fails is counted as failed, and the user after it is not run`(proofload: Proofload) {
        val api = endpoint()

        val checkout = scenario("refused") {
            exec(pay) { send(api.get("/pay?id=4")) }
            exec(confirm) { send(api.get("/products")) }
        }

        val result = proofload.run(checkout.at(10.perSecond, over = 1.seconds))

        result[pay].failedWith(HttpStatus(503)) shouldBe 10L
        result[pay].failed.count shouldBe 10L
        withClue("every request was refused, so the whole step is the failed side of it") {
            result[pay].ok.count shouldBe 0L
            result[pay].serviceTime.count shouldBe result[pay].failed.serviceTime.count
        }
        result.ran(confirm) shouldBe false
    }
}
