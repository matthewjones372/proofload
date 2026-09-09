package io.github.matthewjones372.proofload.kotest

import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.sessionKey
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Duration.Companion.seconds

private val hits = sessionKey<Long>("hits")

private val oneStep = scenario("browse") { exec("home") { set(hits, 1L) } }

class ProofloadSpecTest : StringSpec({

    "a spec runs a simulation and reads the result" {
        val result = proofload().run(oneStep.at(4.perSecond, over = 1.seconds))

        result["home"].count shouldBe 4L
        result.failed shouldBe 0L
    }

    "two tests in one spec do not share what they recorded" {
        val result = proofload().run(oneStep.at(2.perSecond, over = 1.seconds))

        result["home"].count shouldBe 2L
    }

    "what a run measured is attached to a failure" {
        val proofload = proofload()
        proofload.run(oneStep.at(2.perSecond, over = 1.seconds))

        proofload.summary().toString() shouldContain "home"
    }

    "a runner that ran nothing has nothing to say" {
        proofload().summary() shouldBe null
    }
})
