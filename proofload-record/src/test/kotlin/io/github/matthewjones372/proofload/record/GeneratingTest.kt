package io.github.matthewjones372.proofload.record

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The generated file is checked in under `src/test`, so this module compiles
 * it, detekt reads it and spotless formats it — which turns "the output
 * compiles and is formatted" from a claim into three gates that already exist.
 *
 * A generator that produced something unformatted would move that file, and
 * this test would fail on the diff rather than on a promise.
 */
class GeneratingTest {

    private val har: Path get() = Path.of(checkNotNull(System.getProperty("proofload.record.checkoutHar")))

    private val checkedIn: Path =
        Path.of("src/test/kotlin/io/github/matthewjones372/proofload/record/generated/Checkout.kt")

    private fun generated(): String =
        draft(readHar(Files.readString(har)))
            .asKotlin(
                packageName = "io.github.matthewjones372.proofload.record.generated",
                scenario = "checkout",
                from = "checkout.har",
            )

    @Test
    fun `the cookbook's own checkout regenerates to the file checked in beside it`() {
        val regenerated = generated()

        if (System.getProperty("proofload.regenerate") == "true") {
            Files.writeString(checkedIn, regenerated)
        }

        withClue("run with -Dproofload.regenerate=true and read the diff before keeping it") {
            regenerated shouldBe Files.readString(checkedIn)
        }
    }
}
