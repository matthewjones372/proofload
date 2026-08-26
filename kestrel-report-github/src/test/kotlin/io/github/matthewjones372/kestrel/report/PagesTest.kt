package io.github.matthewjones372.kestrel.report

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant

class PagesTest {

    private fun Path.report(name: String, at: String): Path {
        val file = resolve(name)
        Files.writeString(file, "<!doctype html><title>$name</title>")
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse(at)))
        return file
    }

    @Test
    fun `an index lists the reports beside it, newest first`(@TempDir dir: Path) {
        dir.report("monday.html", "2026-08-24T09:00:00Z")
        dir.report("wednesday.html", "2026-08-26T09:00:00Z")
        dir.report("tuesday.html", "2026-08-25T09:00:00Z")

        val index = writePagesIndex(dir)

        val order = Regex("""href="([^"]+)"""").findAll(Files.readString(index)).map { it.groupValues[1] }
        order.toList() shouldContainExactly listOf("wednesday.html", "tuesday.html", "monday.html")
    }

    @Test
    fun `the index is rebuilt, so a deleted report leaves no dead link`(@TempDir dir: Path) {
        val gone = dir.report("gone.html", "2026-08-24T09:00:00Z")
        dir.report("kept.html", "2026-08-26T09:00:00Z")
        writePagesIndex(dir)

        Files.delete(gone)
        val index = writePagesIndex(dir)

        Files.readString(index) shouldContain "kept.html"
        Files.readString(index) shouldNotContain "gone.html"
    }

    @Test
    fun `the index never lists itself`(@TempDir dir: Path) {
        dir.report("run.html", "2026-08-26T09:00:00Z")

        val index = writePagesIndex(dir)

        Files.readString(index) shouldNotContain """href="index.html""""
        index.fileName.toString() shouldBe "index.html"
    }

    @Test
    fun `an empty directory gets an index that says so rather than no index at all`(@TempDir dir: Path) {
        val index = writePagesIndex(dir)

        Files.readString(index) shouldContain "No reports"
    }

    @Test
    fun `a report named with markup cannot inject any into the index`(@TempDir dir: Path) {
        dir.report("""<img src=x onerror=alert(1)>.html""", "2026-08-26T09:00:00Z")

        Files.readString(writePagesIndex(dir)) shouldNotContain "<img"
    }

    @Test
    fun `the index is a page of its own, needing nothing fetched`(@TempDir dir: Path) {
        dir.report("run.html", "2026-08-26T09:00:00Z")

        val page = Files.readString(writePagesIndex(dir))

        page shouldContain "<!doctype html>"
        page shouldNotContain "http://"
        page shouldNotContain "https://"
    }
}
