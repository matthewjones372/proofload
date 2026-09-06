package io.github.matthewjones372.kestrel.java

import io.github.matthewjones372.kestrel.SessionKey
import io.github.matthewjones372.kestrel.sessionKey

/**
 * A typed session key. Kotlin rather than Java, unlike the factories beside it:
 * `SessionKey` is an ordinary class, so nothing here is mangled, and the
 * `Class` a Java caller holds becomes the `KClass` core takes here.
 */
object SessionKeys {

    @JvmStatic
    fun <T : Any> of(type: Class<T>, name: String): SessionKey<T> = sessionKey(name, type.kotlin)
}
