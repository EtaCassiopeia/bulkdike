package bulkdike

import zio.*
import zio.test.*

object FoldWriteSpec extends ZIOSpecDefault:

  /** A store of one log per key, written only if the log is still the one the writer read. */
  final private case class Store(logs: Ref[Map[String, Vector[Int]]], writes: Ref[Int], faults: Ref[List[Fault]]):

    def read(key: String): IO[String, Vector[Int]] = logs.get.map(_.getOrElse(key, Vector.empty))

    def writeIf(key: String, before: Vector[Int], after: Vector[Int]): IO[String, Unit] =
      writes.update(_ + 1) *> faults.modify(f => f.headOption -> f.drop(1)).flatMap {
        case Some(Fault.Conflict) => // another writer got there first
          logs.update(m => m.updated(key, m.getOrElse(key, Vector.empty) :+ -1)) *> ZIO.fail("conflict")
        case Some(Fault.LostResponse) => apply(key, before, after) *> ZIO.fail("timeout")
        case None                     => apply(key, before, after)
      }

    private def apply(key: String, before: Vector[Int], after: Vector[Int]): IO[String, Unit] =
      logs.modify { m =>
        if m.getOrElse(key, Vector.empty) == before then Right(()) -> m.updated(key, after)
        else Left("conflict")                                      -> m
      }.absolve

  private enum Fault:
    case Conflict, LostResponse

  private def store(faults: Fault*) =
    (Ref.make(Map.empty[String, Vector[Int]]) <*> Ref.make(0) <*> Ref.make(faults.toList)).map(Store(_, _, _))

  /** Inputs are numbers; zero is invalid, and a number already in the log is answered with its position. */
  private def step(log: Vector[Int], n: Int): (Vector[Int], Either[String, Int]) =
    if n == 0 then log -> Left("zero")
    else if log.contains(n) then log -> Right(log.indexOf(n))
    else (log :+ n)                  -> Right(log.size)

  private def foldWrite(s: Store): FoldWrite[Any, String, String, Int, Int, Vector[Int], Either[String, Int]] =
    FoldWrite((n: Int) => n, s.read, step, s.writeIf, Schedule.recurs(3))

  def spec = suite("FoldWrite")(
    test("applies a batch in order with one read and one write, and answers each input on its own") {
      for
        s        <- store()
        outcomes <- foldWrite(s).call("k", NonEmptyChunk(5, 0, 7))
        log      <- s.read("k")
        writes   <- s.writes.get
      yield assertTrue(
        log == Vector(5, 7),
        writes == 1,
        outcomes == Map(5 -> Right(0), 0 -> Left("zero"), 7 -> Right(1))
      )
    },
    test("rereads and reapplies after a conflict, on top of what the other writer wrote") {
      for
        s        <- store(Fault.Conflict)
        outcomes <- foldWrite(s).call("k", NonEmptyChunk(5, 7))
        log      <- s.read("k")
      yield assertTrue(log == Vector(-1, 5, 7), outcomes == Map(5 -> Right(1), 7 -> Right(2)))
    },
    test("never applies an input twice when a write landed but its response was lost") {
      for
        s        <- store(Fault.LostResponse)
        outcomes <- foldWrite(s).call("k", NonEmptyChunk(5, 7))
        log      <- s.read("k")
        writes   <- s.writes.get
      yield assertTrue(log == Vector(5, 7), writes == 1, outcomes == Map(5 -> Right(0), 7 -> Right(1)))
    },
    test("applies a repeated input once, and gives every copy the first one's outcome") {
      for
        s <- store()
        write = foldWrite(s)
        outcomes <- write.call("k", NonEmptyChunk(5, 5))
        log      <- s.read("k")
      yield assertTrue(log == Vector(5), write.outcome(5, outcomes) == Right(0))
    },
    test("skips the write when nothing changed") {
      for
        s        <- store()
        outcomes <- foldWrite(s).call("k", NonEmptyChunk(0))
        writes   <- s.writes.get
      yield assertTrue(writes == 0, outcomes == Map(0 -> Left("zero")))
    },
    test("fails with the store's error once retries are exhausted") {
      for
        s    <- store(Fault.Conflict, Fault.Conflict, Fault.Conflict, Fault.Conflict)
        exit <- foldWrite(s).call("k", NonEmptyChunk(5)).exit
      yield assertTrue(exit.causeOption.flatMap(_.failureOption).contains("conflict"))
    },
    test("serves BulkDike.perKey as its call and extract") {
      for
        s   <- store()
        cfg <- ZIO.fromEither(PerKeyConfig.make(maxBatchSize = 10, maxInFlightBatches = 2, maxQueueing = 100)).orDie
        write = foldWrite(s)
        dike    <- BulkDike.perKey(cfg, (_: Int) => "k")(write.call)(write.outcome)
        results <- ZIO.foreachPar(1 to 20)(dike(_))
        log     <- s.read("k")
      yield assertTrue(log.sorted == (1 to 20).toVector, results.forall(_.isRight))
    }
  )
