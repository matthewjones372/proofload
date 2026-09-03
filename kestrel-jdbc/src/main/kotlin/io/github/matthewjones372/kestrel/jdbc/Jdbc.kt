package io.github.matthewjones372.kestrel.jdbc

import io.github.matthewjones372.kestrel.Action
import io.github.matthewjones372.kestrel.ScenarioBuilder
import io.github.matthewjones372.kestrel.StepScope
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Where statements are sent. A value, so a run against two databases names two
 * of these rather than setting a global the second one overwrites.
 *
 * The [DataSource] is handed in rather than built here. The pool is the thing
 * under test as often as the database is, and a pool this module built would
 * have different limits from the one the service runs — pointed at the caller's
 * own, a run measures what production will do.
 */
class Database internal constructor(internal val source: DataSource) {

    /** A statement whose answer is rows, counted rather than read. */
    fun query(sql: String): JdbcAction = JdbcAction(this, sql, Answering.Rows)

    /** A statement whose answer is a count of what it changed. */
    fun update(sql: String): JdbcAction = JdbcAction(this, sql, Answering.Changes)
}

/** Which database a run sends to, named once. */
object Jdbc {

    fun on(source: DataSource): Database = Database(source)
}

/** No data source of its own: `jdbc.on(dataSource)` names one, as `http.baseUrl(...)` does. */
val jdbc: Jdbc = Jdbc

/** Which number a statement answers with. */
internal enum class Answering { Rows, Changes }

/**
 * One statement, as a value: each of these returns another action rather than
 * changing this one, so a statement can be shared between scenarios and read
 * before anything is sent.
 */
class JdbcAction internal constructor(
    private val database: Database,
    private val sql: String,
    private val answering: Answering,
    private val values: ((StepScope) -> List<Any?>)? = null,
) : Action {

    /**
     * The statement as written. A report keyed on the filled-in statement grows
     * a row per user; keyed on the statement it has one row per query.
     */
    val name: String get() = sql

    /**
     * The parameters, in the order the `?`s appear, read from the session so a
     * run is not ten thousand identical selects measuring a query cache.
     *
     * A list rather than one value, because a statement takes as many
     * parameters as it has placeholders and a single-value form would need a
     * second name for every other statement.
     */
    fun binding(values: (StepScope) -> List<Any?>): JdbcAction =
        JdbcAction(database, sql, answering, values)

    override fun run(scope: StepScope) {
        // The checkout is not the query, and the split is the whole point: a
        // connection this user waited for is time it spent queueing for the
        // generator's own resource, and putting it in the query's latency is
        // coordinated omission with a different name.
        val askedAt = System.nanoTime()
        val connection = try {
            database.source.connection
        } catch (refused: SQLException) {
            scope.queued((System.nanoTime() - askedAt).nanoseconds)
            scope.fail(refused.reason())
            return
        }
        scope.queued((System.nanoTime() - askedAt).nanoseconds)
        connection.use { held -> execute(held, scope) }
    }

    /**
     * The statement, timed from the statement.
     *
     * The sample is reported by the body rather than left to the engine, which
     * times the whole of [run] and would put the pool wait inside the number.
     */
    private fun execute(connection: Connection, scope: StepScope) {
        val startedAt = System.nanoTime()
        try {
            connection.prepareStatement(sql).use { statement ->
                statement.bind(scope)
                val answered = statement.answer()
                scope.sample((System.nanoTime() - startedAt).nanoseconds)
                scope.produced(answered)
            }
        } catch (failed: SQLException) {
            val reason = failed.reason()
            // Both: the sample says how long the failure took, and the failure
            // abandons this user's journey the way any other failed step does.
            scope.sample((System.nanoTime() - startedAt).nanoseconds, reason = reason)
            scope.fail(reason)
        }
    }

    private fun PreparedStatement.bind(scope: StepScope) {
        val bound = values?.invoke(scope) ?: return
        bound.forEachIndexed { index, value -> setObject(index + 1, value) }
    }

    /**
     * Rows counted, never read into objects: `next()` in a loop with nothing in
     * the body is the honest measurement of "the database sent this much", and
     * anything more measures the driver's object mapping.
     */
    private fun PreparedStatement.answer(): Long = when (answering) {
        Answering.Rows -> executeQuery().use { rows ->
            var counted = 0L
            while (rows.next()) counted++
            counted
        }

        Answering.Changes -> executeUpdate().toLong()
    }
}

/** Names the step for the statement, which is the row a report wants. */
fun ScenarioBuilder.exec(statement: JdbcAction) {
    exec(statement.name, statement)
}
