package bulkdike.examples

import bulkdike.{BulkDike, BulkDikeConfig, BulkDikeError}
import nl.vroste.rezilience.{CircuitBreaker, Retry, Timeout}
import zio.*

/**
 * Many concurrent requests each need the price for one country. The upstream pricing API accepts many countries per
 * call, so BulkDike coalesces the requests into a few upstream calls, and rezilience protects each of those calls.
 *
 * Run with: `examples/runMain bulkdike.examples.PricingAggregation`
 */
object PricingAggregation extends ZIOAppDefault:

  final case class UpstreamError(message: String)

  /** Stands in for a remote API: answers several countries per call, slowly, and fails once. */
  final class PricingApi(calls: Ref[Int]):
    private val prices = Map("NL" -> BigDecimal("14.24"), "CN" -> BigDecimal("20.50"), "US" -> BigDecimal("9.99"))

    def fetch(countries: NonEmptyChunk[String]): IO[UpstreamError, Map[String, BigDecimal]] =
      for
        n <- calls.updateAndGet(_ + 1)
        _ <- ZIO.sleep(50.millis)
        _ <- ZIO.fail(UpstreamError(s"call $n: 503 Service Unavailable")).when(n == 2) // retried by the policy
      yield countries.toChunk.flatMap(c => prices.get(c).map(c -> _)).toMap

  val run =
    ZIO.scoped {
      for
        calls <- Ref.make(0)
        api = PricingApi(calls)
        // --8<-- [start:resilience]
        timeout <- Timeout.make(1.second)
        retry   <- Retry.make(min = 10.millis, max = 100.millis, maxRetries = Some(2))
        breaker <- CircuitBreaker.withMaxFailures[Any](maxFailures = 5)
        config  <- ZIO.fromEither(
          BulkDikeConfig.make(maxBatchSize = 5, maxBatchDelay = 20.millis, maxInFlightBatches = 2, maxQueueing = 50)
        )
        // Policies wrap the batch call, so a retry or a timeout covers every caller in the batch at once.
        prices <- BulkDike.keyed(config)((countries: NonEmptyChunk[String]) =>
          breaker(retry(timeout(api.fetch(countries))))
        )
        // --8<-- [end:resilience]
        wanted = List("NL", "CN", "US", "NL", "DE", "CN", "US", "NL", "CN", "FR", "US", "NL")
        answers <- ZIO.foreachPar(wanted)(country => prices(country).either.map(country -> _))
        _       <- ZIO.foreachDiscard(answers) { (country, answer) =>
          Console.printLine(s"$country: ${answer.fold(describe, _.fold("no price")(_.toString))}")
        }
        made <- calls.get
        _    <- Console.printLine(s"${wanted.size} requests were served with $made upstream calls (retries included)")
      yield ()
    }

  private def describe(error: BulkDikeError[Any]): String =
    error.fold("rejected: too many requests waiting", cause => s"upstream failed: $cause")
