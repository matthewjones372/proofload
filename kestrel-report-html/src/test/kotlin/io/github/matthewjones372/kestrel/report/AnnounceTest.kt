package io.github.matthewjones372.kestrel.report

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * A run ends with a page somebody is meant to open, so writing one says where
 * it went.
 */
class AnnounceTest {

    private fun printedBy(block: () -> Unit): String {
        val captured = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(captured, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return captured.toString(Charsets.UTF_8)
    }

    @Test
    fun `writing a run's report says where it went, as a link`(@TempDir dir: Path) {
        val path = dir.resolve("run.html")

        val printed = printedBy { Fixtures.fellBehind.writeHtmlReport(path) }

        printed shouldContain "kestrel: report at "
        withClue("the URI a terminal can open, rather than the argument as given") {
            printed shouldContain path.toAbsolutePath().normalize().toUri().toString()
        }
    }

    @Test
    fun `a capacity page says the same thing`(@TempDir dir: Path) {
        val path = dir.resolve("capacity.html")

        val printed = printedBy { Fixtures.capacity.writeHtmlReport(path) }

        printed shouldContain path.toAbsolutePath().normalize().toUri().toString()
    }

    @Test
    fun `a path pointing through a parent is printed normalised, not as it was written`(@TempDir dir: Path) {
        Files.createDirectories(dir.resolve("nested"))
        val path = dir.resolve("nested/../run.html")

        val printed = printedBy { Fixtures.fellBehind.writeHtmlReport(path) }

        withClue("normalised, so no reader has to resolve `..` themselves: $printed") {
            printed.contains("/..") shouldBe false
        }
        printed shouldContain "file:"
    }
}
