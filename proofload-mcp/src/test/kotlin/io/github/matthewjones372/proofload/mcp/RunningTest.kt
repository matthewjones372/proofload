package io.github.matthewjones372.proofload.mcp

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.proofload.Allowance
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.plan.readPlan
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * The one tool that sends load, and the split that makes it usable: a run
 * returns an id rather than a result, because a ten-minute run inside one tool
 * call is a dead connection, a retry, and a second ten-minute run against the
 * same target.
 */
class RunningTest {

    private lateinit var server: HttpServer
    private val arrived = AtomicInteger()

    @BeforeEach
    fun start() {
        arrived.set(0)
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/products") { exchange ->
            arrived.incrementAndGet()
            val body = "[]".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun plan(over: String = "300ms"): String = """
        proofload:  plan/1
        baseUrl:  http://localhost:${server.address.port}
        scenario: checkout
        steps:
          - name: browse
            get:  /products
        load:
          rate: 20/s
          over: $over
        goals:
          - step: browse
            p99:  30s
    """.trimIndent()

    @TempDir
    lateinit var kept: Path

    /** A registry of this test's own: runs are kept on disk now, so a shared one leaks between tests. */
    private fun registry() = Registry(runs = kept.resolve("runs"))

    @Test
    fun `a run over the allowance is refused, and sends nothing`() {
        val answered = registry().start(readPlan(plan()), Allowance(maxRate = 1.perSecond))

        withClue(answered) {
            answered shouldContain """"isError":true"""
            answered shouldContain "refused"
        }
        arrived.get() shouldBe 0
    }

    @Test
    fun `a run answers with an id and what it is about to send`() {
        val answered = registry().start(readPlan(plan()), Allowance.none)

        withClue(answered) {
            answered shouldContain "runId"
            answered shouldContain "runId"
            withClue("a caller has to be able to say what it just started") {
                answered shouldContain "requests over"
                answered shouldContain "localhost"
            }
        }
    }

    @Test
    fun `a second run while one is sending is refused by name`() {
        val runs = registry()
        val first = idIn(runs.start(readPlan(plan(over = "1s")), Allowance.none))

        val answered = runs.start(readPlan(plan()), Allowance.none)

        withClue("a caller handed an id for a run that never started polls it forever") {
            answered shouldContain """"isError":true"""
            answered shouldContain "$first is still sending"
        }
    }

    @Test
    fun `status of a run nobody started says so`() {
        registry().status("r-99") shouldContain """"isError":true"""
    }

    @Test
    fun `a finished run answers with the verdict document`() {
        val runs = registry()
        val id = idIn(runs.start(readPlan(plan()), Allowance.none))

        val answered = eventually { runs.status(id).takeIf { !it.contains("sending") } }

        withClue(answered) {
            answered shouldContain """proofload/run/1"""
            answered shouldContain """verdict"""
        }
    }

    @Test
    fun `a run that is still going says how much is left rather than blocking`() {
        val runs = registry()
        val id = idIn(runs.start(readPlan(plan(over = "1s")), Allowance.none))

        withClue("run returns when the run is underway, not when it ends") {
            runs.status(id) shouldContain "sending"
            runs.status(id) shouldContain "remaining"
        }
    }

    /**
     * Polls rather than spins. 0050 holds one run at a time across the JVM, so
     * a test can be waiting on a run another test left in flight, and a spin
     * loop on this thread would starve the virtual thread it is waiting for.
     */
    private fun eventually(read: () -> String?): String {
        val deadline = System.nanoTime() + SECONDS_ALLOWED * 1_000_000_000L
        while (System.nanoTime() < deadline) {
            read()?.let { return it }
            Thread.sleep(POLL)
        }
        return read() ?: "never finished"
    }

    private companion object {
        const val SECONDS_ALLOWED = 60L
        const val POLL = 50L
    }
}
