package io.github.matthewjones372.kestrel.grpc

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ManagedChannel
import io.grpc.Metadata
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

    /**
     * Answers [answer] of whatever it is sent, fails with [failing] where one
     * is given, or — where [silent] — never answers at all, which is what a
     * deadline is for.
     */
    fun serving(
        answer: (String) -> String = { "filled $it" },
        failing: Status? = null,
        silent: Boolean = false,
    ): ServerServiceDefinition =
        ServerServiceDefinition.builder("orders.v1.Orders")
            .addMethod(
                placeOrder,
                ServerCalls.asyncUnaryCall { request: String, observer: StreamObserver<String> ->
                    if (silent) {
                        // Nothing: the call hangs until something cancels it.
                    } else if (failing != null) {
                        observer.onError(StatusRuntimeException(failing))
                    } else {
                        observer.onNext(answer(request))
                        observer.onCompleted()
                    }
                },
            )
            .build()
}

/**
 * What the server saw on a call's metadata, in the order the calls arrived.
 *
 * The point of reading it server-side rather than asserting on what the client
 * built: what a target's tracing backend joins on is what reached the wire.
 */
internal class Heard {
    val traceparents: MutableList<String?> = java.util.Collections.synchronizedList(mutableListOf())
    val baggage: MutableList<String?> = java.util.Collections.synchronizedList(mutableListOf())
}

/** Records the trace metadata of every call, then lets it through. */
internal class Listening(private val heard: Heard) : io.grpc.ServerInterceptor {

    override fun <Q, A> interceptCall(
        call: io.grpc.ServerCall<Q, A>,
        headers: Metadata,
        next: io.grpc.ServerCallHandler<Q, A>,
    ): io.grpc.ServerCall.Listener<Q> {
        heard.traceparents += headers.get(Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER))
        heard.baggage += headers.get(Metadata.Key.of("baggage", Metadata.ASCII_STRING_MARSHALLER))
        return next.startCall(call, headers)
    }
}

/** An in-process server and a channel onto it, both closed when [use] returns. */
internal class InProcess(
    service: ServerServiceDefinition = Orders.serving(),
    val heard: Heard = Heard(),
) : AutoCloseable {

    private val name: String = InProcessServerBuilder.generateName()

    private val server: Server =
        InProcessServerBuilder.forName(name).directExecutor()
            .addService(io.grpc.ServerInterceptors.intercept(service, Listening(heard)))
            .build().start()

    /** The pool. Tests build stubs on `Grpc.channel`, which is this with the interceptor around it. */
    val managed: ManagedChannel = InProcessChannelBuilder.forName(name).directExecutor().build()

    /**
     * One unary call, as a caller's own generated blocking stub would make it.
     *
     * [on] is what a stub is built on, which is the intercepted channel where
     * one is given — the raw pool has no budget and no trace around it.
     */
    fun place(
        order: String,
        on: Channel = managed,
        options: CallOptions = CallOptions.DEFAULT,
    ): String = ClientCalls.blockingUnaryCall(on, Orders.placeOrder, options, order)

    override fun close() {
        managed.shutdownNow()
        server.shutdownNow()
    }
}
