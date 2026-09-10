package io.github.matthewjones372.proofload.engine

import io.github.matthewjones372.proofload.Arm
import io.github.matthewjones372.proofload.InjectionProfile
import io.github.matthewjones372.proofload.Shard
import io.github.matthewjones372.proofload.constantRate
import io.github.matthewjones372.proofload.hold
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.rampRate
import io.github.matthewjones372.proofload.randomized
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.thenRampTo
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Splitting a run across injectors sends every user exactly once, at the
 * offsets one JVM would have used.
 *
 * The claim `ownedBy`'s KDoc makes, and the one that makes a distributed run
 * checkable against a local one. It was asserted nowhere: `AcrossProcessesTest`
 * runs shards for real and merges their results, which proves the merge rather
 * than the partition, and proves it at the resolution of a machine running
 * several JVMs.
 *
 * Defends invariants 2 and 15.
 */
class ShardOwnershipTest {

    @Test
    fun `the union over every injector is the whole schedule, in order`() {
        shapes().forEach { (name, profile) ->
            val whole = schedule(profile).toList()

            sizes.forEach { of ->
                val union = (0 until of).flatMap { index -> owned(profile, index, of) }

                withClue("$name over $of injectors sent every user exactly once") {
                    union.sortedBy { it.user } shouldContainExactly whole.sortedBy { it.user }
                }
            }
        }
    }

    @Test
    fun `no two injectors send the same user`() {
        shapes().forEach { (name, profile) ->
            sizes.forEach { of ->
                val perInjector = (0 until of).map { index -> owned(profile, index, of).map { it.user }.toSet() }

                perInjector.indices.forEach { one ->
                    (one + 1 until perInjector.size).forEach { other ->
                        withClue("$name: injectors $one and $other of $of overlap") {
                            perInjector[one].intersect(perInjector[other]).shouldBeEmpty()
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `an injector sends its users at the offsets a single JVM would have used`() {
        val profile = rampRate(from = 5.0.perSecond, to = 50.0.perSecond, over = 4.seconds)
        val whole = schedule(profile).associate { it.user to it.offset }

        (0 until 3).forEach { index ->
            owned(profile, index, of = 3).forEach { departure ->
                withClue("user ${departure.user} left when it would have alone") {
                    departure.offset shouldBe whole.getValue(departure.user)
                }
            }
        }
    }

    @Test
    fun `an injector's own departures are still in order`() {
        shapes().forEach { (name, profile) ->
            sizes.forEach { of ->
                (0 until of).forEach { index ->
                    val offsets = owned(profile, index, of).map { it.offset }
                    withClue("$name: injector $index of $of") { offsets shouldContainExactly offsets.sorted() }
                }
            }
        }
    }

    @Test
    fun `one injector of one is the whole run untouched`() {
        shapes().forEach { (name, profile) ->
            withClue(name) {
                owned(profile, index = 0, of = 1) shouldContainExactly schedule(profile).toList()
            }
        }
    }

    @Test
    fun `a run nobody sharded is the whole run untouched`() {
        val profile = constantRate(9.0.perSecond, over = 2.seconds)

        schedule(profile).ownedBy(null).toList() shouldContainExactly schedule(profile).toList()
    }

    @Test
    fun `injectors divide a mix arm by arm, so each sends the same shape`() {
        // Not the same as dividing the merged schedule: an injector that took
        // every third departure of a mix would send the arms in the wrong
        // proportion, which is a different simulation.
        val arms = listOf(
            Arm(scenario("heavy") { exec("step") { } }, constantRate(30.0.perSecond, over = 2.seconds)),
            Arm(scenario("light") { exec("step") { } }, constantRate(3.0.perSecond, over = 2.seconds)),
        )

        (0 until 3).forEach { index ->
            val mine = arms.schedule().ownedBy(shard(index, 3)).toList()

            arms.forEach { arm ->
                val whole = arms.schedule().filter { it.arm === arm }.count()
                val sent = mine.count { it.arm === arm }
                withClue("injector $index sent its third of ${arm.scenario.name}") {
                    sent shouldBe whole / 3 + if (whole % 3 > index) 1 else 0
                }
            }
        }
    }

    private val sizes = listOf(1, 2, 3, 7)

    private fun owned(profile: InjectionProfile, index: Int, of: Int): List<Departure> =
        schedule(profile).ownedBy(shard(index, of)).toList()

    /**
     * One arm per profile, kept, because a `Departure` carries the arm it came
     * from and two `scenario` blocks are two values. A fresh arm per call would
     * make every comparison here fail on a field none of this is about.
     */
    private val arms = mutableMapOf<InjectionProfile, Arm>()

    private fun schedule(profile: InjectionProfile): Sequence<Departure> =
        listOf(arms.getOrPut(profile) { Arm(scenario("one") { exec("step") { } }, profile) }).schedule()

    private fun shard(index: Int, of: Int): Shard = Shard(index = index, of = of, startingAt = ORIGIN)

    private fun shapes(): List<Pair<String, InjectionProfile>> = listOf(
        "a constant rate" to constantRate(20.0.perSecond, over = 3.seconds),
        "a rate that sends seven" to constantRate(7.0.perSecond, over = 1.seconds),
        "a shape that sends nobody" to constantRate(1.0.perSecond, over = 500.milliseconds),
        "a ramp" to rampRate(from = 2.0.perSecond, to = 30.0.perSecond, over = 3.seconds),
        "stages" to hold(5.0.perSecond, 2.seconds).thenRampTo(15.0.perSecond, 2.seconds),
        "a drawn rate" to constantRate(40.0.perSecond, over = 2.seconds).randomized(seed = 3),
    )

    private companion object {
        val ORIGIN: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
