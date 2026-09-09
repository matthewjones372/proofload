package io.github.matthewjones372.proofload.mcp

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.proofload.Allowance
import io.github.matthewjones372.proofload.plan.readPlan
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
        proofload:  plan/1
        baseUrl:  http://localhost:${server.address.port}
        scenario: checkout
        steps:
          - name: browse
            get:  /products
        load:
          rate: 20/s
          over: 200ms
    """.trimIndent()

    @TempDir
    lateinit var kept: Path

    /** A registry of this test's own, since runs are kept on disk and a shared one leaks between tests. */
    private fun registry() = Registry(runs = kept.resolve("runs"))

    /** A finished run and its id, read rather than assumed: an id is no longer a counter. */
    private fun finished(): Pair<Registry, String> {
        val registry = registry()
        val id = idIn(registry.start(readPlan(plan()), Allowance.none))
        while (registry.finished(id) == null) Thread.sleep(POLL)
        return registry to id
    }

    @Test
    fun `list_runs says nothing before anything has run`() {
        listRuns(registry()) shouldContain "no runs yet"
    }

    @Test
    fun `list_runs names every run it started`() {
        val (registry, id) = finished()

        listRuns(registry) shouldContain id
    }

    @Test
    fun `explain hands back the full document, not the summary`() {
        val (registry, id) = finished()

        val answered = explain(registry, id)

        withClue(answered) {
            answered shouldContain "proofload/run/1"
            withClue("Full is the density that carries the timeline and per-step timings") {
                answered shouldContain "timeline"
            }
        }
    }

    @Test
    fun `report writes a page and returns where it went`(@TempDir dir: Path) {
        val (registry, id) = finished()

        val answered = report(registry, id, dir)

        withClue(answered) {
            answered shouldContain "$id.html"
        }
        withClue("an agent reads the json; a person opens this") {
            Files.exists(dir.resolve("$id.html")) shouldBe true
            Files.readString(dir.resolve("$id.html")) shouldContain "<html"
        }
    }

    @Test
    fun `compare against a run nobody has says so`() {
        val (registry, id) = finished()

        compare(registry, id, "r-99") shouldContain """"isError":true"""
    }

    @Test
    fun `a run explained before it finished says to ask status`() {
        explain(registry(), "r-1") shouldContain "status"
    }

    private companion object {
        const val POLL = 50L
    }
}
