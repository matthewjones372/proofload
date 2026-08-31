package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.Engine
import io.github.matthewjones372.kestrel.RunRecorder
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.Simulation
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

/**
 * What happens where the machine will not lend a lock. Every case here is a
 * run that finishes: a load test that died because a file could not be opened
 * is the setup burden this design exists to avoid, arriving by another door.
 */
class MachineLockTest {

    @Test
    fun `a lock file that cannot be opened leaves the run to finish anyway`(@TempDir directory: Path) {
        val occupied = Files.createFile(directory.resolve("not-a-directory"))

        val said = printed {
            withProperties(FILE to occupied.resolve(LOCK).toString()) {
                repeat(2) { Kestrel(engine = Blank().exclusive()).run(nothing) }
            }
        }

        withClue("both runs finished, and the guarantee they lost was named once rather than per run") {
            said.filter { it.startsWith("kestrel: could not lock") } shouldHaveSize 1
        }
    }

    @Test
    fun `a read-only directory downgrades the guarantee rather than failing the run`(@TempDir directory: Path) {
        val readOnly = Files.createDirectory(directory.resolve("read-only"))
        Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-xr-xr-x"))
        assumeTrue(!Files.isWritable(readOnly), "this user writes into read-only directories, so nothing is proved")

        val said = printed {
            withProperties(FILE to readOnly.resolve(LOCK).toString()) {
                Kestrel(engine = Blank().exclusive()).run(nothing)
            }
        }

        said.filter { it.startsWith("kestrel: could not lock") } shouldHaveSize 1
    }

    @Test
    fun `kestrel exclusive false asks the machine for nothing`(@TempDir directory: Path) {
        val lock = directory.resolve(LOCK)

        withProperties(FILE to lock.toString(), "kestrel.exclusive" to "false") {
            Kestrel(engine = Blank().exclusive()).run(nothing)
        }

        withClue("a run that opted out queues for nothing, so it leaves nothing to queue on") {
            Files.exists(lock) shouldBe false
        }
    }
}

private val nothing = scenario("nothing") { exec("nothing") { } }.at(1.perSecond, over = 1.milliseconds)

/** Answers a run with an empty result: what these tests are about is the lock in front of one. */
internal class Blank : Engine {

    private val answer = RunRecorder(Instant.now()).freeze()

    override fun run(simulation: Simulation): RunResult = answer
}

/** [work] with [properties] set, and the properties as they were afterwards. */
internal fun <T> withProperties(vararg properties: Pair<String, String>, work: () -> T): T {
    val were = properties.map { (name, _) -> name to System.getProperty(name) }
    properties.forEach { (name, value) -> System.setProperty(name, value) }
    return try {
        work()
    } finally {
        were.forEach { (name, was) -> was?.let { System.setProperty(name, it) } ?: System.clearProperty(name) }
    }
}

/** The lines [work] printed. Tests here run one at a time in this JVM, so stdout is theirs to take. */
internal fun printed(work: () -> Unit): List<String> {
    val buffer = ByteArrayOutputStream()
    val was = System.out
    System.setOut(PrintStream(buffer, true, StandardCharsets.UTF_8))
    try {
        work()
    } finally {
        System.setOut(was)
    }
    return buffer.toString(StandardCharsets.UTF_8).lines().filter { it.isNotBlank() }
}

private const val FILE = "kestrel.exclusive.file"

private const val LOCK = "kestrel-machine.lock"
