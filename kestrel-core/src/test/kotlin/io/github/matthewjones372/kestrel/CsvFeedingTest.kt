package io.github.matthewjones372.kestrel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

private val customer = sessionKey<String>("customer")
private val tier = sessionKey<String>("tier")
private val balance = sessionKey<Long>("balance")

private fun Path.accounts(text: String): Path = Files.writeString(resolve("accounts.csv"), text)

class CsvFeedingTest {

    @Test
    fun `two keys fill from the two columns of the same name`(@TempDir dir: Path) {
        val accounts = csv(dir.accounts("customer,tier\nc-1,gold\nc-2,silver\n"))

        val users = accounts.feeding(customer, tier)

        users.forUser(0)[customer] shouldBe "c-1"
        users.forUser(0)[tier] shouldBe "gold"
        users.forUser(1)[customer] shouldBe "c-2"
        users.forUser(1)[tier] shouldBe "silver"
    }

    @Test
    fun `a key naming a column the file has not is refused before the run, naming both`(@TempDir dir: Path) {
        val accounts = csv(dir.accounts("customer,tier\nc-1,gold\n"))
        val missing = sessionKey<String>("account_id")

        val thrown = shouldThrow<IllegalArgumentException> { accounts.feeding(customer, missing) }

        thrown.message.toString() shouldContain "account_id"
        thrown.message.toString() shouldContain "customer"
        thrown.message.toString() shouldContain "tier"
    }

    @Test
    fun `the conversion overload fills a key whose type is not text`(@TempDir dir: Path) {
        val accounts = csv(dir.accounts("balance\n1200\n7\n"))

        val users = accounts.feeding(balance) { it.toLong() }

        users.forUser(0)[balance] shouldBe 1_200L
        users.forUser(1)[balance] shouldBe 7L
    }

    @Test
    fun `the conversion overload refuses a column the file has not, before the run`(@TempDir dir: Path) {
        val accounts = csv(dir.accounts("customer\nc-1\n"))

        val thrown = shouldThrow<IllegalArgumentException> { accounts.feeding(balance) { it.toLong() } }

        thrown.message.toString() shouldContain "balance"
        thrown.message.toString() shouldContain "customer"
    }

    @Test
    fun `a file wraps round at the end rather than running out`(@TempDir dir: Path) {
        val users = csv(dir.accounts("customer\nc-1\nc-2\n")).feeding(customer)

        users.forUser(2)[customer] shouldBe "c-1"
        users.forUser(1_000_001)[customer] shouldBe "c-2"
    }

    @Test
    fun `the same user gets the same row twice, so a failure can be looked at`(@TempDir dir: Path) {
        val users = csv(dir.accounts("customer,tier\nc-1,gold\nc-2,silver\n")).feeding(customer, tier)

        users.forUser(93) shouldBe users.forUser(93)
    }

    @Test
    fun `a header with nothing under it feeds nothing rather than dividing by zero`(@TempDir dir: Path) {
        csv(dir.accounts("customer,tier\n")).feeding(customer, tier).forUser(3) shouldBe Session.empty
    }

    @Test
    fun `a fed key can be overridden by a later feeder, as an override reads everywhere else`(@TempDir dir: Path) {
        val users = csv(dir.accounts("tier\ngold\n")).feeding(tier) + feedFrom(tier, listOf("platinum"))

        users.forUser(0)[tier] shouldBe "platinum"
    }
}
