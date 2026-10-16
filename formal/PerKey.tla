------------------------------- MODULE PerKey -------------------------------
(***************************************************************************)
(* The concurrency protocol of bulkdike.BulkDike.perKey, at the            *)
(* granularity of its atomic steps (STM transactions and uninterruptible  *)
(* regions).                                                               *)
(*                                                                         *)
(* Callers submit inputs for a key. Each key has a lane, a FIFO of waiting *)
(* inputs. Workers, one per batch slot, take a batch from the front of a  *)
(* lane, mark the key busy, run the batch and release the key. Time is    *)
(* abstracted away: a partial batch may become due at any moment.         *)
(* Callers may be interrupted while they wait. Closing the scope          *)
(* interrupts every worker, then stops admission and releases whatever is *)
(* still waiting, in one transaction.                                     *)
(*                                                                         *)
(* Two switches weaken the protocol, to show each mechanism is needed:    *)
(*   BusyGuard  - a key whose batch is in flight is not taken again       *)
(*   AtomicTake - taking a batch and recording it as owned by the worker  *)
(*                is one uninterruptible step                             *)
(* The implementation has both. Without the first, a key has two batches  *)
(* in flight; without the second, a caller can be stranded.               *)
(***************************************************************************)
EXTENDS Naturals, Sequences, FiniteSets

CONSTANTS Callers, Keys, Workers, MaxBatch, MaxQueue, BusyGuard, AtomicTake

ASSUME MaxBatch >= 1 /\ MaxQueue >= MaxBatch

VARIABLES
  caller,   \* where each caller is in `apply`
  promise,  \* each caller's result
  lane,     \* each key's waiting inputs, oldest first
  admitted, \* each key's inputs in admission order (history, for checking order)
  log,      \* each key's inputs in the order they were sent (history, for checking order)
  busy,     \* keys whose batch is in flight
  open,     \* admission is open
  worker,   \* each worker's activity
  wkey,     \* the key of each worker's batch
  taken,    \* inputs a worker has taken and owns until their results are set
  inHand,   \* inputs taken out of a lane but not yet recorded as owned (only without AtomicTake)
  sent,     \* how many batch calls carried each caller's input
  phase     \* lifecycle of the enclosing scope

vars == <<caller, promise, lane, admitted, log, busy, open, worker, wkey, taken, inHand, sent, phase>>

NoKey == "none"
Outcomes == {"succeeded", "failed"}

Range(s) == {s[i] : i \in 1..Len(s)}
Queued == {c \in Callers : \E k \in Keys : c \in Range(lane[k])}

TypeOK ==
  /\ caller \in [Callers -> {"idle", "waiting", "rejected", "refused"}]
  /\ promise \in [Callers -> {"pending", "interrupted"} \cup Outcomes]
  /\ lane \in [Keys -> Seq(Callers)]
  /\ admitted \in [Keys -> Seq(Callers)]
  /\ log \in [Keys -> Seq(Callers)]
  /\ busy \subseteq Keys
  /\ open \in BOOLEAN
  /\ worker \in [Workers -> {"idle", "taking", "running", "dead"}]
  /\ wkey \in [Workers -> Keys \cup {NoKey}]
  /\ taken \in [Workers -> SUBSET Callers]
  /\ inHand \in [Workers -> SUBSET Callers]
  /\ sent \in [Callers -> Nat]
  /\ phase \in {"open", "closing", "closed"}

Init ==
  /\ caller = [c \in Callers |-> "idle"]
  /\ promise = [c \in Callers |-> "pending"]
  /\ lane = [k \in Keys |-> <<>>]
  /\ admitted = [k \in Keys |-> <<>>]
  /\ log = [k \in Keys |-> <<>>]
  /\ busy = {}
  /\ open = TRUE
  /\ worker = [w \in Workers |-> "idle"]
  /\ wkey = [w \in Workers |-> NoKey]
  /\ taken = [w \in Workers |-> {}]
  /\ inHand = [w \in Workers |-> {}]
  /\ sent = [c \in Callers |-> 0]
  /\ phase = "open"

Interrupt(cs) == [c \in Callers |-> IF c \in cs /\ promise[c] = "pending" THEN "interrupted" ELSE promise[c]]

(* `admit`: one transaction checks that admission is open and that the bound holds, and appends to the lane. *)
Submit(c, k) ==
  /\ caller[c] = "idle"
  /\ IF ~open
     THEN caller' = [caller EXCEPT ![c] = "refused"] /\ UNCHANGED <<lane, admitted>>
     ELSE IF Cardinality(Queued) < MaxQueue
          THEN /\ lane' = [lane EXCEPT ![k] = Append(@, c)]
               /\ admitted' = [admitted EXCEPT ![k] = Append(@, c)]
               /\ caller' = [caller EXCEPT ![c] = "waiting"]
          ELSE caller' = [caller EXCEPT ![c] = "rejected"] /\ UNCHANGED <<lane, admitted>>
  /\ UNCHANGED <<promise, log, busy, open, worker, wkey, taken, inHand, sent, phase>>

(* The caller is interrupted while awaiting its result. *)
Cancel(c) ==
  /\ caller[c] = "waiting"
  /\ promise[c] = "pending"
  /\ promise' = [promise EXCEPT ![c] = "interrupted"]
  /\ UNCHANGED <<caller, lane, admitted, log, busy, open, worker, wkey, taken, inHand, sent, phase>>

Alive(w) == worker[w] # "dead"

(* `take`: one transaction takes the oldest inputs of a lane (any number up to MaxBatch: the deadline, size and *)
(* weight rules only decide how many) and marks the key busy.                                                  *)
Take(w) ==
  /\ worker[w] = "idle"
  /\ \E k \in Keys, n \in 1..MaxBatch :
       /\ n <= Len(lane[k])
       /\ BusyGuard => k \notin busy
       /\ LET got == Range(SubSeq(lane[k], 1, n)) IN
            /\ lane' = [lane EXCEPT ![k] = SubSeq(@, n + 1, Len(@))]
            /\ busy' = busy \cup {k}
            /\ wkey' = [wkey EXCEPT ![w] = k]
            /\ IF AtomicTake
               THEN taken' = [taken EXCEPT ![w] = got] /\ UNCHANGED inHand
               ELSE inHand' = [inHand EXCEPT ![w] = got] /\ UNCHANGED taken
  /\ worker' = [worker EXCEPT ![w] = "taking"]
  /\ UNCHANGED <<caller, promise, admitted, log, open, sent, phase>>

(* Only without AtomicTake: the inputs just taken are recorded as owned, in a later step. *)
Record(w) ==
  /\ inHand[w] # {}
  /\ taken' = [taken EXCEPT ![w] = inHand[w]]
  /\ inHand' = [inHand EXCEPT ![w] = {}]
  /\ UNCHANGED <<caller, promise, lane, admitted, log, busy, open, worker, wkey, sent, phase>>

(* `run` leaves out callers interrupted while waiting, and sends the rest in lane order. *)
Dispatch(w) ==
  /\ worker[w] = "taking"
  /\ inHand[w] = {}
  /\ LET k    == wkey[w]
         live == SelectSeq(admitted[k], LAMBDA c : c \in taken[w] /\ promise[c] = "pending")
     IN  /\ log' = [log EXCEPT ![k] = @ \o live]
         /\ sent' = [c \in Callers |-> IF c \in Range(live) THEN sent[c] + 1 ELSE sent[c]]
         /\ taken' = [taken EXCEPT ![w] = Range(live)]
  /\ worker' = [worker EXCEPT ![w] = "running"]
  /\ UNCHANGED <<caller, promise, lane, admitted, busy, open, wkey, inHand, phase>>

(* The batch call has returned: results are set one caller at a time, interruptibly. *)
Complete(w, c) ==
  /\ worker[w] = "running"
  /\ c \in taken[w]
  /\ \E outcome \in Outcomes :
       promise' = [promise EXCEPT ![c] = IF @ = "pending" THEN outcome ELSE @]
  /\ taken' = [taken EXCEPT ![w] = @ \ {c}]
  /\ UNCHANGED <<caller, lane, admitted, log, busy, open, worker, wkey, inHand, sent, phase>>

(* `release`: the key may be taken again. *)
Finish(w) ==
  /\ worker[w] = "running"
  /\ taken[w] = {}
  /\ busy' = busy \ {wkey[w]}
  /\ worker' = [worker EXCEPT ![w] = "idle"]
  /\ wkey' = [wkey EXCEPT ![w] = NoKey]
  /\ UNCHANGED <<caller, promise, lane, admitted, log, open, taken, inHand, sent, phase>>

BeginClose ==
  /\ phase = "open"
  /\ phase' = "closing"
  /\ UNCHANGED <<caller, promise, lane, admitted, log, busy, open, worker, wkey, taken, inHand, sent>>

(* Scope finalizer, part 1: interrupt each worker; its `onInterrupt` releases what it owns. *)
KillWorker(w) ==
  /\ phase = "closing"
  /\ Alive(w)
  /\ worker' = [worker EXCEPT ![w] = "dead"]
  /\ promise' = Interrupt(taken[w])
  /\ taken' = [taken EXCEPT ![w] = {}]
  /\ inHand' = [inHand EXCEPT ![w] = {}] \* never recorded, so nobody releases them
  /\ UNCHANGED <<caller, lane, admitted, log, busy, open, wkey, sent, phase>>

(* Scope finalizer, part 2 (`close`): one transaction stops admission and empties every lane. *)
Close ==
  /\ phase = "closing"
  /\ \A w \in Workers : ~Alive(w)
  /\ open' = FALSE
  /\ promise' = Interrupt(Queued)
  /\ lane' = [k \in Keys |-> <<>>]
  /\ phase' = "closed"
  /\ UNCHANGED <<caller, admitted, log, busy, worker, wkey, taken, inHand, sent>>

WorkerStep(w) == Take(w) \/ Record(w) \/ Dispatch(w) \/ Finish(w) \/ \E c \in Callers : Complete(w, c)

Next ==
  \/ \E c \in Callers : Cancel(c) \/ \E k \in Keys : Submit(c, k)
  \/ \E w \in Workers : WorkerStep(w) \/ KillWorker(w)
  \/ BeginClose \/ Close

Fairness ==
  /\ \A w \in Workers : WF_vars(WorkerStep(w)) /\ WF_vars(KillWorker(w))
  /\ WF_vars(Close)

Spec == Init /\ [][Next]_vars /\ Fairness

-----------------------------------------------------------------------------
(* Safety *)

(* A key never has two batches in flight. *)
OneInFlightPerKey ==
  \A k \in Keys : Cardinality({w \in Workers : worker[w] \in {"taking", "running"} /\ wkey[w] = k}) <= 1

(* Each key's inputs are sent in the order they were admitted. *)
Before(s, a, b) == \E i, j \in 1..Len(s) : i < j /\ s[i] = a /\ s[j] = b
InOrderPerKey == \A k \in Keys : \A a, b \in Range(log[k]) : Before(log[k], a, b) => Before(admitted[k], a, b)

AtMostOnce == \A c \in Callers : sent[c] <= 1

TurnedAwayNeverSent == \A c \in Callers : caller[c] \in {"rejected", "refused"} => sent[c] = 0

BatchBounded == \A w \in Workers : Cardinality(taken[w] \cup inHand[w]) <= MaxBatch

(* An input is in at most one place: a lane, or the hands of one worker. *)
SingleOwner ==
  /\ \A w1, w2 \in Workers : w1 # w2 => (taken[w1] \cup inHand[w1]) \cap (taken[w2] \cup inHand[w2]) = {}
  /\ \A w \in Workers : (taken[w] \cup inHand[w]) \cap Queued = {}

NoneStrandedAfterClose ==
  phase = "closed" => \A c \in Callers : caller[c] = "waiting" => promise[c] # "pending"

Safety ==
  /\ OneInFlightPerKey /\ InOrderPerKey /\ AtMostOnce /\ TurnedAwayNeverSent
  /\ BatchBounded /\ SingleOwner /\ NoneStrandedAfterClose

(* Liveness: every caller that was let in eventually gets a result, is told it failed, or is interrupted. *)
EveryCallerAnswered == \A c \in Callers : (caller[c] = "waiting") ~> (promise[c] # "pending")
=============================================================================
