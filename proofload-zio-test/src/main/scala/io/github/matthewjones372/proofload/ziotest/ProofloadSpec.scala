package io.github.matthewjones372.proofload.ziotest

import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.Simulation
import java.nio.file.Path
import zio.Chunk
import zio.IO
import zio.test.TestAspect
import zio.test.ZIOSpecDefault

/**
 * A load spec that is only its measurement.
 *
 * Two aspects every load spec needs, and one of them is easy to miss.
 * zio-test hands a spec a `TestClock`, so any time the *spec* takes (a
 * readiness retry, a `Schedule`, a timeout) never advances and the spec hangs
 * rather than failing. The run itself is on the wall clock and is fine, which
 * is what makes the omission hard to find: the load works and the scaffolding
 * around it stops.
 *
 * `sequential` is the other: two load tests running at once measure each other.
 * The runner takes the machine either way, so what this prevents is the second
 * spec waiting on the first while its own readiness checks time out.
 *
 * No timeout here. The right one is the length of what is being run, which this
 * cannot know, and a wrong default is worse than none.
 */
abstract class ProofloadSpec extends ZIOSpecDefault:

  override def aspects: Chunk[zio.test.TestAspectAtLeastR[zio.test.TestEnvironment]] =
    Chunk(TestAspect.sequential, TestAspect.withLiveClock)

  /** Where reports and the index are written. Per spec, because the index is per directory. */
  def reportsTo: Path = Path.of("target/proofload")

  /**
   * Run it, write its page under [[reportsTo]], and append its table to the job
   * summary.
   *
   * One call because a run whose report is not written is a run nobody can
   * read. A caller who wants the result without a report still has
   * `proofload.run`, and a project that cannot change its base class has
   * `proofload.measured`, which this is.
   */
  def measured(name: String)(simulation: Simulation): IO[ProofloadError, RunResult] =
    proofload.measured(name, into = reportsTo)(simulation)
