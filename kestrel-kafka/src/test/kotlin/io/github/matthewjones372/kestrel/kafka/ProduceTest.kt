package io.github.matthewjones372.kestrel.kafka

import io.github.matthewjones372.kestrel.Correlation
import io.github.matthewjones372.kestrel.Progress
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.TimedOut
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.engine.run
import io.github.matthewjones372.kestrel.fedBy
import io.github.matthewjones372.kestrel.feed
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.sessionKey
import io.github.matthewjones372.kestrel.step
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.errors.RecordTooLargeException
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * Whether the adapter is right needs no broker: a `MockProducer` sees exactly
 * what a real one would have been handed.
 */
class ProduceTest {

    private val account = sessionKey<Long>("account")

    private val submitted = step("submitted")

    private val byAccount = Correlation { session -> session[account] ?: -1L }

    private fun produced(
        producer: org.apache.kafka.clients.producer.Producer<ByteArray, ByteArray>,
        users: Int = 5,
    ): RunResult {
        val broker = kafka.brokers("nowhere:9092").over(producer)
        val trades = scenario("trades") {
            emit(
                submitted,
                broker.topic("trades")
                    .keyed { session -> session[account]?.toString()?.toByteArray() }
                    .value { session -> "trade for ${session[account]}".toByteArray() }
                    .correlatedBy(Header("trade-id")),
                keyedBy = byAccount,
            )
        }
        return trades.at(users.times(4).perSecond, over = 250.milliseconds)
            .fedBy(feed(account) { it })
            .run(Progress.silent)
    }

    private fun mock(): MockProducer<ByteArray, ByteArray> =
        MockProducer(true, ByteArraySerializer(), ByteArraySerializer())

    /**
     * A producer that takes every record and then refuses it, deterministically.
     *
     * Delegation rather than `MockProducer`'s own `errorNext`, which needs a
     * second thread racing the sends and a test that races is one that
     * eventually lies.
     */
    private class Refusing(
        private val real: MockProducer<ByteArray, ByteArray>,
        private val why: Exception,
    ) : org.apache.kafka.clients.producer.Producer<ByteArray, ByteArray> by real {

        override fun send(
            record: org.apache.kafka.clients.producer.ProducerRecord<ByteArray, ByteArray>,
        ) = java.util.concurrent.CompletableFuture<org.apache.kafka.clients.producer.RecordMetadata>()
            .also { it.completeExceptionally(why) }
    }

    @Test
    fun `every record carries the key, the value and the correlation the scenario named`() {
        val producer = mock()

        val result = produced(producer)

        val sent = producer.history()
        result[submitted].count shouldBe 5L
        sent.size shouldBe result[submitted].count.toInt()
        withClue("the key decides ordering and which broker takes the write") {
            sent.map { it.key().decodeToString() } shouldContainExactly (0 until sent.size).map { "$it" }
        }
        sent.forEachIndexed { user, record ->
            withClue("record $user") {
                record.topic() shouldBe "trades"
                record.value().decodeToString() shouldBe "trade for $user"
                record.headers().lastHeader("trade-id").value().decodeToString() shouldBe "$user"
            }
        }
    }

    @Test
    fun `the id in the header is the one the run counted the departure under`() {
        val producer = mock()

        produced(producer)

        withClue("stated once at the call site and threaded to both, so a run cannot match on an id it never sent") {
            producer.history().map { it.headers().lastHeader("trade-id").value().decodeToString().toLong() }
                .shouldContainExactly((0L until producer.history().size).toList())
        }
    }

    @Test
    fun `a send the broker refuses is a failed step naming why`() {
        val result = produced(Refusing(mock(), RecordTooLargeException("too big")))

        result[submitted].count shouldBe 5L
        withClue("the class rather than the message: a message carries a topic and an offset") {
            result[submitted].failedWith(BrokerRefused("RecordTooLargeException")) shouldBe 5L
        }
        result[submitted].ok.count shouldBe 0L
    }

    @Test
    fun `a broker that did not answer in time is TimedOut, under the one name every module uses`() {
        val result = produced(Refusing(mock(), org.apache.kafka.common.errors.TimeoutException("slow")))

        result[submitted].count shouldBe 5L
        result[submitted].failedWith(TimedOut) shouldBe 5L
    }

    @Test
    fun `a topic with no value says so rather than sending an empty record`() {
        val producer = mock()
        val broker = kafka.brokers("nowhere:9092").over(producer)

        val result = scenario("trades") {
            emit(submitted, broker.topic("trades"), keyedBy = byAccount)
        }.at(8.perSecond, over = 250.milliseconds).run(Progress.silent)

        result[submitted].failedWith(NothingToSend) shouldBe result[submitted].count
        producer.history().shouldContainExactly(emptyList())
    }

    @Test
    fun `brokers nobody named is a refusal rather than a producer pointed at nothing`() {
        val why = runCatching { kafka.producer }.exceptionOrNull()?.message.orEmpty()

        withClue(why) { why.contains("no brokers") shouldBe true }
    }
}
