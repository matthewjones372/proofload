package io.github.matthewjones372.proofload.scala

import java.time.Duration as JavaDuration
import _root_.scala.concurrent.duration.FiniteDuration
import _root_.scala.concurrent.duration.NANOSECONDS

/**
 * A Scala codebase writes `1.minute`; every Proofload signature it reaches takes
 * a `java.time.Duration`, and `kotlin.time.Duration` arrives as a bare `Long`
 * with no unit attached.
 *
 * `_root_.scala` throughout this module: the package is itself named `scala`,
 * so an unqualified `scala.concurrent` resolves to it instead.
 */
private[scala] def asJava(duration: FiniteDuration): JavaDuration = JavaDuration.ofNanos(duration.toNanos)

private[scala] def asScala(duration: JavaDuration): FiniteDuration = FiniteDuration(duration.toNanos, NANOSECONDS)

/**
 * Given rather than only a method, so a Java facade signature — a goal's limit,
 * a pause — takes what Scala wrote without the caller converting at each call.
 * Applying it needs `import scala.language.implicitConversions` at the use
 * site, which is Scala's rule and not this module's.
 */
given Conversion[FiniteDuration, JavaDuration] = asJava(_)

given Conversion[JavaDuration, FiniteDuration] = asScala(_)
