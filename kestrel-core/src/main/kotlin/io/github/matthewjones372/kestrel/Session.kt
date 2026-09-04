package io.github.matthewjones372.kestrel

import kotlin.reflect.KClass

/**
 * A name and the type stored under it, declared once and shared by every file
 * that touches the value. Gatling hands back `Any` and asks for a cast at each
 * use; a key carries the type instead, so the cast happens nowhere.
 */
class SessionKey<T : Any> @PublishedApi internal constructor(
    val name: String,
    @PublishedApi internal val type: KClass<T>,
) {
    override fun equals(other: Any?): Boolean =
        other is SessionKey<*> && other.name == name && other.type == type

    override fun hashCode(): Int = 31 * name.hashCode() + type.hashCode()

    override fun toString(): String = "SessionKey($name: ${type.simpleName})"
}

inline fun <reified T : Any> sessionKey(name: String): SessionKey<T> = SessionKey(name, T::class)

/**
 * The same key where the type is only known at runtime. A `reified inline`
 * function compiles to no method at all, so this is the only form a caller
 * outside Kotlin — or one holding a `Class` rather than writing a literal — can
 * reach.
 */
fun <T : Any> sessionKey(name: String, type: KClass<T>): SessionKey<T> = SessionKey(name, type)

/**
 * The state one virtual user carries between steps. Immutable, so a step can
 * be handed a session without the engine copying defensively before a run and
 * without two users sharing a map.
 */
class Session private constructor(private val values: Map<String, Any>) {

    operator fun <T : Any> get(key: SessionKey<T>): T? {
        val value = values[key.name] ?: return null
        // Two keys of one name and different types is a mistake nobody
        // declared, so it is a throw rather than a null the caller silently
        // treats as absent.
        check(key.type.isInstance(value)) {
            "session key '${key.name}' holds a ${value::class.simpleName}, not a ${key.type.simpleName}"
        }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    fun <T : Any> set(key: SessionKey<T>, value: T): Session = Session(values + (key.name to value))

    /** This session, with [other]'s values over the top. */
    internal fun and(other: Session): Session = Session(values + other.values)

    override fun equals(other: Any?): Boolean = other is Session && other.values == values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = "Session($values)"

    companion object {
        val empty: Session = Session(emptyMap())
    }
}
