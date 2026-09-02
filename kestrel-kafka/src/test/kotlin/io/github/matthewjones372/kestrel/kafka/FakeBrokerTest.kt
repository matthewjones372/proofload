package io.github.matthewjones372.kestrel.kafka

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

            withClue("$read") { read.take(PRODUCED) shouldBe (0 until PRODUCED).map { "trade $it" } }
        }
    }

    private companion object {
        const val PRODUCED = 10

        val PATIENCE = 30.seconds
    }
}
