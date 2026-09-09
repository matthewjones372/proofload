package io.github.matthewjones372.proofload.export

import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * A checked-in expected output, read as bytes rather than rebuilt in the test.
 *
 * `-Dproofload.regenerate=true` rewrites it from the run and fails, so a change
 * to the exposition is a diff to read rather than a file to hand-edit.
 */
internal object Golden {

    fun text(name: String): String {
        val stream = requireNotNull(javaClass.getResourceAsStream("/golden/$name")) {
            "no golden file at proofload-export/src/test/resources/golden/$name"
        }
        return stream.reader(Charsets.UTF_8).use { it.readText() }
    }

    fun regenerate(name: String, actual: String): Nothing {
        val path = Path.of("src/test/resources/golden", name).toAbsolutePath()
        Files.writeString(path, actual, Charsets.UTF_8)
        error("rewrote $path from this run. Re-run without -Dproofload.regenerate and read the diff.")
    }

    val regenerating: Boolean get() = System.getProperty("proofload.regenerate").toBoolean()
}

internal infix fun String.matches(name: String) {
    if (Golden.regenerating) Golden.regenerate(name, this)
    this shouldBe Golden.text(name)
}
