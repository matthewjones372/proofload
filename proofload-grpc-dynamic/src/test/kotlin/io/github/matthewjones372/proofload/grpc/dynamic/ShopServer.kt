package io.github.matthewjones372.proofload.grpc.dynamic

import com.google.protobuf.DynamicMessage
import io.grpc.ManagedChannel
import io.grpc.MethodDescriptor
import io.grpc.Server
import io.grpc.ServerServiceDefinition
import io.grpc.ServiceDescriptor
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.protobuf.ProtoFileDescriptorSupplier
import io.grpc.protobuf.lite.ProtoLiteUtils
import io.grpc.protobuf.services.ProtoReflectionService
import io.grpc.protobuf.services.ProtoReflectionServiceV1
import io.grpc.stub.ServerCalls
import io.grpc.stub.StreamObserver
import java.util.concurrent.atomic.AtomicInteger

/**
 * `shop.Orders`, served without a generated stub on either side.
 *
 * The server answers with `DynamicMessage` for the same reason the client sends
 * one, which makes this a test of the marshalling rather than of protoc: if the
 * bytes were wrong, a generated server would be the thing that noticed, and
 * there is not one here to hide behind.
 */
internal class ShopServer(
    private val answers: (String) -> Status = { Status.OK },
    /** Which reflection service, if any, this target serves. */
    describing: Describing = Describing.Nothing,
) : AutoCloseable {

    /** What a target does when asked to describe itself. */
    enum class Describing { Nothing, V1, V1Alpha }

    private val schema = descriptorSet(Shop.descriptorSet)

    // Built once. gRPC binds a handler to the very instance the service
    // descriptor holds, so a second descriptor of the same method is refused at
    // startup rather than at the call.
    private val wired = listOf("PlaceOrder", "GetOrder").associateWith { wire(it) }

    private val name = InProcessServerBuilder.generateName()

    private val called = AtomicInteger()

    /** How many calls this server has been handed, across every method. */
    val calls: Int get() = called.get()

    private val server: Server = InProcessServerBuilder.forName(name)
        .directExecutor()
        .addService(orders())
        .also { builder ->
            // gRPC's own reflection service, so the client here is judged
            // against the implementation it will meet rather than against a
            // second hand-written one that could be wrong in the same way.
            when (describing) {
                Describing.Nothing -> Unit
                Describing.V1 -> builder.addService(ProtoReflectionServiceV1.newInstance())
                Describing.V1Alpha -> builder.addService(ProtoReflectionService.newInstance())
            }
        }
        .build()
        .start()

    /** A channel onto this server, for `grpc.over(...)`. */
    fun channel(): ManagedChannel = InProcessChannelBuilder.forName(name).directExecutor().build()

    override fun close() {
        server.shutdownNow()
    }

    private fun orders(): ServerServiceDefinition {
        // A schema descriptor on the service, because that is what reflection
        // reads: a service defined only by its method names can be called and
        // cannot describe itself.
        val declared = ServiceDescriptor.newBuilder("shop.Orders")
            .setSchemaDescriptor(ProtoFileDescriptorSupplier { schema.method("shop.Orders/PlaceOrder").file })
        wired.values.forEach { declared.addMethod(it) }

        val service = ServerServiceDefinition.builder(declared.build())
        wired.forEach { (method, descriptor) ->
            service.addMethod(descriptor, ServerCalls.asyncUnaryCall { _, answer -> answer.answer(method) })
        }
        return service.build()
    }

    private fun StreamObserver<DynamicMessage>.answer(method: String) {
        called.incrementAndGet()
        val status = answers(method)
        if (!status.isOk) {
            onError(StatusRuntimeException(status))
            return
        }
        onNext(schema.method("shop.Orders/$method").outputType.messageFrom("""{"id": "order-1"}"""))
        onCompleted()
    }

    private fun wire(method: String): MethodDescriptor<DynamicMessage, DynamicMessage> {
        val declared = schema.method("shop.Orders/$method")
        return MethodDescriptor.newBuilder<DynamicMessage, DynamicMessage>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("shop.Orders/$method")
            .setRequestMarshaller(ProtoLiteUtils.marshaller(DynamicMessage.getDefaultInstance(declared.inputType)))
            .setResponseMarshaller(ProtoLiteUtils.marshaller(DynamicMessage.getDefaultInstance(declared.outputType)))
            .build()
    }
}
