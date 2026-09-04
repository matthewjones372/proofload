package io.github.matthewjones372.kestrel.examples

import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.http.http
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.step
import io.github.matthewjones372.kestrel.stepNames
import io.github.matthewjones372.kestrel.userCount
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * The right-hand column of the table in `docs/for-agents.md`, written out as
 * code the compiler has seen, and checked to still be what the table quotes. A
 * shape a document recommends and the build never compiles is the mistake the
 * table exists to stop, made by the table.
 */
class WrittenRightTest {

    private val browse = step("browse")

    private val placeOrder = step("place order")

    private val row = Regex("""^\|(.*)\|$""")

    /** An inline span is a fragment of a line, not a line, so it is matched inside one. */
    private val span = Regex("""(`{1,2})(.+?)\1""")

    private val repoRoot: File
        get() {
            val root = System.getProperty("kestrel.repoRoot")
            withClue("the build must pass -Dkestrel.repoRoot; see examples/build.gradle.kts") {
                root.shouldNotBeNull()
            }
            return File(root!!)
        }

    @Test
    fun `a scenario is a value, so what it will do is known before anything is sent`() {
        val api = http.baseUrl("https://orders.internal")

        val checkout = scenario("checkout") {
            exec(browse, api.get("/products"))
            exec(placeOrder, api.post("/orders").expecting(201).checking("has an id") { it.body.contains("\"id\"") })
        }

        val fiftyASecond = checkout.at(50.perSecond, over = 1.minutes)

        checkout.stepNames shouldBe listOf("browse", "place order")
        fiftyASecond.userCount() shouldBe 3000L
    }

    @Test
    fun `every call the table says to write is one this module compiles`() {
        val quoted = rightColumn().flatMap { cell -> span.findAll(cell).map { it.groupValues[2] } }
        val compiled = repoRoot.resolve("examples/src")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { it.readLines() }
            .toList()

        val invented = quoted.filterNot { fragment -> compiled.any { fragment in it } }

        withClue("the table has no rows to check") { quoted.shouldNotBeEmpty() }
        withClue("the table quotes calls no example makes: $invented") { invented.shouldBeEmpty() }
    }

    private fun rightColumn(): List<String> {
        val table = repoRoot.resolve("docs/for-agents.md").readText()
            .substringAfter("## Written wrong, written right", missingDelimiterValue = "")
            .substringBefore("\n## ")
        return table.lines()
            .mapNotNull { row.find(it.trim())?.groupValues?.get(1) }
            .map { it.split("|").last().trim() }
            .filterNot { it.isBlank() || it.all { character -> character == '-' } }
            .drop(1)
    }
}
