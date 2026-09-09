package io.github.matthewjones372.proofload.examples

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.proofload.Runs
import io.github.matthewjones372.proofload.StepName
import io.github.matthewjones372.proofload.Tell
import io.github.matthewjones372.proofload.against
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.baseline.readAll
import io.github.matthewjones372.proofload.baseline.writeInto
import io.github.matthewjones372.proofload.engine.Proofload
import io.github.matthewjones372.proofload.explained
import io.github.matthewjones372.proofload.http.exec
import io.github.matthewjones372.proofload.http.http
import io.github.matthewjones372.proofload.junit5.LoadTest
import io.github.matthewjones372.proofload.p99
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.percent
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.warmingUp
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The loop a team actually runs: measure, keep the numbers, change the service,
 * measure again, and ask whether anything got worse.
 *
 * The target here gets slower on purpose between the two sets of runs, so the
 * answer is known before the tool is asked.
 *
 * Sets of runs rather than single ones, because a single run cannot bound its
 * own noise. One measurement of a target says nothing about how far a second
 * would land from it, so the size of a change it reports has to be judged
 * against a spread borrowed from somewhere else — a null step, the injector's
 * own stalls — which is a guess wearing a measurement's clothes. `against` over
 * two populations takes its interval from resampling the runs that made them,
 * so the spread comes from the thing being compared.
 */
@Tag("timing")
class RegressionTest {

    private lateinit var server: HttpServer

    private val fast = 20.milliseconds

    private val slow = 200.milliseconds

    private val latency = AtomicLong(fast.inWholeMilliseconds)

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

    private fun measure(proofload: Proofload) = proofload.run(
        scenario("paying") { exec(http.baseUrl("http://localhost:${server.address.port}").get("/pay")) }
            .at(100.perSecond, over = 2.seconds)
            // Declared rather than thrown away by hand: every run of both
            // populations pays for the same warm-up, which is what makes them
            // one population rather than a first run and the rest.
            .warmingUp(2.seconds),
    )

    private fun population(proofload: Proofload) = Runs(List(REPEATS) { measure(proofload) })

    @LoadTest
    fun `a service that got slower is reported as worse, and one that did not is not`(
        proofload: Proofload,
        @TempDir dir: Path,
    ) {
        // The floor's remaining job, and its only honest one: whether this
        // machine can support a claim at the magnitude this test makes one at.
        // It is not asked whether a difference is real — it never watched the
        // target, and the interval below did.
        val floor = proofload.calibrate()
        assumeTrue(
            floor.supports(fast),
            "this machine moves by ${floor.movementAt(fast)} between identical runs, which is too much of the " +
                "$fast this test makes its claims about, so the machine cannot answer the question and is not " +
                "being asked",
        )

        // A directory rather than a file: a population is what a comparison
        // needs, and one file per run is what a team's loop leaves behind.
        val kept = dir.resolve("baseline")
        repeat(REPEATS) { measure(proofload).writeInto(kept) }
        val before = Runs.readAll(kept)

        val unchanged = population(proofload).against(before, p99(PAY), acceptable = ACCEPTABLE)
        withClue("same target twice: ${unchanged.explained(ACCEPTABLE)}") {
            // Not worse, rather than better: two sets of runs of one unchanging
            // target on a busy machine can honestly fail to tell, and a test
            // that failed on that would be asserting the machine was quiet.
            unchanged.verdict shouldNotBe Tell.Worse
        }

        // The deploy that made it worse.
        latency.set(slow.inWholeMilliseconds)

        val slower = population(proofload).against(before, p99(PAY), acceptable = ACCEPTABLE)
        withClue("target ten times slower: ${slower.explained(ACCEPTABLE)}") {
            slower.verdict shouldBe Tell.Worse
        }
    }
}

private val PAY = StepName("/pay")

/**
 * Five runs a side, which is the fewest `against` will make an interval out of
 * and twenty seconds of wall clock in a task nobody puts in `build`.
 */
private const val REPEATS = 5

/**
 * Ten times slower clears this by a distance, and an unchanged target has to
 * move by more than a fifth before this test calls it a regression — which is
 * the point: the threshold is declared here, beside the assertion, rather than
 * inferred from what the machine happened to be doing.
 */
private val ACCEPTABLE = 20.percent
