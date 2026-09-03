package io.github.matthewjones372.kestrel.record

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * This module carries a JSON parser on purpose — a HAR is arbitrary JSON out of
 * a browser, and the `V2Encoding.kt` precedent for hand-rolling is a few hundred
 * bytes of a written-down binary format rather than this.
 *
 * What matters is that nobody else pays for it. It runs before a run rather
 * than during one, and no module here depends on it: a consumer taking
 * kestrel-http does not get a JSON parser because a recorder wanted one.
 */
class NoThirdPartyDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "kestrel-core", "kotlinx-serialization")

    private fun classpath(): List<String> {
        val raw = System.getProperty("kestrel.record.runtimeClasspath")
        withClue("the build must pass -Dkestrel.record.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }
        return raw!!.split(File.pathSeparator).filter { it.isNotBlank() }
    }

    @Test
    fun `what is here is kestrel-core, the Kotlin standard library and the JSON parser it exists to use`() {
        val unexpected = classpath().filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("kestrel-record grew a dependency beyond the parser it carries: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }

    @Test
    fun `kestrel-core is on the runtime classpath`() {
        classpath().filter { it.startsWith("kestrel-core") }.shouldNotBeEmpty()
    }

    @Test
    fun `no module depends on this one, so nobody else pays for the parser`() {
        val root = Path.of("..").toAbsolutePath().normalize()
        val declaring = Files.list(root).use { entries ->
            entries.filter { it.fileName.toString().startsWith("kestrel-") }
                .filter { it.fileName.toString() != "kestrel-record" }
                .map { it.resolve("build.gradle.kts") }
                .filter { Files.exists(it) }
                .filter { Files.readString(it).contains("kestrel-record") }
                .map { root.relativize(it).toString() }
                .toList()
        }

        withClue("a tool that runs before a run has no business on anybody's classpath: $declaring") {
            declaring.shouldBeEmpty()
        }
    }
}
