package io.github.matthewjones372.proofload.benchmarks

import io.github.matthewjones372.proofload.Progress
import io.github.matthewjones372.proofload.Scenario
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.run
import io.github.matthewjones372.proofload.http.Exchange
import io.github.matthewjones372.proofload.http.JdkHttpClient
import io.github.matthewjones372.proofload.http.Request
import io.github.matthewjones372.proofload.http.Transport
import io.github.matthewjones372.proofload.http.exec
import io.github.matthewjones372.proofload.http.http
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Is one `HttpClient` the bound?
 *
 * The JDK's client carries its own selector thread, so every request a run
 * makes passes through one thread's readiness loop however many cores the
 * machine has. That is the specific thing people mean when they doubt this
 * design scales next to a generator built on several event loops.
 *
 * 0120's argument is that the cheapest test of it is not a faster client but
 * *more of the same one*: striping changes how many selectors the run has and
 * nothing else, where swapping in another library changes the client, the
 * threading model, the pool and the allocation behaviour at once and answers
 * none of them separately.
 *
 * Runs the arms forwards and then backwards. Ports linger in `TIME_WAIT` and
 * the reading is machine-wide, so a later arm inherits what the earlier ones
 * spent: an effect that is real survives the reversal, and one that is
 * accumulation flips with it.
 */
fun main() {
    val target = System.getProperty("proofload.axisRate")?.toIntOrNull() ?: AXIS_RATE

    // Alternating and repeated rather than one pass per count. The first run of
    // this at 10,000 a second swept 1, 2, 4, 8 and then 8, 4, 2, 1, and the
    // numbers improved monotonically with *time* rather than with clients: one
    // client was the worst arm going up and the best coming down. Two reasons,
    // both fixed here. Every arm gets its own warm-up, as the rate sweep
    // already gives each rate. And each arm settles first, because a run at
    // this rate spends the machine's whole ephemeral range and the next one
    // inherits it in TIME_WAIT.
    val arms = (1..REPEATS).flatMap { round ->
        CLIENT_COUNTS.map { clients ->
            settle()
            apart { warming ->
                striping(warming.baseUrl, clients).at(target.perSecond, over = SWEEP_WARMUP).run(Progress.silent)
            }
            Arm(clients, "round $round", measure(target, clients))
        }
    }

    val page = axisReport(target, arms)
    println(page)

    val into = Path.of("build/reports/proofload/clients-axis.md")
    Files.createDirectories(into.parent)
    Files.writeString(into, page)
    println("written to ${into.toAbsolutePath()}")
}

/**
 * Long enough for the ports the last arm spent to leave TIME_WAIT.
 *
 * A sleep, in a benchmark harness rather than in library code: what is being
 * waited for is the operating system's own timer, and there is nothing to
 * subscribe to. Without it every arm after the first measures a machine with
 * no ports left, which is what the first run of this actually measured.
 */
@Suppress("ForbiddenMethodCall")
private fun settle() = Thread.sleep(SETTLE.inWholeMilliseconds)

private fun measure(rate: Int, clients: Int): Measured {
    val run = apart { target ->
        striping(target.baseUrl, clients).at(rate.perSecond, over = SWEEP_WINDOW).run(Progress.silent)
    }
    return Measured(rate, run.answered, loadAverage(), served = run.served, connections = run.connections)
}

internal class Arm(val clients: Int, val order: String, val measured: Measured)

/** The same step as the rest of the sweep, over [clients] clients instead of the shipped one. */
internal fun striping(baseUrl: String, clients: Int): Scenario =
    scenario("over a socket") { exec(http.baseUrl(baseUrl).over(Striped(clients)).get("/")) }

/**
 * [clients] JDK clients, a user pinned to one for its whole journey.
 *
 * By thread id rather than a counter: a shared counter is an atomic increment
 * on the departure path, which is what the measurement rules keep off it, and
 * pinning means what changes between arms is how many selectors there are
 * rather than whether a connection is reused.
 */
internal class Striped(clients: Int) : Transport {

    private val over: List<Transport> = List(clients) {
        // Built the same way `proofload-http`'s own client is, so the count is
        // the only thing that differs between an arm and the shipped path.
        JdkHttpClient(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build())
    }

    override fun exchange(request: Request): Exchange =
        over[(Thread.currentThread().threadId() % over.size).toInt()].exchange(request)
}

internal fun axisReport(rate: Int, arms: List<Arm>): String {
    val rows = arms.map { arm ->
        "| ${arm.clients} | ${arm.order} | ${arm.measured.result.count.grouped()} | " +
            "${arm.measured.result.failed.grouped()} | ${arm.measured.result.behind.p50.readable()} | " +
            "${arm.measured.result.behind.p99.readable()} | ${arm.measured.served?.p50.readable()} | " +
            "${arm.measured.perConnection} | ${arm.measured.room} | " +
            "${if (arm.measured.keptSchedule) "yes" else "no"} |"
    }
    return (
        listOf(
            "# Does more than one client move the ceiling?",
            "",
            "One rate, ${rate.grouped()} a second, against a target in a JVM of its own. What changes",
            "between rows is how many `HttpClient`s the run has and nothing else: a user is pinned",
            "to one for its whole journey, and every client is built the way `proofload-http`",
            "builds its own.",
            "",
            "Each count is measured $REPEATS times, alternating, with a $SETTLE settle before each so the",
            "ports the last arm spent have left TIME_WAIT. Drift then shows up as spread within a",
            "count rather than as a trend across the table, which is how the first run of this went",
            "wrong: swept once up and once down, the numbers tracked time and not clients.",
            "",
            "**What this cannot say.** Requests per connection is `spec-0118-reuse` and is not",
            "built, so a row that improved might have improved by opening more connections rather",
            "than by having more selectors. Files and ports are machine-wide and cumulative.",
            "",
            "| Clients | Order | Requests | Failed | Behind p50 | Behind p99 | Served p50 | " +
                "Per conn | Files / Ports | p50 within $SWEEP_BUDGET |",
            "|---:|:---|---:|---:|---:|---:|---:|---:|---:|:---:|",
        ) + rows + footer(arms.map { it.measured })
        ).joinToString(separator = "\n")
}

private fun footer(measured: List<Measured>): List<String> {
    val loads = measured.map { it.load }.filter { it >= 0.0 }
    return listOf(
        "",
        "Measured on ${machine()}, under a one-minute load average of " +
            "${loads.min().rounded()} to ${loads.max().rounded()} across the sweep.",
    )
}

/**
 * The rate one client carried cleanly in the apart sweep.
 *
 * 10,000 was the first choice, being where one client failed — but at that rate
 * the failure is the machine's ephemeral range and its descriptors, which no
 * number of clients changes. A rate the machine can actually sustain is the one
 * where a selector could be the thing in the way.
 */
internal const val AXIS_RATE = 5_000

internal val CLIENT_COUNTS = listOf(1, 4)

/** Each count measured this many times, alternating, so drift shows as spread rather than as a trend. */
internal const val REPEATS = 5

internal val SETTLE: Duration = 15.seconds
