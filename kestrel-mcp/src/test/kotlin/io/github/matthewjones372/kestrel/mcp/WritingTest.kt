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

/** A benchmark somebody can commit, argue with, and run again. */
class WritingTest {

    private lateinit var server: HttpServer

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/products") { exchange ->
            // Slow enough that a few milliseconds of scheduling jitter is not a
            // material share of the tail. A sub-millisecond target makes every
            // run "behind", correctly and uselessly.
            Thread.sleep(SERVED)
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
          over: 1s
    """.trimIndent()

    private fun finished(): Registry {
        val registry = Registry()
        registry.start(readPlan(plan()), Allowance.none)
        while (registry.ran("r-1") == null) Thread.sleep(POLL)
        return registry
    }

    @Test
    fun `it writes a file, and says where`(@TempDir dir: Path) {
        val into = dir.resolve("benchmarks/checkout.md")

        val answered = writeSpec(finished(), "r-1", into.toString(), why = null)

        withClue(answered) { answered shouldContain "checkout.md" }
        Files.exists(into) shouldBe true
    }

    @Test
    fun `it records what was measured, not what was hoped for`(@TempDir dir: Path) {
        val into = dir.resolve("b.md")
        writeSpec(finished(), "r-1", into.toString(), why = null)

        val written = Files.readString(into)
        withClue(written) {
            written shouldContain "| step | p50 | p99 |"
            written shouldContain "browse"
            withClue("either it kept its schedule or it says to read the numbers with care") {
                (written.contains("kept its own schedule") || written.contains("Read these with care")) shouldBe true
            }
        }
    }

    @Test
    fun `the plan it embeds is one the reader accepts`(@TempDir dir: Path) {
        val into = dir.resolve("b.md")
        writeSpec(finished(), "r-1", into.toString(), why = null)

        val embedded = Files.readString(into).substringAfter("```yaml").substringBefore("```")

        withClue("a benchmark nobody can run again is documentation") {
            readPlan(embedded).scenario shouldBe "checkout"
        }
    }

    @Test
    fun `with nobody's answers it says the questions are open`(@TempDir dir: Path) {
        val into = dir.resolve("b.md")
        writeSpec(finished(), "r-1", into.toString(), why = null)

        withClue("inventing a reason nobody gave is worse than saying nobody has") {
            Files.readString(into) shouldContain "Nobody has answered this yet"
        }
    }

    @Test
    fun `with answers it uses them`(@TempDir dir: Path) {
        val into = dir.resolve("b.md")
        writeSpec(finished(), "r-1", into.toString(), why = "Search is the only endpoint with a distribution.")

        val written = Files.readString(into)
        withClue(written) {
            written shouldContain "Search is the only endpoint with a distribution."
            written shouldContain "## What this does not cover"
        }
    }

    @Test
    fun `a run nobody has cannot be written up`(@TempDir dir: Path) {
        writeSpec(Registry(), "r-9", dir.resolve("b.md").toString(), null) shouldContain """"isError":true"""
    }

    private companion object {
        const val POLL = 50L
        const val SERVED = 40L
    }
}
