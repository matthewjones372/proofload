@file:JvmName("OneRun")

package io.github.matthewjones372.proofload.examples

import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.baseline.writeInto
import io.github.matthewjones372.proofload.engine.Proofload
import io.github.matthewjones372.proofload.http.http
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.step
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

private val pay = step("pay")

/**
 * One run of one simulation, written where the next invocation will not
 * overwrite it.
 *
 * This and a shell loop are the whole of the forking launcher: ten JVMs leave
 * ten files, and `Runs.readAll` reads them back as one population. Takes the
 * directory to write into, the target to send at, and how long to send for.
 */
fun main(args: Array<String>) {
    val directory = Path.of(args.getOrElse(0) { "build/proofload" })
    val target = http.baseUrl(args.getOrElse(1) { "http://localhost:8080" })
    val over = args.getOrElse(2) { "1000" }.toLong().milliseconds

    val paying = scenario("paying") { exec(pay, target.get("/pay")) }
    val written = Proofload().run(paying.at(50.perSecond, over = over)).writeInto(directory)

    println("proofload: wrote $written")
}
