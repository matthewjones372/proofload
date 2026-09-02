package io.github.matthewjones372.kestrel.report

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * A step that went to the target twice per request says so. Folded into one
 * measurement it would report the target as slower than it is.
 */
class AttemptsViewTest {

    @Test
    fun `a step with more attempts than requests names both`() {
        val run = Fixtures.fellBehind.let { result ->
            result.copy(
                steps = result.steps.mapValues { (name, step) ->
                    if (name == "pay") step.copy(attempts = step.count + 32) else step.copy(attempts = step.count)
                },
            )
        }

        val page = run.toHtmlReport()

        page shouldContain "went to the target more than once per request"
        page shouldContain "attempts"
    }

    @Test
    fun `a run where every step went once says nothing about attempts`() {
        val run = Fixtures.fellBehind.let { result ->
            result.copy(steps = result.steps.mapValues { (_, step) -> step.copy(attempts = step.count) })
        }

        run.toHtmlReport() shouldNotContain "went to the target more than once per request"
    }
}
