package io.github.matthewjones372.kestrel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class ExclusivityTest {

    private val queued = Exclusivity.Waiting(Instant.parse("2026-08-31T09:00:00Z"))

    @Test
    fun `a run granted the machine straight away starts without having waited`() {
        Exclusivity.Idle.then(Exclusivity.Running) shouldBe Exclusivity.Running
    }

    @Test
    fun `a run that waited starts once the machine is granted`() {
        queued.then(Exclusivity.Running) shouldBe Exclusivity.Running
    }

    @Test
    fun `a calibration takes the machine the same way a run does`() {
        Exclusivity.Idle.then(Exclusivity.Calibrating).then(Exclusivity.Idle) shouldBe Exclusivity.Idle
    }

    @Test
    fun `a run drains before it gives the machine back`() {
        Exclusivity.Running.then(Exclusivity.Draining).then(Exclusivity.Idle) shouldBe Exclusivity.Idle
    }

    @Test
    fun `nothing reaches running without having been granted the machine`() {
        withClue("draining is the run that already has it, so it cannot be granted it again") {
            shouldThrow<IllegalStateException> { Exclusivity.Draining.then(Exclusivity.Running) }
        }
    }

    @Test
    fun `a run that never started cannot drain`() {
        shouldThrow<IllegalStateException> { Exclusivity.Idle.then(Exclusivity.Draining) }
    }

    @Test
    fun `a run holding the machine does not queue for it`() {
        shouldThrow<IllegalStateException> { Exclusivity.Running.then(queued) }
    }
}
