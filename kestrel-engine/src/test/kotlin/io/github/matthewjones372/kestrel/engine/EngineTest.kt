package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.sessionKey
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class EngineTest {

    private val cart = sessionKey<String>("cart")

    @Test
    fun `a one-step scenario at one user a second counts that step once`() {
        val result = scenario("checkout") { exec("browse") { } }
            .at(1.perSecond, over = 1.seconds)
            .run()

        result["browse"].count shouldBe 1L
        result.ok shouldBe 1L
        result.failed shouldBe 0L
    }

    @Test
    fun `a step that declares a failure is counted under the reason it gave`() {
        val result = scenario("checkout") { exec("pay") { fail("503") } }
            .at(1.perSecond, over = 1.seconds)
            .run()

        result["pay"].failures shouldContainExactly mapOf("503" to 1L)
        result.failed shouldBe 1L
    }

    @Test
    fun `a step that throws is a failure named for the exception`() {
        val result = scenario("checkout") { exec("pay") { error("the target hung up") } }
            .at(1.perSecond, over = 1.seconds)
            .run()

        result["pay"].failures shouldContainExactly mapOf("java.lang.IllegalStateException" to 1L)
    }

    @Test
    fun `what one step puts in the session is there for the next`() {
        val result = scenario("checkout") {
            exec("browse") { set(cart, "two hats") }
            exec("pay") { if (this[cart] != "two hats") fail("no cart") }
        }.at(1.perSecond, over = 1.seconds).run()

        result["pay"].ok shouldBe 1L
    }

    @Test
    fun `every request records how late it left against the departure it was promised`() {
        val result = scenario("checkout") { exec("browse") { } }
            .at(4.perSecond, over = 1.seconds)
            .run()

        result["browse"].count shouldBe 4L
        result.behind.count shouldBe 4L
    }
}
