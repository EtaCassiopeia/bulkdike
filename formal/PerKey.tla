------------------------------- MODULE PerKey -------------------------------
(***************************************************************************)
(* The concurrency protocol of bulkdike.BulkDike.perKey, at the            *)
(* granularity of its atomic steps (STM transactions and uninterruptible  *)
(* regions).                                                               *)
(*                                                                         *)
(* Callers submit inputs for a key. Each key has a lane of waiting inputs *)
(* kept in ascending rank, ties in admission order (Order.by; with one    *)
(* rank for all, Order.arrival). Workers, one per batch slot, take a      *)
(* batch from the front of a lane, mark the key busy, run the batch and   *)
(* release the key. Time is abstracted away: a partial batch may become   *)
(* due at any moment.                                                     *)
(* Callers may be interrupted while they wait. Closing the scope          *)
(* interrupts every worker, then stops admission and releases whatever is *)
(* still waiting, in one transaction.                                     *)
(*                                                                         *)
(* With Direct (a zero maxBatchDelay), a caller whose key is idle claims *)
(* the key in the admission transaction and sends its input itself, as a  *)
(* batch of one, from a fiber forked into the dike's scope; it releases   *)
(* the key once that call is over. Without Direct, every input is         *)
(* batched by a worker.                                                   *)
(*                                                                         *)
(* Six switches weaken the protocol, to show each mechanism is needed:    *)
(*   Sorted          - an input joins its lane in rank order, not at the  *)
(*                     back                                               *)
(*   BusyGuard       - a key whose batch is in flight is not taken again  *)
(*   AtomicTake      - taking a batch and recording it as owned by the    *)
(*                     worker is one uninterruptible step                 *)
(*   AtomicAdmission - checking that admission is open and joining the    *)
(*                     lane are one transaction                           *)
(*   DirectGuard     - only an idle key (nothing waiting, nothing in      *)
(*                     flight) is claimed for a direct call               *)
(*   AwaitCall       - an interrupted direct caller releases its key only *)
(*                     once its call is over                              *)
(* The implementation has all six. Without Sorted, inputs waiting         *)
(* together are sent out of rank order; without BusyGuard, DirectGuard or *)
(* AwaitCall, a key has two calls in flight; without either atomicity, a  *)
(* caller can be stranded.                                                *)
(***************************************************************************)
EXTENDS Naturals, Sequences, FiniteSets

CONSTANTS Callers, Keys, Workers, MaxBatch, MaxQueue, BusyGuard, AtomicTake, AtomicAdmission,
          Direct, DirectGuard, AwaitCall, Sorted, LowRank

ASSUME MaxBatch >= 1 /\ MaxQueue >= MaxBatch /\ LowRank \subseteq Callers

VARIABLES
  caller,   \* where each caller is in `apply`
  promise,  \* each caller's result
  lane,     \* each key's waiting inputs, oldest first
  admitted, \* each key's inputs in admission order (history, for checking order)
  log,      \* each key's inputs in the order they were sent (history, for checking order)
  busy,     \* keys whose batch, or direct call, is in flight
  open,     \* admission is open
  worker,   \* each worker's activity
  wkey,     \* the key of each worker's batch
  taken,    \* inputs a worker has taken and owns until their results are set
  inHand,   \* inputs taken out of a lane but not yet recorded as owned (only without AtomicTake)
  sent,     \* how many batch calls carried each caller's input
  phase,    \* lifecycle of the enclosing scope
  pick,     \* the key each caller submits to, between check and join (only without AtomicAdmission)
  call,     \* each direct caller's call: forked, running, or over
  dkey,     \* the key each direct caller claimed
  gone,     \* the inputs of its key already out of the lane when each caller was admitted (history, for order)
  batch     \* each worker's batch, in lane order

vars == <<caller, promise, lane, admitted, log, busy, open, worker, wkey, taken, inHand, sent, phase, pick, call, dkey, gone, batch>>

NoKey == "none"
Outcomes == {"succeeded", "failed"}

Range(s) == {s[i] : i \in 1..Len(s)}
Queued == {c \in Callers : \E k \in Keys : c \in Range(lane[k])}

TypeOK ==
  /\ caller \in [Callers -> {"idle", "admitted", "waiting", "rejected", "refused", "direct", "done"}]
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
  /\ call \in [Callers -> {"none", "forked", "running", "over"}]
  /\ dkey \in [Callers -> Keys \cup {NoKey}]
  /\ gone \in [Callers -> SUBSET Callers]
  /\ batch \in [Workers -> Seq(Callers)]

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
  /\ pick = [c \in Callers |-> NoKey]
  /\ call = [c \in Callers |-> "none"]
  /\ dkey = [c \in Callers |-> NoKey]
  /\ gone = [c \in Callers |-> {}]
  /\ batch = [w \in Workers |-> <<>>]

Interrupt(cs) == [c \in Callers |-> IF c \in cs /\ promise[c] = "pending" THEN "interrupted" ELSE promise[c]]

Idle(k) == lane[k] = <<>> /\ k \notin busy

Rank(c) == IF c \in LowRank THEN 0 ELSE 1

(* `c` placed after every input of a rank up to its own, in a lane kept in rank order. *)
Insert(s, c) == SelectSeq(s, LAMBDA x : Rank(x) <= Rank(c)) \o <<c>> \o SelectSeq(s, LAMBDA x : Rank(x) > Rank(c))

(* Claims an idle key for a direct call, or else joins its lane if the bound allows. *)
Join(c, k) ==
  LET ahead == Range(admitted[k]) \ Range(lane[k]) IN
  /\ IF Direct /\ (DirectGuard => Idle(k))
     THEN /\ busy' = busy \cup {k}
          /\ admitted' = [admitted EXCEPT ![k] = Append(@, c)]
          /\ caller' = [caller EXCEPT ![c] = "direct"]
          /\ call' = [call EXCEPT ![c] = "forked"]
          /\ dkey' = [dkey EXCEPT ![c] = k]
          /\ gone' = [gone EXCEPT ![c] = ahead]
          /\ UNCHANGED lane
     ELSE /\ IF Cardinality(Queued) < MaxQueue
             THEN /\ lane' = [lane EXCEPT ![k] = IF Sorted THEN Insert(@, c) ELSE Append(@, c)]
                  /\ admitted' = [admitted EXCEPT ![k] = Append(@, c)]
                  /\ caller' = [caller EXCEPT ![c] = "waiting"]
                  /\ gone' = [gone EXCEPT ![c] = ahead]
             ELSE caller' = [caller EXCEPT ![c] = "rejected"] /\ UNCHANGED <<lane, admitted, gone>>
          /\ UNCHANGED <<busy, call, dkey>>
  /\ UNCHANGED batch

(* `admit`: one transaction checks that admission is open and that the bound holds, and appends to the lane. The *)
(* caller's key is fixed from here on, as `pick`.                                                                  *)
Submit(c, k) ==
  /\ caller[c] = "idle"
  /\ IF ~open
     THEN caller' = [caller EXCEPT ![c] = "refused"] /\ UNCHANGED <<lane, admitted, pick, busy, call, dkey, gone, batch>>
     ELSE IF AtomicAdmission
          THEN Join(c, k) /\ UNCHANGED pick
          ELSE /\ caller' = [caller EXCEPT ![c] = "admitted"]
               /\ pick' = [pick EXCEPT ![c] = k]
               /\ UNCHANGED <<lane, admitted, busy, call, dkey, gone, batch>>
  /\ UNCHANGED <<promise, log, open, worker, wkey, taken, inHand, sent, phase>>

(* Only without AtomicAdmission: the caller joins its lane after, and apart from, the check. *)
Enter(c) ==
  /\ caller[c] = "admitted"
  /\ Join(c, pick[c])
  /\ UNCHANGED <<promise, log, open, worker, wkey, taken, inHand, sent, phase, pick>>

(* The caller is interrupted while awaiting its result. *)
Cancel(c) ==
  /\ caller[c] = "waiting"
  /\ promise[c] = "pending"
  /\ promise' = [promise EXCEPT ![c] = "interrupted"]
  /\ UNCHANGED <<caller, lane, admitted, log, busy, open, worker, wkey, taken, inHand, sent, phase, pick, call, dkey, gone, batch>>

Alive(w) == worker[w] # "dead"

(* `take`: one transaction takes the first inputs of a lane (any number up to MaxBatch: the deadline, size and *)
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
            /\ batch' = [batch EXCEPT ![w] = SubSeq(lane[k], 1, n)]
            /\ IF AtomicTake
               THEN taken' = [taken EXCEPT ![w] = got] /\ UNCHANGED inHand
               ELSE inHand' = [inHand EXCEPT ![w] = got] /\ UNCHANGED taken
  /\ worker' = [worker EXCEPT ![w] = "taking"]
  /\ UNCHANGED <<caller, promise, admitted, log, open, sent, phase, pick, call, dkey, gone>>

(* Only without AtomicTake: the inputs just taken are recorded as owned, in a later step. *)
Record(w) ==
  /\ inHand[w] # {}
  /\ taken' = [taken EXCEPT ![w] = inHand[w]]
  /\ inHand' = [inHand EXCEPT ![w] = {}]
  /\ UNCHANGED <<caller, promise, lane, admitted, log, busy, open, worker, wkey, sent, phase, pick, call, dkey, gone, batch>>

(* `run` leaves out callers interrupted while waiting, and sends the rest in lane order. *)
Dispatch(w) ==
  /\ worker[w] = "taking"
  /\ inHand[w] = {}
  /\ LET k    == wkey[w]
         live == SelectSeq(batch[w], LAMBDA c : c \in taken[w] /\ promise[c] = "pending")
     IN  /\ log' = [log EXCEPT ![k] = @ \o live]
         /\ sent' = [c \in Callers |-> IF c \in Range(live) THEN sent[c] + 1 ELSE sent[c]]
         /\ taken' = [taken EXCEPT ![w] = Range(live)]
  /\ worker' = [worker EXCEPT ![w] = "running"]
  /\ UNCHANGED <<caller, promise, lane, admitted, busy, open, wkey, inHand, phase, pick, call, dkey, gone, batch>>

(* The batch call has returned: results are set one caller at a time, interruptibly. *)
Complete(w, c) ==
  /\ worker[w] = "running"
  /\ c \in taken[w]
  /\ \E outcome \in Outcomes :
       promise' = [promise EXCEPT ![c] = IF @ = "pending" THEN outcome ELSE @]
  /\ taken' = [taken EXCEPT ![w] = @ \ {c}]
  /\ UNCHANGED <<caller, lane, admitted, log, busy, open, worker, wkey, inHand, sent, phase, pick, call, dkey, gone, batch>>

(* `release`: the key may be taken again. *)
Finish(w) ==
  /\ worker[w] = "running"
  /\ taken[w] = {}
  /\ busy' = busy \ {wkey[w]}
  /\ worker' = [worker EXCEPT ![w] = "idle"]
  /\ wkey' = [wkey EXCEPT ![w] = NoKey]
  /\ UNCHANGED <<caller, promise, lane, admitted, log, open, taken, inHand, sent, phase, pick, call, dkey, gone, batch>>

(* The forked call starts: the direct caller's input is sent, alone. *)
CallStart(c) ==
  /\ call[c] = "forked"
  /\ log' = [log EXCEPT ![dkey[c]] = Append(@, c)]
  /\ sent' = [sent EXCEPT ![c] = @ + 1]
  /\ call' = [call EXCEPT ![c] = "running"]
  /\ UNCHANGED <<caller, promise, lane, admitted, busy, open, worker, wkey, taken, inHand, phase, pick, dkey, gone, batch>>

CallEnd(c) ==
  /\ call[c] = "running"
  /\ \E outcome \in Outcomes :
       promise' = [promise EXCEPT ![c] = IF @ = "pending" THEN outcome ELSE @]
  /\ call' = [call EXCEPT ![c] = "over"]
  /\ UNCHANGED <<caller, lane, admitted, log, busy, open, worker, wkey, taken, inHand, sent, phase, pick, dkey, gone, batch>>

(* The direct caller is interrupted: `onInterrupt` interrupts its call and, with AwaitCall, waits until it is over. *)
CancelDirect(c) ==
  /\ caller[c] = "direct"
  /\ promise[c] = "pending"
  /\ promise' = [promise EXCEPT ![c] = "interrupted"]
  /\ IF AwaitCall THEN call' = [call EXCEPT ![c] = "over"] ELSE UNCHANGED call
  /\ UNCHANGED <<caller, lane, admitted, log, busy, open, worker, wkey, taken, inHand, sent, phase, pick, dkey, gone, batch>>

(* Closing the scope interrupts every call forked into it. *)
KillCall(c) ==
  /\ phase # "open"
  /\ call[c] \in {"forked", "running"}
  /\ call' = [call EXCEPT ![c] = "over"]
  /\ promise' = Interrupt({c})
  /\ UNCHANGED <<caller, lane, admitted, log, busy, open, worker, wkey, taken, inHand, sent, phase, pick, dkey, gone, batch>>

(* `ensuring(release)`: the direct caller frees its key. *)
Release(c) ==
  /\ caller[c] = "direct"
  /\ \/ call[c] = "over"
     \/ ~AwaitCall /\ promise[c] = "interrupted"
  /\ busy' = busy \ {dkey[c]}
  /\ caller' = [caller EXCEPT ![c] = "done"]
  /\ UNCHANGED <<promise, lane, admitted, log, open, worker, wkey, taken, inHand, sent, phase, pick, call, dkey, gone, batch>>

DirectStep(c) == CallStart(c) \/ CallEnd(c) \/ Release(c)

BeginClose ==
  /\ phase = "open"
  /\ phase' = "closing"
  /\ UNCHANGED <<caller, promise, lane, admitted, log, busy, open, worker, wkey, taken, inHand, sent, pick, call, dkey, gone, batch>>

(* Scope finalizer, part 1: interrupt each worker; its `onInterrupt` releases what it owns. *)
KillWorker(w) ==
  /\ phase = "closing"
  /\ Alive(w)
  /\ worker' = [worker EXCEPT ![w] = "dead"]
  /\ promise' = Interrupt(taken[w])
  /\ taken' = [taken EXCEPT ![w] = {}]
  /\ inHand' = [inHand EXCEPT ![w] = {}] \* never recorded, so nobody releases them
  /\ UNCHANGED <<caller, lane, admitted, log, busy, open, wkey, sent, phase, pick, call, dkey, gone, batch>>

(* Scope finalizer, part 2 (`close`): one transaction stops admission and empties every lane. *)
Close ==
  /\ phase = "closing"
  /\ \A w \in Workers : ~Alive(w)
  /\ open' = FALSE
  /\ promise' = Interrupt(Queued)
  /\ lane' = [k \in Keys |-> <<>>]
  /\ phase' = "closed"
  /\ UNCHANGED <<caller, admitted, log, busy, worker, wkey, taken, inHand, sent, pick, call, dkey, gone, batch>>

WorkerStep(w) == Take(w) \/ Record(w) \/ Dispatch(w) \/ Finish(w) \/ \E c \in Callers : Complete(w, c)

Next ==
  \/ \E c \in Callers : Cancel(c) \/ Enter(c) \/ \E k \in Keys : Submit(c, k)
  \/ \E c \in Callers : DirectStep(c) \/ CancelDirect(c) \/ KillCall(c)
  \/ \E w \in Workers : WorkerStep(w) \/ KillWorker(w)
  \/ BeginClose \/ Close

Fairness ==
  /\ \A w \in Workers : WF_vars(WorkerStep(w)) /\ WF_vars(KillWorker(w))
  /\ \A c \in Callers : WF_vars(Enter(c)) /\ WF_vars(DirectStep(c))
  /\ WF_vars(Close)

Spec == Init /\ [][Next]_vars /\ Fairness

-----------------------------------------------------------------------------
(* Safety *)

(* A key never has two calls in flight, batched or direct. *)
OneInFlightPerKey ==
  \A k \in Keys :
    Cardinality({w \in Workers : worker[w] \in {"taking", "running"} /\ wkey[w] = k}) +
    Cardinality({c \in Callers : call[c] \in {"forked", "running"} /\ dkey[c] = k}) <= 1

(* Each key's inputs are sent in rank order, ties in admission order, except that an input admitted once another had *)
(* already left the lane (a late low rank) may follow it whatever its rank.                                          *)
Before(s, a, b) == \E i, j \in 1..Len(s) : i < j /\ s[i] = a /\ s[j] = b
Precedes(k, a, b) == Rank(a) < Rank(b) \/ (Rank(a) = Rank(b) /\ Before(admitted[k], a, b))
InOrderPerKey ==
  \A k \in Keys : \A a, b \in Range(log[k]) : Before(log[k], a, b) => Precedes(k, a, b) \/ a \in gone[b]

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

(* Liveness: every direct caller gets an answer and frees its key. *)
EveryDirectCallerDone == \A c \in Callers : (caller[c] = "direct") ~> (caller[c] = "done")
=============================================================================
