package bulkdike

import zio.*
import zio.test.TestClock

object VirtualTime:

  /**
   * Runs a simulation to completion on the TestClock: jumps straight to the next pending sleep, again and again, until
   * `done` holds. Gives up when nothing is left to wake up, or after `maxSteps` jumps; the caller then reports whatever
   * is unfinished as hung.
   *
   * Every `TestClock.adjust` first waits, in real time, for all fibers to block, so the number of jumps is what makes a
   * simulation fast or slow: scenarios should keep their instants on a coarse grid so that wake-ups coincide.
   */
  def settle(done: UIO[Boolean], maxSteps: Int = 20000): UIO[Unit] =
    def loop(left: Int, idle: Boolean): UIO[Unit] =
      done.flatMap { finished =>
        ZIO
          .unless(finished || left == 0) {
            (TestClock.sleeps <*> Clock.instant).flatMap { (sleeps, now) =>
              sleeps.minOption match
                case Some(wake) =>
                  TestClock.adjust(Duration.fromInterval(now, wake) max Duration.Zero) *> loop(left - 1, idle = false)
                case None if !idle => TestClock.adjust(Duration.Zero) *> loop(left - 1, idle = true) // let fibers block
                case None          => ZIO.unit
            }
          }
          .unit
      }
    TestClock.adjust(Duration.Zero) *> loop(maxSteps, idle = false)
