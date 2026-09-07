package io.github.matthewjones372.kestrel.grpc.dynamic

import io.github.matthewjones372.kestrel.Progress
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.engine.run
import io.github.matthewjones372.kestrel.grpc.grpc
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A target that will describe itself needs nothing from the caller, which is
 * why this is the way in rather than the fallback.
 *
 * Judged against gRPC's own reflection service on the other end. A second
 * hand-written implementation would be a test that agrees with this client
 * about a wire format they could both have wrong.
 */
class ReflectedTest {

    @Test
    fun `a target serving v1 needs no descriptor set`() {
        ShopServer(describing = ShopServer.Describing.V1).use { server ->
            val schema = grpc.over(server.channel()).reflected()

            withClue("the reflection endpoint itself is left out: a plan calling it benchmarks gRPC, not a service") {
                schema.methods shouldContainExactly listOf("shop.Orders/GetOrder", "shop.Orders/PlaceOrder")
            }
        }
    }

    @Test
    fun `a target serving only v1alpha is described too, not reported as having reflection off`() {
        ShopServer(describing = ShopServer.Describing.V1Alpha).use { server ->
            val schema = grpc.over(server.channel()).reflected()

            withClue("saying `reflection is off` here sends somebody to change a setting that is already right") {
                schema.methods shouldContainExactly listOf("shop.Orders/GetOrder", "shop.Orders/PlaceOrder")
            }
        }
    }

    @Test
    fun `a target with reflection off says so, and says what to do instead`() {
        ShopServer(describing = ShopServer.Describing.Nothing).use { server ->
            val thrown = shouldThrow<IllegalArgumentException> {
                grpc.over(server.channel()).reflected(within = 2.seconds)
            }

            withClue(thrown.message.orEmpty()) {
                thrown.message.orEmpty() shouldContain "v1alpha"
                thrown.message.orEmpty() shouldContain "protoc --descriptor_set_out"
            }
        }
    }

    @Test
    fun `a schema fetched from the target calls the same as one read from a file`() {
        ShopServer(describing = ShopServer.Describing.V1).use { server ->
            val service = grpc.over(server.channel())
            val call = service.call(service.reflected(), "shop.Orders/PlaceOrder", """{"cart": "1 anvil"}""")

            val result = scenario("orders") { exec(call) }
                .at(20.perSecond, over = 250.milliseconds)
                .run(Progress.silent)

            withClue("where the descriptors came from is not something a run should be able to tell") {
                result["shop.Orders/PlaceOrder"].failed.count shouldBe 0L
            }
        }
    }
}
