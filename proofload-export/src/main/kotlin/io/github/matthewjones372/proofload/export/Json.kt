package io.github.matthewjones372.proofload.export

/**
 * Enough JSON to write a document by hand, indented two spaces a level so a
 * golden is read in a diff rather than on one line.
 *
 * `proofload-report-html` has its own copy of these. Sharing them would mean a
 * published JSON-writing API on one of the two modules, which commits Proofload
 * to a surface neither module is about; the page's document and this one are
 * deliberately allowed to differ, and 0087 says so.
 */
internal fun jsonObject(depth: Int, fields: List<Pair<String, String>>): String =
    if (fields.isEmpty()) {
        "{}"
    } else {
        fields.joinToString(
            separator = ",\n",
            prefix = "{\n",
            postfix = "\n${indent(depth)}}",
        ) { (key, value) -> "${indent(depth + 1)}${jsonString(key)}: $value" }
    }

internal fun <T> Collection<T>.jsonArray(depth: Int, element: (T) -> String): String =
    if (isEmpty()) {
        "[]"
    } else {
        joinToString(
            separator = ",\n",
            prefix = "[\n",
            postfix = "\n${indent(depth)}]",
        ) { "${indent(depth + 1)}${element(it)}" }
    }

internal fun jsonString(value: String): String =
    value.map(::escaped).joinToString(separator = "", prefix = "\"", postfix = "\"")

private fun indent(depth: Int): String = INDENT.repeat(depth)

private fun escaped(char: Char): String = when (char) {
    '"' -> "\\\""

    '\\' -> "\\\\"

    '\n' -> "\\n"

    '\r' -> "\\r"

    '\t' -> "\\t"

    // A failure reason is whatever the target said, and a caller may well paste
    // this document into a page. Escaping these three costs nothing and means
    // no reason can close an element it lands in.
    '<', '>', '&' -> unicodeEscape(char)

    else -> if (char < ' ') unicodeEscape(char) else char.toString()
}

private fun unicodeEscape(char: Char): String =
    "\\u" + char.code.toString(HEX_RADIX).padStart(UNICODE_ESCAPE_DIGITS, '0')

private const val INDENT = "  "
private const val HEX_RADIX = 16
private const val UNICODE_ESCAPE_DIGITS = 4
