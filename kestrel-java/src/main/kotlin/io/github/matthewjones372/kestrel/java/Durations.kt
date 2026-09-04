package io.github.matthewjones372.kestrel.java

import kotlin.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/**
 * Internal rather than published: `kotlin.time.Duration` is a value class, so a
 * public function taking or returning one is a name with a hash in it — the
 * thing this module exists to keep out of Java source.
 */
internal fun java.time.Duration.asKestrel(): Duration = toKotlinDuration()

internal fun Duration.asJava(): java.time.Duration = toJavaDuration()
