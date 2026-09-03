package io.github.matthewjones372.kestrel.record

import java.nio.file.Files
import java.nio.file.Path

/**
 * What the command line was asked for.
 *
 * A value, so the parsing is testable without a process and the defaults are
 * readable in one place.
 */
data class Arguments(
    val har: Path,
    val packageName: String,
    val out: Path,
    val scenario: String,
    val include: Regex?,
    val exclude: Regex?,
) {

    /** `com.acme.load` and a scenario called `checkout` land in `com/acme/load/Checkout.kt`. */
    val file: Path
        get() = out.resolve(packageName.replace('.', '/'))
            .resolve("${scenario.replaceFirstChar { it.uppercase() }}.kt")
}

/**
 * Reads a HAR and writes a scenario somebody edits.
 *
 * An entry point in a published module, which makes this the only one. A tool
 * nobody depends on is not a library — but it is also not worth a second module
 * and a second set of coordinates, and it runs before a run rather than during
 * one, so nothing about it reaches a timed path.
 */
fun main(args: Array<String>) {
    val asked = parse(args) ?: return
    val recorded = readHar(Files.readString(asked.har))
    val drafted = draft(recorded, asked.include, asked.exclude)
    val source = drafted.asKotlin(asked.packageName, asked.scenario, asked.har.fileName.toString())

    asked.file.parent?.let { Files.createDirectories(it) }
    Files.writeString(asked.file, source)
    println("kestrel: ${drafted.steps.size} steps from ${recorded.size} recorded requests, at ${asked.file}")
    if (drafted.captures.isNotEmpty()) {
        println("kestrel: captured ${drafted.captures.joinToString { it.name }}")
    }
    val credentials = drafted.steps.sumOf { it.dropped.size }
    if (credentials > 0) println("kestrel: $credentials credential header(s) dropped, each left as a TODO")
}

private fun parse(args: Array<String>): Arguments? {
    val positional = args.filterNot { it.startsWith("--") }
    val flags = args.toList().windowed(2).filter { it.first().startsWith("--") }
        .associate { (name, value) -> name.removePrefix("--") to value }
    val har = positional.firstOrNull()
    if (har == null) {
        println(usage)
        return null
    }
    return Arguments(
        har = Path.of(har),
        packageName = flags["package"] ?: "load",
        out = Path.of(flags["out"] ?: "src/test/kotlin"),
        scenario = flags["scenario"] ?: Path.of(har).fileName.toString().substringBeforeLast('.'),
        include = flags["include"]?.let { Regex(it) },
        exclude = flags["exclude"]?.let { Regex(it) },
    )
}

private val usage = """
    kestrel-record — a HAR read into a Kotlin scenario you edit and commit.

      <file.har>            the recording, exported by any browser or proxy
      --package <name>      the package the generated file declares (default: load)
      --out <dir>           the source root it is written under (default: src/test/kotlin)
      --scenario <name>     what to call the scenario (default: the file's name)
      --include <regex>     only paths matching this
      --exclude <regex>     no paths matching this

    Static assets are excluded whatever you pass: a page load is forty requests
    to a CDN and one to the API, and including them measures somebody else's
    cache. Every credential is dropped, and there is no flag to keep one.
""".trimIndent()
