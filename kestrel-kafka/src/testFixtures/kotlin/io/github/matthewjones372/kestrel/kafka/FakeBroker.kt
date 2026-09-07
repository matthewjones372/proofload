package io.github.matthewjones372.kestrel.kafka

import org.apache.kafka.common.message.ApiVersionsResponseData
import org.apache.kafka.common.message.FetchRequestData
import org.apache.kafka.common.message.FetchResponseData
import org.apache.kafka.common.message.InitProducerIdResponseData
import org.apache.kafka.common.message.ListOffsetsRequestData
import org.apache.kafka.common.message.ListOffsetsResponseData
import org.apache.kafka.common.message.MetadataResponseData
import org.apache.kafka.common.message.ProduceRequestData
import org.apache.kafka.common.message.ProduceResponseData
import org.apache.kafka.common.message.ResponseHeaderData
import org.apache.kafka.common.protocol.ApiKeys
import org.apache.kafka.common.protocol.ByteBufferAccessor
import org.apache.kafka.common.protocol.Message
import org.apache.kafka.common.protocol.ObjectSerializationCache
import org.apache.kafka.common.record.MemoryRecords
import org.apache.kafka.common.requests.RequestHeader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Enough of the Kafka wire protocol for a real `KafkaProducer` to connect and
 * produce, over a real socket, with no dependency this module did not already
 * have.
 *
 * What this exists to prove is narrow and worth being clear about. That
 * `kafka-clients` speaks Kafka is Apache's test suite, not this repository's.
 * What is unproven without a socket is the producer's *accumulator* — the
 * batching, the sender thread, `max.block.ms` — which no mock models at all,
 * and which is the part most likely to decide what a Kafka run can drive.
 *
 * It answers three requests and nothing else: `ApiVersions`, `Metadata` and
 * `Produce`. It stores nothing, so it is not a broker and cannot be read from;
 * a consumer would need `ListOffsets` and `Fetch` on top, and a record batch
 * to hand back.
 *
 * A test fixture rather than a test source, because the module that lowers a
 * plan onto a topic needs the same socket to prove it lowered one — and a
 * second hand-rolled broker would be a second thing to keep in step with the
 * client's own wire versions.
 *
 * The response header is written by hand because `AbstractResponse`'s own
 * `serializeWithHeader` is package-private. The version of that header is
 * `ApiKeys.responseHeaderVersion(apiVersion)`, which is public and is what
 * decides whether tagged fields follow it — getting that wrong is silent and
 * looks like a corrupt frame much further along.
 */
class FakeBroker : AutoCloseable {

    private val socket = ServerSocket(0)

    private val produced = AtomicLong()

    // The batches a produce handed over, kept whole. A Produce request carries
    // a record batch a Fetch response can hand straight back, so nothing here
    // encodes one — which is what makes a consumer affordable at all.
    private val log = java.util.concurrent.ConcurrentLinkedQueue<MemoryRecords>()

    /** How many records this broker has been handed, across every produce request. */
    val recordsProduced: Long get() = produced.get()

    val bootstrap: String get() = "localhost:${socket.localPort}"

    private val accepting = Thread.ofPlatform().daemon().start {
        while (!socket.isClosed) {
            val connection = runCatching { socket.accept() }.getOrNull() ?: return@start
            Thread.ofPlatform().daemon().start { serve(connection) }
        }
    }

    private fun serve(connection: Socket) {
        connection.use {
            val from = DataInputStream(it.getInputStream().buffered())
            val to = DataOutputStream(it.getOutputStream().buffered())
            while (!it.isClosed) {
                val size = runCatching { from.readInt() }.getOrNull() ?: return
                val frame = ByteArray(size).also(from::readFully)
                val buffer = ByteBuffer.wrap(frame)
                val header = RequestHeader.parse(buffer)
                val answer = answer(header, buffer) ?: return
                to.write(framed(header, answer))
                to.flush()
            }
        }
    }

    private fun answer(header: RequestHeader, body: ByteBuffer): Message? = when (header.apiKey()) {
        ApiKeys.API_VERSIONS -> apiVersions()
        ApiKeys.METADATA -> metadata()
        ApiKeys.INIT_PRODUCER_ID -> initProducerId()
        ApiKeys.PRODUCE -> produce(body, header.apiVersion())
        ApiKeys.LIST_OFFSETS -> listOffsets(body, header.apiVersion())
        ApiKeys.FETCH -> fetch(body, header.apiVersion())
        else -> null
    }

    /** Only what this answers, so the client negotiates down to versions it will not surprise us with. */
    private fun apiVersions(): ApiVersionsResponseData = ApiVersionsResponseData().apply {
        setApiKeys(
            ApiVersionsResponseData.ApiVersionCollection(
                listOf(
                    ApiKeys.API_VERSIONS,
                    ApiKeys.METADATA,
                    ApiKeys.INIT_PRODUCER_ID,
                    ApiKeys.PRODUCE,
                    ApiKeys.LIST_OFFSETS,
                    ApiKeys.FETCH,
                )
                    .map { key ->
                        ApiVersionsResponseData.ApiVersion()
                            .setApiKey(key.id)
                            .setMinVersion(key.oldestVersion())
                            .setMaxVersion(key.latestVersion())
                    }.iterator(),
            ),
        )
    }

    /**
     * A producer id, because idempotence is on by default since 3.0 and the
     * client's transaction manager will not send a record without one.
     */
    private fun initProducerId(): InitProducerIdResponseData =
        InitProducerIdResponseData().setProducerId(1L).setProducerEpoch(0)

    /** One broker, which is this one, and one topic with one partition it leads. */
    private fun metadata(): MetadataResponseData = MetadataResponseData().apply {
        setBrokers(
            MetadataResponseData.MetadataResponseBrokerCollection(
                listOf(
                    MetadataResponseData.MetadataResponseBroker()
                        .setNodeId(0)
                        .setHost("localhost")
                        .setPort(socket.localPort),
                ).iterator(),
            ),
        )
        setClusterId("kestrel-fake")
        setControllerId(0)
        setTopics(
            MetadataResponseData.MetadataResponseTopicCollection(
                listOf(
                    MetadataResponseData.MetadataResponseTopic()
                        .setName(TOPIC)
                        .setPartitions(
                            listOf(
                                MetadataResponseData.MetadataResponsePartition()
                                    .setPartitionIndex(0)
                                    .setLeaderId(0)
                                    .setReplicaNodes(listOf(0))
                                    .setIsrNodes(listOf(0)),
                            ),
                        ),
                ).iterator(),
            ),
        )
    }

    /**
     * Counts the records and answers that they landed at an offset.
     *
     * Nothing is stored: the batch is read for its record count and dropped,
     * because what this proves is that a real producer's accumulator and
     * sender thread work against a socket, not that anything can be read back.
     */
    private fun produce(body: ByteBuffer, version: Short): ProduceResponseData {
        val request = ProduceRequestData(ByteBufferAccessor(body), version)
        request.topicData().forEach { topic ->
            topic.partitionData().forEach { partition ->
                (partition.records() as MemoryRecords?)?.let { records ->
                    produced.addAndGet(records.records().count().toLong())
                    log.add(records)
                }
            }
        }
        return ProduceResponseData().apply {
            setResponses(
                ProduceResponseData.TopicProduceResponseCollection(
                    request.topicData().map { topic ->
                        ProduceResponseData.TopicProduceResponse()
                            .setName(topic.name())
                            .setPartitionResponses(
                                topic.partitionData().map { partition ->
                                    ProduceResponseData.PartitionProduceResponse()
                                        .setIndex(partition.index())
                                        .setErrorCode(0)
                                        .setBaseOffset(0L)
                                        .setLogAppendTimeMs(-1L)
                                        .setLogStartOffset(0L)
                                },
                            )
                    }.iterator(),
                ),
            )
        }
    }

    /**
     * Where the log starts and ends, which is what a consumer asks before it
     * can position itself.
     */
    private fun listOffsets(body: ByteBuffer, version: Short): ListOffsetsResponseData {
        val request = ListOffsetsRequestData(ByteBufferAccessor(body), version)
        return ListOffsetsResponseData().setTopics(
            request.topics().map { topic ->
                ListOffsetsResponseData.ListOffsetsTopicResponse()
                    .setName(topic.name())
                    .setPartitions(
                        topic.partitions().map { partition ->
                            ListOffsetsResponseData.ListOffsetsPartitionResponse()
                                .setPartitionIndex(partition.partitionIndex())
                                .setErrorCode(0)
                                .setTimestamp(-1L)
                                // Earliest is zero and latest is everything
                                // produced so far, which is all a consumer
                                // assigning a partition by hand needs.
                                .setOffset(if (partition.timestamp() == -2L) 0L else produced.get())
                                .setLeaderEpoch(0)
                        },
                    )
            },
        )
    }

    /**
     * Hands back the batches a produce left, from the offset asked for.
     *
     * The batches are the producer's own, unaltered: a Produce request carries
     * a valid record batch and a Fetch response takes one, so no batch is
     * encoded here. Everything before [from] is skipped by count rather than
     * by seeking inside a batch, which is enough for a consumer reading a
     * partition it assigned from the beginning.
     */
    private fun fetch(body: ByteBuffer, version: Short): FetchResponseData {
        val request = FetchRequestData(ByteBufferAccessor(body), version)
        return FetchResponseData().setResponses(
            request.topics().map { topic ->
                FetchResponseData.FetchableTopicResponse()
                    .setTopic(TOPIC)
                    .setTopicId(topic.topicId())
                    .setPartitions(
                        topic.partitions().map { partition ->
                            val held = log.toList()
                            val sending = held.drop(batchesBefore(held, partition.fetchOffset()))
                            FetchResponseData.PartitionData()
                                .setPartitionIndex(partition.partition())
                                .setErrorCode(0)
                                .setHighWatermark(produced.get())
                                .setLastStableOffset(produced.get())
                                .setLogStartOffset(0L)
                                .setRecords(sending.firstOrNull() ?: MemoryRecords.EMPTY)
                        },
                    )
            },
        )
    }

    private fun batchesBefore(held: List<MemoryRecords>, offset: Long): Int {
        var seen = 0L
        held.forEachIndexed { at, batch ->
            if (seen >= offset) return at
            seen += batch.records().count()
        }
        return held.size
    }

    /** Length prefix, response header at its own version, then the body at the request's. */
    private fun framed(header: RequestHeader, body: Message): ByteArray {
        val headerVersion = header.apiKey().responseHeaderVersion(header.apiVersion())
        val responseHeader = ResponseHeaderData().setCorrelationId(header.correlationId())
        val cache = ObjectSerializationCache()
        val size = responseHeader.size(cache, headerVersion) + body.size(cache, header.apiVersion())
        val buffer = ByteBuffer.allocate(Int.SIZE_BYTES + size)
        buffer.putInt(size)
        val writable = ByteBufferAccessor(buffer)
        responseHeader.write(writable, cache, headerVersion)
        body.write(writable, cache, header.apiVersion())
        return buffer.array()
    }

    override fun close() {
        socket.close()
        accepting.interrupt()
    }

    companion object {
        const val TOPIC: String = "trades"
    }
}
