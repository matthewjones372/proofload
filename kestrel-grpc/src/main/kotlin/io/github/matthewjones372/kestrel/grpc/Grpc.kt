package io.github.matthewjones372.kestrel.grpc

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ClientInterceptors
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.MethodDescriptor
import kotlin.time.Duration

/**
 * Where calls are made to. A value, so a test against two services names two
 * of these rather than setting a global the second one overwrites.
 *
 * This module never sees a `.proto`, a protoc plugin or the protobuf runtime.
 * The caller generates their stubs and hands over the descriptor the generated
 * code already carries; what is added is a name for the row, a budget, a trace
 * and a recording.
 */
class Grpc internal constructor(
    internal val target: String,
    internal val traced: Boolean = false,
    internal val deadline: Duration? = null,
    private val opened: (Grpc) -> ManagedChannel = ::channelFor,
) {

    /**
     * The channel every call from here shares, opened once and lazily.
     *
     * One for the run rather than one per user, on the same argument as the
     * shared HTTP client: a `ManagedChannel` is a thread-safe pool, and one
     * per user would measure TLS handshakes and connection setup rather than
     * the target. The cost is gRPC's known trap — one channel resolves to one
     * subchannel, so a run can land on one backend and HTTP/2 caps calls at
     * that connection's `SETTINGS_MAX_CONCURRENT_STREAMS`. That is a
     * generator's ceiling, and read as the target's it is a lie; the tool
     * reports the injector's own limits beside every number for this reason.
     *
     * Handed to the caller so their generated stub can be built on it: the
     * seam is under the stub, not around it.
     */
    val managed: ManagedChannel by lazy { opened(this) }

    /**
     * What a caller builds their generated stub on.
     *
     * The pool with this module's interceptor around it, so a call made
     * through a stub gets the budget and the trace whether or not the caller
     * remembered to ask. [managed] is the pool itself, for shutting it down.
     */
    val channel: Channel by lazy { ClientInterceptors.intercept(managed, Budget(deadline)) }

    /** Calls this service instead. */
    fun target(target: String): Grpc = Grpc(target, traced, deadline, opened)

    /**
     * Puts a W3C `traceparent` and a synthetic-traffic `baggage` entry on the
     * metadata of every call from here, so a slow measurement has a trace id
     * to follow into whatever the target exports its spans to.
     */
    fun traced(): Grpc = Grpc(target, traced = true, deadline = deadline, opened = opened)

    /**
     * The budget a call gets when it does not set one of its own.
     *
     * A call with no deadline waits as long as the target likes, which in a
     * load test is a user who never departs again and a percentile that never
     * arrives. A caller's own `withDeadlineAfter` still wins: gRPC keeps the
     * earlier of the two.
     */
    fun deadline(within: Duration): Grpc = Grpc(target, traced, deadline = within, opened = opened)

    /** Sends over [channel] instead of one opened for [target] — an in-process server, or a caller's own. */
    fun over(channel: ManagedChannel): Grpc = Grpc(target, traced, deadline) { channel }

    /**
     * One call of [descriptor], made by [body].
     *
     * The descriptor is passed rather than inferred, which is the whole
     * difference from the Pelican module: a Pelican call site is not a step, so
     * its transport has to recover a name from templates, while a gRPC call
     * site *is* one and the descriptor already carries the name and the types.
     *
     * [body] is the caller's own stub call. Nothing here builds it, so a
     * blocking stub, a future stub and a coroutine stub are all the same to
     * this module.
     */
    fun <Q, A> call(descriptor: MethodDescriptor<Q, A>, body: () -> A): GrpcAction<Q, A> {
        require(descriptor.type == MethodDescriptor.MethodType.UNARY) {
            "${descriptor.fullMethodName} is ${descriptor.type} and `call` measures one request and one " +
                "answer; a stream is `stream`, `send` and `awaiting`, because one sample cannot carry both " +
                "how long a hundred messages took and how long each of them did"
        }
        return GrpcAction(descriptor, this, body)
    }
}

/**
 * The channel gRPC itself builds for a target, on whichever transport is on the
 * caller's classpath.
 *
 * This module names no transport: `grpc-netty-shaded` and `grpc-okhttp` each
 * carry a thread model, and a caller with generated stubs has already chosen
 * one. `forTarget` finds it by service loader.
 */
private fun channelFor(grpc: Grpc): ManagedChannel {
    require(grpc.target.isNotBlank()) {
        "no target: `grpc.target(\"orders.internal:8443\")` names one, or `over(channel)` supplies its own"
    }
    return ManagedChannelBuilder.forTarget(grpc.target).build()
}

/** No target of its own: `grpc.target(...)` gives one, or `over(channel)` supplies a channel. */
val grpc: Grpc = Grpc("")

/**
 * Gives a call the run's deadline where it has none of its own.
 *
 * Only where there is none: gRPC keeps whichever deadline is on the
 * `CallOptions`, so writing one over a caller's `withDeadlineAfter` would
 * silently lengthen or shorten a budget they had stated. A call with no
 * deadline at all waits as long as the target likes, which in a load test is a
 * user who never departs again and a percentile that never arrives.
 *
 * An interceptor for this and not for the measuring: an interceptor that
 * recorded would double-count the step it sits inside, and it runs on gRPC's
 * own executor threads, where writing into a recorder sharded per user is a
 * race the report reads as a missing request.
 */
private class Budget(private val within: Duration?) : ClientInterceptor {

    override fun <Q, A> interceptCall(
        method: io.grpc.MethodDescriptor<Q, A>,
        options: CallOptions,
        next: Channel,
    ): ClientCall<Q, A> {
        val budgeted = if (within == null || options.deadline != null) {
            options
        } else {
            options.withDeadlineAfter(within.inWholeMilliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        return next.newCall(method, budgeted)
    }
}
