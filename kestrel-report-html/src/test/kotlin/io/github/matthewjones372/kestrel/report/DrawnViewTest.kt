package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Plan
import io.github.matthewjones372.kestrel.PlannedArm
import io.github.matthewjones372.kestrel.Shape
import io.github.matthewjones372.kestrel.hold
import io.github.matthewjones372.kestrel.perSecond
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * A p99 measured over a million keys at skew 1.1 is a different number from the
 * same run over a thousand cycled ones, and nothing else on the page says which.
 */
class DrawnViewTest {

    private fun pageDrawing(vararg shapes: Shape) = Fixtures.fellBehind.copy(
        plan = Plan(
            listOf(
                PlannedArm(
                    scenario = "checkout",
                    steps = listOf("browse", "pay"),
                    profile = hold(120.perSecond, over = 4.seconds),
                    drawn = shapes.toList(),
                ),
            ),
        ),
    ).toHtmlReport()

    @Test
    fun `the page says what the data was drawn from, and from what seed`() {
        val page = pageDrawing(Shape("zipf(keys=1000000, skew=1.1)", seed = 20260904))

        page shouldContain "Data: zipf(keys=1000000, skew=1.1), seed 20260904."
    }

    @Test
    fun `every generator a run named is listed, each of them once`() {
        val keys = Shape("zipf(keys=1000000, skew=1.1)", seed = 4L)
        val ids = Shape("uuids()", seed = 5L)

        val page = pageDrawing(keys, ids, keys)

        page shouldContain "Data: zipf(keys=1000000, skew=1.1), seed 4; uuids(), seed 5."
    }

    @Test
    fun `a run that named no generator says nothing rather than that it drew nothing`() {
        pageDrawing() shouldNotContain "Data:"
    }
}
