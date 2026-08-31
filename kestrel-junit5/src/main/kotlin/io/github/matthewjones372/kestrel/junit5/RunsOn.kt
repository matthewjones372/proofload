package io.github.matthewjones372.kestrel.junit5

import io.github.matthewjones372.kestrel.Engine

/**
 * A test class whose load tests are sent by [engine] rather than by the
 * default one. A class that does not implement this runs on virtual threads,
 * so `@LoadTest` costs an implementer nothing.
 *
 * An implemented interface rather than the two other seams JUnit offers. An
 * attribute on [LoadTest] could only name a class for JUnit to instantiate
 * reflectively, so an engine that takes a setting could not be handed over at
 * all.
 *
 * A `@RegisterExtension` field of [KestrelExtension] is the shape a JUnit
 * reader expects, and it does not work here: [LoadTest] registers that
 * extension itself, a field-registered instance is not deduplicated against a
 * declared one, and the test then fails with "Discovered multiple competing
 * ParameterResolvers". Keeping the field would mean a second extension type
 * whose only job is to carry a value, plus the `@JvmField` a Kotlin `val`
 * needs before JUnit can see it at all — ceremony, for what one overridden
 * property says.
 */
interface RunsOn {

    val engine: Engine
}
