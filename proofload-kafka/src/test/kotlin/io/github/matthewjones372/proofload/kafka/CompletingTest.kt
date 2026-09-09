package io.github.matthewjones372.proofload.kafka

import io.github.matthewjones372.proofload.Correlation
import io.github.matthewjones372.proofload.Progress
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.completing
import io.github.matthewjones372.proofload.engine.run
import io.github.matthewjones372.proofload.fedBy
import io.github.matthewjones372.proofload.feed
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.sessionKey
import io.github.matthewjones372.proofload.step
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.clients.consumer.OffsetResetStrategy
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The answer arrives on another topic. What a run says about it — matched,
 * unmatched, still in flight — is 0040's, and this is the adapter under it.
 */
class CompletingTest {

    private val account = sessionKey<Long>("account")
    private val submitted = step("submitted")
    private val settled = step("settled")
    private val byAccount = Correlation { session -> session[account] ?: -1L }

    /**
     * A consumer that answers whatever the producer was handed, up to
     * [answering] of them.
     *
     * Wired to the producer so the id it returns is one an `emit` really
     * wrote, rather than one the test made up: whether those two are the same
     * id is the whole question. Delegating to a `MockConsumer` and overriding
     * `poll` rather than driving its scheduled poll tasks, which needs a second
     * thread racing the sends — and a test that races is one that eventually
     * lies.
     */
    private class Answering(
        private val producer: MockProducer<ByteArray, ByteArray>,
        private val answering: Int = Int.MAX_VALUE,
        private val header: String = "trade-id",
        private val real: MockConsumer<ByteArray, ByteArray> = MockConsumer(OffsetResetStrategy.LATEST),
    ) : Consumer<ByteArray, ByteArray> by real {

        private val settlements = TopicPartition("settlements", 0)

        private val answered = AtomicLong()

        override fun subscribe(topics: MutableCollection<String>) = Unit

        override fun poll(timeout: java.time.Duration): ConsumerRecords<ByteArray, ByteArray> {
            val sent = producer.history()
            val records = generateSequence { answered.get().toInt().takeIf { it < minOf(sent.size, answering) } }
                .map { at ->
                    val id = sent[at].headers().lastHeader("trade-id")?.value() ?: ByteArray(0)
                    ConsumerRecord<ByteArray, ByteArray>(
                        settlements.topic(),
                        settlements.partition(),
                        answered.getAndIncrement(),
                        ByteArray(0),
                        ByteArray(0),
                    ).also { it.headers().add(RecordHeader(header, id)) }
                }
                .toList()
            return ConsumerRecords(mapOf(settlements to records))
        }
    }

    /** This module's completions over [consumer], which a test can also hold to read its counters. */
    private fun completionsOver(
        producer: MockProducer<ByteArray, ByteArray>,
        consumer: Consumer<ByteArray, ByteArray>,
    ): TopicCompletions =
        kafka.brokers("nowhere:9092").over(producer)
            .topic("settlements").correlatedBy(Header("trade-id"))
            .completions(consumer) as TopicCompletions

    private fun ran(
        producer: MockProducer<ByteArray, ByteArray>,
        consumer: Consumer<ByteArray, ByteArray>,
        sink: io.github.matthewjones372.proofload.Completions = completionsOver(producer, consumer),
        users: Int = 4,
        draining: kotlin.time.Duration = 3.seconds,
    ): RunResult {
        val broker = kafka.brokers("nowhere:9092").over(producer)
        val trades = scenario("trades") {
            emit(
                submitted,
                broker.topic("trades")
                    .value { session -> "trade for ${session[account]}".toByteArray() }
                    .correlatedBy(Header("trade-id")),
                keyedBy = byAccount,
            )
        }
        return trades.at(users.times(4).perSecond, over = 250.milliseconds)
            .fedBy(feed(account) { it })
            .completing(
                settled,
                from = sink,
                drainingFor = draining,
            )
            .run(Progress.silent)
    }

    private fun mock() = MockProducer(true, ByteArraySerializer(), ByteArraySerializer())

    @Test
    fun `the id an emit wrote is the id the completions match on`() {
        val producer = mock()

        val result = ran(producer, Answering(producer))

        result[submitted].count shouldBe 4L
        withClue("every departure answered, on a topic that never saw the payload") {
            result[settled].count shouldBe 4L
            result[settled].unmatched shouldBe 0L
            result[settled].inFlight shouldBe 0L
        }
    }

    @Test
    fun `a departure nothing ever answered is unmatched, once it had the window to be`() {
        val producer = mock()

        val result = ran(producer, Answering(producer, answering = 2))

        withClue("two answered, and two given a whole second and never answered") {
            result[settled].count shouldBe 2L
            result[settled].unmatched shouldBe 2L
            result[settled].inFlight shouldBe 0L
        }
    }

    @Test
    fun `a record carrying no id this run can read is counted rather than dropped in silence`() {
        val producer = mock()
        // Four records on the topic, put there directly: what is being checked
        // is what the sink does with them, and running an engine to make them
        // would put a drain window between the claim and the assertion.
        repeat(4) { producer.send(ProducerRecord("trades", ByteArray(0), ByteArray(0))) }
        val completions = completionsOver(producer, Answering(producer, header = "something-else"))

        val read = completions.poll(1.seconds)

        withClue("otherwise they show as something unmatched with nothing to say the id was the problem") {
            read shouldBe emptyList()
            completions.unreadableRecords shouldBe 4L
        }
    }

    @Test
    fun `and every departure it should have answered is left unanswered`() {
        val producer = mock()

        val result = ran(producer, Answering(producer, header = "something-else"))

        withClue("nothing on that topic can be matched to anything this run sent") {
            result[settled].count shouldBe 0L
            (result[settled].unmatched + result[settled].inFlight) shouldBe 4L
        }
    }

    @Test
    fun `a completions topic with no correlation header named is refused where it is written`() {
        val why = shouldThrow<IllegalArgumentException> {
            kafka.brokers("nowhere:9092").topic("settlements").completions(Answering(mock()))
        }.message.orEmpty()

        withClue(why) { why shouldContain "no correlation header" }
    }
}
