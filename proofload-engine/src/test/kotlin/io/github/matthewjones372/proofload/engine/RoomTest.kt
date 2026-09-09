package io.github.matthewjones372.proofload.engine

import io.github.matthewjones372.proofload.Headroom
import io.github.matthewjones372.proofload.Progress
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.ranOutOfRoom
import io.github.matthewjones372.proofload.scenario
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A run that ran out of descriptors or ports was measuring itself. These
 * sample what this process had left, so a failure count has somewhere to be
 * explained from.
 */
class RoomTest {

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `a run reports a descriptor peak above zero and under this JVM's limit`() {
        val touching = scenario("touching") { exec("touch") { } }

        val result = touching.at(20.perSecond, over = 1.seconds).run(Progress.silent)

        val openFiles = result.limits.openFiles.shouldBeInstanceOf<Headroom.Measured>()
        withClue("this JVM has files open, and fewer than it is allowed") {
            openFiles.peak shouldBeGreaterThan 0L
            (openFiles.peak < openFiles.limit) shouldBe true
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `an ordinary run has not run out of room`() {
        val touching = scenario("touching") { exec("touch") { } }

        val result = touching.at(20.perSecond, over = 1.seconds).run(Progress.silent)

        withClue("${result.limits}") { result.ranOutOfRoom() shouldBe false }
    }

    @Test
    fun `a source that is not there reads absent with a reason, never a zero`() {
        val watch = watchForRoom(interval = 50.milliseconds, sources = emptyList())

        val limits = watch.stop()

        limits.openFiles.shouldBeInstanceOf<Headroom.Absent>().because shouldContain "open files"
        limits.ports.shouldBeInstanceOf<Headroom.Absent>().because shouldContain "ephemeral ports"
        limits.cpu.shouldBeInstanceOf<Headroom.Absent>().because shouldContain "CPU"
    }

    @Test
    fun `the peak is the highest a source reached, not its last reading`() {
        val readings = ArrayDeque(listOf(10L, 900L, 20L))
        val falling = object : Room.Source {
            override val name: String get() = Room.OPEN_FILES
            override fun read(): Long? = readings.removeFirstOrNull()
            override fun headroom(peak: Long): Headroom = Headroom.Measured(peak, 1_000L)
        }
        val room = Room(listOf(falling))

        repeat(3) { room.observe() }

        room.frozen().openFiles shouldBe Headroom.Measured(peak = 900L, limit = 1_000L)
    }
}
