package bulkdike.examples

import bulkdike.{BulkDike, BulkDikeError, PerKeyConfig}
import zio.*

/**
 * Clients append entries to per-entity logs in a store with conditional writes. Each entity's writes must be applied in
 * order, and a round trip to the store costs about the same whether it carries one entry or twenty, so a busy entity is
 * limited by round trips, not by entries.
 *
 * `BulkDike.perKey` makes this process the only writer of each entity: the entries of an entity that arrive while its
 * previous write is in flight travel together in the next one, and different entities are written in parallel. The
 * write itself is fenced and idempotent (see `docs/decisions/0005-fenced-single-writer.md`), so retrying it is safe.
 *
 * Run with: `examples/runMain bulkdike.examples.OrderedWrites`
 */
object OrderedWrites extends ZIOAppDefault:

  final case class Entry(id: String, entity: String, amount: Long)

  enum StoreError:
    case Throttled, Conflict

  /** Stands in for the store: one log per entity, appends conditional on the log's length, throttling now and then. */
  final class Store(logs: Ref[Map[String, Vector[String]]], requests: Ref[Int]):

    def read(entity: String): UIO[Vector[String]] =
      ZIO.sleep(2.millis) *> logs.get.map(_.getOrElse(entity, Vector.empty))

    def append(entity: String, expectedLength: Int, ids: Chunk[String]): IO[StoreError, Unit] =
      ZIO.sleep(5.millis) *> requests.updateAndGet(_ + 1).flatMap { n =>
        if n % 7 == 0 then ZIO.fail(StoreError.Throttled)
        else
          logs.modify { all =>
            val log = all.getOrElse(entity, Vector.empty)
            if log.size != expectedLength then Left(StoreError.Conflict) -> all
            else Right(())                                               -> all.updated(entity, log ++ ids)
          }.absolve
      }

  /**
   * One round trip per batch: read the log, leave out entries already applied (a retried write may have landed), append
   * the rest only if the log has not changed since it was read, and answer every entry on its own. An invalid entry is
   * rejected without failing the others; only a store error fails, and retries, the whole batch.
   */
  def write(
    store: Store
  )(entity: String, entries: NonEmptyChunk[Entry]): IO[StoreError, Map[String, Either[String, Int]]] =
    val (invalid, valid) = entries.toChunk.partition(_.amount == 0)
    val roundTrip        =
      for
        log <- store.read(entity)
        fresh = valid.map(_.id).distinct.filterNot(log.contains)
        _ <- store.append(entity, log.size, fresh).when(fresh.nonEmpty)
        after = log ++ fresh
      yield valid.map(e => e.id -> Right(after.indexOf(e.id))).toMap ++
        invalid.map(e => e.id -> Left("amount must not be zero"))
    roundTrip.retry(Schedule.exponential(10.millis).jittered && Schedule.recurs(5))

  val run =
    ZIO.scoped {
      for
        logs     <- Ref.make(Map.empty[String, Vector[String]])
        requests <- Ref.make(0)
        store = Store(logs, requests)
        config <- ZIO.fromEither(
          // No delay: an entity's entries wait only while its previous write is in flight. The weight limit keeps a
          // write under the store's request size limit, with weights in (estimated) bytes.
          PerKeyConfig.make(maxBatchSize = 25, maxInFlightBatches = 8, maxQueueing = 1000, maxBatchWeight = 2000)
        )
        writer <- BulkDike.perKey(config, (_: Entry).entity, (e: Entry) => 40L + e.id.length)(write(store))(
          (entry, outcomes) => outcomes(entry.id)
        )
        entries = Chunk.tabulate(300) { n =>
          val entity = if n % 5 == 0 then s"quiet-${n % 3}" else "busy" // most traffic goes to one entity
          Entry(s"entry-$n", entity, amount = if n == 42 then 0 else n.toLong + 1)
        }
        outcomes <- ZIO.foreachPar(entries)(entry => writer(entry).either)
        rejected = outcomes.collect { case Right(Left(reason)) => reason }
        failed   = outcomes.collect { case Left(error) => describe(error) }
        written <- logs.get
        made    <- requests.get
        _       <- Console.printLine(s"${entries.size} entries, ${rejected.size} rejected, ${failed.size} failed")
        _       <- ZIO.foreachDiscard(written.toList.sortBy(_._1)) { (entity, log) =>
          Console.printLine(
            s"$entity: ${log.size} entries applied, each exactly once: ${log.distinct.size == log.size}"
          )
        }
        _ <- Console.printLine(s"$made write requests to the store, throttled ones included")
      yield ()
    }

  private def describe(error: BulkDikeError[StoreError]): String =
    error.fold("rejected: too many entries waiting", cause => s"store failed: $cause")
