package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Plan
import io.github.matthewjones372.kestrel.users
import io.kotest.assertions.withClue
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/**
 * A closed run's numbers were shaped by the target. Read as an open run's they
 * say a service stayed fast while doing less work, so the page says which it is
 * before it says anything else.
 */
class ClosedViewTest {

    private val closed = Fixtures.fellBehind.copy(
        plan = Plan(scenario = "looping", steps = listOf("pay"), profile = users(50, over = 10.minutes)),
    )

    @Test
    fun `a closed run says so, and says what it cannot tell anyone`() {
        val page = closed.toHtmlReport()

        page shouldContain "A closed run"
        withClue("the thing a reader has to know before believing any percentile below it") {
            page shouldContain "throttled by the target's own responses"
            page shouldContain "one clock rather than two"
        }
    }

    @Test
    fun `it names the population, because that is the only thing the run asked for`() {
        closed.toHtmlReport() shouldContain "50 users looping"
    }

    @Test
    fun `an open run says none of it`() {
        val page = Fixtures.fellBehind.toHtmlReport()

        page shouldNotContain "A closed run"
        page shouldNotContain "kestrel-closed"
    }
}
