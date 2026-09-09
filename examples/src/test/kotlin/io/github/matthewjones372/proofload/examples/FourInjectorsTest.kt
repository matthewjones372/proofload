package io.github.matthewjones372.proofload.examples

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.proofload.Shards
import io.github.matthewjones372.proofload.baseline.readAll
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Four JVMs on one host, which is the only thing a test can fork, standing in
 * for four hosts.
 *
 * What one host can still show is everything the partition claims: four
 * processes offer exactly the departures one would, each user leaves exactly
 * once, and the four files read back as one run rather than four experiments.
 * What it cannot show is the machine — four injectors sharing four cores is the
 * arrangement this spec exists to escape, so the rate here is small.
 */
@Tag("timing")
class FourInjectorsTest {

    private lateinit var server: HttpServer

    private val served = AtomicLong()

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/thing") { exchange ->
            served.incrementAndGet()
            val body = "{}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `four injectors given one instant read back as the run they were pieces of`(@TempDir into: Path) {
        // Far enough ahead that four JVMs all reach it, which is what the
        // instant is for: a host that is still loading classes at the moment
        // its neighbours depart smears the second they share.
        val together = Instant.now().plusSeconds(TO_START)

        val injectors = (0 until OF).map { index -> fork(index, into, together) }
        injectors.forEach { it.waitFor() }

        withClue("one file per injector, none of them overwritten") {
            Files.list(into).use { it.count() } shouldBe OF.toLong()
        }
        val shards = Shards.readAll(into)
        shards.of shouldBe OF
        withClue("200/s for a second, split four ways and merged back") {
            shards.merged["fetch"].count shouldBe USERS
            shards.each.sumOf { it["fetch"].count } shouldBe USERS
        }
        withClue("started ${shards.startedApart} apart") { shards.startedApart shouldBeLessThan 1.seconds }
    }

    /**
     * A JVM of its own running [OneInjector] on this test's classpath.
     *
     * `proofload.exclusive=false` because 0050's lock is per host and these four
     * share one: on four hosts each takes its own, and the lock is held before
     * the wait so that queueing for a machine cannot eat the alignment window.
     * Four on one host would serialise instead, which is the one thing a
     * single-host stand-in has to opt out of.
     */
    private fun fork(index: Int, into: Path, together: Instant): Process = ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Dproofload.exclusive=false",
        "-cp",
        System.getProperty("java.class.path"),
        "io.github.matthewjones372.proofload.examples.OneInjector",
        into.toString(),
        "http://localhost:${server.address.port}",
        SENDING_FOR.inWholeMilliseconds.toString(),
        index.toString(),
        OF.toString(),
        together.toEpochMilli().toString(),
    ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()

    private companion object {
        const val OF = 4
        const val TO_START = 8L
        const val USERS = 200L
        val SENDING_FOR = 1_000.milliseconds
    }
}
