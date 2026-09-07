package io.github.matthewjones372.kestrel.grpc

import io.github.matthewjones372.kestrel.SYNTHETIC
import io.github.matthewjones372.kestrel.StepScope
import io.github.matthewjones372.kestrel.Traceparent
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ClientInterceptors
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
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
    /** Where calls go, as written rather than resolved: what a narration prints and a fence would read. */
    val target: String,
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
    val channel: Channel by lazy { ClientInterceptors.intercept(managed, Budget(deadline), Tracing(traced)) }

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

/**
 * Puts a W3C `traceparent` and the synthetic-traffic `baggage` entry on every
 * call's outgoing metadata, so a slow measurement has a trace id to follow into
 * whatever the target exports its spans to.
 *
 * An interceptor, unlike the measuring: metadata belongs to the call rather
 * than to the step around it, and it has to be attached where gRPC starts the
 * call rather than where the action starts timing. The id is told to the step's
 * scope as well as sent, because a percentile with no id beside it leaves a
 * reader searching a backend by timestamp — the search this exists to replace.
 *
 * The scope is reached through a thread local rather than passed: an
 * interceptor's signature is gRPC's, and a call made through a caller's own
 * generated stub goes straight from the step body into the channel with nowhere
 * to thread one through.
 */
private class Tracing(private val traced: Boolean) : ClientInterceptor {

    override fun <Q, A> interceptCall(
        method: MethodDescriptor<Q, A>,
        options: CallOptions,
        next: Channel,
    ): ClientCall<Q, A> {
        if (!traced) return next.newCall(method, options)
        return object : SimpleForwardingClientCall<Q, A>(next.newCall(method, options)) {
            override fun start(responses: Listener<A>, headers: Metadata) {
                val traceparent = Traceparent.next()
                headers.put(TRACEPARENT, traceparent)
                headers.put(BAGGAGE, SYNTHETIC)
                telling?.get()?.traced(Traceparent.idIn(traceparent))
                super.start(responses, headers)
            }
        }
    }
}

/**
 * The step whose body is making a call on this thread, so the interceptor can
 * tell it the id it sent.
 *
 * A user runs on one virtual thread and a call is made from inside its own step
 * body, so the thread holding this is the one the interceptor runs on: gRPC
 * starts the call on the calling thread. Cleared as soon as the body returns,
 * so a later call from a step that is not traced tells nobody.
 */
private val telling: ThreadLocal<StepScope?>? = ThreadLocal.withInitial { null }

/** Runs [body] with [scope] reachable by the tracing interceptor. */
internal fun <T> tellingScope(scope: StepScope, body: () -> T): T {
    val before = telling?.get()
    telling?.set(scope)
    return try {
        body()
    } finally {
        telling?.set(before)
    }
}

private val TRACEPARENT: Metadata.Key<String> =
    Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER)

private val BAGGAGE: Metadata.Key<String> = Metadata.Key.of("baggage", Metadata.ASCII_STRING_MARSHALLER)

private class Budget(private val within: Duration?) : ClientInterceptor {

    override fun <Q, A> interceptCall(
        method: MethodDescriptor<Q, A>,
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
