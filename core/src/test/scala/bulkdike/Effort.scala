package bulkdike

import zio.*
import zio.test.{TestAspect, TestAspectPoly}

/**
 * How hard the randomised suites try. Environment overrides:
 *   - `BULKDIKE_SAMPLES`: every sample and repetition count, e.g. to search much longer before a release.
 *   - `BULKDIKE_TEST_TIMEOUT_SECONDS`: the per-test time limit, so a change that makes callers hang is reported
 *     quickly.
 */
object Effort:
  private def env(name: String) = sys.env.get(name).flatMap(_.toIntOption)

  def samples(default: Int): TestAspectPoly = TestAspect.samples(env("BULKDIKE_SAMPLES").getOrElse(default))

  def repeats(default: Int): TestAspectPoly = TestAspect.nonFlaky(env("BULKDIKE_SAMPLES").getOrElse(default))

  def timeout(default: Duration): TestAspectPoly =
    TestAspect.timeout(env("BULKDIKE_TEST_TIMEOUT_SECONDS").fold(default)(_.seconds))
