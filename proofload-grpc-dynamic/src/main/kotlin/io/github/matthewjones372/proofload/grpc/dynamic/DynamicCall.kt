package io.github.matthewjones372.proofload.grpc.dynamic

import com.google.protobuf.DynamicMessage
import io.github.matthewjones372.proofload.Action
import io.github.matthewjones372.proofload.ScenarioBuilder
import io.github.matthewjones372.proofload.StepScope
import io.github.matthewjones372.proofload.TimedOut
import io.github.matthewjones372.proofload.grpc.Grpc
import io.github.matthewjones372.proofload.grpc.GrpcStatus
import io.grpc.CallOptions
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.grpc.protobuf.lite.ProtoLiteUtils
import io.grpc.stub.ClientCalls
import com.google.protobuf.Descriptors.MethodDescriptor as ProtoMethod

/**
 * One call to a method nobody generated a stub for, as a value.
 *
 * The typed path is [io.github.matthewjones372.proofload.grpc.Grpc.call], and
 * stays the one to reach for where the stubs exist: a rename breaks that build
 * and silently changes nothing here. This is for the caller with a plan file
 * and a descriptor set.
 *
 * Built on 0071's `Grpc` rather than beside it, so the channel, the deadline
 * and the `traceparent` are the ones that module already fits — there is one
 * gRPC seam in this repository and this is not a second.
 */
class DynamicCall internal constructor(
    private val method: ProtoMethod,
    private val origin: Grpc,
    private val request: DynamicMessage,
    private val expected: Status.Code = Status.Code.OK,
    private val declared: Set<Status.Code> = emptySet(),
) : Action {

    /** `shop.Orders/PlaceOrder` — what the report rows on, and what the plan wrote. */
    val name: String get() = "${method.service.fullName}/${method.name}"

    /**
     * The status that counts as a success.
     *
     * The name rather than a number, because that is what the generated code
     * and every log line already call them, and a wire integer here would be a
     * translation nobody asked for.
     */
    fun expecting(status: Status.Code): DynamicCall = DynamicCall(method, origin, request, status, declared)

    /**
     * Statuses this method is documented to answer with.
     *
     * They still fail the step — a declared `NOT_FOUND` did not do what was
     * asked — but as [DeclaredGrpcStatus] rather than `GrpcStatus`, so a report
     * separates a service working as written from one doing something nobody
     * wrote down.
     */
    fun declaring(vararg statuses: Status.Code): DynamicCall =
        DynamicCall(method, origin, request, expected, declared + statuses.toSet())

    override fun run(scope: StepScope) {
        answerFrom(scope)
    }

    /**
     * Calls, and records what happened on [scope]. Reached through [send].
     *
     * A unary call either answers, in which case the status was `OK`, or throws
     * carrying the status the target sent — so the two branches below are the
     * two things that can happen and there is no third.
     */
    internal fun answerFrom(scope: StepScope): DynamicMessage? {
        if (scope.narrating) scope.note("$name to ${origin.target}\n> ${request.asJson()}")

        val answer = try {
            ClientCalls.blockingUnaryCall(origin.channel, method.wire(), CallOptions.DEFAULT, request)
        } catch (refused: StatusRuntimeException) {
            return answered(refused.status.code, scope)
        } catch (refused: StatusException) {
            return answered(refused.status.code, scope)
        }

        if (scope.narrating) scope.note("< ${answer.asJson()}")
        // A method whose success is a status other than OK answered anyway,
        // which is not what the step asked for.
        return if (expected == Status.Code.OK) answer else answered(Status.Code.OK, scope)
    }

    /**
     * What a status that is not an answer means for the step.
     *
     * `DEADLINE_EXCEEDED` is core's own [TimedOut] rather than a name of its
     * own, so "how many timed out" has one answer across HTTP, WebSocket and
     * gRPC instead of three spellings of it — the same choice 0071 made.
     */
    private fun answered(code: Status.Code, scope: StepScope): DynamicMessage? {
        if (code == expected) return null

        scope.fail(
            when {
                code == Status.Code.DEADLINE_EXCEEDED -> TimedOut
                code in declared -> DeclaredGrpcStatus(code)
                else -> GrpcStatus(code)
            },
        )
        return null
    }
}

/**
 * The gRPC method this protobuf method is, with marshallers over
 * `DynamicMessage`.
 *
 * `ProtoLiteUtils` rather than a marshaller written here: it is the one gRPC's
 * own generated code uses, so a dynamic call puts the same bytes on the wire as
 * a generated stub would and this module is not the author of a wire format.
 */
private fun ProtoMethod.wire(): MethodDescriptor<DynamicMessage, DynamicMessage> =
    MethodDescriptor.newBuilder<DynamicMessage, DynamicMessage>()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName("${service.fullName}/$name")
        .setRequestMarshaller(ProtoLiteUtils.marshaller(DynamicMessage.getDefaultInstance(inputType)))
        .setResponseMarshaller(ProtoLiteUtils.marshaller(DynamicMessage.getDefaultInstance(outputType)))
        .build()

/**
 * One call of [method], with [body] as the request.
 *
 * The JSON is turned into a message here rather than at the first departure, so
 * a body that does not fit the schema is a refusal while the scenario is being
 * built — which is the only moment a plan can still be corrected.
 */
fun Grpc.call(method: ProtoMethod, body: String): DynamicCall =
    DynamicCall(method, this, method.inputType.messageFrom(body))

/** The same, naming the method as a plan writes it. */
fun Grpc.call(schema: Schema, method: String, body: String): DynamicCall = call(schema.method(method), body)

/** Names the step for the method, which is the row a report wants. */
fun ScenarioBuilder.exec(call: DynamicCall) {
    exec(call.name, call)
}

/** Makes [call] and records what happened on this step. */
fun StepScope.send(call: DynamicCall): DynamicMessage? = call.answerFrom(this)
