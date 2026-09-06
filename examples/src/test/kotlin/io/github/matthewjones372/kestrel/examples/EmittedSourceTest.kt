package io.github.matthewjones372.kestrel.examples

import io.github.matthewjones372.kestrel.plan.asKotlin
import io.github.matthewjones372.kestrel.plan.readPlan
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * `kestrel emit` claims the source it prints compiles. A golden cannot show
 * that, so the emitted file is checked in beside the other examples and the
 * build compiles it; this keeps it identical to what the emitter writes today.
 *
 * `-Dkestrel.regenerate=true` rewrites it and fails, so a change to the emitter
 * is a diff to read rather than a file to hand-edit.
 */
class EmittedSourceTest {

    @Test
    fun `the checked-in source is what the emitter writes for the plan beside it`() {
        val plan = requireNotNull(javaClass.getResourceAsStream("/checkout.yaml")) { "no /checkout.yaml" }
            .reader(Charsets.UTF_8)
            .use { it.readText() }

        val emitted = readPlan(plan).asKotlin(packageName = PACKAGE, from = "checkout.yaml")

        if (System.getProperty("kestrel.regenerate").toBoolean()) {
            Files.createDirectories(SOURCE.parent)
            Files.writeString(SOURCE, emitted, Charsets.UTF_8)
            error("rewrote $SOURCE. Re-run without -Dkestrel.regenerate and read the diff.")
        }

        withClue("the compiler has already agreed this file is Kotlin; this is that it is still the emitter's") {
            Files.readString(SOURCE, Charsets.UTF_8) shouldBe emitted
        }
    }

    private companion object {
        const val PACKAGE = "io.github.matthewjones372.kestrel.examples"
        val SOURCE: Path = Path.of("src/main/kotlin/io/github/matthewjones372/kestrel/examples/EmittedCheckout.kt")
    }
}
