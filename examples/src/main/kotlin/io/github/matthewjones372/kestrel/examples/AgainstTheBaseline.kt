package io.github.matthewjones372.kestrel.examples

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.kestrel.against
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.baseline.readBaseline
import io.github.matthewjones372.kestrel.baseline.writeBaseline
import io.github.matthewjones372.kestrel.calibratedBy
import io.github.matthewjones372.kestrel.engine.Kestrel
import io.github.matthewjones372.kestrel.http.http
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.report.appendToStepSummary
import io.github.matthewjones372.kestrel.report.markdown
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.step
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

private val pay = step("pay")

/**
 * The whole of a baseline in CI: run one simulation, compare it to whatever
 * baseline was restored beside it, put the comparison in the job summary, and
 * leave this run behind as the next one's baseline.
 *
 * The target is a JDK HttpServer in this same process, so the workflow needs no
 * network and no container and the only thing being exercised is the recipe.
 *
 * An object rather than a top-level `main` beside [OneRun]'s: two of those in
 * one package compile, and then a caller writing `main(…)` reaches whichever
 * the compiler picked.
 */
object AgainstTheBaseline {

    /** Takes the baseline file to read and write, and how long to send for. */
    @JvmStatic
    fun main(args: Array<String>) {
        val baseline = Path.of(args.getOrElse(0) { "build/kestrel/examples.kestrel" })
        val over = args.getOrElse(1) { "5000" }.toLong().milliseconds

        val server = served()
        try {
            val kestrel = Kestrel()
            val target = http.baseUrl("http://localhost:${server.address.port}")
            val paying = scenario("paying") { exec(pay, target.get("/pay")) }

            val floor = kestrel.calibrate()
            val result = kestrel.run(paying.at(RATE, over = over)).calibratedBy(floor)
            val previous = baseline.takeIf { Files.exists(it) }?.let(::readBaseline)
            val comparison = result.against(previous)

            result.appendToStepSummary(comparison, floor)
            println(result.markdown(comparison, floor))
            result.writeBaseline(baseline)

            // Failures fail the job and latency does not. A gate that fails a
            // third of the time because of a noisy neighbour is one somebody
            // deletes, and the half that was worth having goes with it.
            if (result.failed > 0L) exitProcess(1)
        } finally {
            server.stop(0)
        }
    }
}

private fun served(): HttpServer = HttpServer.create(InetSocketAddress(0), 0).apply {
    createContext("/pay") { exchange ->
        val body = "{}".toByteArray()
        exchange.sendResponseHeaders(HTTP_OK, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
    // A pool rather than the one thread this defaults to: a target that queues
    // is a target whose latency is this file rather than the service. Daemon
    // threads, because `HttpServer.stop` leaves an executor it was handed
    // running and a live pool holds the JVM open after the summary is written.
    executor = Executors.newFixedThreadPool(HANDLERS) { work -> Thread(work).apply { isDaemon = true } }
    start()
}

private val RATE = 200.perSecond

private const val HANDLERS = 8

private const val HTTP_OK = 200
