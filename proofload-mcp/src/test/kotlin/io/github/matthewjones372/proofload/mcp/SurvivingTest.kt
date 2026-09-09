package io.github.matthewjones372.proofload.mcp

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.proofload.Allowance
import io.github.matthewjones372.proofload.plan.readPlan
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * A server written for one person at a terminal hands out `r-1` from a
 * counter and keeps its runs in a map. Deployed as one of several, two
 * replicas answer `r-1` to two different callers; restarted, it tells a
 * caller polling its own run that the run never existed.
 */
class SurvivingTest {

    private lateinit var server: HttpServer

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/thing") { it.sendResponseHeaders(200, -1); it.close() }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun plan() = readPlan(
        """
        proofload:  plan/1
        baseUrl:  http://localhost:${server.address.port}
        scenario: probe
        steps:
          - name: fetch
            get: /thing
        load:
          rate: 20/s
          over: 300ms
        """.trimIndent(),
    )

    private fun finishedRun(keptIn: Path): Pair<Registry, String> {
        val registry = Registry(runs = keptIn)
        // The envelope escapes the document inside it, so the id is read by
        // shape rather than by slicing on a quote that is a backslash away —
        // and only from a start that succeeded. A refusal names the *other*
        // run's id, so reading one out of it waits forever for a run this
        // registry never began.
        val started = registry.start(plan(), Allowance.none)
        withClue(started) { started shouldContain "runId" }
        val id = requireNotNull(Regex("""r-[0-9a-f]{8}""").find(started)) { started }.value

        val until = System.nanoTime() + WAIT.inWholeNanoseconds
        while (registry.ran(id) == null) {
            withClue("the run never finished, and a test that waits forever reports nothing") {
                (System.nanoTime() < until) shouldBe true
            }
            Thread.sleep(POLL)
        }
        return registry to id
    }

    @Test
    fun `two servers started together hand out different ids`(@TempDir dir: Path) {
        val (_, first) = finishedRun(dir.resolve("one"))
        val (_, second) = finishedRun(dir.resolve("two"))

        withClue("two replicas both answering r-1 is two callers given one another's run") {
            (first == second) shouldBe false
        }
    }

    @Test
    fun `an id says nothing about how many runs came before it`(@TempDir dir: Path) {
        val (_, id) = finishedRun(dir)

        withClue("a counter tells every caller how busy the server is, which is nobody's business") {
            id shouldNotContain "-1"
        }
    }

    @Test
    fun `a run that finished before a restart is still readable after one`(@TempDir dir: Path) {
        val (before, id) = finishedRun(dir)
        val registryCount = before.finished(id)?.count

        // A second registry over the same directory is what a restarted
        // process is: nothing of the first is in memory here.
        val restarted = Registry(runs = dir)

        withClue("a caller polling its own run must not be told the run never existed") {
            restarted.status(id) shouldContain "proofload/run/1"
            restarted.finished(id)?.count shouldBe registryCount
            restarted.ran(id)?.plan?.scenario shouldBe "probe"
        }
    }

    @Test
    fun `a restarted server lists what it kept`(@TempDir dir: Path) {
        finishedRun(dir)
        finishedRun(dir)

        Registry(runs = dir).listed() shouldHaveSize 2
    }

    @Test
    fun `an id nobody started is still unknown after a restart`(@TempDir dir: Path) {
        finishedRun(dir)

        withClue("reading a directory must not turn every id into one that exists") {
            Registry(runs = dir).status("r-nothing") shouldContain "no run"
        }
    }

    private companion object {
        const val POLL = 50L
        val WAIT = 30.seconds
    }
}
