package io.github.matthewjones372.kestrel.junit5

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.ExtendWith
import java.util.concurrent.TimeUnit

/**
 * A `@Test` that is handed a [Kestrel].
 *
 * The timeout is here because a build's default one is written for unit tests:
 * a load test that runs for two minutes is not hung, and inheriting a
 * sixty-second default would kill it. The rate and the window stay in Kotlin
 * rather than becoming attributes here — a constant can be shared between two
 * tests, an annotation value cannot.
 *
 * Which engine sends the run is the test class's business rather than this
 * annotation's: a class that implements [RunsOn] names one, and one that does
 * not runs on virtual threads.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Test
@ExtendWith(KestrelExtension::class)
@Timeout(value = 1, unit = TimeUnit.HOURS)
annotation class LoadTest
