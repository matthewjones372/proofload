package io.github.matthewjones372.proofload.mcp

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.util.jar.JarFile

/**
 * Which main a launcher starts when it is handed this module's coordinate and
 * nothing else — the command `docs/mcp.md` tells a reader to run.
 *
 * Coursier reads `Main-Class` from every jar it resolved and keys each one by
 * (`Implementation-Vendor-Id`, `Specification-Title`), both defaulting to the
 * empty string. `proofload-mcp` and `proofload-cli` both carry a main, so two
 * unnamed manifests key alike, the map keeps one of them, and
 * `cs launch io.github.matthewjones372:proofload-mcp:0.1.0-rc4` started the
 * command line: usage text and exit code 4 where a client expected JSON-RPC.
 */
class LaunchingTest {

    private val server = "io.github.matthewjones372.proofload.mcp.ServerKt"
    private val coordinate = Coordinate("io.github.matthewjones372", "proofload-mcp")

    @Test
    fun `every jar that carries a main names the coordinate it came from`() {
        val unnamed = mains()
            .filter { (named, _) -> named.vendor.isEmpty() || named.title.isEmpty() }
            .map { (_, main) -> main }

        withClue("a launcher tells two mains apart by the coordinate each names, but these name none: $unnamed") {
            unnamed.shouldBeEmpty()
        }
    }

    @Test
    fun `no two of them name the same coordinate`() {
        val shared = mains().groupBy { (named, _) -> named }.filterValues { it.size > 1 }.keys

        withClue("two jars keying alike is what makes a launcher keep whichever it saw last: $shared") {
            shared.shouldBeEmpty()
        }
    }

    @Test
    fun `the main a launcher picks for this coordinate is the server, not the command line`() {
        withClue("`cs launch ${coordinate.vendor}:${coordinate.title}:VERSION` with no `-M` starts this") {
            retainedMain() shouldBe server
        }
    }

    /** The pair a launcher keys a main by. */
    private data class Coordinate(val vendor: String, val title: String)

    /**
     * `coursier.install.MainClass.retainedMainClassOpt`, over the classpath this
     * module's coordinate resolves to. Written out rather than reached for,
     * because depending on a launcher to test what it does with a jar would tie
     * this build to its release train for four lines of matching.
     */
    private fun retainedMain(): String? {
        val mains = mains().toMap()
        if (mains.size == 1) return mains.values.first()

        val named = mains.entries.firstOrNull { (key, _) ->
            key.vendor == coordinate.vendor &&
                (key.title == coordinate.title || key.title.startsWith("${coordinate.title}_"))
        }
        if (named != null) return named.value

        return mains.filterKeys { it.vendor == coordinate.vendor }.values.toSet().singleOrNull()
    }

    /** Kept as pairs rather than a map: two jars keying alike is the thing being asserted. */
    private fun mains(): List<Pair<Coordinate, String>> =
        launchClasspath().mapNotNull { jar ->
            JarFile(jar).use { open ->
                val attributes = open.manifest?.mainAttributes ?: return@use null
                val main = attributes.getValue("Main-Class") ?: return@use null
                Coordinate(
                    attributes.getValue("Implementation-Vendor-Id").orEmpty(),
                    attributes.getValue("Specification-Title").orEmpty(),
                ) to main
            }
        }

    /** This module's own jar and what it resolves, which is what a launcher puts on the classpath. */
    private fun launchClasspath(): List<File> {
        val own = System.getProperty("proofload.mcp.jar")
        val resolved = System.getProperty("proofload.mcp.runtimeClasspath")
        withClue("the build must pass -Dproofload.mcp.jar and -Dproofload.mcp.runtimeClasspath; see build.gradle.kts") {
            own.shouldNotBeNull()
            resolved.shouldNotBeNull()
        }

        return (listOf(own!!) + resolved!!.split(File.pathSeparator))
            .filter { it.isNotBlank() }
            .map(::File)
            .filter { it.isFile && it.extension == "jar" }
    }
}
