package io.github.matthewjones372.kestrel.java

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class DurationsTest {

    @Test
    fun `a duration crosses the boundary and comes back the length it was`() {
        java.time.Duration.ofMillis(250).asKestrel() shouldBe 250.milliseconds
        250.milliseconds.asJava() shouldBe java.time.Duration.ofMillis(250)
    }
}
