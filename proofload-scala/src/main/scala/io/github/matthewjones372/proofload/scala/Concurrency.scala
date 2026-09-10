package io.github.matthewjones372.proofload.scala

import io.github.matthewjones372.proofload.ConcurrencyKt
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.Concurrency as Core

/**
 * The users a run had in flight, beside the number its own throughput and
 * latency say it should have had.
 *
 * Little's law is arithmetic rather than a model, so the two sides disagreeing
 * is a fact about the measurement and not about the target: every other number
 * on the page is suspect until it is explained. A run the law cannot be asked
 * of says why, rather than reporting a ratio nobody can read.
 */
sealed trait Concurrency

object Concurrency:

  /** What the law found. It holds no number of its own: every one is core's. */
  final class Measured private[scala] (private val measured: Core.Measured) extends Concurrency:

    /** Users running, averaged over the samples taken in the segment. */
    def observed: Double = measured.getObserved

    /** Throughput times mean service time: what was outstanding on the wire. */
    def fromServiceTime: Double = measured.getFromServiceTime

    /** The same against the clock that counts from the promised departure. */
    def fromResponseTime: Double = measured.getFromResponseTime

    /** How many samples the observed side rests on. */
    def samples: Int = measured.getSamples

    /** What was measured over what was predicted: one where the law holds. */
    def ratio: Double = measured.getRatio

    /** The queue the generator itself was holding, in requests. */
    def backlog: Double = measured.getBacklog

    /** Whether the two sides agree to within what this measurement can resolve. */
    def agrees: Boolean = measured.getAgrees

    override def toString: String = measured.toString

  /** Why the law could not be asked of this run. */
  final case class Absent(because: String) extends Concurrency

extension (result: RunResult)

  /** Little's law over the segment this run settled into, or why it cannot be asked. */
  def concurrency: Concurrency = ConcurrencyKt.getConcurrency(result) match
    case measured: Core.Measured => Concurrency.Measured(measured)
    case absent: Core.Absent => Concurrency.Absent(absent.getBecause)
