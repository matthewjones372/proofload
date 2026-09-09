package io.github.matthewjones372.proofload.engine

import io.github.matthewjones372.proofload.Arm
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.constantRate
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeSorted
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class BookingTest {

    @Test
    fun `a fill books the departures inside the window and leaves the rest alone`() {
        val booked = mutableListOf<Duration>()

        val again = window().fill(elapsed = Duration.ZERO) { departure -> booked += departure.offset }

        booked shouldBe (0..5).map { it.seconds }
        withClue("the sixth second's departure comes into view a window before it leaves") {
            again shouldBe 1.seconds
        }
    }

    @Test
    fun `filling as it asks books every departure once, in order`() {
        val window = window()
        val booked = mutableListOf<Departure>()

        generateSequence(Duration.ZERO) { elapsed ->
            window.fill(elapsed) { departure -> booked += departure }?.plus(elapsed)
        }.toList()

        booked.map { it.user } shouldBe (0L..9L).toList()
        booked.map { it.offset } shouldBe (0..9).map { it.seconds }
    }

    /** A pump that wakes late is a run that is behind, which is a lateness to report, not a gap to drop. */
    @Test
    fun `a fill that comes late books what fell due while it was away, still in order`() {
        val window = window()
        val booked = mutableListOf<Duration>()
        window.fill(elapsed = Duration.ZERO) { }

        val again = window.fill(elapsed = 20.seconds) { departure -> booked += departure.offset }

        booked shouldBe (6..9).map { it.seconds }
        booked.shouldBeSorted()
        again shouldBe null
    }

    @Test
    fun `a profile that describes no departures asks for no second fill`() {
        val booked = mutableListOf<Duration>()

        val again = BookingWindow(emptySequence<Departure>().iterator(), 5.seconds)
            .fill(elapsed = Duration.ZERO) { departure -> booked += departure.offset }

        booked shouldBe emptyList()
        again shouldBe null
    }

    /**
     * Longer than the engine's own window, so the run cannot finish unless the
     * pump booked more than the first window's worth.
     */
    @Test
    fun `a run longer than the booking window still departs every user it named`() {
        val result = scenario("nothing") { exec("step") { } }.at(2.perSecond, over = 7.seconds).run()

        result["step"].count shouldBe 14L
        result.arrivals.count shouldBe 14L
    }

    /** Ten departures a second apart, booked five seconds ahead of themselves. */
    private fun window(): BookingWindow = BookingWindow(
        (0..9).asSequence().map { Departure(nothing, it.toLong(), it.seconds) }.iterator(),
        5.seconds,
    )

    private val nothing = Arm(scenario("nothing") { exec("step") { } }, constantRate(1.perSecond, over = 10.seconds))
}
