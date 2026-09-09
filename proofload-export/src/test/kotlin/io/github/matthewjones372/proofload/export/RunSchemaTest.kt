package io.github.matthewjones372.proofload.export

import com.networknt.schema.InputFormat
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The schema is the contract, so it is checked against the documents this
 * module actually writes rather than reviewed by eye.
 *
 * `additionalProperties` is false throughout, which makes an undeclared field a
 * failure here. That is the producer's half of the promise; the reader's half —
 * ignore what you do not know — is stated on the schema itself and is why a new
 * optional field is not a breaking change.
 */
class RunSchemaTest {

    private val schema = JsonSchemaFactory
        .getInstance(SpecVersion.VersionFlag.V202012)
        .getSchema(Files.readString(SCHEMA, Charsets.UTF_8))

    @Test
    fun `the summary is what the schema says a summary is`() {
        schema.validate(Golden.text("run-summary.json"), InputFormat.JSON).shouldBeEmpty()
    }

    @Test
    fun `the full document is what the schema says one is`() {
        schema.validate(Golden.text("run-full.json"), InputFormat.JSON).shouldBeEmpty()
    }

    @Test
    fun `a document with a field nobody declared is refused`() {
        val undeclared = Golden.text("run-summary.json").replaceFirst("{", """{"invented": 1,""")

        withClue("this is the gate: emitting a field without declaring it fails the build") {
            schema.validate(undeclared, InputFormat.JSON).map { it.message }.size shouldBe 1
        }
    }

    @Test
    fun `a document claiming another version is not one of these`() {
        val wrong = Golden.text("run-summary.json").replaceFirst("proofload/run/1", "proofload/run/2")

        withClue("the version is pinned by the schema, not merely described by it") {
            schema.validate(wrong, InputFormat.JSON).isEmpty() shouldBe false
        }
    }

    private companion object {
        val SCHEMA: Path = Path.of("..", "docs", "schemas", "run-1.json")
    }
}
