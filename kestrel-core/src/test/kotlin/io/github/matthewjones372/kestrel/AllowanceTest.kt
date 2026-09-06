package io.github.matthewjones372.kestrel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

/**
 * What a run on this machine may do. A fence somebody committed, not a sandbox:
 * it stops a mistake, and the docs say so where anyone would assume otherwise.
 */
class AllowanceTest {

    @Test
    fun `no file means nothing is bounded`(@TempDir dir: Path) {
        val absent = Allowance.fromFile(dir.resolve("kestrel.toml"))

        withClue("failing closed on a machine with no config trains people to delete the config") {
            absent shouldBe Allowance.none
            absent.maxRate.shouldBeNull()
            absent.hosts shouldBe emptyList()
        }
    }

    @Test
    fun `the four keys are read`() {
        val read = Allowance.read(
            """
            # what a run on this machine may do
            hosts       = ["localhost", "*.staging.internal"]
            maxRate     = "500/s"
            maxDuration = "10m"
            maxRequests = 2_000_000
            """.trimIndent(),
        )

        read.hosts shouldBe listOf("localhost", "*.staging.internal")
        read.maxRate shouldBe 500.perSecond
        read.maxDuration shouldBe 10.minutes
        read.maxRequests shouldBe 2_000_000L
    }

    @Test
    fun `a key nobody knows names itself and its line`() {
        val thrown = shouldThrow<IllegalArgumentException> {
            Allowance.read("maxRate = \"500/s\"\nmaxHosts = 3\n")
        }

        withClue(thrown.message.orEmpty()) {
            thrown.message.orEmpty() shouldContain "maxHosts"
            thrown.message.orEmpty() shouldContain "line 2"
        }
    }

    @Test
    fun `a rate nobody can read names its line rather than defaulting`() {
        val thrown = shouldThrow<IllegalArgumentException> { Allowance.read("maxRate = \"quite fast\"\n") }

        withClue(thrown.message.orEmpty()) {
            thrown.message.orEmpty() shouldContain "line 1"
        }
    }

    @Test
    fun `an exact host is allowed and its neighbours are not`() {
        val allowance = Allowance.read("""hosts = ["localhost"]""")

        allowance.allows("localhost") shouldBe true
        allowance.allows("orders.internal") shouldBe false
    }

    @Test
    fun `a wildcard covers a subdomain and not the domain itself`() {
        val allowance = Allowance.read("""hosts = ["*.staging.internal"]""")

        allowance.allows("orders.staging.internal") shouldBe true
        withClue("a pattern for the children is not a pattern for the parent") {
            allowance.allows("staging.internal") shouldBe false
        }
        allowance.allows("orders.prod.internal") shouldBe false
    }

    @Test
    fun `an empty host list allows every host`() {
        withClue("an unset key is unlimited, and an empty list is an unset key") {
            Allowance.none.allows("anything.at.all") shouldBe true
        }
    }

    @Test
    fun `a refusal names the number to come down to`() {
        val refusal = Refusal.OverRate(asked = 500.perSecond, allowed = 20.perSecond)

        withClue("a refusal that names only the problem is one somebody guesses at twice") {
            refusal.described shouldContain "500"
            refusal.described shouldContain "20"
        }
    }

    @Test
    fun `a host refusal says what would have been allowed`() {
        val refusal = Refusal.HostNotAllowed("orders.prod.internal", listOf("localhost", "*.staging.internal"))

        refusal.described shouldContain "*.staging.internal"
    }

    @Test
    fun `a file is read the same way the text is`(@TempDir dir: Path) {
        val path = dir.resolve("kestrel.toml")
        Files.writeString(path, "maxRate = \"20/s\"\n")

        Allowance.fromFile(path) shouldBe Allowance.read("maxRate = \"20/s\"")
    }
}
