package bulkdike

import zio.*
import zio.test.*

object PerKeyPropertySpec extends ZIOSpecDefault:

  private def honoursContract(scenarios: Gen[Any, PerKeySimulation.Scenario]) =
    check(scenarios) { s =>
      PerKeySimulation.run(s).map(trace => assertTrue(PerKeySimulation.violations(s, trace) == Nil))
    }

  import FencedWriterSimulation.{Command, Fault, Scenario, Submission, Writer}

  /** Command 0 is sent to its owner, whose write is delayed on the wire past the owner's crash. */
  private val lateWriteAfterCrash =
    Scenario(List(Submission(Command(0, 0), 0, misrouted = false)), List(Fault.Late(50)), Some(0 -> 10L))

  /** Command 0 is applied, but the response is lost, so the writer retries. */
  private val lostResponse =
    Scenario(List(Submission(Command(0, 0), 0, misrouted = false)), List(Fault.LostResponse), None)

  private def violations(s: Scenario, writer: Writer) =
    FencedWriterSimulation.run(s, writer).map(FencedWriterSimulation.violations(s, _))

  def spec = suite("BulkDike.perKey properties")(
    suite("simulated workloads")(
      test("honours its contract under random load, weights, latency and failures") {
        honoursContract(PerKeySimulation.scenario(cancellations = false, close = false))
      },
      test("honours its contract when callers are interrupted at random times") {
        honoursContract(PerKeySimulation.scenario(cancellations = true, close = false))
      },
      test("never strands a caller when its scope closes mid-flight") {
        honoursContract(PerKeySimulation.scenario(cancellations = true, close = true))
      }
    ) @@ Effort.samples(150),
    suite("fenced single writer")(
      test("applies every command exactly once and answers it truthfully, under faults, misrouting and a crash") {
        check(FencedWriterSimulation.scenario) { s =>
          violations(s, Writer()).map(found => assertTrue(found == Nil))
        }
      } @@ Effort.samples(150),
      test("needs the fence: a write still on the wire from a crashed owner would apply a command twice") {
        for
          fenced   <- violations(lateWriteAfterCrash, Writer())
          unfenced <- violations(lateWriteAfterCrash, Writer(fenced = false))
        yield assertTrue(fenced == Nil, unfenced.exists(_.contains("applied a command twice")))
      },
      test("needs deduplication: a retry after a lost response would apply a command twice") {
        for
          deduplicated <- violations(lostResponse, Writer())
          blind        <- violations(lostResponse, Writer(deduplicated = false))
        yield assertTrue(deduplicated == Nil, blind.exists(_.contains("applied a command twice")))
      }
    )
  ) @@ Effort.timeout(10.minutes)
