package io.github.matthewjones372.kestrel.benchmarks

import io.github.matthewjones372.kestrel.Correlation
import io.github.matthewjones372.kestrel.Progress
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.engine.run
import io.github.matthewjones372.kestrel.fedBy
import io.github.matthewjones372.kestrel.feed
import io.github.matthewjones372.kestrel.kafka.Header
import io.github.matthewjones372.kestrel.kafka.emit
import io.github.matthewjones372.kestrel.kafka.kafka
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.sessionKey
import io.github.matthewjones372.kestrel.step
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.Metric
import org.apache.kafka.common.MetricName
import org.apache.kafka.common.PartitionInfo
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.Uuid
import org.apache.kafka.common.errors.ProducerFencedException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.time.Duration as JavaDuration

/**
 * What the Kafka path costs, with the broker taken out of it.
 *
 * The same rule as the other ceiling: each row is one rate, and a rate kept its
 * schedule when the median departure left within the budget of when it was due.
 * What is left in the path is exactly what `kestrel-kafka` adds — the caller's
 * serializer, the record, the correlation header, and waiting on the send's
 * future — so the number bounds the adapter.
 *
 * **The accumulator is not in it, and the figure is worthless read as if it
 * were.** A real `KafkaProducer` batches into an accumulator, hands batches to
 * a sender thread, and blocks up to `max.block.ms` when that fills; none of
 * that is here. This says what the adapter costs, not what producing costs.
 */
fun main() {
    val measured = KAFKA_RATES.map(::measureProducing)

    val page = kafkaReport(measured)
    println(page)

    val into = Path.of("build/reports/kestrel/kafka-ceiling.md")
    Files.createDirectories(into.parent)
    Files.writeString(into, page)
    println("written to ${into.toAbsolutePath()}")
}

private val account = sessionKey<Long>("account")

private val submitted = step("submitted")

private fun measureProducing(rate: Int): Measured {
    val broker = kafka.brokers("nowhere:9092").over(Discarding())
    val producing = scenario("producing") {
        emit(
            submitted,
            broker.topic("trades")
                .keyed { session -> session[account]?.toString()?.toByteArray() }
                // A caller's serializer, kept trivial on purpose: a real one is
                // the caller's cost and would be measured as this tool's.
                .value { session -> "trade for ${session[account]}".toByteArray() }
                .correlatedBy(Header("trade-id")),
            keyedBy = Correlation { session -> session[account] ?: 0L },
        )
    }

    val feeding = feed(account) { it }
    // Thrown away, as the other sweep throws one away: the first run pays for
    // class loading and JIT, and charging that to the lowest rate would report
    // a ceiling that moves with the order the rates happen to be in.
    producing.at(rate.perSecond, over = KAFKA_WARMUP).fedBy(feeding).run(Progress.silent)

    val measured = producing.at(rate.perSecond, over = KAFKA_WINDOW).fedBy(feeding).run(Progress.silent)
    return Measured(rate, measured, loadAverage())
}

/**
 * A producer that answers immediately and keeps nothing.
 *
 * Not `MockProducer`, which retains every record it is handed: at a hundred
 * thousand a second for five seconds that is half a million records held live,
 * and the allocation and collection of that list would be measured as the
 * adapter's cost. What is wanted here is the adapter with the broker removed,
 * not a growing list.
 */
private class Discarding : Producer<ByteArray, ByteArray> {

    private val done: Future<RecordMetadata> = CompletableFuture.completedFuture(
        RecordMetadata(TopicPartition("trades", 0), 0L, 0, 0L, 0, 0),
    )

    override fun send(record: ProducerRecord<ByteArray, ByteArray>): Future<RecordMetadata> = done

    override fun send(
        record: ProducerRecord<ByteArray, ByteArray>,
        callback: org.apache.kafka.clients.producer.Callback?,
    ): Future<RecordMetadata> {
        callback?.onCompletion(done.get(), null)
        return done
    }

    override fun initTransactions() = Unit
    override fun beginTransaction() = Unit
    override fun sendOffsetsToTransaction(
        offsets: MutableMap<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata>,
        groupMetadata: ConsumerGroupMetadata,
    ) = throw ProducerFencedException("no transactions here")

    @Deprecated("kept because the interface still declares it", ReplaceWith("sendOffsetsToTransaction"))
    override fun sendOffsetsToTransaction(
        offsets: MutableMap<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata>,
        consumerGroupId: String,
    ) = throw ProducerFencedException("no transactions here")

    override fun commitTransaction() = Unit
    override fun abortTransaction() = Unit
    override fun flush() = Unit
    override fun partitionsFor(topic: String): MutableList<PartitionInfo> = mutableListOf()
    override fun metrics(): MutableMap<MetricName, out Metric> = mutableMapOf()
    override fun clientInstanceId(timeout: JavaDuration): Uuid = Uuid.ZERO_UUID
    override fun close() = Unit
    override fun close(timeout: JavaDuration) = Unit
}

internal fun kafkaReport(measured: List<Measured>): String {
    val ceiling = measured.lastOrNull { it.keptSchedule && it.answeredEverything }
    return (
        listOf(
            "# What the Kafka path costs",
            "",
            "One rate a row, each a $KAFKA_WINDOW run, and a rate kept its schedule when the",
            "median departure left within $KAFKA_BUDGET of when it was due — the same rule the",
            "other ceiling uses, so the two can be read against each other.",
            "",
            "The producer answers immediately and keeps nothing, so what is left between the",
            "departure the profile promised and the sample the recorder took is what",
            "`kestrel-kafka` adds: the serializer lambda, the record, the correlation header,",
            "and waiting on the send's future.",
            "",
            "**The accumulator is not in this number.** A real `KafkaProducer` batches into an",
            "accumulator, hands batches to a sender thread, and blocks up to `max.block.ms`",
            "when that fills. None of that is here. This bounds the adapter — the part this",
            "repository wrote — and says nothing about what producing to a broker costs.",
            "",
            "**Read the p99 column before the verdict.** The median rule is what names the",
            "ceiling, and it is the rule the other sweep uses, which is the only reason the",
            "two are comparable at all. But a median can sit inside a millisecond while the",
            "tail is hundreds of them, and it does here: the rate this table calls a ceiling",
            "is not a rate anyone should drive. Where the p99 column climbs is where the",
            "adapter stopped keeping up for the users that matter.",
            "",
            "`fellBehind()` is not reported here, for the reason the null-step sweep does not",
            "report it either: it asks whether the backlog is large against the response time",
            "it inflates, and a producer that answers immediately has no response time to",
            "speak of, so it says yes at every rate. A column that is constant tells a reader",
            "nothing and reads like it does.",
            "",
            "| Rate | Records | Failed | Behind p50 | Behind p99 | Behind max | " +
                "p50 within $KAFKA_BUDGET |",
            "|---:|---:|---:|---:|---:|---:|:---:|",
        ) + measured.map { it.kafkaRow() } + listOf(
            "",
            ceiling?.let {
                "Ceiling for the adapter: **${it.rate.grouped()} a second** on this machine, with no " +
                    "accumulator in the path."
            } ?: "No rate here kept its schedule with nothing failed.",
        )
        ).joinToString(separator = "\n", postfix = "\n")
}

private fun Measured.kafkaRow(): String = listOf(
    rate.grouped(),
    result.count.grouped(),
    result.failed.grouped(),
    result.behind.p50.readable(),
    result.behind.p99.readable(),
    result.behind.max.readable(),
    if (keptSchedule) "yes" else "no",
).joinToString(separator = " | ", prefix = "| ", postfix = " |")

private val KAFKA_WARMUP: Duration = 1.seconds

private val KAFKA_WINDOW: Duration = 5.seconds

private val KAFKA_BUDGET: Duration = 1.milliseconds

private val KAFKA_RATES = listOf(1_000, 5_000, 10_000, 25_000, 50_000, 100_000, 250_000, 500_000)
