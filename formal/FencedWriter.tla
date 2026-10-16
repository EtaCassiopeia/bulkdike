---------------------------- MODULE FencedWriter ----------------------------
(***************************************************************************)
(* The deployment pattern Ferry.perKey is meant for, for one key (keys  *)
(* are independent).                                                      *)
(*                                                                         *)
(* Clients send commands to nodes. Each node runs one batch for the key at *)
(* a time: it reads the key's log, leaves out commands already applied,   *)
(* and asks the store to append the rest only if the log still has the    *)
(* length it read (the fence). The store answers, or the answer is lost.  *)
(* On a refused write or a lost answer the node reads again and retries;  *)
(* answers report each command's position in the log.                     *)
(*                                                                         *)
(* Ownership is not modelled as a rule: a client may send to any live     *)
(* node, so two nodes may write the key at once, as during an ownership   *)
(* handover, or when a client cannot reach the owner. Nodes may crash;    *)
(* their writes already on the wire are still delivered later, and their  *)
(* clients send again. The pattern must be safe without help from routing.*)
(*                                                                         *)
(* Two switches weaken the writer, to show each safeguard is needed:      *)
(*   Fenced       - the store refuses an append if the log has grown      *)
(*   Deduplicated - a node leaves out commands already in the log         *)
(* Without either, a command is applied twice.                            *)
(***************************************************************************)
EXTENDS Naturals, Sequences, FiniteSets

CONSTANTS Commands, Nodes, MaxCrashes, MaxLost, Fenced, Deduplicated

VARIABLES
  log,     \* the key's log in the store
  client,  \* each command's client: idle, waiting at a node, or answered
  answer,  \* the position each answered command was given
  inbox,   \* commands sent to each node, not yet in a batch
  node,    \* each node's state
  batch,   \* each node's current batch
  read,    \* the log as the node last read it
  wire,    \* appends sent to the store, not yet applied: [from, length, ids]
  crashes, \* crashes so far
  lost     \* lost answers so far

vars == <<log, client, answer, inbox, node, batch, read, wire, crashes, lost>>

Range(s) == {s[i] : i \in 1..Len(s)}
Position(s, c) == CHOOSE i \in 1..Len(s) : s[i] = c

(* Some fixed order for a set of commands. *)
AsSeq(S) == CHOOSE f \in [1..Cardinality(S) -> S] : \A i, j \in 1..Cardinality(S) : i # j => f[i] # f[j]

TypeOK ==
  /\ log \in Seq(Commands)
  /\ client \in [Commands -> {"idle", "answered"} \cup Nodes]
  /\ node \in [Nodes -> {"idle", "reading", "writing", "down"}]
  /\ batch \in [Nodes -> SUBSET Commands]
  /\ inbox \in [Nodes -> SUBSET Commands]

Init ==
  /\ log = <<>>
  /\ client = [c \in Commands |-> "idle"]
  /\ answer = [c \in Commands |-> 0]
  /\ inbox = [n \in Nodes |-> {}]
  /\ node = [n \in Nodes |-> "idle"]
  /\ batch = [n \in Nodes |-> {}]
  /\ read = [n \in Nodes |-> <<>>]
  /\ wire = {}
  /\ crashes = 0
  /\ lost = 0

(* A client sends its command to any live node: the owner, or not. *)
Send(c, n) ==
  /\ client[c] = "idle"
  /\ node[n] # "down"
  /\ client' = [client EXCEPT ![c] = n]
  /\ inbox' = [inbox EXCEPT ![n] = @ \cup {c}]
  /\ UNCHANGED <<log, answer, node, batch, read, wire, crashes, lost>>

(* The node's key is free: its next batch takes what has arrived. *)
Take(n) ==
  /\ node[n] = "idle"
  /\ inbox[n] # {}
  /\ batch' = [batch EXCEPT ![n] = inbox[n]]
  /\ inbox' = [inbox EXCEPT ![n] = {}]
  /\ node' = [node EXCEPT ![n] = "reading"]
  /\ UNCHANGED <<log, client, answer, read, wire, crashes, lost>>

Answer(n, s) ==
  /\ client' = [c \in Commands |-> IF c \in batch[n] THEN "answered" ELSE client[c]]
  /\ answer' = [c \in Commands |-> IF c \in batch[n] THEN Position(s, c) ELSE answer[c]]
  /\ batch' = [batch EXCEPT ![n] = {}]
  /\ node' = [node EXCEPT ![n] = "idle"]

(* Read the log; if every command of the batch is already in it, answer from it. Otherwise append the rest. *)
Read(n) ==
  /\ node[n] = "reading"
  /\ read' = [read EXCEPT ![n] = log]
  /\ LET fresh == IF Deduplicated THEN batch[n] \ Range(log) ELSE batch[n] IN
       IF fresh = {}
       THEN Answer(n, log) /\ UNCHANGED wire
       ELSE /\ wire' = wire \cup {[from |-> n, length |-> Len(log), ids |-> AsSeq(fresh)]}
            /\ node' = [node EXCEPT ![n] = "writing"]
            /\ UNCHANGED <<client, answer, batch>>
  /\ UNCHANGED <<log, inbox, crashes, lost>>

(* The store applies an append, or refuses it if the log has grown since it was read. The sender, if still waiting *)
(* for it, learns the result, unless the answer is lost: then it reads again, like after a refusal. A live node     *)
(* has at most one append on the wire, so the one delivered is the one it waits for.                               *)
Deliver(m) ==
  /\ m \in wire
  /\ wire' = wire \ {m}
  /\ LET applies == ~Fenced \/ Len(log) = m.length
         waiting == node[m.from] = "writing"
     IN  /\ log' = IF applies THEN log \o m.ids ELSE log
         /\ \E answered \in {TRUE, FALSE} :
              /\ answered \/ lost < MaxLost
              /\ lost' = IF answered THEN lost ELSE lost + 1
              /\ IF waiting /\ applies /\ answered
                 THEN Answer(m.from, read[m.from] \o m.ids)
                 ELSE IF waiting
                      THEN node' = [node EXCEPT ![m.from] = "reading"] /\ UNCHANGED <<client, answer, batch>>
                      ELSE UNCHANGED <<node, client, answer, batch>>
  /\ UNCHANGED <<inbox, read, crashes>>

(* A node crashes: whatever it held is lost to it, its clients see a failure and send again elsewhere. Its *)
(* appends already on the wire are still delivered.                                                         *)
Crash(n) ==
  /\ node[n] # "down"
  /\ crashes < MaxCrashes
  /\ node' = [node EXCEPT ![n] = "down"]
  /\ client' = [c \in Commands |-> IF client[c] = n THEN "idle" ELSE client[c]]
  /\ inbox' = [inbox EXCEPT ![n] = {}]
  /\ batch' = [batch EXCEPT ![n] = {}]
  /\ crashes' = crashes + 1
  /\ UNCHANGED <<log, answer, read, wire, lost>>

Next ==
  \/ \E c \in Commands, n \in Nodes : Send(c, n)
  \/ \E n \in Nodes : Take(n) \/ Read(n) \/ Crash(n)
  \/ \E m \in wire : Deliver(m)

Spec == Init /\ [][Next]_vars

-----------------------------------------------------------------------------
(* No command is ever applied twice. *)
AtMostOnce == \A i, j \in 1..Len(log) : i # j => log[i] # log[j]

(* An answer is the truth: the command is in the log, at the position given. *)
AnswersTruthful == \A c \in Commands : client[c] = "answered" => answer[c] \in 1..Len(log) /\ log[answer[c]] = c

Safety == AtMostOnce /\ AnswersTruthful

(* Keeps the unfenced variants finite: once a command is applied twice the invariant is violated anyway. *)
LogBound == Len(log) <= Cardinality(Commands) + 1
=============================================================================
