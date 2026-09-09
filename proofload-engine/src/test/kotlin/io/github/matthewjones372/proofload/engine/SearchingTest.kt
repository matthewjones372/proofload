package io.github.matthewjones372.proofload.engine

import io.github.matthewjones372.proofload.Rung
import io.github.matthewjones372.proofload.failureRate
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.percent
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.step
import io.github.matthewjones372.proofload.sustainable
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class SearchingTest {

    private val serve = step("serve")

    /**
     * Two hundred thousand a second from one scheduler thread is a rate no
     * injector offers, so every rung of this is the generator's own ceiling
     * rather than anything the target did.
     *
     * It was twenty thousand, over ten times the window. That is a rate this
     * machine *does* offer once the JVM is warm, so the first rung was void on
     * a cold JVM and not on a warm one — which meant the test passed alone and
     * failed inside a full build, for a reason that had nothing to do with what
     * it was testing. The rung still books the same two thousand departures;
     * only the rate it books them at has moved out of reach.
     */
    private val beyondTheInjector = scenario("target") { exec(serve) { } }
        .sustainable(
            upTo = 2_000_000.perSecond,
            holding = 10.milliseconds,
            expecting = listOf(failureRate under 1.percent),
        )

    @Test
    fun `each rung of a search is a real run at its own rate`() {
        val capacity = beyondTheInjector.run()

        capacity.curve.first().rate shouldBe 200_000.perSecond
        withClue("the rung sent what its own rate asked for over the window it held") {
            capacity.curve.first().result.count shouldBe 2_000L
        }
    }

    @Test
    fun `a rung the injector could not offer is void, and the search stops rather than answer with it`() {
        val capacity = beyondTheInjector.run()

        capacity.curve.single().outcome shouldBe Rung.Outcome.Void
        withClue("no goal was missed, so nothing limited the rate") {
            capacity.limitedBy shouldBe null
        }
        withClue("a rate the generator could not offer is not a rate the target sustained") {
            capacity.voided shouldBe true
            capacity.rate shouldBe null
        }
    }

    @Test
    fun `a search run through Proofload leaves every rung in the summary a failure prints`() {
        val proofload = Proofload()

        val capacity = proofload.run(beyondTheInjector)

        proofload.summary().toString() shouldContain "serve"
        withClue("one line of the summary for each rung that ran") {
            proofload.summary().toString().lines().count { it.startsWith("proofload:") } shouldBe capacity.curve.size
        }
    }
}
