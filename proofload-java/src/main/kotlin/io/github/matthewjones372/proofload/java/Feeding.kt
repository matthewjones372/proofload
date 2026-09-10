package io.github.matthewjones372.proofload.java

import io.github.matthewjones372.proofload.Feeder
import io.github.matthewjones372.proofload.SessionKey
import io.github.matthewjones372.proofload.feed
import java.util.function.LongFunction

/** A feeder whose values Kotlin states with a lambda, which is the only part of `feed` Java cannot write. */
internal object Feeds {

    @JvmStatic
    @JvmName("of")
    fun <T : Any> of(key: SessionKey<T>, value: LongFunction<T>): Feeder = feed(key) { user -> value.apply(user) }
}
