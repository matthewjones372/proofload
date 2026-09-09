package io.github.matthewjones372.proofload.junit5

import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.Proofload
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.sessionKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import org.junit.platform.launcher.listeners.TestExecutionSummary
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

internal const val FIXTURE = "fixture"

private val hits = sessionKey<Long>("hits")

private val oneStep = scenario("browse") { exec("home") { set(hits, 1L) } }

class LoadTestTest {

    @LoadTest
    fun `a load test is an ordinary test that is handed a runner`(proofload: Proofload) {
        val result = proofload.run(oneStep.at(4.perSecond, over = 1.seconds))

        result["home"].count shouldBe 4L
        result.failed shouldBe 0L
    }

    /** What naming an engine has to leave alone: a bare load test still runs on Loom. */
    @LoadTest
    fun `a load test that names no engine runs its users on virtual threads`(proofload: Proofload) {
        val virtual = AtomicBoolean(false)

        proofload.run(
            scenario("browse") { exec("home") { virtual.set(Thread.currentThread().isVirtual) } }
                .at(1.perSecond, over = 1.seconds),
        )

        virtual.get() shouldBe true
    }

    @Test
    fun `the runner can also be asked for by a plain test that extends the class`() {
        val summary = run(PlainlyExtended::class.java)

        summary.testsSucceededCount shouldBe 1L
    }

    @Test
    fun `a failing load test says which step it was looking at`() {
        val summary = run(FailsOnPurpose::class.java)

        summary.failures.single().exception.suppressed.single().message.toString() shouldContain "home"
    }

    @Test
    fun `a passing load test is not decorated with anything`() {
        val summary = run(PlainlyExtended::class.java)

        summary.failures.shouldBeEmptyList()
    }

    private fun List<*>.shouldBeEmptyList() = isEmpty() shouldBe true

    private fun run(target: Class<*>): TestExecutionSummary {
        val listener = SummaryGeneratingListener()
        LauncherFactory.create().execute(
            LauncherDiscoveryRequestBuilder.request().selectors(selectClass(target)).build(),
            listener,
        )
        return listener.summary
    }
}

/**
 * Run by the launcher above, not by the build: one of these fails on purpose,
 * and a fixture that fails is only a result when something is reading it.
 */
@Tag(FIXTURE)
@ExtendWith(ProofloadExtension::class)
class PlainlyExtended {

    @Test
    fun `runs`(proofload: Proofload) {
        proofload.run(oneStep.at(2.perSecond, over = 1.seconds))["home"].count shouldBe 2L
    }
}

@Tag(FIXTURE)
class FailsOnPurpose {

    @LoadTest
    fun `fails after a run`(proofload: Proofload) {
        proofload.run(oneStep.at(2.perSecond, over = 1.seconds))

        throw AssertionError("p99 was too high")
    }
}
