"""Ferry.make: one bounded queue; a batch leaves when full or due; at most two calls run; a full queue rejects."""

from hud import AMBER, BLUE, CYAN, RED, Scene

SRC_X, QY = 70, 215                 # where inputs come from; the queue's row
Q_X, SLOT = 178, 30                 # queue's left edge; one slot's width
LANES = {1: 145, 2: 285}            # the two batch slots' rows
DOCK_X, SHUTTLE_W, CROSS = 500, 136, 190
API_X = DOCK_X + SHUTTLE_W + CROSS + 16


def qx(slot: int) -> float:
    """Centre of a queue slot; slot 0 is the front, nearest the batch slots."""
    return Q_X + 8 * SLOT - SLOT / 2 - slot * SLOT


def seat_x(i: int) -> float:
    return DOCK_X + 23 + i * 30


def batch(sc: Scene, lane, color, tag, take, depart, arrive, leave, back, inputs):
    """inputs: (label, arrive_at, [(time, queue slot)]): each joins the queue, may shift, then boards."""
    y = LANES[lane]
    for i, (label, at, slots) in enumerate(inputs):
        pts = [(at - 0.4, SRC_X, QY, 0), (at, qx(slots[0][1]), QY)]
        for (t, s), (_, prev) in zip(slots[1:], slots):
            pts += [(t, qx(prev), QY), (t + 0.35, qx(s), QY)]
        pts += [(take, qx(slots[-1][1]), QY), (take + 0.35, seat_x(i), y), (depart, seat_x(i), y),
                (arrive, seat_x(i) + CROSS, y), (leave, seat_x(i) + CROSS, y, 0)]
        sc.add(sc.chip(label, color, sc.path(pts)))
    track = [(0, 0, 0), (depart, 0, 0), (arrive, CROSS, 0), (leave, CROSS, 0), (back, 0, 0)]
    label = sc.text(DOCK_X + SHUTTLE_W / 2, y - 24, tag, "tag", color, "middle", sc.shown([(take, leave)]))
    sc.add(f'<g style="{sc.path(track)}">', label, "</g>")
    sc.add(sc.pulse(API_X, y, color, arrive))


def build() -> Scene:
    T = 14.0
    sc = Scene(
        API_X + 88, 400, T,
        "Ferry.make",
        "one queue · leaves when full or due · ≤ maxInFlightBatches calls",
        "a batch leaves when maxBatchSize (4) inputs wait, or maxBatchDelay after its oldest; a full queue rejects at once",
        "Ferry.make: inputs wait in one bounded queue; a batch leaves when it holds maxBatchSize inputs or when its "
        "oldest input has waited maxBatchDelay; at most two batch calls run; when the queue is full a new call is "
        "rejected at once.",
    )
    sc.add(sc.text(28, 88, "CALLERS", "tiny"), sc.text(Q_X, 88, "QUEUE", "tiny"),
           sc.text(DOCK_X, 88, "BATCH SLOTS · maxInFlightBatches = 2", "tiny"),
           sc.text(API_X + 30, 88, "API", "tiny", anchor="middle"))
    # the queue
    sc.add(f'<rect x="{Q_X}" y="{QY - 20}" width="{8 * SLOT}" height="40" rx="6" fill="#070b16" stroke="#334155"/>')
    sc.add(*(f'<line x1="{Q_X + k * SLOT}" y1="{QY - 14}" x2="{Q_X + k * SLOT}" y2="{QY + 14}" stroke="#1e293b"/>'
             for k in range(1, 8)))
    sc.add(sc.text(Q_X + 4 * SLOT, QY + 38, "maxQueueing = 8", "tiny", anchor="middle"))
    # the batch slots and the API
    for n, y in LANES.items():
        sc.add(sc.text(DOCK_X - 14, y + 4, f"slot {n}", "tiny", anchor="end"), sc.rail(DOCK_X, y, API_X, BLUE))
    sc.add(sc.panel(API_X, 108, 60, 214), sc.text(API_X + 30, 219, "call", "tiny", anchor="middle"))

    # Each slot's shuttle, through all its trips
    trips ={1: [(1.9, 3.3, 3.8, 5.2), (6.4, 7.8, 8.3, 9.7), (10.15, 11.55, 12.0, 13.4)],
             2: [(5.0, 6.4, 6.9, 8.3), (8.75, 10.15, 10.6, 12.0)]}
    for n, ts in trips.items():
        track = [(0, 0, 0)]
        for d, a, l, b in ts:
            track += [(d, 0, 0), (a, CROSS, 0), (l, CROSS, 0), (b, 0, 0)]
        sc.add(f'<g style="{sc.path(track)}">{sc.shuttle(SHUTTLE_W, BLUE, DOCK_X, LANES[n])}</g>')

    batch(sc, 1, CYAN, "batch of 4 · full", 1.5, 1.9, 3.3, 3.8, 5.2,
          [(str(i), 0.1 + 0.3 * i, [(0, i - 1)]) for i in range(1, 5)])
    # A lone input waits for company until its deadline
    batch(sc, 2, AMBER, "batch of 2 · due", 4.6, 5.0, 6.4, 6.9, 8.3,
          [("5", 2.2, [(0, 0)]), ("6", 3.4, [(0, 1)])])
    circ = 2 * 3.1416 * 13
    ring = sc.anim([(0, f"opacity:0;stroke-dashoffset:{circ:.1f}"), (2.2, f"opacity:0;stroke-dashoffset:{circ:.1f}"),
                    (2.35, f"opacity:1;stroke-dashoffset:{circ:.1f}"), (4.6, "opacity:1;stroke-dashoffset:0"),
                    (4.9, "opacity:0;stroke-dashoffset:0"), (T, "opacity:0;stroke-dashoffset:0")])
    sc.add(f'<circle cx="{qx(0)}" cy="{QY - 50}" r="13" fill="none" stroke="#1e293b" stroke-width="3" '
           f'style="{sc.shown([(2.2, 4.9)])}"/>',
           f'<circle cx="{qx(0)}" cy="{QY - 50}" r="13" fill="none" stroke="{AMBER}" stroke-width="3" '
           f'stroke-dasharray="{circ:.1f}" transform="rotate(-90 {qx(0)} {QY - 50})" style="{ring}"/>',
           sc.text(qx(0) - 22, QY - 46, "oldest + maxBatchDelay", "hud", AMBER, "end", sc.shown([(2.2, 4.9)])))
    batch(sc, 1, CYAN, "batch of 4 · full", 6.0, 6.4, 7.8, 8.3, 9.7,
          [(str(i), 5.3 + 0.2 * (i - 7), [(0, i - 7)]) for i in range(7, 11)])
    # A burst while both slots are busy fills the queue; the next call is rejected
    burst = {i: 6.5 + 0.15 * (i - 11) for i in range(11, 19)}
    batch(sc, 2, CYAN, "batch of 4 · full", 8.35, 8.75, 10.15, 10.6, 12.0,
          [(str(i), burst[i], [(0, i - 11)]) for i in range(11, 15)])
    batch(sc, 1, CYAN, "batch of 4 · full", 9.75, 10.15, 11.55, 12.0, 13.4,
          [(str(i), burst[i], [(0, i - 11), (8.4, i - 15)]) for i in range(15, 19)])
    sc.add(sc.chip("19", RED, sc.path([(7.4, SRC_X, QY, 0), (7.8, Q_X - 18, QY), (8.5, 120, QY + 46, 0)])),
           sc.text(Q_X - 14, QY - 30, "Rejected: queue full", "tag", RED, "end", sc.shown([(7.8, 9.0)])),
           sc.text(Q_X + 4 * SLOT, QY - 30, "both slots busy: inputs wait", "hud", None, "middle",
                   sc.shown([(6.5, 8.4)])))
    return sc
