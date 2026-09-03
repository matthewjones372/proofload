package io.github.matthewjones372.kestrel.http

import io.github.matthewjones372.kestrel.Session
import io.github.matthewjones372.kestrel.StepResult
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

private const val MEGABYTE = 1024 * 1024

/**
 * A body held as a `String` is a body held in memory, once per user. A test
 * that uploads anything large cannot run, and one that uploads something
 * larger than the heap cannot be written.
 */
class StreamedBodyTest {

    private val payload = "a".repeat(MEGABYTE)

    @Test
    fun `a megabyte arrives whole, without ever being one string in a request`() {
        serving("/uploads" to Reply(201)) { server ->
            val result = http.baseUrl(server.baseUrl)
                .put("/uploads")
                .bodyFrom(bytes = MEGABYTE.toLong()) { payload.byteInputStream() }
                .expecting(201)
                .run(Session.empty)

            result.shouldBeInstanceOf<StepResult.Ok>()
            server.received.single().body.length shouldBe MEGABYTE
        }
    }

    @Test
    fun `a length given is sent as content-length, and none is chunked`() {
        serving("/uploads" to Reply(201)) { server ->
            http.baseUrl(server.baseUrl)
                .put("/uploads")
                .bodyFrom(bytes = 5L) { "hello".byteInputStream() }
                .expecting(201)
                .run(Session.empty)

            server.received.single().headers["content-length"] shouldBe "5"
        }

        serving("/uploads" to Reply(201)) { server ->
            http.baseUrl(server.baseUrl)
                .put("/uploads")
                .bodyFrom { "hello".byteInputStream() }
                .expecting(201)
                .run(Session.empty)

            withClue("a length nobody knows is chunked rather than guessed") {
                server.received.single().headers["transfer-encoding"] shouldBe "chunked"
                server.received.single().body shouldBe "hello"
            }
        }
    }

    @Test
    fun `a retry sends the whole body again rather than an empty one`() {
        val opened = AtomicInteger()
        serving("/uploads" to Reply(503)) { server ->
            http.baseUrl(server.baseUrl)
                .put("/uploads")
                .bodyFrom(bytes = 5L) { opened.incrementAndGet(); "hello".byteInputStream() }
                .expecting(201)
                .retrying(times = 1, on = { it.status == 503 }, backingOff = 1.milliseconds)
                .run(Session.empty)

            withClue("a stream is read once, so each attempt asks for its own") {
                opened.get() shouldBe 2
                server.received.map { it.body } shouldBe listOf("hello", "hello")
            }
        }
    }
}
