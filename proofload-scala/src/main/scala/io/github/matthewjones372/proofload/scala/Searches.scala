package io.github.matthewjones372.proofload.scala

import io.github.matthewjones372.proofload.Capacity
import io.github.matthewjones372.proofload.Goal
import io.github.matthewjones372.proofload.Rate
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.Rung
import io.github.matthewjones372.proofload.Scenario
import io.github.matthewjones372.proofload.Search
import io.github.matthewjones372.proofload.Verdict
import io.github.matthewjones372.proofload.java.Searches
import _root_.scala.concurrent.duration.FiniteDuration
import _root_.scala.jdk.CollectionConverters.ListHasAsScala
import _root_.scala.jdk.CollectionConverters.SeqHasAsJava

extension (scenario: Scenario)

  /**
   * The highest rate this scenario sustains while every goal holds, hunted
   * below `upTo`. A vararg rather than a list, matching `scenario(...)`.
   */
  def sustainable(upTo: Rate, holding: FiniteDuration, expecting: Goal*): Search =
    Searches.sustainable(scenario, upTo, asJava(holding), expecting.toList.asJava)

extension (search: Search)

  /** The same search, with every rung warmed for `over` at that rung's own rate before it is measured. */
  def warmingUp(over: FiniteDuration): Search = Searches.warmingUp(search, asJava(over))

extension (capacity: Capacity)

  /** The highest rate every goal held at, empty where even the lowest rung missed one. */
  def rate: Option[Rate] = Option(Searches.rate(capacity))

  /** Every rung that ran, lowest first. */
  def curve: Seq[Rung] = capacity.getCurve.asScala.toSeq

  /** The goal that stopped the climb. */
  def limitedBy: Option[Goal] = Option(capacity.getLimitedBy)

  /** True where a rung ended void, which makes [[rate]] a floor the generator reached rather than the target's ceiling. */
  def voided: Boolean = capacity.getVoided

extension (rung: Rung)

  def rate: Rate = Searches.rate(rung)

  def result: RunResult = rung.getResult

  def verdicts: Seq[Verdict] = rung.getVerdicts.asScala.toSeq

  def outcome: Rung.Outcome = rung.getOutcome
