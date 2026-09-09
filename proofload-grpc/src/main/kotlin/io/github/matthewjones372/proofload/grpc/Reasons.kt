package io.github.matthewjones372.proofload.grpc

import io.github.matthewjones372.proofload.Reason
import io.grpc.Status

/**
 * The target answered with a status the step did not ask for.
 *
 * The canonical code rather than the wire integer or the description:
 * `HttpStatus` is an int because an HTTP status is one, and gRPC's own form is
 * this enum. The description is left off for the reason `Threw` leaves off a
 * message — it carries a host, a port and often an id, so a report keyed on it
 * grows a row per request saying what one row says once.
 */
data class GrpcStatus(val code: Status.Code) : Reason {
    override val described: String get() = code.name
}
