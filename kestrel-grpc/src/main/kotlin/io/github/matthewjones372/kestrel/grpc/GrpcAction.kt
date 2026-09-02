package io.github.matthewjones372.kestrel.grpc

import io.github.matthewjones372.kestrel.Action
import io.github.matthewjones372.kestrel.ScenarioBuilder
import io.github.matthewjones372.kestrel.StepScope
import io.github.matthewjones372.kestrel.TimedOut
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException

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

    /**
     * Calls, and records what happened on [scope]. Reached through [send].
     *
     * A status is a value the target sent, so it fails the step by name rather
     * than escaping: `UNAVAILABLE` and `NOT_FOUND` are two findings, and one
     * `Threw("StatusRuntimeException")` row covering both is the question 0063
     * exists to let a reader ask. `DEADLINE_EXCEEDED` is recorded as core's
     * own `TimedOut`, because a target that was reachable and too slow is the
     * one failure a reader acts on differently, whichever protocol said so.
     *
     * Anything that is not a status escapes to the engine, which records it as
     * `Threw(class)`: a marshaller that cannot serialise its own argument is a
     * bug in the caller's code rather than a thing the target did, and a user
     * whose journey is built on it has nothing left to do.
     */
    internal fun callFrom(scope: StepScope): A? {
        if (scope.narrating) scope.note("${descriptor.type} ${descriptor.fullMethodName} to ${origin.target}")
        val answer = try {
            // The scope is reachable from the tracing interceptor for the
            // length of the call and no longer, so the id it sends lands
            // beside the percentile this step is about.
            tellingScope(scope, body)
        } catch (refused: StatusRuntimeException) {
            scope.fail(refused.status.asReason())
            return null
        } catch (refused: StatusException) {
            scope.fail(refused.status.asReason())
            return null
        }
        if (scope.narrating) scope.note("< $answer")
        return answer
    }
}

/**
 * The status as this repository's vocabulary for a failure.
 *
 * `DEADLINE_EXCEEDED` becomes core's [TimedOut] rather than a `GrpcStatus` of
 * its own: every module that can time out reports it under one name, so a goal
 * or a report reading "how many timed out" gets one answer across HTTP,
 * WebSocket and gRPC instead of three spellings of it.
 */
private fun Status.asReason(): io.github.matthewjones372.kestrel.Reason =
    if (code == Status.Code.DEADLINE_EXCEEDED) TimedOut else GrpcStatus(code)

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
