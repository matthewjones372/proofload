package io.github.matthewjones372.proofload.mcp

import io.github.matthewjones372.proofload.Change
import io.github.matthewjones372.proofload.Comparison
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.against
import io.github.matthewjones372.proofload.export.Density
import io.github.matthewjones372.proofload.export.json
import io.github.matthewjones372.proofload.report.markdown
import io.github.matthewjones372.proofload.report.writeHtmlReport
import java.nio.file.Files
import java.nio.file.Path

/**
 * What became of the runs this server has sent.
 *
 * The split here is deliberate and is the reason `report` exists at all: an
 * agent reads [explain]'s JSON, and a person opens the page [report] writes.
 * Handing a model the page's bytes would be handing it inlined SVG to no
 * purpose, so this returns the path and lets whoever wants it open it.
 */
internal fun explain(registry: Registry, id: String?): String = onFinished(registry, id) { result ->
    content(result.json(Density.Full))
}

/**
 * The run for the third reader — the person watching the chat, who gets neither
 * [explain]'s JSON nor the page [report] writes.
 *
 * `docs/mcp.md` argues that handing a person the JSON is handing them the thing
 * the charts were made from, and that is exactly what watching `status` does. So
 * this hands over the markdown a job summary already carries: the same renderer,
 * because a chat-only one would be a second behaviour to keep in step.
 */
internal fun summarised(registry: Registry, id: String?): String = onFinished(registry, id) { result ->
    content(result.markdown())
}

internal fun report(registry: Registry, id: String?, into: Path): String = onFinished(registry, id) { result ->
    val page = into.resolve("$id.html")
    Files.createDirectories(page.parent)
    result.writeHtmlReport(page)
    content("wrote ${page.toAbsolutePath()} — one self-contained file, open it in a browser")
}

/** Every run this server has started, newest first, each with what it concluded. */
internal fun listRuns(registry: Registry): String {
    val runs = registry.listed()
    if (runs.isEmpty()) return content("no runs yet; start one with `run`")

    return content(
        runs.joinToString("\n") { (id, at) ->
            when (at) {
                is Progressing.Sending -> "$id  sending — ${at.describes}"
                is Progressing.Finished -> "$id  ${at.result.count} requests, ${at.result.failed} failed"
                is Progressing.Stopped -> "$id  stopped — ${at.why}"
            }
        },
    )
}

/**
 * One run against another, in 0038's three answers rather than two.
 *
 * "Cannot tell" is the one that matters: a comparison that only ever says
 * better or worse will say one of them about noise, and a caller acting on that
 * chases a regression nobody introduced.
 */
internal fun compare(registry: Registry, id: String?, against: String?): String =
    onFinished(registry, id) { now ->
        val baseline = against?.let { registry.finished(it) }
            ?: return@onFinished content("no finished run `$against` to compare against", failed = true)

        content(now.against(baseline).described(), failed = false)
    }

private fun Comparison.described(): String = when (this) {
    is Comparison.NotComparable -> "not comparable: $why"

    is Comparison.Compared -> buildString {
        slowdown?.let { appendLine("the machine ran the probe ${it}x slower than the baseline's") }
        changes.forEach { appendLine(it.described()) }
        if (changes.isEmpty()) appendLine("no step ran in both")
    }.trimEnd()
}

private fun Change.described(): String = when (this) {
    is Change.Better -> "$step  better: $before to $now"

    is Change.Worse -> "$step  worse: $before to $now"

    is Change.Indistinguishable -> "$step  cannot tell: $before to $now"

    is Change.Added -> "$step  new, nothing to compare it to"

    // The baseline had it and this run does not, which core's own comment calls
    // the most interesting change there is.
    is Change.Gone -> "$step  gone — the baseline ran it and this run did not"
}

private fun onFinished(registry: Registry, id: String?, read: (RunResult) -> String): String =
    when (val result = id?.let { registry.finished(it) }) {
        null -> content("no finished run `$id`; `status` says whether one is still sending", failed = true)
        else -> read(result)
    }
