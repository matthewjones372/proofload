package io.github.matthewjones372.proofload.kafka

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.proofload.Correlation
import io.github.matthewjones372.proofload.Progress
import io.github.matthewjones372.proofload.Session
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.run
import io.github.matthewjones372.proofload.fedBy
import io.github.matthewjones372.proofload.feed
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.sessionKey
import io.github.matthewjones372.proofload.step
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * A serializer that fetches a schema is the caller's code on the departure
 * thread, and this is what that costs.
 *
 * The registry is stubbed on the JDK's own HTTP server rather than depended
 * on: Confluent's serializer is not on Maven Central, and nothing new is on
 * this module's test classpath for it. What is being shown is the seam — a
 * `(Session) -> ByteArray?` this module never looks inside — and where the
 * first record's round trip lands.
 */
class RegistryTest {

    private val account = sessionKey<Long>("account")
    private val submitted = step("submitted")
    private val byAccount = Correlation { session -> session[account] ?: -1L }

    private lateinit var registry: HttpServer

    private val lookups = AtomicInteger()

    @BeforeEach
    fun start() {
        registry = HttpServer.create(InetSocketAddress(0), 0)
        registry.createContext("/subjects/trades-value/versions/latest") { exchange ->
            lookups.incrementAndGet()
            // What a registry answers with, cut down to the one field a
            // serializer puts on the wire.
            val body = """{"id":42,"schema":"\"string\""}""".toByteArray()
            // Slow enough that the round trip is visible in a latency rather
            // than lost in the noise of a mock producer.
            Thread.sleep(REGISTRY_TAKES)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        registry.start()
    }

    @AfterEach
    fun stop() = registry.stop(0)

    /**
     * A caller's serializer: one lookup per subject, cached, then Confluent's
     * wire framing — a zero byte, the schema id, and the payload.
     *
     * Hand-written because the real one cannot be depended on here, and
     * because what this module promises is that it never looks inside.
     */
    private inner class Registering : (Session) -> ByteArray? {

        // Cached under a lock, as a real serializer caches per subject: five
        // users reaching this at once make one lookup and four waits, not five
        // lookups.
        private val schemaId: Int by lazy { fetch() }

        override fun invoke(session: Session): ByteArray {
            val id = schemaId
            val payload = "trade for ${session[account]}".toByteArray()
            return ByteArrayOutputStream().apply {
                write(0)
                write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(id).array())
                write(payload)
            }.toByteArray()
        }

        private fun fetch(): Int {
            val at = URI.create("http://localhost:${registry.address.port}/subjects/trades-value/versions/latest")
            val answered = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(at).build(), HttpResponse.BodyHandlers.ofString())
            return Regex(""""id":(\d+)""").find(answered.body())!!.groupValues[1].toInt()
        }
    }

    @Test
    fun `a caller's serializer resolves its schema against the registry, once`() {
        val producer = MockProducer(true, null, ByteArraySerializer(), ByteArraySerializer())
        val broker = kafka.brokers("nowhere:9092").over(producer)
        val serializer = Registering()

        val result = scenario("trades") {
            emit(
                submitted,
                broker.topic("trades").value(serializer).correlatedBy(Header("trade-id")),
                keyedBy = byAccount,
            )
        }.at(20.perSecond, over = 250.milliseconds).fedBy(feed(account) { it }).run(Progress.silent)

        result[submitted].count shouldBe 5L
        withClue("once per subject and cached, which is what the first record pays for") {
            lookups.get() shouldBe 1
        }
        withClue("Confluent's framing: a zero byte, then the schema id") {
            producer.history().forEach { record ->
                record.value()[0] shouldBe 0.toByte()
                ByteBuffer.wrap(record.value(), 1, Int.SIZE_BYTES).int shouldBe 42
            }
        }
    }

    @Test
    fun `the round trip lands in the step's own latency, and not in the generator's lateness`() {
        val producer = MockProducer(true, null, ByteArraySerializer(), ByteArraySerializer())
        val broker = kafka.brokers("nowhere:9092").over(producer)

        val result = scenario("trades") {
            emit(
                submitted,
                broker.topic("trades").value(Registering()).correlatedBy(Header("trade-id")),
                keyedBy = byAccount,
            )
        }.at(20.perSecond, over = 250.milliseconds).fedBy(feed(account) { it }).run(Progress.silent)

        withClue("max ${result[submitted].serviceTime.max}, p50 ${result[submitted].serviceTime.p50}") {
            (result[submitted].serviceTime.max.inWholeMilliseconds >= REGISTRY_TAKES) shouldBe true
            withClue("and only the records that departed during the fetch paid it") {
                (result[submitted].serviceTime.p50.inWholeMilliseconds < REGISTRY_TAKES) shouldBe true
            }
        }
        withClue("behind was ${result.behind.max}") {
            // Where it does *not* land, which is worth a test of its own: each
            // user runs on a thread of its own, so one blocked in a serializer
            // holds up nobody's departure. The cost is this step's latency,
            // and reading it as the broker's is the mistake to warn about.
            (result.behind.max.inWholeMilliseconds < REGISTRY_TAKES) shouldBe true
        }
    }

    private companion object {
        const val REGISTRY_TAKES = 60L
    }
}
