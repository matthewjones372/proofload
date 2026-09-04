package io.github.matthewjones372.kestrel.engine

import kotlin.time.Duration

/**
 * A cursor over a run's departures that hands out only the ones due within
 * [window] of now, so a scheduler holds a window's worth of tasks rather than a
 * whole run's.
 *
 * What that is worth is measured rather than asserted: `:benchmarks:footprint`
 * reports a live set that does not grow with the users a run sends, and a
 * scheduler holding every departure of a long run at a high rate is the obvious
 * way to lose that.
 *
 * Offsets are read from the schedule's lazy sequence in the order it produced
 * them, so nothing here recomputes a departure and nothing can hand a recorder
 * a gap that runs backwards.
 */
internal class BookingWindow(
    private val departures: Iterator<Departure>,
    private val window: Duration,
) {

    // The one departure pulled from the sequence and found too far off to book.
    // A sequence cannot be un-consumed, and holding it is cheaper than copying
    // the window out on the thread whose own delay this tool reports as
    // lateness.
    private var held: Departure? = null

    /**
     * Books every departure due by [elapsed] plus a window, and answers with how
     * long until the next one comes into view — null once none are left.
     *
     * A fill that arrives late books what fell due while it was away, in order.
     * Those users leave late, which the run's own `behind` series measures; a
     * window that cannot be refilled in time is not a new kind of failure.
     */
    fun fill(elapsed: Duration, book: (Departure) -> Unit): Duration? =
        fillUntil(elapsed + window, book)?.let { next -> next.offset - window - elapsed }

    private tailrec fun fillUntil(until: Duration, book: (Departure) -> Unit): Departure? {
        val next = held ?: if (departures.hasNext()) departures.next() else return null
        if (next.offset > until) {
            held = next
            return next
        }
        held = null
        book(next)
        return fillUntil(until, book)
    }
}
