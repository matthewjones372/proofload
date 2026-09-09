package io.github.matthewjones372.proofload.examples

import io.github.matthewjones372.proofload.examples.catalogue.simulation
import io.github.matthewjones372.proofload.plan.asSimulation
import io.github.matthewjones372.proofload.plan.readPlan
import io.github.matthewjones372.proofload.sessionKey
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import org.junit.jupiter.api.Test

/**
 * `emit` says the source it prints is the same run in the language. For a
 * drawn plan that is a claim about data, and the two only agree if the emitted
 * source carries the seed the plan derived — which it did not at first, and
 * which nothing but this would have noticed.
 */
class DrawnEquivalenceTest {

    private val keys = listOf("sku", "page", "region")

    @Test
    fun `the emitted source draws exactly what the plan draws`() {
        val plan = readPlan(
            requireNotNull(javaClass.getResourceAsStream("/catalogue.yaml")) { "no /catalogue.yaml" }
                .reader(Charsets.UTF_8)
                .use { it.readText() },
        )

        val fromPlan = plan.asSimulation().arms.single().feeder
        val fromKotlin = simulation.arms.single().feeder

        keys.forEach { name ->
            val key = sessionKey<String>(name)
            val planned = (0L until 500L).map { fromPlan.forUser(it)[key] }
            val written = (0L until 500L).map { fromKotlin.forUser(it)[key] }

            withClue("`$name` drew differently in the file and in the source it says it equals") {
                planned shouldContainExactly written
            }
            withClue("a key that drew one value for every user would pass the above and mean nothing") {
                planned.distinct().size shouldBeGreaterThan 1
            }
        }
    }
}
