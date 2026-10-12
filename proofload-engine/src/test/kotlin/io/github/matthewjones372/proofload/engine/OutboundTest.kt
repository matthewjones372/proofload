package io.github.matthewjones372.proofload.engine

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class OutboundTest {

    @Test
    fun `the oldest departure out is the earliest second not yet back`() {
        val out = Outbound()
        out.left(3_400.milliseconds)
        out.left(3_900.milliseconds)
        out.left(5_100.milliseconds)

        out.back(3_400.milliseconds)
        out.oldest() shouldBe 3.seconds

        out.back(3_900.milliseconds)
        out.oldest() shouldBe 5.seconds
    }

    @Test
    fun `nothing out has no oldest`() {
        val out = Outbound()
        out.left(1.seconds)
        out.back(1.seconds)

        out.oldest() shouldBe null
    }
}
