package io.github.matthewjones372.proofload.kafka

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.junit.jupiter.api.Test
import java.util.Properties
import kotlin.time.Duration.Companion.seconds

/**
 * Whether a broker built out of `kafka-clients`' own protocol classes can
 * satisfy a real `KafkaProducer` over a socket.
 */
class FakeBrokerTest {

    @Test
    fun `a real producer connects, batches and produces over a socket`() {
        FakeBroker().use { broker ->
            val settings = Properties().apply {
                setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.bootstrap)
                setProperty(ProducerConfig.ACKS_CONFIG, "all")
                setProperty(ProducerConfig.LINGER_MS_CONFIG, "0")
                setProperty(ProducerConfig.MAX_BLOCK_MS_CONFIG, "10000")
            }

            KafkaProducer(settings, ByteArraySerializer(), ByteArraySerializer()).use { producer ->
                val acked = (0 until 100).map { number ->
                    producer.send(
                        ProducerRecord(FakeBroker.TOPIC, "$number".toByteArray(), "trade $number".toByteArray()),
                    )
                }.map { it.get() }

                withClue("the accumulator, the sender thread and the ack path, over a real socket") {
                    acked.size shouldBe 100
                    acked.all { it.topic() == FakeBroker.TOPIC } shouldBe true
                }
            }

            broker.recordsProduced shouldBe 100L
        }
    }

    @Test
    fun `a real consumer assigns a partition and fetches what was produced`() {
        FakeBroker().use { broker ->
            KafkaProducer(
                Properties().apply {
                    setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.bootstrap)
                    setProperty(ProducerConfig.ACKS_CONFIG, "all")
                },
                ByteArraySerializer(),
                ByteArraySerializer(),
            ).use { producer ->
                (0 until PRODUCED).forEach { number ->
                    producer.send(ProducerRecord(FakeBroker.TOPIC, null, "trade $number".toByteArray()))
                }
                producer.flush()
            }

            val settings = Properties().apply {
                setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.bootstrap)
                setProperty(ConsumerConfig.GROUP_ID_CONFIG, "spike")
                setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
                // Both default to 30s, which is the whole of PATIENCE below: a
                // client that spent one request timeout retrying left the loop
                // no budget to see the retry succeed, so a loaded runner failed
                // this on content while the real cause was the clock. Short
                // enough that several attempts fit inside the deadline.
                setProperty(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000")
                setProperty(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "10000")
            }

            val read = KafkaConsumer(settings, ByteArrayDeserializer(), ByteArrayDeserializer()).use { consumer ->
                // Assigned by hand rather than subscribed: a group needs a
                // coordinator, which is several more requests and is not what
                // this is proving.
                val partition = TopicPartition(FakeBroker.TOPIC, 0)
                consumer.assign(listOf(partition))
                consumer.seekToBeginning(listOf(partition))
                // Until they arrive or the clock runs out, rather than a fixed
                // number of polls: the first few are spent on metadata and
                // offsets, and how many that takes is the machine's business.
                val seen = mutableListOf<String>()
                val giveUp = System.nanoTime() + PATIENCE.inWholeNanoseconds
                while (seen.size < PRODUCED && System.nanoTime() < giveUp) {
                    consumer.poll(java.time.Duration.ofMillis(200))
                        .forEach { seen += it.value().decodeToString() }
                }
                seen
            }

            // The deadline is a guard against a hang, not a claim about speed.
            // Saying which one ended the loop keeps a slow runner from being
            // reported as a broker that fetched the wrong records.
            withClue("only ${read.size} of $PRODUCED records arrived within $PATIENCE: $read") {
                read.size shouldBe PRODUCED
            }
            withClue("$read") { read.take(PRODUCED) shouldBe (0 until PRODUCED).map { "trade $it" } }
        }
    }

    /**
     * The same round trip with a flush between every record, so the log holds ten
     * batches rather than however many the accumulator happened to make.
     *
     * This is the case that was broken and invisible. A client's batches all arrive
     * claiming to start at offset zero; while there was one of them, handing it back
     * unchanged worked. With several, a consumer past zero discarded each later batch
     * as already seen, and the run read as a gap in the middle — records 0 to 3 and 8
     * to 9 arriving, 4 to 7 gone. It surfaced as one JVM of three failing, because
     * how many batches the accumulator makes is a matter of timing.
     */
    @Test
    fun `a consumer reads every batch, and not only the first`() {
        FakeBroker().use { broker ->
            KafkaProducer(
                Properties().apply {
                    setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.bootstrap)
                    setProperty(ProducerConfig.ACKS_CONFIG, "all")
                    setProperty(ProducerConfig.LINGER_MS_CONFIG, "0")
                },
                ByteArraySerializer(),
                ByteArraySerializer(),
            ).use { producer ->
                (0 until PRODUCED).forEach { number ->
                    producer.send(ProducerRecord(FakeBroker.TOPIC, null, "trade $number".toByteArray()))
                    // One batch per record, which is the point.
                    producer.flush()
                }
            }

            val settings = Properties().apply {
                setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.bootstrap)
                setProperty(ConsumerConfig.GROUP_ID_CONFIG, "batches")
                setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
                setProperty(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000")
                setProperty(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "10000")
            }

            val read = KafkaConsumer(settings, ByteArrayDeserializer(), ByteArrayDeserializer()).use { consumer ->
                val partition = TopicPartition(FakeBroker.TOPIC, 0)
                consumer.assign(listOf(partition))
                consumer.seekToBeginning(listOf(partition))
                val seen = mutableListOf<String>()
                val giveUp = System.nanoTime() + PATIENCE.inWholeNanoseconds
                while (seen.size < PRODUCED && System.nanoTime() < giveUp) {
                    consumer.poll(java.time.Duration.ofMillis(200))
                        .forEach { seen += it.value().decodeToString() }
                }
                seen
            }

            withClue("ten batches, and a gap in the middle is what a lost one looks like: $read") {
                read shouldBe (0 until PRODUCED).map { "trade $it" }
            }
        }
    }

    private companion object {
        const val PRODUCED = 10

        val PATIENCE = 30.seconds
    }
}
