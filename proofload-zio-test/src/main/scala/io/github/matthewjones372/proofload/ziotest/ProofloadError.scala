package io.github.matthewjones372.proofload.ziotest

/**
 * Why a run did not produce a result.
 *
 * Three cases rather than one per failure mode, because the caller's real
 * question is whether to retry: an [[Invalid]] simulation never will, an
 * [[Interrupted]] run may, and [[Failed]] is everything else. Adding a case
 * later is source-compatible for a match with a default; splitting one is not.
 *
 * A `Throwable`, so the error channel narrows without breaking a caller who
 * wrote `Task[RunResult]`: ZIO is covariant in its error type.
 *
 * Nothing the target did appears here. A refused connection and a bug in a step
 * body are both recorded as failed requests, on the result, with the reason
 * they failed for: a run that measured a target refusing every connection is a
 * measurement rather than an error, and losing it to an exception would throw
 * away the answer.
 */
enum ProofloadError(cause: Throwable) extends Exception(cause):

  /** The simulation could not be run as written. Retrying sends the same thing again. */
  case Invalid(cause: Throwable) extends ProofloadError(cause)

  /** The run did not finish. Nothing is known about the target, rather than something bad. */
  case Interrupted(cause: Throwable) extends ProofloadError(cause)

  /** Anything else: the machine, the disk, a bug here. */
  case Failed(cause: Throwable) extends ProofloadError(cause)

object ProofloadError:

  private[ziotest] def of(cause: Throwable): ProofloadError = cause match
    case already: ProofloadError => already
    case interrupted: InterruptedException => Interrupted(interrupted)
    case invalid: IllegalArgumentException => Invalid(invalid)
    case other => Failed(other)
