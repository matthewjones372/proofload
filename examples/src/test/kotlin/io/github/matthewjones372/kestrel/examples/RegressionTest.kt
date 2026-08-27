package io.github.matthewjones372.kestrel.examples

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.kestrel.Change
import io.github.matthewjones372.kestrel.Comparison
import io.github.matthewjones372.kestrel.Floor
import io.github.matthewjones372.kestrel.against
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.baseline.readBaseline
import io.github.matthewjones372.kestrel.baseline.writeBaseline
import io.github.matthewjones372.kestrel.engine.Kestrel
import io.github.matthewjones372.kestrel.http.exec
import io.github.matthewjones372.kestrel.http.http
import io.github.matthewjones372.kestrel.junit5.LoadTest
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
 * The target here gets slower on purpose between the two runs, so the answer is
 * known before the tool is asked.
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

    private fun measure(kestrel: Kestrel) = kestrel.run(
        scenario("paying") { exec(http.baseUrl("http://localhost:${server.address.port}").get("/pay")) }
            .at(100.perSecond, over = 2.seconds),
    )

    @LoadTest
    fun `a service that got slower is reported as worse, and one that did not is not`(
        kestrel: Kestrel,
        @TempDir dir: Path,
    ) {
        // Measured once and kept, per JVM: a floor measured between the two
        // runs would be measuring the same drift it is here to bound.
        val floor = kestrel.calibrate()
        assumeTrue(
            floor.separates(fast, slow),
            "this machine ${floor.asAClue}, which the $fast to $slow slowdown injected here does not clear, " +
                "so the machine cannot answer the question and is not being asked",
        )

        val baseline = dir.resolve("baseline.kestrel")

        // Thrown away. The first run of a JVM pays for class loading, JIT and
        // opening connections, and it is measurably slower than every run
        // after it — so a baseline taken from a cold process would report the
        // next release as an improvement.
        measure(kestrel)

        measure(kestrel).writeBaseline(baseline)

        // Judged against what the machine can see rather than against
        // `Indistinguishable`: two absolute measurements minutes apart differ
        // by the afternoon as well as by the code, and only a difference the
        // floor cannot explain is a difference in the target.
        val unchanged = measure(kestrel).against(readBaseline(baseline))
            .shouldBeInstanceOf<Comparison.Compared>()
            .changes
            .single()
        withClue("same target twice, on a machine that ${floor.asAClue}: $unchanged") {
            unchanged.beyond(floor) shouldBe false
        }

        // The deploy that made it worse.
        latency.set(slow.inWholeMilliseconds)

        val slower = measure(kestrel).against(readBaseline(baseline))
        withClue("target ten times slower, on a machine that ${floor.asAClue}: $slower") {
            val worse = slower.shouldBeInstanceOf<Comparison.Compared>()
                .changes
                .single()
                .shouldBeInstanceOf<Change.Worse>()
            (worse.now > worse.before) shouldBe true
        }
    }
}

/**
 * Whether this change is larger than what the machine moves by on its own
 * between identical runs, which is the only kind that is a property of the
 * target rather than of the afternoon it was measured in.
 *
 * A step that appeared or vanished is not a difference between two numbers, so
 * there is no size there for a floor to explain away.
 */
private fun Change.beyond(floor: Floor): Boolean = when (this) {
    is Change.Worse -> floor.separates(before, now)
    is Change.Better -> floor.separates(before, now)
    is Change.Indistinguishable -> false
    is Change.Added, is Change.Gone -> true
}

/** The floor as a failure has to name it: both gates, in the units each is measured in. */
private val Floor.asAClue: String
    get() = "moves by $absolute between repeats and stalls ${hiccups.p99} at p99"
