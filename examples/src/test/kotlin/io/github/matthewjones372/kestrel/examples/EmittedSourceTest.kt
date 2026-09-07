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
        checkEmitted(plan = "checkout.yaml", into = PACKAGE, source = "EmittedCheckout.kt")
    }

    /**
     * The Kafka half, which the compiler has more to say about: a produce step
     * answered on another topic emits an `emit`/`completing` pair, a
     * correlation and a feeder, and none of those is a string this test could
     * have judged.
     */
    @Test
    fun `a plan whose steps are topics emits Kotlin too`() {
        // A package of its own, because both files declare a `placeOrder`
        // handle and an emitted file is meant to be pasted somewhere, not
        // merged with the last one.
        checkEmitted(plan = "orders.yaml", into = "$PACKAGE.orders", source = "orders/EmittedOrders.kt")
    }

    private fun checkEmitted(plan: String, into: String, source: String) {
        val read = requireNotNull(javaClass.getResourceAsStream("/$plan")) { "no /$plan" }
            .reader(Charsets.UTF_8)
            .use { it.readText() }

        val emitted = readPlan(read).asKotlin(packageName = into, from = plan)
        val path = Path.of("$SOURCES/$source")

        if (System.getProperty("kestrel.regenerate").toBoolean()) {
            Files.createDirectories(path.parent)
            Files.writeString(path, emitted, Charsets.UTF_8)
            error("rewrote $path. Re-run without -Dkestrel.regenerate and read the diff.")
        }

        withClue("the compiler has already agreed this file is Kotlin; this is that it is still the emitter's") {
            Files.readString(path, Charsets.UTF_8) shouldBe emitted
        }
    }

    private companion object {
        const val PACKAGE = "io.github.matthewjones372.kestrel.examples"
        const val SOURCES = "src/main/kotlin/io/github/matthewjones372/kestrel/examples"
    }
}
