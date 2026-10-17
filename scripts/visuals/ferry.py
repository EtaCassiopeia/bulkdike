"""The README's scene: an idle key is sent at once; a busy key's inputs gather while its call is in flight."""

from hud import CYAN, PINK, DIM, Scene

LANE_X, DOCK_X, CROSS, SHUTTLE_W = 262, 400, 270, 122
STORE_X = DOCK_X + CROSS + SHUTTLE_W + 22
LOG_X = STORE_X + 84


def lane(sc: Scene, y, key, mode, color, trips, batches, arrivals):
    """trips: (load, depart, arrive, leave_store, back); batches: input numbers per trip;
    arrivals: (number, arrive_at, board_at, slot in the lane)."""
    sc.add(sc.text(28, y - 4, f"key = {key}", "key"), sc.text(28, y + 15, mode, "mode", fill=color))
    sc.add(sc.bracket(LANE_X, y, DOCK_X - LANE_X - 14), sc.rail(DOCK_X, y, STORE_X, color))
    for n, arrive, board, slot in arrivals:
        x = DOCK_X - 26 - slot * 22
        sc.add(sc.chip(n, color, sc.path(
            [(arrive, x - 30, y, 0), (arrive + 0.3, x, y), (board - 0.1, x, y), (board, x + 20, y, 0)], "linear"
        )))
    track = [(0.0, 0, 0)]
    for _, depart, arrive, leave, back in trips:
        track += [(depart, 0, 0), (arrive, CROSS, 0), (leave, CROSS, 0), (back, 0, 0)]
    parts = [sc.shuttle(SHUTTLE_W, color, DOCK_X, y)]
    for (load, _, _, leave, _), inputs in zip(trips, batches):
        start = DOCK_X + SHUTTLE_W / 2 - (len(inputs) - 1) * 10
        parts += [sc.chip(n, color, sc.shown([(load, leave)]), start + i * 20, y) for i, n in enumerate(inputs)]
        label = "call [1 input]" if len(inputs) == 1 else f"call [batch of {len(inputs)}]"
        parts.append(sc.text(DOCK_X + SHUTTLE_W / 2, y - 24, label, "tag", color, "middle", sc.shown([(load, leave)])))
    sc.add(f'<g style="{sc.path(track)}">', *parts, "</g>")
    sc.add(*(sc.pulse(STORE_X, y, color, arrive) for _, _, arrive, _, _ in trips))


def build() -> Scene:
    sc = Scene(
        LOG_X + 236, 380, 12.0,
        "Ferry.perKey",
        "≤ 1 call in flight per key · batch size follows load · no timer",
        "inputs that arrive while a key's call is in flight wait in its lane, in order, and leave together in its next call",
        "Ferry.perKey: an idle key's input is sent at once; a busy key's inputs gather in its lane while its call is in "
        "flight and travel together in its next call, in order, one call per key at a time.",
    )
    sc.add(sc.text(28, 88, "CALLERS", "tiny"), sc.text(STORE_X + 30, 88, "STORE", "tiny", anchor="middle"),
           sc.text(LOG_X + 14, 88, "STORE LOG", "tiny"))
    sc.add(sc.panel(STORE_X, 104, 60, 210), sc.text(STORE_X + 30, 213, "write", "tiny", anchor="middle"),
           sc.panel(LOG_X, 104, sc.w - 28 - LOG_X, 210, "#070b16", "#1e293b"))
    lane(sc, 150, "a", "idle → sent at once, no queue", CYAN,
         [(1.0, 1.3, 2.8, 3.3, 4.8), (7.0, 7.3, 8.8, 9.3, 10.8)], [[1], [2]],
         [(1, 0.6, 1.0, 0), (2, 6.6, 7.0, 0)])
    lane(sc, 268, "b", "busy → batched while in flight", PINK,
         [(0.0, 0.5, 2.0, 2.5, 4.0), (4.0, 4.5, 6.0, 6.5, 8.0), (8.0, 8.5, 10.0, 10.5, 12.0)],
         [[1], [2, 3, 4], [5, 6, 7, 8, 9]],
         [(1, 11.4, 12.0, 0), (2, 0.9, 4.0, 0), (3, 1.7, 4.0, 1), (4, 2.8, 4.0, 2),
          (5, 4.7, 8.0, 0), (6, 5.3, 8.0, 1), (7, 6.0, 8.0, 2), (8, 6.7, 8.0, 3), (9, 7.4, 8.0, 4)])
    log = [(2.0, "b", "[1]", PINK), (2.8, "a", "[1]", CYAN), (6.0, "b", "[2 3 4]", PINK),
           (8.8, "a", "[2]", CYAN), (10.0, "b", "[5 6 7 8 9]", PINK)]
    for i, (t, key, inputs, color) in enumerate(log):
        sc.add(f'<text x="{LOG_X + 14}" y="{128 + i * 24}" class="log" style="{sc.shown([(t, 11.8)], 0.2)}">'
               f'<tspan fill="{DIM}">{t:04.1f}s</tspan>  <tspan fill="{color}">{key} ← {inputs}</tspan></text>')
    return sc
