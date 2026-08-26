package io.github.matthewjones372.kestrel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private val cart = sessionKey<String>("cart")
private val orderId = sessionKey<Long>("orderId")

class SessionTest {

    @Test
    fun `an empty session holds nothing`() {
        Session.empty[cart] shouldBe null
    }

    @Test
    fun `a key hands back the type it was declared with`() {
        val session = Session.empty.set(orderId, 7L)

        val id: Long? = session[orderId]
        id shouldBe 7L
    }

    @Test
    fun `setting a key returns a new session and leaves the old one alone`() {
        val before = Session.empty
        val after = before.set(cart, "empty")

        after[cart] shouldBe "empty"
        before[cart] shouldBe null
    }

    @Test
    fun `sessions carrying the same values are equal`() {
        Session.empty.set(cart, "empty") shouldBe Session.empty.set(cart, "empty")
    }

    @Test
    fun `two keys of the same name and type are the same key`() {
        sessionKey<String>("cart") shouldBe cart
        Session.empty.set(cart, "full")[sessionKey<String>("cart")] shouldBe "full"
    }

    @Test
    fun `a key that shadows another name with a different type is a bug, and says so`() {
        val session = Session.empty.set(cart, "empty")

        shouldThrow<IllegalStateException> { session[sessionKey<Long>("cart")] }
    }

    @Test
    fun `an action reports success as a value rather than by not throwing`() {
        val result = Action { session -> session.set(orderId, 1L).ok() }.run(Session.empty)

        result shouldBe StepResult.Ok(Session.empty.set(orderId, 1L))
    }

    @Test
    fun `a failed step names its reason and still carries the session on`() {
        val result = Session.empty.set(orderId, 1L).failed("status 503")

        result shouldBe StepResult.Failed(Session.empty.set(orderId, 1L), "status 503")
        result.session[orderId] shouldBe 1L
    }
}
