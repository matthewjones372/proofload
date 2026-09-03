package io.github.matthewjones372.kestrel

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val began = Instant.parse("2026-09-03T09:00:00Z")

private val pay = step("pay")

/**
 * A run whose hold plainly missed can meet an aggregate goal because its ramp
 * was long enough and easy enough to pull the mixture under the line. Lengthen
 * the ramp and the verdict flips, which is a green test somebody earned by
 * editing the profile.
 */
class StageGoalTest {

    private fun ran(profile: InjectionProfile, seconds: Int, service: (Int) -> Long): RunResult {
        val recorder = RunRecorder(began)
        repeat(seconds) { at ->
            recorder.record(
                step = pay.name,
                failure = null,
                serviceTime = service(at).milliseconds,
                schedulingDelay = Duration.ZERO,
                at = at.seconds,
            )
        }
        return recorder.freeze().copy(plan = Plan(listOf(PlannedArm("paying", listOf(pay.name), profile))))
    }

    /** Three easy seconds, then three ten times slower: the mixture describes neither. */
    private fun rampThenHold(goals: List<Goal>): RunResult {
        val result = ran(
            hold(100.perSecond, over = 3.seconds).then(hold(200.perSecond, over = 3.seconds)),
            seconds = 6,
        ) { at -> if (at < 3) 10L else 100L }
        return result.copy(plan = result.plan.copy(goals = goals))
    }

    @Test
    fun `a verdict about the run names no stage, and an unstaged run answers once`() {
        val result = ran(hold(100.perSecond, over = 3.seconds), seconds = 3) { 10L }
            .let { it.copy(plan = it.plan.copy(goals = listOf(p99(pay) under 500.milliseconds))) }

        val verdicts = result.verdicts

        verdicts shouldHaveSize 1
        verdicts.single().stage shouldBe null
        verdicts.single().met shouldBe true
    }

    @Test
    fun `in every stage answers one verdict per stage, each naming its own`() {
        val result = rampThenHold(listOf((p99(pay) under 50.milliseconds).inEveryStage))

        val verdicts = result.verdicts

        verdicts shouldHaveSize 2
        withClue("the easy ramp met it and the hold after it did not") {
            verdicts.map { it.met } shouldBe listOf(true, false)
        }
        verdicts.map { it.stage?.index } shouldBe listOf(0, 1)
        result.metEveryGoal shouldBe false
    }

    @Test
    fun `the same goal without the wrapper meets it off the mixture`() {
        val wrapped = rampThenHold(listOf((p99(pay) under 50.milliseconds).inEveryStage))
        val aggregate = rampThenHold(listOf(p99(pay) under 200.milliseconds))

        withClue("one number over both stages is a number about neither, and it passes") {
            aggregate.metEveryGoal shouldBe true
            wrapped.metEveryGoal shouldBe false
        }
    }

    @Test
    fun `a stage goal missed by less than the timeline's own buckets cannot be told`() {
        val measured = rampThenHold(emptyList()).stages[1].responseTime.p99
        val result = rampThenHold(listOf((p99(pay) under measured - 1.milliseconds).inEveryStage))

        val hold = result.verdicts[1]

        withClue("a miss inside the bucket it was read off is the measurement, not the target") {
            hold.refused.shouldNotBeNull()
            hold.met shouldBe true
        }
        withClue(hold.refused?.why.orEmpty()) {
            hold.refused?.why.orEmpty().contains("buckets") shouldBe true
        }
    }

    @Test
    fun `a stage goal missed by many buckets fails, and says which stage`() {
        val measured = rampThenHold(emptyList()).stages[1].responseTime.p99
        val result = rampThenHold(listOf((p99(pay) under measured / 2).inEveryStage))

        val hold = result.verdicts[1]

        hold.met shouldBe false
        hold.refused shouldBe null
        hold.stage?.index shouldBe 1
    }

    @Test
    fun `a run nobody staged answers the wrapped goal once, about itself`() {
        val result = ran(hold(100.perSecond, over = 3.seconds), seconds = 3) { 10L }
            .let { it.copy(plan = it.plan.copy(goals = listOf((p99(pay) under 500.milliseconds).inEveryStage))) }

        result.verdicts shouldHaveSize 1
        withClue("one stage is the run, and a second verdict saying the same thing is noise") {
            result.verdicts.single().stage shouldBe null
        }
    }

    @Test
    fun `a stage that lost its schedule is named, where the run as a whole did not`() {
        val recorder = RunRecorder(began)
        repeat(6) { at ->
            recorder.record(
                step = pay.name,
                failure = null,
                serviceTime = 10.milliseconds,
                // The climb loses ground and the flat hold before it did not.
                schedulingDelay = if (at < 3) Duration.ZERO else 2.seconds,
                at = at.seconds,
            )
        }
        val climbing = hold(100.perSecond, over = 3.seconds)
            .then(rampRate(100.perSecond, 200.perSecond, over = 3.seconds))
        val result = recorder.freeze().copy(
            plan = Plan(
                arms = listOf(PlannedArm("paying", listOf(pay.name), climbing)),
                goals = listOf(Goal.KeptSchedule.inEveryStage),
            ),
        )

        val verdicts = result.verdicts

        withClue("a run that held its schedule flat and lost it climbing found a ceiling") {
            verdicts.map { it.met } shouldBe listOf(true, false)
        }
    }
}
