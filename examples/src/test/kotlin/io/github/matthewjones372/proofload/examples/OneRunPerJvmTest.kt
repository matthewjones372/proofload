package io.github.matthewjones372.proofload.examples

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.proofload.Runs
import io.github.matthewjones372.proofload.baseline.readAll
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path

/**
 * The loop CI can run today: a `main` per run, a directory of files, and one
 * set of runs read back out of it. The forking is a shell's job, so ten calls
 * here stand in for the ten invocations — the file names have to keep them
 * apart either way, and two calls in one JVM are the harder half of that.
 */
class OneRunPerJvmTest {

    private lateinit var server: HttpServer

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/pay") { exchange ->
            val body = "{}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `ten invocations leave ten files that read back as one set of runs`(@TempDir directory: Path) {
        repeat(10) { main(arrayOf(directory.toString(), "http://localhost:${server.address.port}", "100")) }

        withClue("one file per invocation") { Files.list(directory).use { it.count() } shouldBe 10L }

        val runs = Runs.readAll(directory)
        runs.size shouldBe 10
        runs.merged["pay"].count shouldBe runs.each.sumOf { it["pay"].count }
        runs.merged["pay"].serviceTime.count shouldBe runs.merged["pay"].count
    }
}
