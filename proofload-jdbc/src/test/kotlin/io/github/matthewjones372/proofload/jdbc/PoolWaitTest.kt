package io.github.matthewjones372.proofload.jdbc

import io.github.matthewjones372.proofload.Progress
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.run
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.step
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection
import javax.sql.DataSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val byId = step("select by id")

private const val CHECKOUT_TAKES = 300L

/**
 * `behind` all over again, one layer down: the generator's own queueing
 * reported as the target's speed. A hand-written step times the checkout and
 * the query together and calls the total the database's latency.
 */
class PoolWaitTest {

    /** A pool that is busy for as long as it takes, without needing another user to make it so. */
    private class SlowToLend(private val source: DataSource) : DataSource by source {
        override fun getConnection(): Connection {
            Thread.sleep(CHECKOUT_TAKES)
            return source.connection
        }
    }

    @Test
    fun `the wait for a connection is beside the query, not inside it`() {
        withDatabase(listOf("create table orders (id int primary key)")) { source ->
            val result = scenario("reading") {
                exec(byId, jdbc.on(SlowToLend(source)).query("select id from orders"))
            }
                .at(4.perSecond, over = 1.seconds)
                .run(Progress.silent)

            withClue("query ${result[byId].serviceTime.max}, pool ${result[byId].waitedForPool.p50}") {
                result[byId].waitedForPool.p50 shouldBeGreaterThan (CHECKOUT_TAKES - 1).milliseconds
                result[byId].serviceTime.max shouldBeLessThan result[byId].waitedForPool.p50
            }
        }
    }

    @Test
    fun `a pool smaller than the users reports the queueing as queueing`() {
        withDatabase(listOf("create table orders (id int primary key)")) { source ->
            SmallPool(source, size = 1).use { pool ->
                val result = scenario("reading") {
                    exec(byId, jdbc.on(pool).query("select id from orders"))
                }
                    .at(20.perSecond, over = 1.seconds)
                    .run(Progress.silent)

                result[byId].ok.count shouldBe 20L
                withClue("query ${result[byId].serviceTime.p99}, pool ${result[byId].waitedForPool.p99}") {
                    result[byId].waitedForPool.count shouldBe 20L
                }
            }
        }
    }

    @Test
    fun `a step that waited for nothing reports no wait at all`() {
        withDatabase(listOf("create table orders (id int primary key)")) { source ->
            val result = scenario("reading") {
                exec(byId) { }
            }
                .at(2.perSecond, over = 1.seconds)
                .run(Progress.silent)

            withClue("absent rather than a zero: nothing waited, and nothing measured a wait") {
                result[byId].waitedForPool.count shouldBe 0L
            }
        }
    }
}
