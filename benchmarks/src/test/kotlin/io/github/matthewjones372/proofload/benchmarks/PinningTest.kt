package io.github.matthewjones372.proofload.benchmarks

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * What the apart table says about processors, including when it could not set
 * any.
 *
 * Nothing here pins anything: whether `taskset` exists is the build machine's
 * business, and a test that asserted it would fail on a platform the sweep is
 * meant to degrade on rather than refuse.
 */
class PinningTest {

    @Test
    fun `a pinned sweep names the processors each end had`() {
        val described = Pinning.Pinned(generator = "0-1", target = "2-3").described

        described shouldContain "generator on cpu 0-1"
        described shouldContain "target on cpu 2-3"
    }

    @Test
    fun `a sweep that could not pin says so rather than leaving the reader to assume`() {
        val described = Pinning.Pinned(generator = null, target = null).described

        described shouldContain "not pinned"
        described shouldContain "shared"
        described shouldNotContain "cpu 0"
    }

    @Test
    fun `half a pinning is no pinning, because the ends would still overlap`() {
        // A target pinned while the generator is not is two ends sharing the
        // target's processors, which is worse than sharing all of them and
        // would read as a pinned row.
        Pinning.Pinned(generator = "0-1", target = null).described shouldContain "not pinned"
        Pinning.Pinned(generator = null, target = "2-3").described shouldContain "not pinned"
    }
}
