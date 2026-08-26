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
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Test
@ExtendWith(KestrelExtension::class)
@Timeout(value = 1, unit = TimeUnit.HOURS)
annotation class LoadTest
