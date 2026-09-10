import java.io.File

/**
 * The sixteen statements of `docs/invariants.md`, and which tests defend each.
 *
 * A statement with no executable defence is a sentence, which is what 0134
 * exists to replace. A test defends one by saying so in its KDoc:
 *
 * ```
 * Defends invariant 3.
 * Defends invariants 1, 3 and 14.
 * ```
 *
 * Here rather than in a build script for the reason `ApiSurface` is: a task
 * action that calls a script-level function captures the script, and through it
 * the `Project`, which the configuration cache cannot store.
 */
object Invariants {

    /**
     * Which invariants must have a test behind them today.
     *
     * 0134 asks for a gate that fails on any invariant with nothing behind it.
     * Read literally that would fail the day it landed, because eleven of the
     * sixteen have no citing test and five of those describe behaviour the tool
     * does not have yet. So the gate is a ratchet instead: these are the ones
     * that *are* defended, and the build goes red when one of them loses its
     * last test. An invariant gains a test and is added here; none may quietly
     * lose one.
     */
    val REQUIRED = setOf(1, 2, 3, 4, 5, 14, 15)

    /** Every invariant `docs/invariants.md` states, by number. */
    val ALL = 1..16

    private val citation = Regex("""Defends invariants?\s+([0-9,\s]+?(?:\s+and\s+\d+)?)\s*\.""")

    private val number = Regex("""\d+""")

    /** Test file to the invariants its KDoc says it defends. */
    fun citations(testSources: List<File>): Map<Int, List<String>> {
        val found = mutableMapOf<Int, MutableList<String>>()
        testSources.forEach { file ->
            citation.findAll(file.readText()).forEach { match ->
                number.findAll(match.groupValues[1]).forEach { each ->
                    found.getOrPut(each.value.toInt()) { mutableListOf() }.add(file.name)
                }
            }
        }
        return found.mapValues { (_, files) -> files.distinct().sorted() }
    }

    /** Sixteen rows, in order, for a reader at a terminal. */
    fun report(cited: Map<Int, List<String>>): String =
        ALL.joinToString(separator = "\n") { number ->
            val tests = cited[number].orEmpty()
            val mark = when {
                tests.isNotEmpty() -> "defended"
                number in REQUIRED -> "MISSING"
                else -> "not yet"
            }
            "%2d  %-9s %s".format(number, mark, tests.joinToString().ifEmpty { "-" })
        }

    /**
     * The invariants that must have a test and do not, which is what turns the
     * build red. Empty is the passing case.
     */
    fun undefended(cited: Map<Int, List<String>>): List<Int> =
        REQUIRED.sorted().filter { cited[it].isNullOrEmpty() }

    /** What a reader is told when the ratchet slips. */
    fun complaint(missing: List<Int>): String {
        val named = when (missing.size) {
            1 -> "invariant ${missing.single()}"
            else -> "invariants " + missing.dropLast(1).joinToString() + " and " + missing.last()
        }
        return "no test defends $named. Each had one, and docs/invariants.md still says so. Restore the " +
            "test, or change the page and Invariants.REQUIRED together so the claim and the code agree."
    }
}
