package io.github.matthewjones372.kestrel.kotest

import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.sessionKey
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Duration.Companion.seconds

private val hits = sessionKey<Long>("hits")

private val oneStep = scenario("browse") { exec("home") { set(hits, 1L) } }

class KestrelSpecTest : StringSpec({

    "a spec runs a simulation and reads the result" {
        val result = kestrel().run(oneStep.at(4.perSecond, over = 1.seconds))

        result["home"].count shouldBe 4L
        result.failed shouldBe 0L
    }

    "two tests in one spec do not share what they recorded" {
        val result = kestrel().run(oneStep.at(2.perSecond, over = 1.seconds))

        result["home"].count shouldBe 2L
    }

    "what a run measured is attached to a failure" {
        val kestrel = kestrel()
        kestrel.run(oneStep.at(2.perSecond, over = 1.seconds))

        kestrel.summary().toString() shouldContain "home"
    }

    "a runner that ran nothing has nothing to say" {
        kestrel().summary() shouldBe null
    }
})
