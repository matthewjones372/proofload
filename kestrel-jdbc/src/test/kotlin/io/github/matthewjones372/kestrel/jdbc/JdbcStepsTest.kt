package io.github.matthewjones372.kestrel.jdbc

import io.github.matthewjones372.kestrel.Progress
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.engine.run
import io.github.matthewjones372.kestrel.fedBy
import io.github.matthewjones372.kestrel.feed
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.sessionKey
import io.github.matthewjones372.kestrel.step
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

private val orderId = sessionKey<Int>("orderId")

private val byId = step("select by id")

private val insert = step("insert an order")

private val schema = listOf(
    "create table orders (id int primary key, sku varchar(32))",
    "insert into orders values (1, 'anvil'), (2, 'rope'), (3, 'net')",
)

/**
 * A statement is a string the caller wrote, as a path is, and what comes back
 * is counted rather than read into objects: a mapper on the timed path is a
 * measurement of the mapper.
 */
class JdbcStepsTest {

    private fun reading(source: DataSource, sql: String, id: (Long) -> Int = { (it % 3 + 1).toInt() }): RunResult =
        scenario("reading") {
            exec(byId, jdbc.on(source).query(sql).binding { listOf(it[orderId]) })
        }
            .at(20.perSecond, over = 1.seconds)
            .fedBy(feed(orderId, id))
            .run(Progress.silent)

    @Test
    fun `a select is one sample per execution, whatever it returned`() {
        withDatabase(schema) { source ->
            val result = reading(source, "select id, sku from orders where id = ?")

            result[byId].count shouldBe 20L
            result[byId].ok.count shouldBe 20L
        }
    }

    @Test
    fun `a step nobody named is named for the statement`() {
        withDatabase(schema) { source ->
            val result = scenario("reading") {
                exec(jdbc.on(source).query("select id from orders where id = ?").binding { listOf(it[orderId]) })
            }
                .at(3.perSecond, over = 1.seconds)
                .fedBy(feed(orderId) { 1 })
                .run(Progress.silent)

            withClue("a report keyed on the filled-in statement grows a row per user") {
                result.steps.keys shouldBe setOf("select id from orders where id = ?")
            }
        }
    }

    @Test
    fun `the rows a query returned are counted, and are not the executions`() {
        withDatabase(schema) { source ->
            val result = reading(source, "select id, sku from orders where id <= ?") { 3 }

            withClue("twenty executions of a select that returned all three rows each time") {
                result[byId].count shouldBe 20L
                result[byId].rows shouldBe 60L
            }
        }
    }

    @Test
    fun `an update reports the rows it changed`() {
        withDatabase(schema) { source ->
            val result = scenario("writing") {
                exec(
                    insert,
                    jdbc.on(source).update("update orders set sku = 'anvil' where id = ?")
                        .binding { listOf(it[orderId]) },
                )
            }
                .at(3.perSecond, over = 1.seconds)
                .fedBy(feed(orderId) { user -> (user % 3 + 1).toInt() })
                .run(Progress.silent)

            result[insert].ok.count shouldBe 3L
            withClue("three updates of one row each") { result[insert].rows shouldBe 3L }
        }
    }

    @Test
    fun `a binding reads the session, so a run is not ten thousand identical selects`() {
        withDatabase(schema) { source ->
            val seen = mutableListOf<Int>()
            val result = scenario("reading") {
                exec(
                    byId,
                    jdbc.on(source).query("select sku from orders where id = ?").binding { scope ->
                        val id = requireNotNull(scope[orderId])
                        synchronized(seen) { seen += id }
                        listOf(id)
                    },
                )
            }
                .at(6.perSecond, over = 1.seconds)
                .fedBy(feed(orderId) { user -> (user % 3 + 1).toInt() })
                .run(Progress.silent)

            result[byId].ok.count shouldBe 6L
            withClue("$seen") { seen.toSet() shouldBe setOf(1, 2, 3) }
        }
    }
}
