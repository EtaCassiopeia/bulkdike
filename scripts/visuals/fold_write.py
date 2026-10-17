"""FoldWrite: read once, apply in memory, write only if the store still holds what was read; retry on conflict."""

from hud import AMBER, CYAN, DIM, FAINT, GREEN, PINK, RED, Scene

T = 11.5
STAGE_Y = 150
STAGES = {"READ": 222, "APPLY": 362, "WRITE": 502}   # left edges, 112 wide
MEM_Y, OUT_Y = 236, 300
STORE_X, STORE_W = 700, 196

# When each stage is active, over the two rounds
ACTIVE = {"READ": [(1.0, 2.0), (5.2, 6.2)], "APPLY": [(2.0, 3.4), (6.2, 7.4)], "WRITE": [(3.6, 4.5), (7.6, 8.5)]}


def build() -> Scene:
    sc = Scene(
        STORE_X + STORE_W + 28, 420, T,
        "FoldWrite",
        "read once · apply in memory · write if unchanged · retry the round trip",
        "a conflict retries the whole round trip, which re-reads what landed; an invalid input fails only itself",
        "FoldWrite: the batch call reads the key's state once, applies the inputs to it in memory, and writes the "
        "result only if the store still holds the version it read. Another writer gets in first, so the write is "
        "refused; the round trip is retried, re-reads the new version and succeeds. Input 3 is invalid and is "
        "rejected on its own, without failing the others.",
    )
    sc.add(sc.text(28, 88, "BATCH", "tiny"), sc.text(222, 88, "ONE ROUND TRIP", "tiny"),
           sc.text(STORE_X, 88, "STORE · CONDITIONAL WRITES", "tiny"))

    # The batch, and each input's outcome at the end
    for i, n in enumerate([1, 2, 3]):
        y = 140 + i * 50
        sc.add(sc.chip(n, PINK, sc.path([(0.2, 20, y, 0), (0.6, 60, y)])))
        ok = n != 3
        label = f"✓ at {3 + i}" if ok else "✗ amount = 0"
        sc.add(sc.text(80, y + 4, label, "tag", GREEN if ok else RED, style=sc.shown([(8.9 + 0.15 * i, T - 0.2)])))

    # The stages, each lit while active
    for name, x in STAGES.items():
        sc.add(f'<rect x="{x}" y="{STAGE_Y - 22}" width="112" height="44" rx="8" fill="#070b16" stroke="#334155"/>',
               f'<rect x="{x}" y="{STAGE_Y - 22}" width="112" height="44" rx="8" fill="{CYAN}" fill-opacity=".08" '
               f'stroke="{CYAN}" filter="url(#glow)" style="{sc.shown(ACTIVE[name], 0.12)}"/>',
               sc.text(x + 56, STAGE_Y + 5, name, "big", anchor="middle"))
    sc.add(f'<path d="M{STAGES["READ"] + 112} {STAGE_Y} H{STAGES["APPLY"]} M{STAGES["APPLY"] + 112} {STAGE_Y} '
           f'H{STAGES["WRITE"]}" stroke="#334155" stroke-width="1.5"/>')
    sc.add(sc.text(STAGES["READ"] + 56, STAGE_Y + 42, "before = v7", "hud", CYAN, "middle", sc.shown([(1.5, 4.8)])),
           sc.text(STAGES["READ"] + 56, STAGE_Y + 42, "before = v8", "hud", CYAN, "middle", sc.shown([(5.7, T - 0.2)])),
           sc.text(STAGES["WRITE"] + 56, STAGE_Y + 42, "if version = v7", "hud", None, "middle", sc.shown([(3.6, 4.8)])),
           sc.text(STAGES["WRITE"] + 56, STAGE_Y + 42, "if version = v8", "hud", None, "middle", sc.shown([(7.6, T - 0.2)])))

    # The state in memory: what was read, then the inputs applied by step
    sc.add(sc.text(222, MEM_Y + 5, "in memory", "tiny"))
    mem_x = 330
    rounds = [(["x", "y"], 1.6, 4.8, 2.4), (["x", "y", "z"], 5.8, T - 0.2, 6.6)]
    for base, on, off, first in rounds:
        for i, s in enumerate(base):
            color = AMBER if s == "z" else FAINT
            sc.add(sc.chip(s, color, sc.shown([(on, off)]), mem_x + i * 26, MEM_Y))
        for j, n in enumerate([1, 2]):
            sc.add(sc.chip(n, PINK, sc.shown([(first + 0.4 * j, off)]), mem_x + (len(base) + j) * 26, MEM_Y))
        sc.add(sc.text(mem_x + (len(base) + 2) * 26 + 6, MEM_Y + 4, "3 ✗ rejected by step", "hud", RED,
                       style=sc.shown([(first + 0.8, off)])))

    # The store: its version and its log
    sc.add(sc.panel(STORE_X, 116, STORE_W, 214))
    for v, on, off in [("v7", 0.0, 3.0), ("v8", 3.0, 8.3), ("v9", 8.3, T)]:
        sc.add(sc.text(STORE_X + STORE_W / 2, 152, f"version {v}", "big", None, "middle", sc.shown([(on, off)], 0.1)))
    log = [("x", FAINT, 0.0), ("y", FAINT, 0.0), ("z", AMBER, 3.0), ("1", PINK, 8.3), ("2", PINK, 8.4)]
    for i, (s, color, at) in enumerate(log):
        sc.add(sc.chip(s, color, sc.shown([(at, T)]) if at else "", STORE_X + 28 + i * 28, 200))
    sc.add(sc.text(STORE_X + 14, 236, "log", "tiny"))

    # Another writer lands first
    sc.add(sc.chip("z", AMBER, sc.path([(2.2, STORE_X + 84, 60, 0), (2.9, STORE_X + 84, 112), (3.1, STORE_X + 84, 120, 0)])),
           sc.text(STORE_X + STORE_W / 2, 284, "z: another writer, first", "hud", AMBER, "middle", sc.shown([(2.8, 5.0)])),
           sc.pulse(STORE_X + 84, 152, AMBER, 3.0, 20))

    # Reads and writes travel between the round trip and the store
    def trip(t0, t1, x0, x1, color, label):
        return sc.chip(label, color, sc.path([(t0, x0, STAGE_Y, 0), (t0 + 0.15, x0, STAGE_Y), (t1 - 0.15, x1, STAGE_Y),
                                              (t1, x1, STAGE_Y, 0)]), w=34)
    sc.add(trip(1.0, 1.6, STORE_X, STAGES["READ"] + 112, CYAN, "v7"),
           trip(5.2, 5.8, STORE_X, STAGES["READ"] + 112, CYAN, "v8"),
           trip(3.7, 4.3, STAGES["WRITE"] + 112, STORE_X, PINK, "1 2"),
           trip(7.7, 8.3, STAGES["WRITE"] + 112, STORE_X, PINK, "1 2"))
    sc.add(sc.text(STORE_X - 12, STAGE_Y - 30, "✗ refused: store is at v8", "tag", RED, "end", sc.shown([(4.3, 5.2)])),
           sc.text(STORE_X - 12, STAGE_Y - 30, "✓ written: v8 → v9", "tag", GREEN, "end", sc.shown([(8.3, T - 0.2)])),
           sc.pulse(STORE_X, STAGE_Y, RED, 4.3, 16), sc.pulse(STORE_X, STAGE_Y, GREEN, 8.3, 16))
    sc.add(sc.text(STAGES["READ"], OUT_Y + 4, "retry: back off, then repeat the whole round trip", "tag", AMBER,
                   style=sc.shown([(4.6, 5.6)])),
           sc.text(STAGES["READ"], OUT_Y + 4, "one outcome per input: a bad input fails only itself", "hud", DIM,
                   style=sc.shown([(8.8, T - 0.2)])))
    return sc
