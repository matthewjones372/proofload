package io.github.matthewjones372.kestrel

import java.nio.file.Files
import java.nio.file.Path

/**
 * A file of test data, read whole and held as a value.
 *
 * Held in memory rather than streamed: a file read between a departure and the
 * request it makes is measured as the target's latency.
 */
class CsvFile internal constructor(private val cells: Map<String, List<String>>) {

    /** The names the header row gave, in the order it gave them. */
    val columns: List<String> get() = cells.keys.toList()

    /** How many rows of data the file held. The header is not one of them. */
    val rows: Int get() = cells.values.first().size

    /** What [column] held, top to bottom, or null where the header did not name it. */
    fun column(column: String): List<String>? = cells[column]

    override fun equals(other: Any?): Boolean = other is CsvFile && other.cells == cells

    override fun hashCode(): Int = cells.hashCode()

    // The data is the value but the wrong thing to print: twelve thousand rows
    // in a failure message bury the assertion that produced it.
    override fun toString(): String = "CsvFile(columns=$columns, rows=$rows)"
}

/**
 * Reads [path] once, now, and returns what it held.
 *
 * The header row names the columns, so a file that gains one leaves the rest
 * where they were. The grammar is a deliberately small part of RFC 4180 —
 * quoted fields, doubled quotes within them, and no newline inside a field;
 * anything wider is a CSV library, and core carries no dependencies.
 */
fun csv(path: Path): CsvFile {
    // A blank line is not a row: a file that ends in a newline and a stray one
    // would otherwise hand some user a row of empty fields.
    val lines = Files.readAllLines(path).filter { it.isNotBlank() }
    require(lines.isNotEmpty()) { "a csv is a header and its rows, and $path has neither" }

    val header = fieldsIn(lines.first())
    require(header.distinct().size == header.size) {
        "$path names a column twice in its header, and the second would hide the first: $header"
    }

    val body = lines.drop(1).map(::fieldsIn)
    body.forEachIndexed { index, row ->
        require(row.size == header.size) {
            "$path has ${header.size} columns in its header but ${row.size} in row ${index + 1}: $row"
        }
    }
    return CsvFile(header.withIndex().associate { (at, name) -> name to body.map { row -> row[at] } })
}

private const val QUOTE = '"'
private const val COMMA = ','

/**
 * A line part-read. [inQuotes] is the state a comma is read in, and
 * [afterQuote] is what tells a closing quote from the first of a doubled pair.
 */
private data class Reading(
    val done: List<String> = emptyList(),
    val field: String = "",
    val inQuotes: Boolean = false,
    val afterQuote: Boolean = false,
)

private fun Reading.read(character: Char): Reading = when {
    inQuotes && character == QUOTE -> copy(inQuotes = false, afterQuote = true)
    inQuotes -> copy(field = field + character)
    afterQuote && character == QUOTE -> copy(field = field + QUOTE, inQuotes = true, afterQuote = false)
    character == QUOTE -> copy(inQuotes = true)
    character == COMMA -> Reading(done = done + field)
    else -> copy(field = field + character, afterQuote = false)
}

private fun fieldsIn(line: String): List<String> {
    val read = line.fold(Reading()) { reading, character -> reading.read(character) }
    return read.done + read.field
}
