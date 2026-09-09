package io.github.matthewjones372.proofload

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

private fun Path.holding(name: String, text: String): Path = Files.writeString(resolve(name), text)

class CsvTest {

    @Test
    fun `the header names the columns and is never row zero`(@TempDir dir: Path) {
        val accounts = csv(dir.holding("accounts.csv", "id,tier\n7,gold\n8,silver\n"))

        accounts.columns shouldContainExactly listOf("id", "tier")
        accounts.rows shouldBe 2
        accounts.column("id") shouldContainExactly listOf("7", "8")
        accounts.column("tier") shouldContainExactly listOf("gold", "silver")
    }

    @Test
    fun `a quoted field containing a comma is one field`(@TempDir dir: Path) {
        val accounts = csv(dir.holding("accounts.csv", "id,name\n7,\"Jones, Matthew\"\n"))

        accounts.rows shouldBe 1
        accounts.column("name") shouldContainExactly listOf("Jones, Matthew")
    }

    @Test
    fun `a doubled quote inside a quoted field is one quote`(@TempDir dir: Path) {
        val accounts = csv(dir.holding("accounts.csv", "id,name\n7,\"say \"\"hello\"\" twice\"\n"))

        accounts.column("name") shouldContainExactly listOf("say \"hello\" twice")
    }

    @Test
    fun `a field between two commas is empty rather than missing`(@TempDir dir: Path) {
        val accounts = csv(dir.holding("accounts.csv", "id,tier,region\n7,,eu-west\n"))

        accounts.column("tier") shouldContainExactly listOf("")
    }

    @Test
    fun `an empty file is refused by name`(@TempDir dir: Path) {
        val thrown = shouldThrow<IllegalArgumentException> { csv(dir.holding("nothing.csv", "")) }

        thrown.message.toString() shouldContain "nothing.csv"
    }

    @Test
    fun `a header with nothing under it is a file of no rows, not a file of one`(@TempDir dir: Path) {
        val accounts = csv(dir.holding("accounts.csv", "id,tier\n"))

        accounts.columns shouldContainExactly listOf("id", "tier")
        accounts.rows shouldBe 0
    }

    @Test
    fun `a blank line at the end is not a row of empty fields`(@TempDir dir: Path) {
        val accounts = csv(dir.holding("accounts.csv", "id,tier\n7,gold\n\n"))

        accounts.rows shouldBe 1
    }

    @Test
    fun `a column the header never named is absent rather than empty`(@TempDir dir: Path) {
        csv(dir.holding("accounts.csv", "id,tier\n7,gold\n")).column("customer").shouldBeNull()
    }

    @Test
    fun `a row wider or narrower than the header is refused by name`(@TempDir dir: Path) {
        val thrown = shouldThrow<IllegalArgumentException> {
            csv(dir.holding("ragged.csv", "id,tier\n7,gold,extra\n"))
        }

        thrown.message.toString() shouldContain "ragged.csv"
    }

    @Test
    fun `a header that names one column twice is refused, since the second would hide the first`(
        @TempDir dir: Path,
    ) {
        val thrown = shouldThrow<IllegalArgumentException> {
            csv(dir.holding("twice.csv", "id,tier,id\n7,gold,8\n"))
        }

        thrown.message.toString() shouldContain "twice.csv"
    }

    @Test
    fun `the same file read twice is the same value`(@TempDir dir: Path) {
        val file = dir.holding("accounts.csv", "id,tier\n7,gold\n")

        csv(file) shouldBe csv(file)
    }
}
