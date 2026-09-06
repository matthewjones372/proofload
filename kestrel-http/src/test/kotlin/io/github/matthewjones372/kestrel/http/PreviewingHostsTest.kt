package io.github.matthewjones372.kestrel.http

import io.github.matthewjones372.kestrel.Allowance
import io.github.matthewjones372.kestrel.InjectionProfile
import io.github.matthewjones372.kestrel.Preview
import io.github.matthewjones372.kestrel.Refusal
import io.github.matthewjones372.kestrel.Scenario
import io.github.matthewjones372.kestrel.Simulation
import io.github.matthewjones372.kestrel.Step
import io.github.matthewjones372.kestrel.preview
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Core declares [io.github.matthewjones372.kestrel.Targeted] and this module
 * answers it, so a preview taken in core can name a host it cannot itself read.
 */
class PreviewingHostsTest {

    private val api = http.baseUrl("https://orders.internal")

    @Test
    fun `an http step names the host it would send to`() {
        val allowed = simulation().preview().shouldBeInstanceOf<Preview.Allowed>()

        allowed.hosts shouldBe listOf("orders.internal")
        withClue("nothing here is a step whose target could not be read") {
            allowed.untargeted shouldBe 0
        }
    }

    @Test
    fun `a host nobody allowed is refused before anything departs`() {
        val refused = simulation()
            .preview(Allowance(hosts = listOf("localhost", "*.staging.internal")))
            .shouldBeInstanceOf<Preview.Refused>()

        refused.reason shouldBe Refusal.HostNotAllowed("orders.internal", listOf("localhost", "*.staging.internal"))
    }

    @Test
    fun `a host the allowance names is allowed`() {
        val staged = Simulation(
            scenario = Scenario(
                "checkout",
                listOf(Step.Exec("browse", http.baseUrl("https://orders.staging.internal").get("/products"))),
            ),
            profile = InjectionProfile.ConstantRate(50.0, 1.minutes),
        )

        staged.preview(Allowance(hosts = listOf("*.staging.internal"))).shouldBeInstanceOf<Preview.Allowed>()
    }

    private fun simulation(): Simulation = Simulation(
        scenario = Scenario("checkout", listOf(Step.Exec("browse", api.get("/products")))),
        profile = InjectionProfile.ConstantRate(50.0, 1.minutes),
    )
}
