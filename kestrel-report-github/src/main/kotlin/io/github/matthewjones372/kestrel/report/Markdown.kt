package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Change
import io.github.matthewjones372.kestrel.Comparison
import io.github.matthewjones372.kestrel.Concurrency
import io.github.matthewjones372.kestrel.Floor
import io.github.matthewjones372.kestrel.Headroom
import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.Interval
import io.github.matthewjones372.kestrel.Plan
import io.github.matthewjones372.kestrel.PlannedArm
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.Stage
import io.github.matthewjones372.kestrel.StepStats
import io.github.matthewjones372.kestrel.TIGHT
import io.github.matthewjones372.kestrel.ThinkTime
import io.github.matthewjones372.kestrel.Verdict
import io.github.matthewjones372.kestrel.concurrency
import io.github.matthewjones372.kestrel.fellBehind
import io.github.matthewjones372.kestrel.heldScheduleFor
import io.github.matthewjones372.kestrel.offered
import io.github.matthewjones372.kestrel.precision
import io.github.matthewjones372.kestrel.ranOutOfRoom
import io.github.matthewjones372.kestrel.seeds
import io.github.matthewjones372.kestrel.stages
import io.github.matthewjones372.kestrel.startRate
import io.github.matthewjones372.kestrel.unanswered
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * What a run measured, as a table GitHub renders and a terminal still reads:
 * no colour, no emoji, and the columns padded so the numbers line up wherever
 * it lands — a job summary, a PR body or a job log.
 */
fun RunResult.markdown(comparison: Comparison? = null, floor: Floor? = null): String =
    blocks(comparison, floor).joinToString(separator = "\n\n", postfix = "\n")

private fun RunResult.blocks(comparison: Comparison?, floor: Floor?): List<String> =
    if (steps.isEmpty()) {
        listOf("No steps ran.", "Started $startedAt.")
    } else {
        listOfNotNull(
            lostWarning(),
            cutShortWarning(),
            floor?.line(),
            behindWarning(),
            roomWarning(),
            concurrencyLine(),
        ) +
            comparison.blocks(floor) + mixBlocks() +
            stepTable() + listOfNotNull(streamLine(), stageTable(), hiccupLine()) +
            failureBlocks() + totals() +
            listOfNotNull(arrivalLine()) + measurementNote()
    }

/**
 * This run against the last one, directly above the table it is about — and
 * absent on a machine whose own movement is too large to bound any of it, where
 * [Floor.line] has already said so in place of a comparison nobody should act
 * on.
 *
 * A refusal is printed rather than left off. A summary with no comparison in it
 * reads the same whether this was a first run or a cache key broke, and those
 * want opposite reactions.
 */
private fun Comparison?.blocks(floor: Floor?): List<String> {
    if (floor != null && !floor.supportsAClaim) return emptyList()
    return when (this) {
        null -> emptyList()

        is Comparison.NotComparable -> listOf("> **Not compared to the last run.** ${why.escapeMarkdown()}")

        is Comparison.Compared -> if (changes.isEmpty()) emptyList() else
            listOfNotNull(caveat?.let { "> **${it.escapeMarkdown()}**" }, headline(), changeTable(), COMPARISON_NOTE)
    }
}

private fun Comparison.Compared.headline(): String {
    val moved = changes.count { it is Change.Worse || it is Change.Better }
    return if (moved == 0) "**Nothing measurably changed since the last run.**"
    else "**$moved of ${changes.size} step${if (changes.size == 1) "" else "s"} measurably changed " +
        "since the last run.**"
}

private fun Comparison.Compared.changeTable(): String = table(
    columns = listOf(
        Column("Step", Align.LEFT),
        Column("Was", Align.RIGHT),
        Column("Now", Align.RIGHT),
        Column("Change", Align.LEFT),
    ),
    rows = changes.map { it.row() },
)

private fun Change.row(): List<String> = when (this) {
    is Change.Indistinguishable -> listOf(step.escapeMarkdown(), before.report(), now.report(), "not distinguishable")

    is Change.Worse ->
        listOf(step.escapeMarkdown(), before.report(), now.report(), "worse (${interval.report()})")

    is Change.Better ->
        listOf(step.escapeMarkdown(), before.report(), now.report(), "better (${interval.report()})")

    is Change.Added -> listOf(step.escapeMarkdown(), "—", "—", "did not run last time")

    is Change.Gone -> listOf(step.escapeMarkdown(), "—", "—", "ran last time and did not run now")
}

private fun Interval.report(): String = "${low.report()}–${high.report()}"

/**
 * Above the backlog warning and above the table, because a record that never
 * arrived is not a missing sample: every throughput number under it is counting
 * work the target may never have finished.
 */
private fun RunResult.lostWarning(): String? {
    if (unanswered.isEmpty()) return null
    val where = unanswered.joinToString(separator = "; ") { step ->
        "${step.name.escapeMarkdown()} — ${step.unmatched} unmatched, ${step.inFlight} in flight"
    }
    return "> **Records that never arrived:** $where. " +
        "An unmatched record is one the sink had the whole drain window to answer for and did not; " +
        "an in-flight one left too late to be given that window."
}

/**
 * Above the table, because it is the frame for every number under it, and below
 * the lost records, because a record that never arrived outranks a caveat about
 * precision. A machine too coarse to bound a claim gets the warning form: this
 * report has no comparison to withhold, so saying it plainly is all it can do.
 */
private fun Floor.line(): String =
    if (supportsAClaim) {
        "Calibrated on this machine: differences under ${resolution.asPercent()} are not resolvable here. " +
            "The injector's own stalls reached ${hiccups.p99.report()} at p99."
    } else {
        "> **This machine cannot support a latency claim.** Repeats of one unchanging measurement landed " +
            "${resolution.asPercent()} apart here, so nothing smaller than that is the code rather than " +
            "the machine."
    }

private fun Double.round(): String = String.format(Locale.ROOT, "%.2f", this)

private fun Double.asPercent(): String = String.format(Locale.ROOT, "%.2f%%", this * PERCENT)

/** A rate as a reader writes it: 45 rather than 45.000000. */
private fun trimmed(rate: Double): String =
    String.format(Locale.ROOT, "%,.6g", rate).trimEnd('0').trimEnd('.')

/**
 * Under the table rather than over it, because it is what the tail above is
 * measured against. Absent when nothing watched: a result assembled from
 * samples has no injector to have stalled.
 */
private fun RunResult.hiccupLine(): String? {
    if (hiccups.count == 0L) return null
    return "The injector's own JVM stalled for ${hiccups.p99.report()} at p99 and ${hiccups.max.report()} at " +
        "worst, measured on a thread no request ran on. A tail that size is this machine as readily as the target."
}

/**
 * A line rather than a warning. Even arrivals are not wrong, they are a choice
 * whose consequence — a p99 that is optimistic against the same mean rate in
 * production — is invisible unless the report names which was asked for.
 */
private fun RunResult.arrivalLine(): String? {
    val shapes = plan.arms.mapNotNull { it.profile }.ifEmpty { return null }
    val drawn = shapes.flatMap { it.seeds }
    val asked =
        if (drawn.isEmpty()) "Arrivals were evenly spaced, which understates queueing against the same mean rate " +
            "in production."
        else "Arrivals were drawn from ${if (drawn.size == 1) "seed" else "seeds"} ${drawn.joinToString(", ")}."

    val thinking = plan.thinking()
    val warmed = plan.warmed()
    if (arrivals.count < 2L) return asked + thinking + warmed
    return "$asked Measured ${arrivals.mean.report()} between departures, coefficient of variation " +
        "${String.format(Locale.ROOT, "%.2f", arrivals.cov)}.$thinking$warmed"
}

/**
 * What the users waited between steps, where they waited at all — and whether
 * every one of them waited the same, which is what makes them click again
 * together.
 */
private fun Plan.thinking(): String {
    val waits = arms.flatMap { it.thinkTimes }.ifEmpty { return "" }
    val drawn = arms.filter { arm -> arm.thinkTimes.any { it !is ThinkTime.Constant } }.mapNotNull { it.thinkSeed }
    val described = waits.distinct().joinToString(separator = ", ") { it.described() }
    val from = if (drawn.isEmpty()) {
        "every user waited exactly that long"
    } else {
        "drawn from ${if (drawn.size == 1) "seed" else "seeds"} ${drawn.joinToString(", ")}"
    }
    return " Think time: $described, $from."
}

private fun ThinkTime.described(): String = when (this) {
    is ThinkTime.Constant -> "a fixed ${duration.report()}"
    is ThinkTime.Exponential -> "exponential, mean ${mean.report()}"
    is ThinkTime.Lognormal -> "lognormal, median ${median.report()}, sigma $sigma"
    is ThinkTime.Uniform -> "uniform, ${from.report()} to ${until.report()}"
}

/**
 * What a run threw away before measuring, where it declared one. Empty
 * otherwise: a summary that says a run did not warm up is noise on every run
 * that never asked to.
 */
private fun Plan.warmed(): String {
    val warmUp = warmUp ?: return ""
    val rate = arms.firstNotNullOfOrNull { it.profile }?.startRate?.perSecond
    val at = if (rate == null) "" else " at ${trimmed(rate)}/s"
    return " Warmed for ${warmUp.over.report()}$at, not counted."
}

/**
 * A run that stopped before its schedule did, naming both windows: every count
 * under it is over the shorter one, and a summary printing only what it
 * measured reads exactly like a run that saw its window out.
 *
 * Absent otherwise, because a warning on every summary is one readers skip. A
 * run legitimately outlasts its window while it waits out the users it started,
 * and a shortfall under [SHORTFALL] of the window is the timeline's own whole
 * seconds as readily as a run somebody stopped.
 */
private fun RunResult.cutShortWarning(): String? {
    val asked = plan.plannedWindow
    val recorded = timeline.size.seconds
    if (timeline.isEmpty() || asked <= Duration.ZERO || asked - recorded < asked * SHORTFALL) return null

    return "> **Cut short:** the schedule asked for ${asked.report()} and the run recorded ${recorded.report()}. " +
        "Every number below is over the shorter window."
}

/**
 * What this process ran out of, where it did: the failures below are its own
 * ceiling as readily as the target's refusal, and a summary that does not say
 * so hands a reader the wrong end of the wire.
 */

/**
 * Little's law on this run, as a note where it holds and a warning where it
 * does not — naming this tool rather than the target, because the identity is
 * arithmetic and a gap is a measurement that does not add up.
 */
private fun RunResult.concurrencyLine(): String? {
    val law = concurrency as? Concurrency.Measured ?: return null

    val sides = "${law.observed.round()} users were running; throughput times mean service time says " +
        "${law.fromServiceTime.round()}"
    return if (law.agrees) {
        "> **Little's law holds:** $sides, a ratio of ${law.ratio.round()}."
    } else {
        "> **These numbers do not add up:** $sides — a ratio of ${law.ratio.round()}. Little's law is " +
            "arithmetic over a settled window, so this is a fault in the measurement rather than in the " +
            "target."
    }
}

private fun RunResult.roomWarning(): String? {
    if (!ranOutOfRoom()) return null

    val tight = limits.all
        .mapNotNull { (name, headroom) ->
            (headroom as? Headroom.Measured)?.takeIf { it.used >= TIGHT }?.let { "$name ${it.peak} of ${it.limit}" }
        }
        .joinToString(separator = ", ")
    return "> **The injector ran out of room:** $tight. Failures below are this process hitting its own " +
        "ceiling as readily as the target refusing work."
}

private fun RunResult.behindWarning(): String? {
    if (!fellBehind()) return null
    return "> **Behind schedule:** ${behind.p99.report()} late at p99, ${behind.max.report()} at worst. " +
        "The response times below include that backlog.${whatLeft()}"
}

/**
 * The load that actually left, and how long the schedule held before it went:
 * the two facts that turn "behind schedule" from a diagnosis into something a
 * reader can act on. Empty where the run named no plan or recorded no seconds.
 */
private fun RunResult.whatLeft(): String {
    val offered = offered ?: return ""
    val held = heldScheduleFor?.let { " The schedule held for ${it.report()}." }.orEmpty()
    return " Asked for ${trimmed(offered.asked.perSecond)}/s; ${trimmed(offered.left.perSecond)}/s left over " +
        "${offered.over.report()}.$held Service times below are the target at that load."
}

/**
 * Each arm's share of the run: what the plan asked for, beside what was
 * counted. Absent for one arm, where both shares are the whole run and the
 * table would say nothing.
 */
private fun RunResult.mixBlocks(): List<String> {
    val arms = plan.arms
    if (arms.size < 2) return emptyList()

    val counted = arms.map { usersCounted(it) }
    val measured = counted.sum()
    val columns = listOf(
        Column("Arm", Align.LEFT),
        Column("Planned users", Align.RIGHT),
        Column("Asked", Align.RIGHT),
        Column("Departed", Align.RIGHT),
    )
    val rows = arms.zip(counted) { arm, users ->
        listOf(
            arm.scenario.escapeMarkdown(),
            arm.plannedUsers.toString(),
            arm.plannedUsers.shareOf(plan.plannedUsers),
            users.shareOf(measured),
        )
    }
    return listOf("**Mix**", table(columns, rows), mixNote(measured))
}

/**
 * What the two shares are, said where they are printed.
 *
 * The departed share is users counted rather than users departed: nothing
 * records a departure per arm, and the arms' own step counts are the only
 * split of the run there is.
 */
private fun mixNote(measured: Long): String =
    if (measured == 0L) {
        "**Asked** is the arm's share of the users the plan named. This run counted no users, so what " +
            "departed cannot be split by arm and only what was asked for is printed."
    } else {
        "**Asked** is the arm's share of the users the plan named. **Departed** is its share of the " +
            "$measured users the run counted: the most any one step of the arm was reached by. A user " +
            "that failed a step still reached it, so this is exact for a scenario whose steps every user " +
            "meets, and a floor for one that puts its steps behind a condition."
    }

/**
 * The users the run counted in an arm.
 *
 * A user is counted once per step it reaches, so the most-reached step of an
 * arm is the users that arm saw. Abandonment does not lower it — a user that
 * failed a step still reached it — so this is exact wherever every user of an
 * arm meets at least one common step, which is every scenario that does not
 * open with a condition. Where one does, it is a floor.
 */
private fun RunResult.usersCounted(arm: PlannedArm): Long =
    arm.steps.mapNotNull { steps[it]?.reached }.maxOrNull() ?: 0L

private fun Long.shareOf(whole: Long): String =
    if (whole == 0L) NOTHING_MEASURED else (toDouble() / whole).asPercent()

/**
 * Which arm sent a step, for a run that has more than one. A step name is
 * unique across a mix, so the arm holding the name is the arm that sent it, and
 * a name no arm planned belongs to none of them.
 */
private fun Plan.armOf(step: String): String? =
    if (arms.size < 2) null
    else arms.firstOrNull { step in it.steps }?.scenario?.escapeMarkdown() ?: NOTHING_MEASURED

/**
 * The steps whose answers outnumbered the runs of their body, or null where
 * none did.
 *
 * Directly under the table, because it is what the Requests column means on
 * those rows: a stream's answers are not requests, and a reader adding the
 * column up as trips to the target would be counting something else. Absent on
 * a run with no stream in it, and on one that counted no visits at all.
 */
private fun RunResult.streamLine(): String? {
    val streams = steps.values.filter { it.streamed }
    if (streams.isEmpty()) return null

    val named = streams.joinToString(separator = ", ") {
        "${it.name.escapeMarkdown()} ${it.count} answers over ${it.visits} runs"
    }
    return "Some steps reported more than one answer per run of their body — a stream rather than a " +
        "loop: $named. Requests counts the answers there, each its own sample, so the percentiles are " +
        "about the messages rather than about the batch."
}

/**
 * What each stage of a staged run measured, or null where nothing staged it.
 *
 * Under the step table rather than above it: the totals there are what a
 * reader looks at first, and this says what they are a mixture of. One stage
 * would be a table repeating them.
 */
private fun RunResult.stageTable(): String? {
    val staged = stages
    if (staged.isEmpty()) return null

    // Only where a goal was asked per stage: a column of blanks on every other
    // staged run is a wider table saying nothing.
    val judged = verdicts.filter { it.stage != null }.groupBy { it.stage?.index }
    val goalColumn = if (judged.isEmpty()) emptyList() else listOf(Column("Goals", Align.LEFT))

    val rows = table(
        columns = listOf(
            Column("Stage", Align.LEFT),
            Column("Window", Align.LEFT),
            Column("Requests", Align.RIGHT),
            Column("OK", Align.RIGHT),
            Column("Failed", Align.RIGHT),
            Column("p50", Align.RIGHT),
            Column("p95", Align.RIGHT),
            Column("p99", Align.RIGHT),
            Column("Max", Align.RIGHT),
        ) + goalColumn,
        rows = staged.map { it.row() + judged[it.index].orEmpty().joinedVerdicts(goalColumn.isNotEmpty()) },
    )
    val width = staged.first().serviceTime.precision?.let { " and so good to ${it.asPercent()}" }.orEmpty()
    return rows + "\n\nEach stage is the seconds of the timeline inside it, read off rather than " +
        "recorded$width — wider than the percentiles above, which are a step's own. " +
        "The table above covers the whole run: on a staged run it is a mixture of these rows and " +
        "describes none of them.${staged.misalignment()}"
}

/**
 * What to say where a stage boundary fell inside a second.
 *
 * A second is the finest thing the timeline holds, so it is counted whole in
 * the stage its own start falls in, rather than left for a reader to notice
 * that a window is not the length the plan asked for.
 */
private fun List<Stage>.misalignment(): String {
    val ragged = filterNot { it.alignedToSeconds }
    if (ragged.isEmpty()) return ""
    val named = ragged.joinToString(separator = ", ") {
        "stage ${it.index + 1} asked for ${it.planned.report()} and holds ${(it.until - it.from).report()}"
    }
    return " A boundary fell inside a second, and a second is counted whole in the stage it " +
        "begins in: $named."
}

/** A stage window's edge, zero rendered as a second rather than as a nanosecond precision. */
private fun Duration.edge(): String = if (this == Duration.ZERO) "0s" else report()

/**
 * A stage's verdicts as one cell, or no cell at all where the table has no such
 * column. Empty rather than absent where a stage was judged and another was not:
 * a row short of a cell is a broken table.
 */
private fun List<Verdict>.joinedVerdicts(wanted: Boolean): List<String> {
    if (!wanted) return emptyList()
    return listOf(joinToString(separator = "; ") { "${it.goal.described}: ${it.said()}" })
}

private fun Verdict.said(): String = when {
    refused != null -> "cannot tell"
    met -> "met"
    else -> "missed"
}

private fun Stage.row(): List<String> = listOf(
    // The ordinal rather than the rate line: this summary is a PR comment and
    // stays terse, the shape it names is on the page, and a ramp printed as
    // its start rate would name the stage after the load it left behind.
    "${index + 1} of $of",
    "${from.edge()}–${until.edge()}",
    count.toString(),
    ok.toString(),
    failed.toString(),
    serviceTime.p50.report(),
    serviceTime.p95.report(),
    serviceTime.p99.report(),
    serviceTime.max.report(),
)

private fun RunResult.stepTable(): String = table(
    columns = listOf(
        Column("Step", Align.LEFT),
    ) + armColumn() + listOf(
        Column("Requests", Align.RIGHT),
        Column("Reached", Align.RIGHT),
        Column("OK", Align.RIGHT),
        Column("Failed", Align.RIGHT),
        Column("p50", Align.RIGHT),
        Column("p95", Align.RIGHT),
        Column("p99", Align.RIGHT),
        Column("Max", Align.RIGHT),
    ),
    rows = steps.values.map { it.row(plan.armOf(it.name)) },
)

// Only for a run that had more than one arm: a column repeating one scenario
// name down every row is noise in the summary it is meant to be missing from.
private fun RunResult.armColumn(): List<Column> =
    if (plan.arms.size < 2) emptyList() else listOf(Column("Arm", Align.LEFT))

private fun StepStats.row(arm: String?): List<String> = listOf(
    name.escapeMarkdown(),
) + listOfNotNull(arm) + listOf(
    count.toString(),
    // A run recorded by something that did not count users has no reaches to
    // print, and a zero would read as a step nobody took.
    if (reached == 0L) NOTHING_MEASURED else reached.toString(),
    ok.count.toString(),
    failed.count.toString(),
    responseTime.p50.report(),
    responseTime.p95.report(),
    responseTime.p99.report(),
    responseTime.max.report(),
)

/**
 * Three significant digits wherever the value sits. `Duration.toString()` prints
 * every nanosecond it holds, and a percentile is the top of a bucket good to
 * [Histogram.PRECISION] — so a fourth digit is the report claiming a precision
 * nobody measured.
 */
private fun Duration.report(): String {
    if (this == Duration.ZERO) return "0s"
    val unit = unitOf(inWholeNanoseconds)
    val magnitude = floor(log10(toDouble(unit))).toInt()
    return toString(unit, (SIGNIFICANT_DIGITS - 1 - magnitude).coerceIn(0, SIGNIFICANT_DIGITS))
}

private fun unitOf(nanos: Long): DurationUnit = when {
    nanos < NANOS_PER_MICRO -> DurationUnit.NANOSECONDS
    nanos < NANOS_PER_MILLI -> DurationUnit.MICROSECONDS
    nanos < NANOS_PER_SECOND -> DurationUnit.MILLISECONDS
    else -> DurationUnit.SECONDS
}

private fun RunResult.failureBlocks(): List<String> {
    val rows = steps.values.flatMap { step ->
        step.failed.reasons.map { (reason, seen) ->
            listOf(step.name.escapeMarkdown(), reason.described.escapeMarkdown(), seen.toString())
        }
    }
    if (rows.isEmpty()) return emptyList()

    val columns = listOf(Column("Step", Align.LEFT), Column("Failure", Align.LEFT), Column("Count", Align.RIGHT))
    return listOf("**Failures**", table(columns, rows))
}

private fun RunResult.totals(): String = "$count requests, $ok ok, $failed failed. Started $startedAt."

private enum class Align { LEFT, RIGHT }

private class Column(val header: String, val align: Align)

private fun table(columns: List<Column>, rows: List<List<String>>): String {
    val widths = columns.mapIndexed { index, column ->
        maxOf(MIN_COLUMN_WIDTH, column.header.length, rows.maxOfOrNull { it[index].length } ?: 0)
    }
    val rule = columns.mapIndexed { index, column ->
        val dashes = "-".repeat(widths[index] - 1)
        if (column.align == Align.LEFT) ":$dashes" else "$dashes:"
    }
    val header = line(columns.map { it.header }, columns, widths)
    return (listOf(header, rule.wrapped()) + rows.map { line(it, columns, widths) }).joinToString("\n")
}

private fun line(cells: List<String>, columns: List<Column>, widths: List<Int>): String =
    cells.mapIndexed { index, cell ->
        if (columns[index].align == Align.LEFT) cell.padEnd(widths[index]) else cell.padStart(widths[index])
    }.wrapped()

private fun List<String>.wrapped(): String = joinToString(separator = " | ", prefix = "| ", postfix = " |")

/**
 * A failure reason is whatever the target said, so it arrives carrying the
 * characters that end a table cell or open a tag. Backslash-escaping ASCII
 * punctuation is what CommonMark defines for this, and it leaves the text
 * readable in a terminal, where nothing renders and the backslashes are the
 * only cost.
 */
private fun String.escapeMarkdown(): String = map { character ->
    when {
        character in ACTIVE_CHARACTERS -> "\\$character"

        // A newline inside a cell ends the row; a control character is not text.
        character.isISOControl() -> " "

        else -> character.toString()
    }
}.joinToString(separator = "")

private const val ACTIVE_CHARACTERS = "\\`*_[]<>|"

// Five, so the alignment colon in the rule still has four dashes to sit
// against and the separator reads as a separator in a terminal.
private const val NOTHING_MEASURED = "—"

private const val MIN_COLUMN_WIDTH = 5

/** Under this share of the window, a shortfall is the timeline's own whole seconds rather than a run that stopped. */
private const val SHORTFALL = 0.1

private const val SIGNIFICANT_DIGITS = 3
private const val NANOS_PER_MICRO = 1_000L
private const val NANOS_PER_MILLI = 1_000_000L
private const val NANOS_PER_SECOND = 1_000_000_000L

private const val PERCENT = 100.0

private const val COMPARISON_NOTE: String =
    "Compared at p99 of response time. A sampling interval bounds which sample the p99 landed on, given how " +
        "many there were. It does not bound how far a repeat of this run would land from it, because two runs " +
        "of one unchanged target drift by the machine as well as by the code. So this says these samples " +
        "differ, not that the target did — repeated runs are what answers the second."

/**
 * The width is read off the timings being reported rather than off the constant
 * the recorder chose with: a report that knows statically which histogram its
 * numbers came from is one refactor away from stating a precision they do not
 * have. A run that counted nothing quotes none.
 */
private fun RunResult.measurementNote(): String =
    "Latency is response time, measured from the departure the profile promised. " +
        precision?.let {
            "Each percentile is the top of its histogram bucket, so it is within " +
                "${String.format(Locale.ROOT, "%.2f", it * PERCENT)}% and never interpolated."
        }.orEmpty().ifEmpty { "Each percentile is the top of its histogram bucket and never interpolated." }
