package io.github.matthewjones372.proofload.engine

import io.github.matthewjones372.proofload.Capacity
import io.github.matthewjones372.proofload.Progress
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.Search
import io.github.matthewjones372.proofload.Simulation
import io.github.matthewjones372.proofload.judgedBy
import java.util.concurrent.atomic.AtomicInteger

/**
 * The search, saying what it is doing as it climbs.
 *
 * A search is the one thing here whose length nobody can work out in advance:
 * the ladder stops as soon as it has the knee and then bisects, so a watcher
 * sees rung after rung with nothing to count against. What it is told is a
 * bound rather than a forecast, and the bound narrows as rungs are spent.
 */
internal fun Search.reported(progress: Progress, run: (Simulation) -> RunResult): Capacity {
    progress.searching(this)

    // The counter case: a rung's number is what the callback is for, and the
    // callback is the only thing that writes it.
    val climbed = AtomicInteger()
    return judgedBy(
        climbed = { rung ->
            val number = climbed.incrementAndGet()
            progress.climbed(rung, number, atMostAfter(number))
        },
        run = run,
    )
}
