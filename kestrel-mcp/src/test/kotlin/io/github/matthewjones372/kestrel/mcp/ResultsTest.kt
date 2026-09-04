package io.github.matthewjones372.kestrel.mcp

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.kestrel.Allowance
import io.github.matthewjones372.kestrel.plan.readPlan
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path

/** What a caller does with a run once it has one. */
class ResultsTest {

    private lateinit var server: HttpServer

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/products") { exchange ->
            val body = "[]".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun plan(): String = """
        kestrel:  plan/1
        baseUrl:  http://localhost:${server.address.port}
        scenario: checkout
        steps:
          - name: browse
            get:  /products
        load:
          rate: 20/s
          over: 200ms
    """.trimIndent()

    private fun finished(): Registry {
        val registry = Registry()
        registry.start(readPlan(plan()), Allowance.none)
        while (registry.finished("r-1") == null) Thread.sleep(POLL)
        return registry
    }

    @Test
    fun `list_runs says nothing before anything has run`() {
        listRuns(Registry()) shouldContain "no runs yet"
    }

    @Test
    fun `list_runs names every run it started`() {
        listRuns(finished()) shouldContain "r-1"
    }

    @Test
    fun `explain hands back the full document, not the summary`() {
        val answered = explain(finished(), "r-1")

        withClue(answered) {
            answered shouldContain "kestrel/run/1"
            withClue("Full is the density that carries the timeline and per-step timings") {
                answered shouldContain "timeline"
            }
        }
    }

    @Test
    fun `report writes a page and returns where it went`(@TempDir dir: Path) {
        val answered = report(finished(), "r-1", dir)

        withClue(answered) {
            answered shouldContain "r-1.html"
        }
        withClue("an agent reads the json; a person opens this") {
            Files.exists(dir.resolve("r-1.html")) shouldBe true
            Files.readString(dir.resolve("r-1.html")) shouldContain "<html"
        }
    }

    @Test
    fun `compare against a run nobody has says so`() {
        compare(finished(), "r-1", "r-99") shouldContain """"isError":true"""
    }

    @Test
    fun `a run explained before it finished says to ask status`() {
        explain(Registry(), "r-1") shouldContain "status"
    }

    private companion object {
        const val POLL = 50L
    }
}
