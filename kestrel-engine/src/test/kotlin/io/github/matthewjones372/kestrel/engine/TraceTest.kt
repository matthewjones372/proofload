package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.feed
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.sessionKey
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
            "kestrel: trace checkout",
            "kestrel:   browse       ok",
            "kestrel:   place order  ok",
            "kestrel:   pay          ok",
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
            "kestrel: trace checkout",
            "kestrel:   browse   ok",
            "kestrel:   pay      FAILED status 503 — user abandoned here",
        )
    }

    @Test
    fun `a traced user starts with what a feeder would have given a real one`() {
        val sending = scenario("sending") {
            exec("send") { if (get(email) != "user0@example.com") fail("no email") }
        }

        val printed = printedBy { sending.trace(feed(email) { user -> "user$user@example.com" }) }

        printed shouldContainExactly listOf("kestrel: trace sending", "kestrel:   send  ok")
    }

    @Test
    fun `a trace leaves nothing behind that a report could be made from`() {
        val kestrel = Kestrel()

        printedBy { kestrel.trace(scenario("checkout") { exec("browse") { } }) }

        kestrel.summary() shouldBe null
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
            "kestrel: trace checkout",
            "kestrel:   browse  ok",
            "kestrel:           GET https://orders.internal/products",
            "kestrel:           < 200",
        )
    }

    @Test
    fun `a step that says nothing prints only its line`() {
        val checkout = scenario("checkout") { exec("browse") { } }

        printedBy { checkout.trace() } shouldContainExactly listOf(
            "kestrel: trace checkout",
            "kestrel:   browse  ok",
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
