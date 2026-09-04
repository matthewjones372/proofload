package io.github.matthewjones372.kestrel.java

import io.github.matthewjones372.kestrel.StepName
import io.github.matthewjones372.kestrel.perMinute
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.percent

/**
 * Core's `@JvmInline` values, boxed.
 *
 * A Kotlin function returning one compiles to a mangled name returning the
 * underlying `String` or `double`, so no Kotlin signature can hand a `Rate` to
 * Java as a `Rate`. `Any` is the one return type the box survives in, which is
 * why the factories a Java caller sees are Java sources over this. `@JvmName`
 * keeps the module suffix an `internal` member would otherwise carry off the
 * name those sources compile against.
 */
internal object Boxes {

    @JvmStatic
    @JvmName("perSecond")
    fun perSecond(rate: Double): Any = rate.perSecond

    @JvmStatic
    @JvmName("perMinute")
    fun perMinute(rate: Double): Any = rate.perMinute

    @JvmStatic
    @JvmName("step")
    fun step(name: String): Any = StepName(name)

    @JvmStatic
    @JvmName("percent")
    fun percent(share: Double): Any = share.percent
}
