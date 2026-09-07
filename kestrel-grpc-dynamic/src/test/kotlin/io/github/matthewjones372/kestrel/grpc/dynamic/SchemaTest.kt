package io.github.matthewjones372.kestrel.grpc.dynamic

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * A descriptor set is what a caller with no stubs has instead of them, and the
 * name they will write in a plan is the one gRPC puts on the wire —
 * `package.Service/Method`, exactly as `grpcurl` takes it.
 */
class SchemaTest {

    @Test
    fun `a method is found by the name a plan writes`() {
        val method = descriptorSet(Shop.descriptorSet).method("shop.Orders/PlaceOrder")

        method.name shouldBe "PlaceOrder"
        method.inputType.fullName shouldBe "shop.Order"
        method.outputType.fullName shouldBe "shop.Confirmation"
    }

    @Test
    fun `a method nobody declared names the ones there are`() {
        val thrown = shouldThrow<IllegalArgumentException> {
            descriptorSet(Shop.descriptorSet).method("shop.Orders/Cancel")
        }

        withClue("a caller who mistyped a method needs the list, not a null") {
            thrown.message.orEmpty() shouldContain "shop.Orders/Cancel"
            thrown.message.orEmpty() shouldContain "shop.Orders/PlaceOrder"
            thrown.message.orEmpty() shouldContain "shop.Orders/GetOrder"
        }
    }

    @Test
    fun `every method it holds is listed, sorted, because that list is read by somebody who mistyped one`() {
        descriptorSet(Shop.descriptorSet).methods shouldContainExactly
            listOf("shop.Orders/GetOrder", "shop.Orders/PlaceOrder")
    }

    @Test
    fun `a set whose files import one another resolves across them`() {
        val method = descriptorSet(Shop.acrossFiles).method("shop.Orders/PlaceOrder")

        withClue("--include_imports writes the imported file into the set, and it has to be linked") {
            method.inputType.fullName shouldBe "shop.Order"
        }
    }

    @Test
    fun `a set missing an import it declares says which file, rather than failing at the call`() {
        val thrown = shouldThrow<IllegalArgumentException> { descriptorSet(Shop.missingImport) }

        withClue("the fix is a protoc flag, so the message names the file that is not there") {
            thrown.message.orEmpty() shouldContain "shop/types.proto"
            thrown.message.orEmpty() shouldContain "shop/service.proto"
        }
    }

    @Test
    fun `a file that is not a descriptor set is refused as one`(@TempDir dir: Path) {
        val nonsense = dir.resolve("shop.protoset")
        Files.writeString(nonsense, "syntax = \"proto3\";\n")

        val thrown = shouldThrow<IllegalArgumentException> { descriptorSet(nonsense) }

        withClue("pointing this at a .proto instead of a .protoset is the mistake most likely to be made") {
            thrown.message.orEmpty() shouldContain nonsense.toString()
            thrown.message.orEmpty() shouldContain "protoc --descriptor_set_out"
        }
    }

    @Test
    fun `a set read from a file is the set read from its bytes`(@TempDir dir: Path) {
        val written = dir.resolve("shop.protoset")
        Files.write(written, Shop.descriptorSet)

        descriptorSet(written).methods shouldBe descriptorSet(Shop.descriptorSet).methods
    }
}
