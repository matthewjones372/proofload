package io.github.matthewjones372.proofload.grpc.dynamic

import io.github.matthewjones372.proofload.Reason
import io.grpc.Status

/**
 * The target answered with a status the caller said this method can answer
 * with — a declared `NOT_FOUND`.
 *
 * Still a failure: a call that did not do what the step asked for is not a
 * success. Its own reason so a reader can tell it apart from `GrpcStatus`,
 * which is the service doing something nobody wrote down — the same split
 * `DeclaredStatus` makes for HTTP, and the one a load test otherwise has to be
 * told by hand for every step.
 */
data class DeclaredGrpcStatus(val code: Status.Code) : Reason {
    override val described: String get() = "${code.name}, declared"
}
