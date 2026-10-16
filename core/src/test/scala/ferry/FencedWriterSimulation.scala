package ferry

import zio.*
import zio.test.*

/**
 * The deployment pattern `Ferry.perKey` is meant for, simulated under injected faults: several nodes, each normally the
 * only writer of the keys it owns, apply each key's batches to a shared store with a conditional append, and clients
 * retry until they get an answer. The pattern is described in `docs/decisions/0005-fenced-single-writer.md` and
 * model-checked in `formal/FencedWriter.tla`; this simulation runs the real `perKey` implementation through it.
 *
 * Faults: the store refuses a write (throttling), applies a write but loses its response, or applies it late, after the
 * node that sent it has crashed. Clients may reach a node that does not own the key, so two nodes can write one key at
 * the same time, as during an ownership handover. One node may crash part way through.
 *
 * The writer is correct only with both of its safeguards: the conditional append (the fence), and reading what is
 * already applied before writing (deduplication). [[Writer]] can switch either off, to show each is needed.
 */
object FencedWriterSimulation:

  final case class Command(id: Int, key: Int)
  final case class Submission(command: Command, gapMs: Long, misrouted: Boolean)

  enum Fault:
    case Throttle
    case LostResponse
    case Late(ms: Long)

  enum StoreError:
    case Throttled, TimedOut, Conflict

  final case class Writer(fenced: Boolean = true, deduplicated: Boolean = true)

  final case class Scenario(submissions: List[Submission], faults: List[Fault], crash: Option[(Int, Long)])

  final case class Trace(logs: Map[Int, Vector[Int]], acks: Map[Int, Int], unanswered: Set[Int], overlapping: Boolean)

  val nodes = 2

  /**
   * One log per key. An append names the log length its writer read; it is refused if the log has grown since, unless
   * the writer is unfenced. A late append is applied after a delay even if its sender is gone, like a request already
   * on the wire.
   */
  final class Store(logs: Ref[Map[Int, Vector[Int]]], faults: Ref[List[Fault]], fenced: Boolean):

    def read(key: Int): UIO[Vector[Int]] = logs.get.map(_.getOrElse(key, Vector.empty))

    def append(key: Int, expectedLength: Int, ids: Chunk[Int]): IO[StoreError, Unit] =
      faults.modify(fs => fs.headOption -> fs.drop(1)).flatMap {
        case Some(Fault.Throttle)     => ZIO.fail(StoreError.Throttled)
        case Some(Fault.LostResponse) => apply(key, expectedLength, ids).ignore *> ZIO.fail(StoreError.TimedOut)
        case Some(Fault.Late(ms))     =>
          (ZIO.sleep(ms.millis) *> apply(key, expectedLength, ids)).forkDaemon.flatMap(_.join)
        case None => apply(key, expectedLength, ids)
      }

    private def apply(key: Int, expectedLength: Int, ids: Chunk[Int]): IO[StoreError, Unit] =
      logs.modify { all =>
        val log = all.getOrElse(key, Vector.empty)
        if fenced && log.size != expectedLength then Left(StoreError.Conflict) -> all
        else Right(())                                                         -> all.updated(key, log ++ ids)
      }.absolve

  /**
   * The batch call: read the key's log, skip commands already applied, append the rest conditionally on the log not
   * having changed, and answer each command with its position in the log. Any store error retries the whole round trip,
   * which re-reads the log: after a lost response, the retry finds the commands applied and answers from there.
   */
  def write(store: Store, writer: Writer)(key: Int, commands: NonEmptyChunk[Command]): IO[StoreError, Map[Int, Int]] =
    val roundTrip =
      for
        log <- store.read(key)
        ids   = commands.toChunk.map(_.id).distinct
        fresh = if writer.deduplicated then ids.filterNot(log.contains) else ids
        _ <- store.append(key, log.size, fresh).when(fresh.nonEmpty)
        after = log ++ fresh
      yield ids.map(id => id -> after.indexOf(id)).toMap
    roundTrip.retry(Schedule.recurs(4) && Schedule.exponential(5.millis))

  def scenario: Gen[Any, Scenario] =
    for
      submissions <- Gen.listOfBounded(1, 24)(
        Gen.int(0, 2) <*> Gen.long(0, 2).map(_ * 5) <*> Gen.weighted(Gen.const(true) -> 1.0, Gen.const(false) -> 3.0)
      )
      faults <- Gen.listOfBounded(0, 12)(
        Gen.oneOf(Gen.const(Fault.Throttle), Gen.const(Fault.LostResponse), Gen.long(1, 12).map(n => Fault.Late(n * 5)))
      )
      crash <- Gen.option(Gen.int(0, nodes - 1) <*> Gen.long(0, 30).map(_ * 5))
    yield Scenario(
      submissions.zipWithIndex.map { case ((key, gap, misrouted), id) => Submission(Command(id, key), gap, misrouted) },
      faults,
      crash
    )

  def run(s: Scenario, writer: Writer = Writer()): UIO[Trace] =
    for
      logs   <- Ref.make(Map.empty[Int, Vector[Int]])
      faults <- Ref.make(s.faults)
      store = Store(logs, faults, writer.fenced)
      cfg     <- ZIO.fromEither(PerKeyConfig.make(maxBatchSize = 8, maxInFlightBatches = 4, maxQueueing = 64)).orDie
      inUse   <- Ref.make(Map.empty[(Int, Int), Int])
      overlap <- Ref.make(false)
      scopes  <- ZIO.foreach(0 until nodes)(_ => Scope.make)
      ferries <- ZIO.foreach(scopes.zipWithIndex) { (scope, node) =>
        scope.extend(Ferry.perKey(cfg, (_: Command).key) { (key, commands: NonEmptyChunk[Command]) =>
          val slot = node -> key
          inUse
            .modify(m => m.getOrElse(slot, 0) -> m.updated(slot, m.getOrElse(slot, 0) + 1))
            .flatMap(n => overlap.set(true).when(n > 0)) *>
            write(store, writer)(key, commands).ensuring(inUse.update(m => m.updated(slot, m(slot) - 1)))
        }((command, positions) => positions(command.id)))
      }
      crashed <- Ref.make(Set.empty[Int])
      acks    <- Ref.make(Map.empty[Int, Int])
      done    <- Ref.make(Set.empty[Int])
      client = (sub: Submission) =>
        def attempt(n: Int): UIO[Unit] =
          crashed.get.flatMap { down =>
            val owner = sub.command.key % nodes
            val node  = if (sub.misrouted && n == 0) || down(owner) then (owner + 1) % nodes else owner
            ferries(node)(sub.command).exit.flatMap {
              case Exit.Success(position) => acks.update(_ + (sub.command.id -> position))
              case Exit.Failure(_)        => ZIO.when(n < 30)(ZIO.sleep(20.millis) *> attempt(n + 1)).unit
            }
          }
        attempt(0).ensuring(done.update(_ + sub.command.id))
      // Clients outlive the fiber that starts them, so they belong to a scope of their own rather than to that fiber.
      clients <- Scope.make
      _ <- ZIO.foreachDiscard(s.submissions)(sub => ZIO.sleep(sub.gapMs.millis) *> client(sub).forkIn(clients)).fork
      _ <- ZIO.foreachDiscard(s.crash) { (node, atMs) =>
        (ZIO.sleep(atMs.millis) *> crashed.update(_ + node) *> scopes(node).close(Exit.unit)).fork
      }
      _     <- VirtualTime.settle(done.get.map(_.size == s.submissions.size))
      _     <- ZIO.foreachDiscard(clients +: scopes)(_.close(Exit.unit))
      _     <- TestClock.adjust(1.minute) // let late writes still on the wire land
      trace <- logs.get <*> acks.get <*> overlap.get
    yield
      val (finalLogs, acked, overlapped) = trace
      Trace(finalLogs, acked, s.submissions.map(_.command.id).toSet -- acked.keySet, overlapped)

  def violations(s: Scenario, t: Trace): List[String] =
    val keyOf = s.submissions.map(sub => sub.command.id -> sub.command.key).toMap
    List(
      t.logs.collect { case (key, log) if log.distinct.size != log.size => s"key $key applied a command twice: $log" },
      t.acks.collect {
        case (id, position) if !t.logs.get(keyOf(id)).flatMap(_.lift(position)).contains(id) =>
          s"command $id was answered with position $position, but the log of key ${keyOf(id)} is ${t.logs.get(keyOf(id))}"
      },
      Option.when(t.unanswered.nonEmpty)(s"commands never answered: ${t.unanswered}"),
      Option.when(t.overlapping)("a node had two batches of one key in flight at once")
    ).flatten
