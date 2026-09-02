package io.github.matthewjones372.kestrel.grpc

import io.grpc.ManagedChannel
import io.grpc.MethodDescriptor
import io.grpc.Server
import io.grpc.ServerServiceDefinition
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.ClientCalls
import io.grpc.stub.ServerCalls
import io.grpc.stub.StreamObserver
import java.io.InputStream

/**
 * A service with no `.proto` behind it.
 *
 * The module never sees a protobuf, a protoc plugin or a generated stub, so
 * neither do its tests: strings on the wire and a marshaller written here.
 * What is being tested is what this module adds — a name, a budget, a trace
 * and a recording — not anybody's code generation.
 */
internal object Orders {

    private val strings = object : MethodDescriptor.Marshaller<String> {
        override fun stream(value: String): InputStream = value.toByteArray().inputStream()
        override fun parse(stream: InputStream): String = stream.readBytes().decodeToString()
    }

    val placeOrder: MethodDescriptor<String, String> = MethodDescriptor.newBuilder(strings, strings)
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName("orders.v1.Orders/PlaceOrder")
        .build()

    val watchFills: MethodDescriptor<String, String> = MethodDescriptor.newBuilder(strings, strings)
        .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
        .setFullMethodName("orders.v1.Orders/WatchFills")
        .build()

    /** Answers [answer] of whatever it is sent, or fails with [failing] where one is given. */
    fun serving(answer: (String) -> String = { "filled $it" }, failing: Status? = null): ServerServiceDefinition =
        ServerServiceDefinition.builder("orders.v1.Orders")
            .addMethod(
                placeOrder,
                ServerCalls.asyncUnaryCall { request: String, observer: StreamObserver<String> ->
                    if (failing != null) {
                        observer.onError(StatusRuntimeException(failing))
                    } else {
                        observer.onNext(answer(request))
                        observer.onCompleted()
                    }
                },
            )
            .build()
}

/** An in-process server and a channel onto it, both closed when [use] returns. */
internal class InProcess(service: ServerServiceDefinition = Orders.serving()) : AutoCloseable {

    private val name: String = InProcessServerBuilder.generateName()

    private val server: Server =
        InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start()

    val channel: ManagedChannel = InProcessChannelBuilder.forName(name).directExecutor().build()

    /** One unary call, as a caller's own generated blocking stub would make it. */
    fun place(order: String): String =
        ClientCalls.blockingUnaryCall(channel, Orders.placeOrder, io.grpc.CallOptions.DEFAULT, order)

    override fun close() {
        channel.shutdownNow()
        server.shutdownNow()
    }
}
