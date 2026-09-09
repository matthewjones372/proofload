package io.github.matthewjones372.proofload.grpc.dynamic

import com.google.protobuf.ByteString
import com.google.protobuf.DescriptorProtos.FileDescriptorSet
import io.github.matthewjones372.proofload.grpc.Grpc
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.reflection.v1.ServerReflectionGrpc
import io.grpc.reflection.v1.ServerReflectionRequest
import io.grpc.reflection.v1.ServerReflectionResponse
import io.grpc.stub.StreamObserver
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import io.grpc.reflection.v1alpha.ServerReflectionGrpc as AlphaGrpc
import io.grpc.reflection.v1alpha.ServerReflectionRequest as AlphaRequest
import io.grpc.reflection.v1alpha.ServerReflectionResponse as AlphaResponse

/**
 * The schema this target will describe of itself.
 *
 * Asks nothing of the caller, which is why it is the way in rather than the
 * fallback: a staging service usually has reflection on already, and it is what
 * `grpcurl` does. A target with it off is answered by [descriptorSet] and a
 * `protoc --descriptor_set_out`, and this says so by name rather than hanging
 * on a stream nobody is going to answer.
 *
 * `v1` first and `v1alpha` after it, because a server serves one or the other
 * and reporting a `v1`-only service as "reflection is off" would be a sentence
 * that sends somebody to change a server setting that is already right.
 */
fun Grpc.reflected(within: Duration = 10.seconds): Schema {
    val files = try {
        describedByV1(within)
    } catch (expectedOnAV1AlphaServer: Unimplemented) {
        try {
            describedByV1Alpha(within)
        } catch (notAlpha: Unimplemented) {
            throw IllegalArgumentException(
                "$target serves neither grpc.reflection.v1 nor v1alpha, so it cannot describe itself. " +
                    "Register a reflection service on it, or name a descriptor set written with " +
                    "`protoc --descriptor_set_out=shop.protoset --include_imports shop.proto`",
                notAlpha,
            )
        }
    }

    return descriptorSet(
        FileDescriptorSet.newBuilder()
            .addAllFile(files.map { com.google.protobuf.DescriptorProtos.FileDescriptorProto.parseFrom(it) })
            .build()
            .toByteArray(),
    )
}

private fun Grpc.describedByV1(within: Duration): List<ByteString> =
    Exchange<ServerReflectionRequest, ServerReflectionResponse>(within) { answers ->
        ServerReflectionGrpc.newStub(channel).serverReflectionInfo(answers)
    }.use { stream ->
        val services = stream
            .ask(ServerReflectionRequest.newBuilder().setListServices("").build())
            .listServicesResponse
            .serviceList
            .map { it.name }
            .filterNot { it.isReflection() }

        services.flatMap { service ->
            stream
                .ask(ServerReflectionRequest.newBuilder().setFileContainingSymbol(service).build())
                .fileDescriptorResponse
                .fileDescriptorProtoList
        }.distinct()
    }

private fun Grpc.describedByV1Alpha(within: Duration): List<ByteString> =
    Exchange<AlphaRequest, AlphaResponse>(within) { answers ->
        AlphaGrpc.newStub(channel).serverReflectionInfo(answers)
    }.use { stream ->
        val services = stream
            .ask(AlphaRequest.newBuilder().setListServices("").build())
            .listServicesResponse
            .serviceList
            .map { it.name }
            .filterNot { it.isReflection() }

        services.flatMap { service ->
            stream
                .ask(AlphaRequest.newBuilder().setFileContainingSymbol(service).build())
                .fileDescriptorResponse
                .fileDescriptorProtoList
        }.distinct()
    }

/**
 * Whether this is the endpoint that just answered.
 *
 * A target lists its reflection service among its services, and `grpcurl`
 * prints it. It is left out here because this schema is the methods a plan can
 * call, and a plan calling `ServerReflectionInfo` would be benchmarking gRPC's
 * own service rather than anything the caller wrote. `grpc.reflection.` is a
 * reserved name, so this matches those two services and nothing of anybody's.
 */
private fun String.isReflection(): Boolean = startsWith("grpc.reflection.")

/** A target that does not serve the reflection version that was asked. */
private class Unimplemented(cause: Throwable?) : RuntimeException(cause)

/**
 * One reflection stream, asked a question at a time.
 *
 * Reflection is a bidirectional stream and this needs it as a function call, so
 * answers land in a queue the caller waits on. Bounded and waited on with a
 * timeout: this runs when a plan is lowered rather than on a departure, so
 * blocking here is a wait for an answer and not a stall inside a measurement —
 * but an unbounded wait would still be a tool that hangs instead of reporting.
 */
private class Exchange<Q, A>(
    private val within: Duration,
    open: (StreamObserver<A>) -> StreamObserver<Q>,
) : AutoCloseable {

    private val answers = ArrayBlockingQueue<Result<A>>(1)

    private val requests: StreamObserver<Q> = open(
        object : StreamObserver<A> {
            override fun onNext(answer: A) {
                answers.put(Result.success(answer))
            }

            override fun onError(refused: Throwable) {
                answers.put(Result.failure(refused))
            }

            override fun onCompleted() = Unit
        },
    )

    fun ask(request: Q): A {
        requests.onNext(request)
        val answered = answers.poll(within.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            ?: throw IllegalArgumentException("no answer to a reflection request within $within")

        return answered.getOrElse { refused ->
            if (refused is StatusRuntimeException && refused.status.code == Status.Code.UNIMPLEMENTED) {
                throw Unimplemented(refused)
            }
            throw IllegalArgumentException("reflection refused: ${refused.message}", refused)
        }
    }

    override fun close() {
        requests.onCompleted()
    }
}
