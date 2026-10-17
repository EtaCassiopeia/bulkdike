"""Order.by: inputs that wait together are sent in rank order; a late input is not held back: it goes next."""

from hud import AMBER, DIM, PINK, Scene

T = 10.6
Y = 196
LANE_X, DOCK_X, CROSS, SHUTTLE_W = 262, 400, 270, 122
STORE_X = DOCK_X + CROSS + SHUTTLE_W + 22
LOG_X = STORE_X + 84


def slot_x(slot: int) -> float:
    """Slot 0 is the front of the lane: the lowest rank, next to leave."""
    return DOCK_X - 26 - slot * 24


def seat_x(i: int, n: int) -> float:
    return DOCK_X + SHUTTLE_W / 2 - (n - 1) * 12 + i * 24


def build() -> Scene:
    sc = Scene(
        LOG_X + 262, 340, T,
        "Order.by(_.day)",
        "rank order within a key · late inputs go in the next call · nothing is held or rejected",
        "inputs that wait together are sorted as they arrive; one that arrives after a higher day was sent rides the next call",
        "Order.by: a key's waiting inputs are kept sorted by rank as they arrive, so each call carries them in day "
        "order. Day 4 arrives after days 6 and 7 were sent; it is not held or rejected, it goes in the next call and "
        "the store places it.",
    )
    sc.add(sc.text(28, 88, "KEY", "tiny"), sc.text(LANE_X, 88, "LANE · KEPT IN RANK ORDER", "tiny"),
           sc.text(STORE_X + 30, 88, "STORE", "tiny", anchor="middle"), sc.text(LOG_X + 14, 88, "STORE LOG", "tiny"))
    sc.add(sc.panel(STORE_X, 120, 60, 150), sc.text(STORE_X + 30, 199, "write", "tiny", anchor="middle"),
           sc.panel(LOG_X, 120, sc.w - 28 - LOG_X, 150, "#070b16", "#1e293b"))
    sc.add(sc.text(28, Y - 4, "key = b", "key"), sc.text(28, Y + 15, "chips show each input's day", "mode", PINK),
           sc.bracket(LANE_X, Y, DOCK_X - LANE_X - 14, "front →"), sc.rail(DOCK_X, Y, STORE_X, PINK))

    # Calls: (load, depart, arrive, leave, back, days in rank order)
    calls = [(0.3, 0.6, 1.8, 2.2, 3.4, [5]), (3.4, 3.8, 5.0, 5.4, 6.6, [3, 6, 7]), (6.6, 7.0, 8.2, 8.6, 9.8, [4, 8, 9])]
    track = [(0.0, 0, 0)]
    for _, d, a, l, b, _ in calls:
        track += [(d, 0, 0), (a, CROSS, 0), (l, CROSS, 0), (b, 0, 0)]
    sc.add(f'<g style="{sc.path(track)}">', sc.shuttle(SHUTTLE_W, PINK, DOCK_X, Y), "</g>")
    sc.add(*(sc.pulse(STORE_X, Y, PINK, a) for _, _, a, _, _, _ in calls))

    # Each input: day, arrival, its lane slot over time, and the call that carries it
    inputs = [
        (5, 0.0, [(0.0, 0)], 0),
        (7, 0.9, [(0.9, 0), (1.5, 1), (2.3, 2)], 1),
        (6, 1.5, [(1.5, 0), (2.3, 1)], 1),
        (3, 2.3, [(2.3, 0)], 1),
        (4, 4.2, [(4.2, 0)], 2),
        (9, 4.8, [(4.8, 1), (5.6, 2)], 2),
        (8, 5.6, [(5.6, 1)], 2),
    ]
    for day, at, slots, call in inputs:
        load, depart, arrive, leave, _, days = calls[call]
        sx = seat_x(days.index(day), len(days))
        color = AMBER if day == 4 else PINK
        pts = [(at - 0.01 if at else 0.0, slot_x(slots[0][1]), Y - 42, 0), (at + 0.3, slot_x(slots[0][1]), Y)]
        for (t, s), (_, prev) in zip(slots[1:], slots):
            pts += [(t, slot_x(prev), Y), (t + 0.3, slot_x(s), Y)]
        pts += [(load, slot_x(slots[-1][1]), Y), (load + 0.3, sx, Y), (depart, sx, Y),
                (arrive, sx + CROSS, Y), (leave, sx + CROSS, Y, 0)]
        sc.add(sc.chip(day, color, sc.path(pts)))

    sc.add(sc.text(slot_x(0), Y - 36, "day 4 is late: days 6 and 7 already left", "hud", AMBER, "middle",
                   sc.shown([(4.3, 6.7)])),
           sc.text(slot_x(1), Y - 36, "6 sorts ahead of 7", "hud", None, "middle", sc.shown([(1.6, 2.3)])),
           sc.text(slot_x(1), Y - 36, "3 sorts ahead of both", "hud", None, "middle", sc.shown([(2.4, 3.4)])))
    # each call's label rides with the shuttle
    tags =[sc.text(DOCK_X + SHUTTLE_W / 2, Y - 24, "call [" + " ".join(map(str, days)) + "]", "tag", PINK, "middle",
                    sc.shown([(load, leave)])) for load, _, _, leave, _, days in calls]
    sc.add(f'<g style="{sc.path(track)}">', *tags, "</g>")

    log = [(1.8, "b ← [5]", PINK), (5.0, "b ← [3 6 7]", PINK), (8.2, "b ← [4 8 9]", PINK),
           (8.4, "  ↳ late 4: store places it", AMBER)]
    for i, (t, s, color) in enumerate(log):
        sc.add(f'<text x="{LOG_X + 14}" y="{146 + i * 24}" class="log" style="{sc.shown([(t, T - 0.2)], 0.2)}">'
               + (f'<tspan fill="{DIM}">{t:04.1f}s</tspan>  ' if not s.startswith(" ") else "")
               + f'<tspan fill="{color}">{s}</tspan></text>')
    return sc
