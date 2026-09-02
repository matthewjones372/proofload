package io.github.matthewjones372.kestrel.examples

import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.baseline.writeInto
import io.github.matthewjones372.kestrel.engine.Kestrel
import io.github.matthewjones372.kestrel.http.http
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.sharded
import io.github.matthewjones372.kestrel.step
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

private val fetch = step("fetch")

/**
 * One injector's share of one run, written where its neighbours will not
 * overwrite it.
 *
 * This and a shell loop over four hosts are the whole of the launcher. Every
 * injector is given the same jar and three scalars — which one it is, how many
 * there are, and the instant they all start on — and sends the users whose
 * number is its own modulo N, so the four of them offer exactly the departures
 * one JVM would.
 *
 * Takes the directory to write into, the target, how long to send for, and
 * those three. See `docs/more-than-one-injector.md`.
 *
 * An object rather than a top-level `main` beside [OneRun]'s, for the reason
 * [AgainstTheBaseline] is one: two of those in a package resolve to whichever
 * the compiler saw first, and a test calling `main` then runs the wrong
 * program.
 */
object OneInjector {

    @JvmStatic
    fun main(args: Array<String>) {
        val directory = Path.of(args.getOrElse(0) { "build/kestrel" })
        val target = http.baseUrl(args.getOrElse(1) { "http://localhost:8080" })
        val over = args.getOrElse(2) { "1000" }.toLong().milliseconds
        val index = args.getOrElse(3) { "0" }.toInt()
        val of = args.getOrElse(4) { "1" }.toInt()
        val startingAt = Instant.ofEpochMilli(args.getOrElse(5) { "0" }.toLong())

        val fetching = scenario("fetching") { exec(fetch, target.get("/thing")) }
        val written = Kestrel()
            .run(fetching.at(200.perSecond, over = over).sharded(index, of, startingAt))
            .writeInto(directory)

        println("kestrel: wrote $written")
    }
}
