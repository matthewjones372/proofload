package io.github.matthewjones372.kestrel.grpc.dynamic

import com.google.protobuf.DynamicMessage
import io.grpc.ManagedChannel
import io.grpc.MethodDescriptor
import io.grpc.Server
import io.grpc.ServerServiceDefinition
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.protobuf.lite.ProtoLiteUtils
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
internal class ShopServer(private val answers: (String) -> Status) : AutoCloseable {

    private val schema = descriptorSet(Shop.descriptorSet)

    private val name = InProcessServerBuilder.generateName()

    private val called = AtomicInteger()

    /** How many calls this server has been handed, across every method. */
    val calls: Int get() = called.get()

    private val server: Server = InProcessServerBuilder.forName(name)
        .directExecutor()
        .addService(orders())
        .build()
        .start()

    /** A channel onto this server, for `grpc.over(...)`. */
    fun channel(): ManagedChannel = InProcessChannelBuilder.forName(name).directExecutor().build()

    override fun close() {
        server.shutdownNow()
    }

    private fun orders(): ServerServiceDefinition {
        val service = ServerServiceDefinition.builder("shop.Orders")
        listOf("PlaceOrder", "GetOrder").forEach { method ->
            service.addMethod(wire(method), ServerCalls.asyncUnaryCall { _, answer -> answer.answer(method) })
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
