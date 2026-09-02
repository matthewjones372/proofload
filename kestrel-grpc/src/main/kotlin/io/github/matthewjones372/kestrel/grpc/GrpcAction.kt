package io.github.matthewjones372.kestrel.grpc

import io.github.matthewjones372.kestrel.Action
import io.github.matthewjones372.kestrel.ScenarioBuilder
import io.github.matthewjones372.kestrel.StepScope
import io.grpc.MethodDescriptor

/**
 * One call, as a value: a step that can be built once, shared between
 * scenarios and read before anything is sent.
 *
 * The action measures rather than an interceptor. An interceptor would
 * double-count the step it sits inside, and it runs on gRPC's own executor
 * threads, where writing into a recorder that is sharded per user rather than
 * locked would be a race the report reads as a missing request.
 */
class GrpcAction<Q, A> internal constructor(
    private val descriptor: MethodDescriptor<Q, A>,
    private val origin: Grpc,
    private val body: () -> A,
) : Action {

    /**
     * The fully-qualified method, which is the row a report wants:
     * `orders.v1.Orders/PlaceOrder`.
     *
     * Off the descriptor rather than off a string somebody typed, so two call
     * sites of one method are one row and a rename moves both.
     */
    val name: String get() = descriptor.fullMethodName

    override fun run(scope: StepScope) {
        callFrom(scope)
    }

    /** Calls, and records what happened on [scope]. Reached through [send]. */
    internal fun callFrom(scope: StepScope): A? {
        if (scope.narrating) scope.note("${descriptor.type} ${descriptor.fullMethodName} to ${origin.target}")
        val answer = body()
        if (scope.narrating) scope.note("< $answer")
        return answer
    }
}

/** Names the step for the method, which is the row a report wants. */
fun ScenarioBuilder.exec(call: GrpcAction<*, *>) {
    exec(call.name, call)
}

/**
 * Makes [call] and records what happened on this step.
 *
 * The verb takes the call because the call is the thing being made; the scope
 * it reports to is the one the step body is already running in. The answer
 * comes back for a step that needs to read it, and is ignored by the many that
 * do not.
 */
fun <Q, A> StepScope.send(call: GrpcAction<Q, A>): A? = call.callFrom(this)
