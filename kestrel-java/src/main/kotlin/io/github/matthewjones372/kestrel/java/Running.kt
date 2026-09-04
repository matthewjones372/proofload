package io.github.matthewjones372.kestrel.java

import io.github.matthewjones372.kestrel.Clock
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.Scenario
import io.github.matthewjones372.kestrel.SessionKey
import io.github.matthewjones372.kestrel.Simulation
import io.github.matthewjones372.kestrel.StepStats
import io.github.matthewjones372.kestrel.Timing
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.http.Http
import io.github.matthewjones372.kestrel.http.HttpAction
import io.github.matthewjones372.kestrel.http.Response
import io.github.matthewjones372.kestrel.http.http
import io.github.matthewjones372.kestrel.perSecond
import java.util.function.Function
import io.github.matthewjones372.kestrel.engine.Kestrel as Engine

/** What sends a simulation. `Kestrel.create().run(simulation)` is the whole of it. */
object Kestrel {

    @JvmStatic
    fun create(): Engine = Engine()
}

/** HTTP steps. Only the calls Kotlin states with a lambda need naming here; the rest of `Http` is already plain. */
object Https {

    @JvmStatic
    fun baseUrl(url: String): Http = http.baseUrl(url)

    /** Takes a value out of the response and puts it in the session under [key]. */
    @JvmStatic
    fun <T : Any> capturing(action: HttpAction, key: SessionKey<T>, extract: Function<Response, T?>): HttpAction =
        action.capture(key) { extract.apply(it) }

    /** [holds] of the response, failed under [name] where it does not. */
    @JvmStatic
    fun checking(action: HttpAction, name: String, holds: Function<Response, Boolean>): HttpAction =
        action.checking(name) { holds.apply(it) }
}

/**
 * The rate a scenario is sent at and the window it is sent over: a run, as one
 * value. Java-side in [Simulations], because `Rate` cannot cross a Kotlin
 * signature.
 */
internal object Runs {

    @JvmStatic
    @JvmName("at")
    fun at(scenario: Scenario, perSecond: Double, over: java.time.Duration): Simulation =
        scenario.at(perSecond.perSecond, over.asKestrel())
}

/** What a run measured, read off core's own value under names with no hash in them. */
internal object Reads {

    @JvmStatic
    @JvmName("stats")
    fun stats(result: RunResult, step: String): StepStats = result[step]

    @JvmStatic
    @JvmName("percentile")
    fun percentile(result: RunResult, step: String, percentile: Double, clock: Clock): java.time.Duration =
        result[step].timing(clock).percentile(percentile).asJava()

    @JvmStatic
    @JvmName("max")
    fun max(result: RunResult, step: String, clock: Clock): java.time.Duration =
        result[step].timing(clock).max.asJava()
}

private fun StepStats.timing(clock: Clock): Timing = when (clock) {
    Clock.ServiceTime -> serviceTime
    Clock.ResponseTime -> responseTime
}
