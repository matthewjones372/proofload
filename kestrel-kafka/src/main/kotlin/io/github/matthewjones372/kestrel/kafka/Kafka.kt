package io.github.matthewjones372.kestrel.kafka

import io.github.matthewjones372.kestrel.Session
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.ByteArraySerializer
import java.util.Properties

/**
 * How many replicas must have the record before the broker answers.
 *
 * Named rather than a string, because it decides what the produce step
 * measures: [All] times an in-sync-replica round trip, [Leader] one write, and
 * [None] times nothing at all — the send is answered before it has left. It is
 * printed wherever the produce latency is, since a p99 means three different
 * things across these three.
 */
enum class Acks(internal val wire: String) {
    All("all"),
    Leader("1"),
    None("0"),
}

/**
 * Where records are produced to. A value, so a test against two clusters names
 * two of these rather than setting a global the second one overwrites.
 */
class Kafka internal constructor(
    internal val brokers: String,
    internal val acks: Acks = Acks.All,
    internal val settings: Map<String, String> = emptyMap(),
    private val opened: (Kafka) -> Producer<ByteArray, ByteArray> = ::producerFor,
) {

    /**
     * The producer every record from here shares, opened once and lazily.
     *
     * One for the run rather than one per user: a `KafkaProducer` is
     * thread-safe and built to be shared — it is a connection pool with a
     * sender thread and an accumulator behind it — so one per virtual user
     * would build both inside the sample. That is the opposite of the per-user
     * cookie jar and for the opposite reason: a jar is a user's own state, a
     * producer is not.
     */
    val producer: Producer<ByteArray, ByteArray> by lazy { opened(this) }

    fun brokers(list: String): Kafka = Kafka(list, acks, settings, opened)

    /** What the broker must have done before it answers, which is what the produce step then times. */
    fun acks(acks: Acks): Kafka = Kafka(brokers, acks, settings, opened)

    /**
     * Any other producer setting, by its Kafka name.
     *
     * Passed through rather than wrapped: this module has no opinion about
     * `batch.size`, and one setting given a Kotlin name here is one a reader
     * cannot look up in Kafka's own documentation.
     */
    fun setting(name: String, value: String): Kafka = Kafka(brokers, acks, settings + (name to value), opened)

    /** Produces through [producer] instead of one opened for [brokers] — a mock, or a caller's own. */
    fun over(producer: Producer<ByteArray, ByteArray>): Kafka = Kafka(brokers, acks, settings) { producer }

    /** Records go to [name], keyed and valued by what the scenario reads out of a session. */
    fun topic(name: String): Topic {
        require(name.isNotBlank()) { "a topic has a name" }
        return Topic(this, name)
    }
}

/**
 * One record's worth of a session: what to send to [topic], and under which
 * key.
 *
 * Both are `(Session) -> ByteArray?` the caller supplies, so a Confluent
 * `KafkaAvroSerializer`, a plain string encoding or a hand-rolled one all work
 * and this module never learns which.
 */
class Topic internal constructor(
    internal val origin: Kafka,
    val name: String,
    internal val key: ((Session) -> ByteArray?)? = null,
    internal val value: ((Session) -> ByteArray?)? = null,
    internal val header: String? = null,
) {

    /** The partition key, which is what decides ordering and which broker takes the write. */
    fun keyed(of: (Session) -> ByteArray?): Topic = Topic(origin, name, of, value, header)

    fun value(of: (Session) -> ByteArray?): Topic = Topic(origin, name, key, of, header)

    /**
     * Where the correlation id rides: a header, by name.
     *
     * A header rather than the payload, because the completion side then needs
     * no deserializer — which is the only option that does not drag the schema
     * registry back in. The key is a second option a caller can take by hand;
     * reading an id out of a payload is refused rather than supported.
     */
    fun correlatedBy(header: Header): Topic = Topic(origin, name, key, value, header.name)
}

/** A header name, so a correlation cannot be given a topic by mistake. */
@JvmInline
value class Header(val name: String) {
    init {
        require(name.isNotBlank()) { "a header has a name" }
    }
}

/**
 * The producer Kafka itself builds for these settings.
 *
 * Bytes in and bytes out: the caller's lambdas have already serialized, so
 * anything else here would be a second encoding this module chose.
 *
 * `linger.ms` is zero unless a caller says otherwise. A producer that lingers
 * turns an evenly spaced departure stream into bursts at the broker, so the
 * arrivals figure would report a smoothness the broker never saw.
 */
private fun producerFor(kafka: Kafka): Producer<ByteArray, ByteArray> {
    require(kafka.brokers.isNotBlank()) {
        "no brokers: `kafka.brokers(\"localhost:9092\")` names them, or `over(producer)` supplies its own"
    }
    val settings = Properties().apply {
        setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.brokers)
        setProperty(ProducerConfig.ACKS_CONFIG, kafka.acks.wire)
        setProperty(ProducerConfig.LINGER_MS_CONFIG, "0")
        kafka.settings.forEach { (name, value) -> setProperty(name, value) }
    }
    return KafkaProducer(settings, ByteArraySerializer(), ByteArraySerializer())
}

/** No brokers of its own: `kafka.brokers(...)` names them, or `over(producer)` supplies a producer. */
val kafka: Kafka = Kafka("")
