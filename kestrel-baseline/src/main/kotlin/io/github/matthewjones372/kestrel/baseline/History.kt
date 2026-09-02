package io.github.matthewjones372.kestrel.baseline

import io.github.matthewjones372.kestrel.Runs
import io.github.matthewjones372.kestrel.Share
import io.github.matthewjones372.kestrel.Statistic
import io.github.matthewjones372.kestrel.Trend
import java.nio.file.Files
import java.nio.file.Path

/**
 * Every point of a series, read out of [history]: one subdirectory per point,
 * holding the runs that point is made of.
 *
 * A directory of directories, the way a baseline is a file. No database and no
 * service: whoever measured a point put its runs somewhere named after what
 * was built — `history/$GITHUB_SHA` — and that name is the only label there is,
 * because the file format carries started-at, machine, probe, plan and buckets
 * and nothing that says what it was a measurement *of*.
 *
 * Ordered by when each point was measured rather than by name, so a commit
 * rebuilt a week later lands where the measurement happened rather than where
 * git would put it.
 */
fun readTrend(history: Path, statistic: Statistic, acceptable: Share = Share(0.0)): Trend {
    val directories = pointsIn(history)
    require(directories.isNotEmpty()) {
        "no points in $history: a trend is one subdirectory per point, each holding that point's runs"
    }

    val points = directories.map { directory ->
        val label = directory.fileName.toString()
        // Named rather than let through: `Runs` refuses an empty set and an
        // unlike one already, and its message is about runs. Which point they
        // were is the half a reader needs to go and look.
        val runs = runCatching { Runs.readAll(directory) }
            .getOrElse { why -> throw IllegalArgumentException("point '$label': ${why.message}", why) }
        Trend.Point(label, runs)
    }

    return Trend(statistic, points.sortedBy { it.runs.first.startedAt }, acceptable)
}

private fun pointsIn(history: Path): List<Path> {
    if (!Files.isDirectory(history)) return emptyList()
    return Files.newDirectoryStream(history).use { entries -> entries.filter { Files.isDirectory(it) } }
}
