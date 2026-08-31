package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Two JVMs, because one cannot show what two do to each other: nothing here is
 * shared between the processes but the machine and the file that stands for it.
 */
class AcrossProcessesTest {

    @Test
    fun `a second process is not inside a run until the first has left one`(@TempDir machine: Path) {
        val lock = machine.resolve(LOCK)
        val first = machine.resolve("first-may-go")
        val second = machine.resolve("second-may-go")

        Forked(lock, first).use { holder ->
            holder.said("in").shouldNotBeNull()
            Forked(lock, second).use { queued ->
                withClue("the machine is taken, so nothing else can be inside a run") {
                    queued.said("in", within = 2.seconds) shouldBe null
                }
                Files.createFile(first)
                val left = holder.said("out").shouldNotBeNull().at()
                val entered = queued.said("in").shouldNotBeNull().at()
                withClue("one after the other: the second entered at $entered, the first left at $left") {
                    entered shouldBeGreaterThanOrEqual left
                }
                Files.createFile(second)
            }
        }
    }

    @Test
    fun `killing the process that holds the machine frees it for the next`(@TempDir machine: Path) {
        val lock = machine.resolve(LOCK)
        val killed = Forked(lock, machine.resolve("never"))
        killed.said("in").shouldNotBeNull()
        killed.kill()

        val next = machine.resolve("next-may-go")
        Forked(lock, next).use { after ->
            withClue("the OS releases a dead holder's lock, so there is nothing here to reap") {
                after.said("in").shouldNotBeNull()
            }
            Files.createFile(next)
        }
    }

    @Test
    fun `kestrel exclusive false lets two processes be inside a run at once`(@TempDir machine: Path) {
        val lock = machine.resolve(LOCK)
        val held = machine.resolve("holder-may-go")
        val beside = machine.resolve("beside-may-go")

        Forked(lock, held).use { holder ->
            holder.said("in").shouldNotBeNull()
            Forked(lock, beside, exclusive = false).use { overlapping ->
                withClue("the opt-out is what a benchmark of the tool runs its several processes with") {
                    overlapping.said("in").shouldNotBeNull()
                }
                Files.createFile(beside)
            }
            Files.createFile(held)
        }
    }

    @Test
    fun `a wait past its ceiling fails naming the process that has the machine`(@TempDir machine: Path) {
        val lock = machine.resolve(LOCK)

        Forked(lock, machine.resolve("never")).use { holder ->
            holder.said("in").shouldNotBeNull()

            val gaveUp =
                withProperties("kestrel.exclusive.file" to lock.toString(), "kestrel.exclusive.timeout" to "1") {
                    shouldThrow<IllegalStateException> {
                        Kestrel(engine = Blank().exclusive()).run(waitingRoom)
                    }
                }

            withClue("an hour of queueing looks like a deadlock, so giving up says whose machine it is") {
                gaveUp.message shouldContain "${holder.pid}"
            }
        }
    }
}

/** The last field of an `in` or `out` line: when that process was inside a run. */
private fun String.at(): Long = substringAfterLast(' ').toLong()

private val waitingRoom = scenario("waiting") { exec("nothing") { } }.at(1.perSecond, over = 1.milliseconds)

/**
 * A JVM of its own running [HoldsTheMachine], pointed at [lock] and let out of
 * its run by [release].
 *
 * The classpath is this test JVM's, so a fork runs the same build's classes
 * without a Gradle task to declare or a jar to assemble.
 */
internal class Forked(
    lock: Path,
    release: Path,
    exclusive: Boolean = true,
) : AutoCloseable {

    private val process = ProcessBuilder(
        listOfNotNull(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Dkestrel.exclusive.file=$lock",
            "-Dkestrel.exclusive=false".takeUnless { exclusive },
            "-cp",
            System.getProperty("java.class.path"),
            "io.github.matthewjones372.kestrel.engine.HoldsTheMachine",
            release.toString(),
        ),
    ).redirectErrorStream(true).start()

    private val arriving = LinkedBlockingQueue<String>()

    private val everything = CopyOnWriteArrayList<String>()

    init {
        Thread.ofPlatform().daemon().start {
            process.inputStream.bufferedReader().forEachLine {
                everything += it
                arriving.put(it)
            }
        }
    }

    val pid: Long get() = process.pid()

    /** Its first line starting with [word], or nothing where none arrived [within]. */
    fun said(word: String, within: Duration = PATIENCE): String? {
        val by = System.nanoTime() + within.inWholeNanoseconds
        return generateSequence { arriving.poll(by - System.nanoTime(), TimeUnit.NANOSECONDS) }
            .firstOrNull { it.startsWith(word) }
    }

    /**
     * Whether it has ever said a line starting with [word]. Worth asking only
     * after a line that comes later has arrived, since a process that has said
     * nothing yet is not a process that will not.
     */
    fun everSaid(word: String): Boolean = everything.any { it.startsWith(word) }

    fun kill() {
        process.destroyForcibly().waitFor()
    }

    override fun close() {
        process.destroyForcibly().waitFor()
    }
}

private const val LOCK = "kestrel-machine.lock"

private val PATIENCE = 15.seconds
