package io.github.matthewjones372.kestrel.cli

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.kestrel.Allowance
import io.github.matthewjones372.kestrel.perSecond
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

/**
 * Every command is a value in and a value out, so what a shell would see is
 * asserted without spawning one.
 */
class CliTest {

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

    @Test
    fun `the arguments name a command`() {
        parse(listOf("run", "plan.yaml", "--json")) shouldBe Command.Run(Path.of("plan.yaml"), json = true)
        parse(listOf("validate", "plan.yaml")) shouldBe Command.Validate(Path.of("plan.yaml"))
        parse(listOf("dance", "plan.yaml")) shouldBe null
        parse(emptyList()) shouldBe null
    }

    @Test
    fun `validate reads the plan and sends nothing`(@TempDir dir: Path) {
        val finished = obey(Command.Validate(planFile(dir)), Allowance.none)

        finished.code shouldBe Code.Met
    }

    @Test
    fun `a plan that does not read exits unusable, with the line rather than a stack trace`(@TempDir dir: Path) {
        val broken = dir.resolve("broken.yaml")
        Files.writeString(broken, plan().replace("scenario:", "scenarios:"))

        val finished = obey(Command.Validate(broken), Allowance.none)

        withClue(finished.error) {
            finished.code shouldBe Code.Unusable
            finished.error shouldContain "line"
            finished.error shouldContain "scenarios"
        }
    }

    @Test
    fun `preview says what it would send and sends none of it`(@TempDir dir: Path) {
        val finished = obey(Command.Preview(planFile(dir)), Allowance.none)

        withClue(finished.out) {
            finished.code shouldBe Code.Met
            finished.out shouldContain "users"
            finished.out shouldContain "requests"
        }
    }

    @Test
    fun `a plan over the allowance is refused rather than run`(@TempDir dir: Path) {
        val finished = obey(Command.Run(planFile(dir), json = false), Allowance(maxRate = 1.perSecond))

        finished.code shouldBe Code.Refused
        finished.error shouldContain "over the"
    }

    @Test
    fun `run prints the run document when asked for json`(@TempDir dir: Path) {
        val finished = obey(Command.Run(planFile(dir), json = true), Allowance.none)

        withClue(finished.out) {
            finished.out shouldContain """"schema": "kestrel/run/1""""
            finished.out shouldContain """"verdict""""
        }
    }

    /**
     * Found by piping it. `--json` exists for a program, and the engine's
     * progress lines were going to the same stdout as the document, so
     * `kestrel run plan.yaml --json | jq` was parsing a run's commentary.
     * `Progress.silent` already names this caller in its own KDoc.
     */
    @Test
    fun `asked for json, nothing is written beside the document`(@TempDir dir: Path) {
        var finished: Finished? = null
        val alsoPrinted = capturingStdout {
            obey(Command.Run(planFile(dir), json = true), Allowance.none).also { finished = it }
        }

        withClue("`main` prints the document, so anything else on stdout is in the same stream as it") {
            alsoPrinted shouldBe ""
        }
        finished!!.out.trim().startsWith("{") shouldBe true
    }

    @Test
    fun `without json the run still says what it is doing`(@TempDir dir: Path) {
        val printed = capturingStdout { obey(Command.Run(planFile(dir), json = false), Allowance.none) }

        withClue("silencing every run would take the countdown from the person watching one") {
            printed shouldContain "kestrel:"
        }
    }

    /** What the engine wrote to stdout while [running], which is where progress goes. */
    private fun capturingStdout(running: () -> Finished): String {
        val was = System.out
        val caught = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(caught, true, Charsets.UTF_8))
        try {
            running()
        } finally {
            System.setOut(was)
        }
        return caught.toString(Charsets.UTF_8)
    }

    @Test
    fun `run without json prints the same verdict in words`(@TempDir dir: Path) {
        val finished = obey(Command.Run(planFile(dir), json = false), Allowance.none)

        withClue(finished.out) {
            finished.out shouldContain "requests"
            finished.out shouldContain "met"
        }
    }

    @Test
    fun `emit prints the plan as source, and sends nothing`(@TempDir dir: Path) {
        val finished = obey(Command.Emit(planFile(dir), packageName = "load"), Allowance.none)

        withClue(finished.out) {
            finished.code shouldBe Code.Met
            finished.out shouldContain "package load"
            finished.out shouldContain "val browse = step(\"browse\")"
        }
    }

    @Test
    fun `from-openapi turns a document into a plan this reads`(@TempDir dir: Path) {
        val document = dir.resolve("orders.yaml")
        Files.writeString(
            document,
            """
            openapi: 3.1.0
            servers: [{url: "https://orders.internal"}]
            paths:
              /orders/{id}:
                get:
                  operationId: getOrder
                  parameters:
                    - {name: id, in: path, required: true, schema: {type: integer, minimum: 1, maximum: 9}}
                  responses:
                    "200": {description: one order}
                    "404": {description: no such order}
            """.trimIndent(),
        )

        val finished = obey(Command.FromOpenApi(document, baseUrl = null), Allowance.none)

        withClue(finished.out) {
            finished.code shouldBe Code.Met
            finished.out shouldContain "getOrder"
            finished.out shouldContain "declared: [404]"
        }

        val written = dir.resolve("plan.yaml")
        Files.writeString(written, finished.out)
        withClue("a generated plan nobody can validate is a generated plan nobody can run") {
            obey(Command.Validate(written), Allowance.none).code shouldBe Code.Met
        }
    }

    private fun planFile(dir: Path): Path =
        dir.resolve("plan.yaml").also { Files.writeString(it, plan()) }

    private fun plan(): String = """
        kestrel:  plan/1
        baseUrl:  http://localhost:${server.address.port}
        scenario: checkout
        steps:
          - name: browse
            get:  /products
        load:
          rate: 20/s
          over: 300ms
        goals:
          - step: browse
            p99:  2s
    """.trimIndent()
}
