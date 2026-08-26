package io.github.matthewjones372.kestrel

/**
 * The state one virtual user carries between steps. Immutable, so a step can
 * be handed a session without the engine having to copy defensively before a
 * run and without two users sharing a mutable map.
 */
class Session private constructor(private val values: Map<String, Any>) {

    operator fun get(key: String): Any? = values[key]

    fun set(key: String, value: Any): Session = Session(values + (key to value))

    override fun equals(other: Any?): Boolean = other is Session && other.values == values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = "Session($values)"

    companion object {
        val empty: Session = Session(emptyMap())
    }
}
