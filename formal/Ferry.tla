------------------------------ MODULE Ferry ------------------------------
(***************************************************************************)
(* The concurrency protocol of ferry.Ferry, at the granularity of   *)
(* its atomic steps (Ref/Queue operations and uninterruptible regions).   *)
(*                                                                         *)
(* Callers submit inputs; workers, one per batch slot, take turns under a *)
(* forming lock to assemble a batch from the queue, then run it and set   *)
(* each caller's result. Callers may be interrupted while they wait.      *)
(* Closing the scope interrupts every worker, then stops admission and     *)
(* releases whatever is still queued.                                      *)
(*                                                                         *)
(* Two switches weaken the protocol, to show each mechanism is needed:    *)
(*   SerialisedAdmission - admission and "stop accepting" share a lock     *)
(*   CleanupOnInterrupt  - an interrupted worker releases what it took     *)
(*   AtomicTake          - taking entries off the queue and recording     *)
(*                         them as owned is one uninterruptible step      *)
(* The implementation has all three; turning any off strands a caller.   *)
(***************************************************************************)
EXTENDS Naturals, Sequences, FiniteSets

CONSTANTS Callers, Workers, MaxBatch, MaxQueue, SerialisedAdmission, CleanupOnInterrupt, AtomicTake

ASSUME MaxBatch >= 1 /\ MaxQueue >= MaxBatch

VARIABLES
  caller,    \* where each caller is in `apply`
  promise,   \* each caller's result
  queue,     \* inputs waiting for a batch
  accepting, \* admission is open
  lock,      \* the forming lock: the worker assembling a batch, or Free
  worker,    \* each worker's activity
  taken,     \* entries a worker has taken off the queue and owns until its batch completes
  batch,     \* entries of a worker's running batch whose result is not yet set
  sent,      \* how many batch calls carried each caller's input
  phase,     \* lifecycle of the enclosing scope
  inHand     \* entries taken off the queue but not yet recorded in `taken` (only without AtomicTake)

vars == <<caller, promise, queue, accepting, lock, worker, taken, batch, sent, phase, inHand>>

Free == "free"
Outcomes == {"succeeded", "failed"}

TypeOK ==
  /\ caller \in [Callers -> {"idle", "admitted", "waiting", "rejected", "refused"}]
  /\ promise \in [Callers -> {"pending", "interrupted"} \cup Outcomes]
  /\ queue \in Seq(Callers)
  /\ accepting \in BOOLEAN
  /\ lock \in Workers \cup {Free}
  /\ worker \in [Workers -> {"idle", "assembling", "running", "dead"}]
  /\ taken \in [Workers -> SUBSET Callers]
  /\ batch \in [Workers -> SUBSET Callers]
  /\ sent \in [Callers -> Nat]
  /\ phase \in {"open", "closing", "stopped", "drained"}
  /\ inHand \in [Workers -> SUBSET Callers]

Init ==
  /\ caller = [c \in Callers |-> "idle"]
  /\ promise = [c \in Callers |-> "pending"]
  /\ queue = <<>>
  /\ accepting = TRUE
  /\ lock = Free
  /\ worker = [w \in Workers |-> "idle"]
  /\ taken = [w \in Workers |-> {}]
  /\ batch = [w \in Workers |-> {}]
  /\ sent = [c \in Callers |-> 0]
  /\ phase = "open"
  /\ inHand = [w \in Workers |-> {}]

Interrupt(cs) == [c \in Callers |-> IF c \in cs /\ promise[c] = "pending" THEN "interrupted" ELSE promise[c]]

Enqueue(c) ==
  IF Len(queue) < MaxQueue
  THEN /\ queue' = Append(queue, c)
       /\ caller' = [caller EXCEPT ![c] = "waiting"]
  ELSE /\ caller' = [caller EXCEPT ![c] = "rejected"]
       /\ UNCHANGED queue

(* `enqueue`: under the `accepting` lock, check and offer in one step. *)
Submit(c) ==
  /\ caller[c] = "idle"
  /\ IF ~accepting
     THEN caller' = [caller EXCEPT ![c] = "refused"] /\ UNCHANGED queue
     ELSE IF SerialisedAdmission
          THEN Enqueue(c)
          ELSE caller' = [caller EXCEPT ![c] = "admitted"] /\ UNCHANGED queue
  /\ UNCHANGED <<promise, accepting, lock, worker, taken, batch, sent, phase, inHand>>

(* Only without SerialisedAdmission: the offer happens after, and apart from, the check. *)
Offer(c) ==
  /\ caller[c] = "admitted"
  /\ Enqueue(c)
  /\ UNCHANGED <<promise, accepting, lock, worker, taken, batch, sent, phase, inHand>>

(* The caller is interrupted while awaiting its result. *)
Cancel(c) ==
  /\ caller[c] = "waiting"
  /\ promise[c] = "pending"
  /\ promise' = [promise EXCEPT ![c] = "interrupted"]
  /\ UNCHANGED <<caller, queue, accepting, lock, worker, taken, batch, sent, phase, inHand>>

Alive(w) == worker[w] # "dead"

(* `assemble`: take the forming lock, then the first entry (recorded in `taken` uninterruptibly). *)
TakeFirst(w) ==
  /\ worker[w] = "idle"
  /\ lock = Free
  /\ queue # <<>>
  /\ lock' = w
  /\ taken' = [taken EXCEPT ![w] = {Head(queue)}]
  /\ queue' = Tail(queue)
  /\ worker' = [worker EXCEPT ![w] = "assembling"]
  /\ UNCHANGED <<caller, promise, accepting, batch, sent, phase, inHand>>

(* `fill`: `takeUpTo` the room left in the batch, recorded in `taken` in the same uninterruptible step. *)
Fill(w) ==
  /\ worker[w] = "assembling"
  /\ Cardinality(taken[w]) < MaxBatch
  /\ queue # <<>>
  /\ LET room == MaxBatch - Cardinality(taken[w])
         n    == IF Len(queue) < room THEN Len(queue) ELSE room
         got  == {queue[i] : i \in 1..n}
     IN  /\ queue' = SubSeq(queue, n + 1, Len(queue))
         /\ IF AtomicTake
            THEN taken' = [taken EXCEPT ![w] = @ \cup got] /\ UNCHANGED inHand
            ELSE inHand' = [inHand EXCEPT ![w] = @ \cup got] /\ UNCHANGED taken
  /\ inHand[w] = {}
  /\ UNCHANGED <<caller, promise, accepting, lock, worker, batch, sent, phase>>

(* Only without AtomicTake: the entries just taken are recorded as owned, in a later step. *)
Record(w) ==
  /\ inHand[w] # {}
  /\ taken' = [taken EXCEPT ![w] = @ \cup inHand[w]]
  /\ inHand' = [inHand EXCEPT ![w] = {}]
  /\ UNCHANGED <<caller, promise, queue, accepting, lock, worker, batch, sent, phase>>

(* The batch is full or its deadline passed (time is abstracted: a deadline may pass at any moment). *)
(* `run` leaves out callers that were interrupted while waiting.                                     *)
Dispatch(w) ==
  /\ worker[w] = "assembling"
  /\ inHand[w] = {}
  /\ lock' = Free
  /\ LET live == {c \in taken[w] : promise[c] = "pending"} IN
       IF live = {}
       THEN /\ worker' = [worker EXCEPT ![w] = "idle"]
            /\ taken' = [taken EXCEPT ![w] = {}]
            /\ UNCHANGED <<batch, sent>>
       ELSE /\ worker' = [worker EXCEPT ![w] = "running"]
            /\ batch' = [batch EXCEPT ![w] = live]
            /\ sent' = [c \in Callers |-> IF c \in live THEN sent[c] + 1 ELSE sent[c]]
            /\ UNCHANGED taken
  /\ UNCHANGED <<caller, promise, queue, accepting, phase, inHand>>

(* The batch call has returned: results are set one caller at a time, interruptibly. *)
Complete(w, c) ==
  /\ worker[w] = "running"
  /\ c \in batch[w]
  /\ \E outcome \in Outcomes :
       promise' = [promise EXCEPT ![c] = IF @ = "pending" THEN outcome ELSE @]
  /\ batch' = [batch EXCEPT ![w] = @ \ {c}]
  /\ UNCHANGED <<caller, queue, accepting, lock, worker, taken, sent, phase, inHand>>

Finish(w) ==
  /\ worker[w] = "running"
  /\ batch[w] = {}
  /\ worker' = [worker EXCEPT ![w] = "idle"]
  /\ taken' = [taken EXCEPT ![w] = {}]
  /\ UNCHANGED <<caller, promise, queue, accepting, lock, batch, sent, phase, inHand>>

(* The enclosing scope starts closing. *)
BeginClose ==
  /\ phase = "open"
  /\ phase' = "closing"
  /\ UNCHANGED <<caller, promise, queue, accepting, lock, worker, taken, batch, sent, inHand>>

(* Scope finalizer, part 1: interrupt each worker; its `onInterrupt` releases what it took. *)
KillWorker(w) ==
  /\ phase = "closing"
  /\ Alive(w)
  /\ worker' = [worker EXCEPT ![w] = "dead"]
  /\ promise' = IF CleanupOnInterrupt THEN Interrupt(taken[w]) ELSE promise
  /\ lock' = IF lock = w THEN Free ELSE lock
  /\ taken' = [taken EXCEPT ![w] = {}]
  /\ batch' = [batch EXCEPT ![w] = {}]
  /\ inHand' = [inHand EXCEPT ![w] = {}] \* never recorded, so nobody releases them
  /\ UNCHANGED <<caller, queue, accepting, sent, phase>>

(* Scope finalizer, part 2 (`close`): stop admission, under the same lock as `enqueue`... *)
StopAccepting ==
  /\ phase = "closing"
  /\ \A w \in Workers : ~Alive(w)
  /\ accepting' = FALSE
  /\ phase' = "stopped"
  /\ UNCHANGED <<caller, promise, queue, lock, worker, taken, batch, sent, inHand>>

(* ...then release every caller still queued. *)
Drain ==
  /\ phase = "stopped"
  /\ promise' = Interrupt({queue[i] : i \in 1..Len(queue)})
  /\ queue' = <<>>
  /\ phase' = "drained"
  /\ UNCHANGED <<caller, accepting, lock, worker, taken, batch, sent, inHand>>

WorkerStep(w) == TakeFirst(w) \/ Fill(w) \/ Record(w) \/ Dispatch(w) \/ Finish(w) \/ \E c \in Callers : Complete(w, c)

Next ==
  \/ \E c \in Callers : Submit(c) \/ Offer(c) \/ Cancel(c)
  \/ \E w \in Workers : WorkerStep(w) \/ KillWorker(w)
  \/ BeginClose \/ StopAccepting \/ Drain

(* Callers, cancellation and closing happen whenever they happen; the runtime keeps workers and finalizers going. *)
Fairness ==
  /\ \A w \in Workers : WF_vars(WorkerStep(w)) /\ WF_vars(KillWorker(w))
  /\ \A c \in Callers : WF_vars(Offer(c))
  /\ WF_vars(StopAccepting) /\ WF_vars(Drain)

Spec == Init /\ [][Next]_vars /\ Fairness

-----------------------------------------------------------------------------
(* Safety *)

(* No input is ever sent in more than one batch call. *)
AtMostOnce == \A c \in Callers : sent[c] <= 1

(* A rejected or refused input is never sent. *)
TurnedAwayNeverSent == \A c \in Callers : caller[c] \in {"rejected", "refused"} => sent[c] = 0

(* Only one batch is assembled at a time, by the lock holder. *)
OneAssembler == \A w \in Workers : worker[w] = "assembling" <=> lock = w

BatchBounded == \A w \in Workers : Cardinality(taken[w]) <= MaxBatch

(* An input is either queued, owned by one worker, or neither. *)
SingleOwner ==
  /\ \A w1, w2 \in Workers : w1 # w2 => taken[w1] \cap taken[w2] = {}
  /\ \A w \in Workers, i \in 1..Len(queue) : queue[i] \notin taken[w]

(* Once the scope has fully closed, nobody is left waiting for a result that will never come. *)
NoneStrandedAfterClose ==
  phase = "drained" => \A c \in Callers : caller[c] = "waiting" => promise[c] # "pending"

Safety == AtMostOnce /\ TurnedAwayNeverSent /\ OneAssembler /\ BatchBounded /\ SingleOwner /\ NoneStrandedAfterClose

(* Liveness: every caller that was let in eventually gets a result, is told it failed, or is interrupted. *)
EveryCallerAnswered == \A c \in Callers : (caller[c] = "waiting") ~> (promise[c] # "pending")
=============================================================================
