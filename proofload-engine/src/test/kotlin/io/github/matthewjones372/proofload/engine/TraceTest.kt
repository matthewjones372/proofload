package io.github.matthewjones372.proofload.engine

import io.github.matthewjones372.proofload.feed
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.sessionKey
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

private val email = sessionKey<String>("email")

class TraceTest {

    @Test
    fun `a three-step scenario prints a line per step, in the order the user walked them`() {
        val walked = mutableListOf<String>()
        val checkout = scenario("checkout") {
            exec("browse") { walked += "browse" }
            exec("place order") { walked += "place order" }
            exec("pay") { walked += "pay" }
        }

        val printed = printedBy { checkout.trace() }

        printed shouldContainExactly listOf(
            "proofload: trace checkout",
            "proofload:   browse       ok",
            "proofload:   place order  ok",
            "proofload:   pay          ok",
        )
        withClue("one user, one pass") {
            walked shouldContainExactly listOf("browse", "place order", "pay")
        }
    }

    @Test
    fun `a failing step prints the reason it gave, and the steps after it are not walked`() {
        val checkout = scenario("checkout") {
            exec("browse") { }
            exec("pay") { fail("status 503") }
            exec("confirm") { }
        }

        val printed = printedBy { checkout.trace() }

        printed shouldContainExactly listOf(
            "proofload: trace checkout",
            "proofload:   browse   ok",
            "proofload:   pay      FAILED status 503 — user abandoned here",
        )
    }

    @Test
    fun `a traced user starts with what a feeder would have given a real one`() {
        val sending = scenario("sending") {
            exec("send") { if (get(email) != "user0@example.com") fail("no email") }
        }

        val printed = printedBy { sending.trace(feed(email) { user -> "user$user@example.com" }) }

        printed shouldContainExactly listOf("proofload: trace sending", "proofload:   send  ok")
    }

    @Test
    fun `a trace leaves nothing behind that a report could be made from`() {
        val proofload = Proofload()

        printedBy { proofload.trace(scenario("checkout") { exec("browse") { } }) }

        proofload.summary() shouldBe null
    }

    @Test
    fun `a step that says what it did has those lines printed under it`() {
        val checkout = scenario("checkout") {
            exec("browse") {
                note("GET https://orders.internal/products")
                note("< 200")
            }
        }

        val printed = printedBy { checkout.trace() }

        printed shouldContainExactly listOf(
            "proofload: trace checkout",
            "proofload:   browse  ok",
            "proofload:           GET https://orders.internal/products",
            "proofload:           < 200",
        )
    }

    @Test
    fun `a step that says nothing prints only its line`() {
        val checkout = scenario("checkout") { exec("browse") { } }

        printedBy { checkout.trace() } shouldContainExactly listOf(
            "proofload: trace checkout",
            "proofload:   browse  ok",
        )
    }
}

private fun printedBy(block: () -> Unit): List<String> {
    val captured = ByteArrayOutputStream()
    val original = System.out
    System.setOut(PrintStream(captured, true, Charsets.UTF_8))
    try {
        block()
    } finally {
        System.setOut(original)
    }
    return captured.toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }
}
