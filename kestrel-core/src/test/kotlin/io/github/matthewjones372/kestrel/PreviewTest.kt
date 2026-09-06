package io.github.matthewjones372.kestrel

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What a plan would send, asked before it sends any of it. Every number is
 * arithmetic over the value, so nothing here opens a socket.
 */
class PreviewTest {

    @Test
    fun `a plan says how many users and how many requests before it runs`() {
        val allowed = simulation().preview().shouldBeInstanceOf<Preview.Allowed>()

        allowed.users shouldBe 3_000L
        allowed.requestsAtLeast shouldBe 6_000L
        allowed.requestsBounded shouldBe true
        allowed.over shouldBe 1.minutes
    }

    @Test
    fun `a staged plan reports the tallest stage rather than the mean`() {
        val staged = simulation(
            InjectionProfile.Stages(
                listOf(
                    InjectionProfile.ConstantRate(10.0, 10.seconds),
                    InjectionProfile.ConstantRate(200.0, 10.seconds),
                    InjectionProfile.ConstantRate(10.0, 10.seconds),
                ),
            ),
        )

        withClue("a fence that averaged a ramp would allow a peak nobody agreed to") {
            staged.preview().shouldBeInstanceOf<Preview.Allowed>().peakRate shouldBe 200.perSecond
        }
    }

    @Test
    fun `a closed run states no rate, because the target decides it`() {
        val closed = simulation(InjectionProfile.ClosedUsers(count = 50, over = 1.minutes))

        closed.preview().shouldBeInstanceOf<Preview.Allowed>().peakRate.shouldBeNull()
    }

    @Test
    fun `a step whose target nobody can read is counted as unknown, not as safe`() {
        val allowed = simulation().preview().shouldBeInstanceOf<Preview.Allowed>()

        withClue("core cannot read a lambda, and quietly calling it allowed is the reassurance to avoid") {
            allowed.untargeted shouldBe 2
            allowed.hosts shouldBe emptyList()
        }
    }

    @Test
    fun `a rate over the allowance is refused with both numbers`() {
        val refused = simulation()
            .preview(Allowance(maxRate = 20.perSecond))
            .shouldBeInstanceOf<Preview.Refused>()

        refused.reason shouldBe Refusal.OverRate(asked = 50.perSecond, allowed = 20.perSecond)
    }

    @Test
    fun `a window over the allowance is refused`() {
        val refused = simulation()
            .preview(Allowance(maxDuration = 30.seconds))
            .shouldBeInstanceOf<Preview.Refused>()

        refused.reason shouldBe Refusal.OverDuration(asked = 1.minutes, allowed = 30.seconds)
    }

    @Test
    fun `a scenario nobody can count is refused against a request cap`() {
        val unbounded = Simulation(
            scenario = Scenario(
                "checkout",
                listOf(Step.During(10.seconds, listOf(Step.Exec("browse") { }))),
            ),
            profile = InjectionProfile.ConstantRate(50.0, 1.minutes),
        )

        withClue("a fence that cannot count cannot fence, and allowing it on a lower bound is a hole") {
            unbounded.preview(Allowance(maxRequests = 1_000_000)).shouldBeInstanceOf<Preview.Refused>()
        }
    }

    @Test
    fun `an unbounded scenario is fine where nobody capped requests`() {
        val unbounded = Simulation(
            scenario = Scenario(
                "checkout",
                listOf(Step.During(10.seconds, listOf(Step.Exec("browse") { }))),
            ),
            profile = InjectionProfile.ConstantRate(50.0, 1.minutes),
        )

        unbounded.preview().shouldBeInstanceOf<Preview.Allowed>().requestsBounded shouldBe false
    }

    private fun simulation(
        profile: InjectionProfile = InjectionProfile.ConstantRate(50.0, 1.minutes),
    ): Simulation = Simulation(
        scenario = Scenario("checkout", listOf(Step.Exec("browse") { }, Step.Exec("pay") { })),
        profile = profile,
    )
}
