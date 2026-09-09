package io.github.matthewjones372.proofload.jdbc

import io.github.matthewjones372.proofload.Reason
import io.github.matthewjones372.proofload.Threw
import io.github.matthewjones372.proofload.TimedOut
import java.sql.SQLException
import java.sql.SQLTimeoutException

/**
 * What the database said went wrong, by the code it is required to say it with.
 *
 * SQLSTATE rather than the vendor code or the message: a deadlock, a unique
 * violation and a serialization failure are three rows in a report under this,
 * and one row saying `SQLException` under a class name. The message carries a
 * table, a key and often an id, so a report keyed on it is a row per request.
 *
 * [described] carries the class of the code as well as the code, because a
 * reader who has not memorised SQLSTATE otherwise has five digits and nothing
 * else: the first two are the class, and they are what says integrity from
 * syntax from connection.
 */
data class SqlState(val code: String) : Reason {

    override val described: String get() = "SQLSTATE $code${named()?.let { " — $it" }.orEmpty()}"

    /** The classes worth naming: the ones a reader acts on differently. */
    private fun named(): String? = when (code.take(SQLSTATE_CLASS)) {
        "08" -> "connection"
        "22" -> "data"
        "23" -> "integrity constraint"
        "40" -> "transaction rollback"
        "42" -> "syntax or access rule"
        "53" -> "insufficient resources"
        "57" -> "operator intervention"
        else -> null
    }
}

/**
 * The first two digits of a SQLSTATE, which are its class.
 *
 * A file-private constant rather than one in a companion: a `const val` in even
 * a private companion is a public static field on the class, and this is not
 * something a caller has any business reading.
 */
private const val SQLSTATE_CLASS = 2

/**
 * The database's own code where it gave one, a timeout where the driver named
 * one, and the class otherwise.
 *
 * A driver is allowed to answer with no SQLSTATE, and one that does is the case
 * this cannot invent a code for — [Threw] is what it gets, which is what a step
 * body writing its own JDBC call already reports today.
 */
internal fun SQLException.reason(): Reason = when {
    this is SQLTimeoutException -> TimedOut
    !sqlState.isNullOrBlank() -> SqlState(sqlState)
    else -> Threw(this::class.simpleName ?: javaClass.name)
}
