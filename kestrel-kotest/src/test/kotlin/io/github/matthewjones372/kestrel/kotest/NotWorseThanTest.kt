package io.github.matthewjones372.kestrel.kotest

import io.github.matthewjones372.kestrel.Difference
import io.github.matthewjones372.kestrel.Machine
import io.github.matthewjones372.kestrel.Spread
import io.github.matthewjones372.kestrel.p99
import io.github.matthewjones372.kestrel.percent
import io.github.matthewjones372.kestrel.step
import io.kotest.assertions.shouldFail
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private val pay = step("pay")

private val here = Machine(cores = 8, jdk = "21.0.2+13", os = "Linux", arch = "aarch64")

private fun difference(interval: Spread) = Difference(
    statistic = p99(pay),
    before = 100.0,
    now = 105.0,
    runs = 10,
    baselineRuns = 10,
    acceptable = 3.percent,
    machine = here,
    baselineMachine = here,
    interval = interval,
)

private val slower = difference(Spread(low = 1.04, high = 1.06))
private val faster = difference(Spread(low = 0.90, high = 0.95))
private val noisy = difference(Spread(low = 0.98, high = 1.09))

class NotWorseThanTest : StringSpec({

    "a statistic that got better is not worse than what was declared acceptable" {
        faster shouldBe NotWorseThan(3.percent)
    }

    "a statistic past the threshold fails, and the message says what moved" {
        shouldFail { slower shouldBe NotWorseThan(3.percent) }
            .message shouldContain "pay p99 is worse than the 3% declared acceptable"
    }

    "the threshold is the assertion's own, not the one the comparison was made at" {
        slower shouldBe NotWorseThan(10.percent)
    }

    "a comparison that cannot tell passes, because a noisy Tuesday is not a regression" {
        noisy shouldBe NotWorseThan(3.percent)
    }

    "unless the team asked to be stopped by one" {
        shouldFail { noisy shouldBe NotWorseThan(3.percent, orCannotTell = true) }
            .message shouldContain "cannot tell"
    }
})
