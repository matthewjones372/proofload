package io.github.matthewjones372.kestrel.baseline

import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.Runs
import io.github.matthewjones372.kestrel.Shards
import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes this run into [directory] under a name no other invocation will take:
 * when it started, which injector it was where a run was split, and which
 * process measured it.
 *
 * All three are needed. Two runs of one JVM start at different times, and two
 * JVMs started together do not, so a loop that forks and a loop that does not
 * each end with one file per run rather than one file written many times. Four
 * injectors given one instant start in the same millisecond by design, and on
 * four hosts they may hold the same pid, so the shard is in the name too — and
 * it is legible there, which is what someone looking at a directory of them
 * over ssh has to work with.
 */
fun RunResult.writeInto(directory: Path): Path {
    val named = "run-${startedAt.toEpochMilli()}-$injector${ProcessHandle.current().pid()}$EXTENSION"
    return writeBaseline(directory.resolve(named))
}

private val RunResult.injector: String
    get() = shard?.let { "${it.index}of${it.of}-" }.orEmpty()

/**
 * Every run [writeInto] left in [directory], as one set, oldest first.
 *
 * A directory rather than a list of paths: one file per JVM invocation is what
 * a shell loop leaves behind, and nothing else has to know their names.
 */
fun Runs.Companion.readAll(directory: Path): Runs {
    val files = runsIn(directory)
    require(files.isNotEmpty()) {
        "no runs in $directory: each invocation writes one there, named run-<started>-<pid>$EXTENSION"
    }
    return Runs(files.map { readBaseline(it) }.sortedBy { it.startedAt })
}

/**
 * Every injector [writeInto] left in [directory], as the one run they were
 * pieces of.
 *
 * The coordinator, and the whole of it: a directory somebody's shell copied
 * four files into. Nothing here talked to an injector while it ran, and
 * nothing had to — each was given three scalars and the same jar, and the
 * plan lines in the files prove they ran the same thing.
 */
fun Shards.Companion.readAll(directory: Path): Shards {
    val files = runsIn(directory)
    require(files.isNotEmpty()) {
        "no injectors in $directory: each writes one there, named run-<started>-<index>of<N>-<pid>$EXTENSION"
    }
    return Shards(files.map { readBaseline(it) }.sortedBy { it.shard?.index })
}

private fun runsIn(directory: Path): List<Path> {
    if (!Files.isDirectory(directory)) return emptyList()
    return Files.newDirectoryStream(directory, "*$EXTENSION").use { it.toList() }
}

private const val EXTENSION = ".kestrel"
