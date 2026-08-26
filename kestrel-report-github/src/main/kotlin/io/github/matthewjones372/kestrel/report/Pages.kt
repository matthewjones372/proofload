package io.github.matthewjones372.kestrel.report

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.name

/**
 * Write an `index.html` listing every report in [directory], newest first.
 *
 * Publishing a run's history is then a workflow step — copy the reports into
 * one directory, call this, upload the directory — rather than a feature with
 * a token and an API behind it.
 *
 * The index is rebuilt from what is actually on disk each time rather than
 * appended to, so a report someone deleted cannot leave a link that 404s.
 */
fun writePagesIndex(directory: Path): Path {
    Files.createDirectories(directory)
    val reports = Files.list(directory).use { entries ->
        entries.toList()
            .filter { it.name.endsWith(HTML) && it.name != INDEX }
            .sortedWith(compareByDescending<Path> { Files.getLastModifiedTime(it) }.thenBy { it.name })
    }
    return Files.writeString(directory.resolve(INDEX), page(reports.map(::entryOf)))
}

private fun entryOf(report: Path): Entry =
    Entry(name = report.name, written = Files.getLastModifiedTime(report).toInstant())

private data class Entry(val name: String, val written: Instant)

private fun page(entries: List<Entry>): String =
    (
        listOf(
            "<!doctype html>",
            """<html lang="en">""",
            "<head>",
            """<meta charset="utf-8">""",
            """<meta name="viewport" content="width=device-width, initial-scale=1">""",
            "<title>Kestrel runs</title>",
            "<style>",
        ) + INDEX_CSS.lines() + listOf(
            "</style>",
            "</head>",
            "<body>",
            "<main>",
            "  <h1>Kestrel runs</h1>",
        ) + body(entries) + listOf(
            "</main>",
            "</body>",
            "</html>",
        )
        ).joinToString(separator = "\n", postfix = "\n")

private fun body(entries: List<Entry>): List<String> =
    if (entries.isEmpty()) listOf("""  <p class="empty">No reports here yet.</p>""")
    else listOf("""  <ul class="runs">""") + entries.flatMap { it.lines() } + listOf("  </ul>")

private fun Entry.lines(): List<String> = listOf(
    "    <li>",
    // A file name comes off a disk, and a disk will hold whatever somebody
    // put there. Escaped in the attribute and in the text.
    """      <a href="${name.escapedForHtml()}">${name.escapedForHtml()}</a>""",
    """      <time datetime="$written">${written.readable()}</time>""",
    "    </li>",
)

private fun Instant.readable(): String = WRITTEN_AT.format(atOffset(ZoneOffset.UTC))

internal fun String.escapedForHtml(): String = map(::htmlEscaped).joinToString(separator = "")

private fun htmlEscaped(char: Char): String = when (char) {
    '&' -> "&amp;"
    '<' -> "&lt;"
    '>' -> "&gt;"
    '"' -> "&quot;"
    '\'' -> "&#39;"
    else -> char.toString()
}

private val WRITTEN_AT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")

private const val INDEX = "index.html"
private const val HTML = ".html"

private val INDEX_CSS = """
    :root { color-scheme: light dark; }
    body { margin: 0; font: 15px/1.5 ui-sans-serif, -apple-system, "Segoe UI", Roboto, Helvetica, sans-serif; }
    main { max-width: 48rem; margin: 0 auto; padding: 2rem 1.25rem; }
    h1 { font-size: 1.4rem; }
    ul.runs { list-style: none; margin: 0; padding: 0; }
    ul.runs li { display: flex; justify-content: space-between; gap: 1rem; padding: 0.45rem 0; border-bottom: 1px solid; }
    ul.runs li { border-color: color-mix(in srgb, currentColor 15%, transparent); }
    time { opacity: 0.65; font-variant-numeric: tabular-nums; white-space: nowrap; }
    .empty { opacity: 0.65; }
""".trimIndent()
