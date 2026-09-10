import java.io.File

/**
 * The signature section of `docs/for-agents.md`, rendered from the checked-in
 * `.api` dumps.
 *
 * Here rather than in `build.gradle.kts` because a task action that calls a
 * script-level function captures the script, and through it the `Project`,
 * which the configuration cache cannot store. Nothing in this file touches a
 * Gradle type, so a `doLast` may close over it.
 */
object ApiSurface {

    const val GENERATED_FROM = "<!-- Rendered from the .api dumps by ./gradlew apiDocDump. Do not edit below. -->"

    const val GENERATED_TO = "<!-- End of the rendered surface. -->"

    /**
     * [document] with everything between the two markers replaced by [dumps]
     * rendered, keyed by the module each dump belongs to.
     *
     * [named] is the path the document came from, and appears only in the
     * failure: this function reads no files, so a caller that has one hands
     * over its text and its name together.
     */
    fun documentWithSurface(document: String, dumps: Map<String, File>, named: String): String {
        val before = document.substringBefore(GENERATED_FROM, missingDelimiterValue = "")
        val after = document.substringAfter(GENERATED_TO, missingDelimiterValue = "")
        check(before.isNotEmpty() && after.isNotEmpty()) {
            "$named must hold the markers `$GENERATED_FROM` and `$GENERATED_TO`."
        }
        return "$before$GENERATED_FROM\n\n${renderedSurface(dumps)}\n$GENERATED_TO$after"
    }

    private fun renderedSurface(dumps: Map<String, File>): String =
        dumps.keys.sorted().joinToString(separator = "\n") { module ->
            val rendered = renderDump(dumps.getValue(module).readText()).joinToString(separator = "\n")
            "### `io.github.matthewjones372:$module`\n\n```text\n$rendered\n```\n"
        }

    // The same rendering twice running is one declaration the compiler emitted
    // two ways, a boxed overload beside an unboxed one, not two a caller can
    // pick from.
    private fun renderDump(dump: String): List<String> = renderedLines(dump)
        .let { lines -> lines.filterIndexed { at, line -> at == 0 || line != lines[at - 1] } }

    private fun renderedLines(dump: String): List<String> = dump.lines().mapNotNull { line ->
        declaresClass.find(line)?.let { found ->
            val (modifiers, name, supertypes) = found.destructured
            val declared = simpleName(name)
            // A file facade is not a type: `ScenarioKt` is where `scenario` and
            // `step` live, and a reader told it is a class will try to make one.
            val kind = when {
                declared.endsWith("Kt") -> "top-level in"
                "interface" in modifiers -> "interface"
                else -> "class"
            }
            val extends = supertypes.split(", ").filter { it.isNotBlank() }.joinToString { simpleName(it) }
            "$kind $declared" + if (extends.isEmpty()) "" else " : $extends"
        } ?: renderMember(line)?.let { "    $it" }
    }

    private fun renderMember(line: String): String? {
        if ("synthetic" in line) return null
        declaresField.find(line)?.groupValues?.let { (_, _, name, type) ->
            return if (name in setOf("Companion", "INSTANCE")) null else "val $name: ${typesIn(type).single()}"
        }
        val (_, _, raw, parameters, returns) = declaresFun.find(line)?.groupValues ?: return null
        val name = demangled(raw)
        if (boilerplate.matches(name)) return null
        val arguments = typesIn(parameters).joinToString()
        val returned = typesIn(returns).single()
        return when {
            name == "<init>" -> "constructor($arguments)"

            arguments.isEmpty() && name.startsWith("get") && name.length > 3 ->
                "val ${name[3].lowercaseChar()}${name.substring(4)}: $returned"

            returned == "Unit" -> "fun $name($arguments)"

            else -> "fun $name($arguments): $returned"
        }
    }

    private fun typesIn(descriptors: String): List<String> = generateSequence(0 to "") { (at, _) ->
        if (at >= descriptors.length) null else readOneType(descriptors, at).let { (name, next) -> next to name }
    }.drop(1).map { it.second }.toList()

    private fun readOneType(descriptor: String, from: Int): Pair<String, Int> {
        val dimensions = descriptor.drop(from).takeWhile { it == '[' }.length
        val at = from + dimensions
        val (name, next) = when (descriptor[at]) {
            'L' -> descriptor.indexOf(';', at).let { simpleName(descriptor.substring(at + 1, it)) to it + 1 }
            else -> (JVM_PRIMITIVES[descriptor[at]] ?: "?") to at + 1
        }
        return "Array<".repeat(dimensions) + name + ">".repeat(dimensions) to next
    }

    private fun simpleName(binary: String): String = binary.substringAfterLast('/').replace('$', '.')

    /** A value class in a signature mangles the name it is in; the suffix is not part of the API. */
    private fun demangled(name: String): String = name.substringBefore("\$default").substringBefore('-')

    private val JVM_PRIMITIVES = mapOf(
        'V' to "Unit", 'Z' to "Boolean", 'B' to "Byte", 'C' to "Char", 'S' to "Short",
        'I' to "Int", 'J' to "Long", 'F' to "Float", 'D' to "Double",
    )

    /** Written by `equals`, `copy`, `component1` and the value-class bridges, and read by nobody. */
    private val boilerplate =
        Regex("""^(component\d*|copy|equals\d*|hashCode|toString|box|unbox|constructor|access.*)$""")

    private val declaresClass = Regex("""^public (.*?)class (\S+)(?: : (.*))? \{$""")

    private val declaresFun = Regex("""^\tpublic (.*?)fun (\S+) \(([^)]*)\)(.*)$""")

    private val declaresField = Regex("""^\tpublic (.*?)field (\S+) (.*)$""")
}
