package io.github.matthewjones372.proofload.jdbc

import org.h2.jdbcx.JdbcDataSource
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * H2 in its PostgreSQL compatibility mode, so the statements the tests send
 * look like statements somebody would write.
 *
 * One named in-memory database per test, held open by the caller's own
 * connection for as long as [block] runs: H2 drops a memory database when the
 * last connection closes, and a pool that opens one per checkout would find an
 * empty schema.
 */
internal fun withDatabase(schema: List<String> = emptyList(), block: (DataSource) -> Unit) {
    val source = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:proofload-${counter()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
        user = "sa"
    }
    source.connection.use { held ->
        schema.forEach { statement -> held.createStatement().use { it.execute(statement) } }
        block(source)
        held.createStatement().use { it.execute("drop all objects") }
    }
}

private val databases = java.util.concurrent.atomic.AtomicLong()

private fun counter(): Long = databases.incrementAndGet()

/**
 * A pool of exactly [size] connections, which is the thing under test: a
 * generator's own queueing looks like the target being slow until the two are
 * counted apart.
 *
 * Small enough to write out rather than take a dependency on Hikari for, and
 * deliberately so — this module carries no pool, and a test that pulled one in
 * would be measuring somebody else's.
 */
internal class SmallPool(private val source: DataSource, size: Int) : DataSource by source, AutoCloseable {

    private val free = ArrayBlockingQueue<Connection>(size)

    init {
        repeat(size) { free.put(Lent(source.connection, free)) }
    }

    override fun getConnection(): Connection =
        free.poll(WAIT_SECONDS, TimeUnit.SECONDS) ?: throw SQLException("no connection free", "08001")

    override fun close() = free.forEach { (it as Lent).really() }

    private companion object {
        const val WAIT_SECONDS = 30L
    }
}

/** A connection that goes back into the pool when a step closes it, rather than to the database. */
private class Lent(
    private val real: Connection,
    private val free: ArrayBlockingQueue<Connection>,
) : Connection by real {

    override fun close() {
        free.put(this)
    }

    fun really() = real.close()
}
