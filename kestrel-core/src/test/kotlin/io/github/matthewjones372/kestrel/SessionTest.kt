package io.github.matthewjones372.kestrel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SessionTest {

    @Test
    fun `an empty session holds nothing`() {
        Session.empty["cart"] shouldBe null
    }

    @Test
    fun `setting a key returns a new session and leaves the old one alone`() {
        val before = Session.empty
        val after = before.set("cart", "empty")

        after["cart"] shouldBe "empty"
        before["cart"] shouldBe null
    }

    @Test
    fun `sessions carrying the same values are equal`() {
        Session.empty.set("cart", "empty") shouldBe Session.empty.set("cart", "empty")
    }

    @Test
    fun `an action reports success as a value rather than by not throwing`() {
        val result = Action { session -> session.set("id", 1L).ok() }.run(Session.empty)

        result shouldBe StepResult.Ok(Session.empty.set("id", 1L))
    }

    @Test
    fun `a failed step names its reason and still carries the session on`() {
        val result = Session.empty.set("id", 1L).failed("status 503")

        result shouldBe StepResult.Failed(Session.empty.set("id", 1L), "status 503")
        result.session["id"] shouldBe 1L
    }
}
