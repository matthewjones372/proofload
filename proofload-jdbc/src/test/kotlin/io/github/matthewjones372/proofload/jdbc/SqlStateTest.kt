package io.github.matthewjones372.proofload.jdbc

import io.github.matthewjones372.proofload.Outcome
import io.github.matthewjones372.proofload.Progress
import io.github.matthewjones372.proofload.Reason
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.run
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.step
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

private val statement = step("the statement")

/**
 * A deadlock, a unique violation and a syntax error are three rows in a report
 * under a SQLSTATE, and one row saying `SQLException` under a class name.
 */
class SqlStateTest {

    private fun failing(source: DataSource, sql: String): Outcome =
        scenario("failing") { exec(statement, jdbc.on(source).update(sql)) }
            .at(4.perSecond, over = 1.seconds)
            .run(Progress.silent)[statement]
            .failed

    @Test
    fun `a unique violation, a missing table and a syntax error are three reasons`() {
        withDatabase(listOf("create table orders (id int primary key)")) { source ->
            val duplicate = failing(source, "insert into orders values (1)")
            val missing = failing(source, "insert into nowhere values (1)")
            val nonsense = failing(source, "insert onto orders values (1)")

            val named = (duplicate.reasons.keys + missing.reasons.keys + nonsense.reasons.keys)
                .map { it.described }
            withClue("$named") {
                named.distinct().size shouldBe 3
            }
        }
    }

    @Test
    fun `a unique violation is an integrity constraint, named as one`() {
        withDatabase(listOf("create table orders (id int primary key)")) { source ->
            val only = failing(source, "insert into orders values (1)").reasons.keys.single()
            only.shouldBeSqlState()
            withClue(only.described) {
                only.described shouldContain "integrity constraint"
            }
        }
    }

    @Test
    fun `the same violation twice is one row and a count, not a row each`() {
        withDatabase(listOf("create table orders (id int primary key)")) { source ->
            val failed = failing(source, "insert into orders values (1)")

            withClue("${failed.reasons}") {
                failed.reasons.size shouldBe 1
                withClue("a reason with identity equality gets a row per request instead of a count") {
                    failed.reasons.values.single() shouldBe failed.count
                }
            }
        }
    }

    private fun Reason.shouldBeSqlState() {
        withClue("$this is not a SqlState, so a deadlock would read as a stack trace") {
            (this is SqlState) shouldBe true
        }
    }
}
