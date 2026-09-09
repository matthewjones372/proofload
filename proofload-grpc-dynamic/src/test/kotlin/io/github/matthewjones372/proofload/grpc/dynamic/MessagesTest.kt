package io.github.matthewjones372.proofload.grpc.dynamic

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * A plan carries JSON because that is what `grpcurl` takes and what somebody
 * can write without a compiler. What it buys is convenience, and what it costs
 * is the guarantee 0071 exists for — so everything the schema can still catch
 * is caught here, before a request goes anywhere.
 */
class MessagesTest {

    private val order = descriptorSet(Shop.descriptorSet).method("shop.Orders/PlaceOrder").inputType

    @Test
    fun `json a plan wrote becomes the message the method declares`() {
        val message = order.messageFrom("""{"cart": "1 anvil", "quantity": 2}""")

        message.descriptorForType.fullName shouldBe "shop.Order"
        message.getField(order.findFieldByName("cart")) shouldBe "1 anvil"
        message.getField(order.findFieldByName("quantity")) shouldBe 2
    }

    @Test
    fun `a field the schema does not declare is refused, naming it`() {
        val thrown = shouldThrow<IllegalArgumentException> {
            order.messageFrom("""{"cart": "1 anvil", "cartt": "typo"}""")
        }

        withClue("this is the drift the typed path exists to stop, caught as early as a file can catch it") {
            thrown.message.orEmpty() shouldContain "cartt"
            thrown.message.orEmpty() shouldContain "shop.Order"
        }
    }

    @Test
    fun `a value of the wrong type is refused before the call, not encoded as something else`() {
        val thrown = shouldThrow<IllegalArgumentException> {
            order.messageFrom("""{"quantity": "two"}""")
        }

        thrown.message.orEmpty() shouldContain "quantity"
    }

    @Test
    fun `something that is not json at all says so`() {
        val thrown = shouldThrow<IllegalArgumentException> { order.messageFrom("cart: 1 anvil") }

        withClue(thrown.message.orEmpty()) { thrown.message.orEmpty() shouldContain "shop.Order" }
    }

    @Test
    fun `an empty body is the message with nothing set, which is a legal request`() {
        withClue("a method taking no arguments is a request of `{}`, and refusing it would be wrong") {
            order.messageFrom("{}").allFields.isEmpty() shouldBe true
        }
    }

    @Test
    fun `an answer comes back as the json a reader can read`() {
        val confirmation = descriptorSet(Shop.descriptorSet).method("shop.Orders/PlaceOrder").outputType
        val answer = confirmation.messageFrom("""{"id": "order-1"}""")

        withClue("a trace prints this, so it is one line rather than protobuf's own multi-line text format") {
            answer.asJson() shouldBe """{"id":"order-1"}"""
        }
    }
}
