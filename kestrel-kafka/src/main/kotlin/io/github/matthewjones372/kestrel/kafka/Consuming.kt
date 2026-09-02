package io.github.matthewjones372.kestrel.kafka

import io.github.matthewjones372.kestrel.Completions
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * A topic's records as the ids they answer for.
 *
 * The sink an engine drains, so `completing(settled, from = topic)` reads
 * exactly as 0040 wrote it. The id comes off the header [Topic.correlatedBy]
 * named, which is why the completion side needs no deserializer at all: the
 * payload is never looked at, so the schema registry this module refuses to
 * depend on is not needed to read one either.
 */
internal class TopicCompletions(
    private val topic: Topic,
    private val opened: () -> Consumer<ByteArray, ByteArray>,
) : Completions {

    private val header: String = requireNotNull(topic.header) {
        "${topic.name} has no correlation header: `correlatedBy(Header(\"trade-id\"))` names the one the " +
            "records carry, and without it there is nothing on them to match a departure to"
    }

    private val consumer: Consumer<ByteArray, ByteArray> by lazy {
        opened().also { it.subscribe(listOf(topic.name)) }
    }

    private val unreadable = AtomicLong()

    /**
     * Records on this topic carrying no readable id.
     *
     * Counted rather than dropped in silence: they answer no departure, so
     * every one of them is a record that will show up as something unmatched
     * with nothing on the page to say the id was the problem rather than the
     * consumer.
     */
    val unreadableRecords: Long get() = unreadable.get()

    override fun poll(within: Duration): List<Long> =
        consumer.poll(within.toJavaDuration()).mapNotNull { record ->
            val id = record.headers().lastHeader(header)?.value()?.decodeToString()?.toLongOrNull()
            if (id == null) unreadable.incrementAndGet()
            id
        }
}

/**
 * This topic's records as completions, read through [consumer].
 *
 * The consumer is the caller's, or one this module opens for the brokers the
 * topic was named from. A group of its own per run and `auto.offset.reset` of
 * `latest`: a completions sink is reading what this run produced, and starting
 * at the beginning of a topic would answer a run's departures with somebody
 * else's records from last week.
 */
fun Topic.completions(consumer: Consumer<ByteArray, ByteArray>? = null): Completions =
    TopicCompletions(this) { consumer ?: consumerFor(origin) }

private fun consumerFor(kafka: Kafka): Consumer<ByteArray, ByteArray> {
    require(kafka.brokers.isNotBlank()) {
        "no brokers: `kafka.brokers(\"localhost:9092\")` names them, or hand `completions` a consumer"
    }
    val settings = Properties().apply {
        setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.brokers)
        setProperty(ConsumerConfig.GROUP_ID_CONFIG, "kestrel-${System.nanoTime()}")
        setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
        // Nothing here commits: a run reads what it produced and is done, and
        // a committed offset would make the next run start somewhere it did
        // not choose.
        setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
        kafka.settings.forEach { (name, value) -> setProperty(name, value) }
    }
    return KafkaConsumer(settings, ByteArrayDeserializer(), ByteArrayDeserializer())
}
