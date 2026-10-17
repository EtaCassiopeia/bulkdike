"""Running on a fleet: the fence refuses a stale writer, and deduplication answers a retry after a lost response."""

from hud import AMBER, BLUE, CYAN, GREEN, PINK, RED, TEXT, Scene

T = 14.0
CLIENT_X, CY = 74, 225
PROC_X, PROC_W = 280, 250
ROWS = {"P1": 150, "P2": 300}
STORE_X, STORE_W = 740, 170
BANNER_Y = 386


def mid(p: str) -> float:
    return PROC_X + PROC_W / 2


def build() -> Scene:
    sc = Scene(
        STORE_X + STORE_W + 28, 460, T,
        "Ferry on a fleet",
        "routing keeps one writer per key · the write keeps it correct when routing does not",
        "the fence (write if the version is unchanged) stops a stale writer; deduplication by id makes retries safe",
        "Two processes run Ferry in front of one store. Normally key k is routed to P1. During a handover both P1 and "
        "P2 write k: P1's conditional write lands first, P2's is refused and retried after re-reading. Later a "
        "response is lost; the client retries on P1, which finds the input already stored and answers without writing "
        "it again.",
    )
    sc.add(sc.text(28, 88, "CLIENTS", "tiny"), sc.text(PROC_X, 88, "PROCESSES · Ferry.perKey", "tiny"),
           sc.text(STORE_X, 88, "STORE", "tiny"))
    sc.add(f'<circle cx="{CLIENT_X}" cy="{CY}" r="22" fill="#070b16" stroke="#334155"/>',
           sc.text(CLIENT_X, CY + 44, "client", "tiny", anchor="middle"))
    for name, y in ROWS.items():
        sc.add(sc.panel(PROC_X, y - 36, PROC_W, 72), sc.text(PROC_X + 14, y - 16, name, "big"),
               sc.rail(PROC_X + PROC_W, y, STORE_X, BLUE),
               f'<path d="M{CLIENT_X + 22} {CY} L{PROC_X} {y}" stroke="#1e293b" stroke-width="2" fill="none"/>')
    sc.add(sc.panel(STORE_X, 110, STORE_W, 230))
    for v, on, off in [("v4", 0, 1.8), ("v5", 1.8, 5.8), ("v6", 5.8, 7.9), ("v7", 7.9, 10.5), ("v8", 10.5, T)]:
        sc.add(sc.text(STORE_X + STORE_W / 2, 146, f"version {v}", "big", None, "middle", sc.shown([(on, off)], 0.1)))
    sc.add(sc.text(STORE_X + 14, 186, "log, after 4 earlier", "tiny"))
    for i, (s, at) in enumerate([("a", 1.8), ("b", 5.8), ("c", 7.9), ("d", 10.5)]):
        sc.add(sc.chip(s, PINK, sc.shown([(at, T)]), STORE_X + 30 + i * 30, 214))

    # Phase banners
    for text, on, off in [("1 · normal routing: k goes to P1", 0.0, 4.0),
                          ("2 · handover: P1 and P2 both write k", 4.0, 9.0),
                          ("3 · lost response: the client retries on P1", 9.0, T)]:
        sc.add(sc.text(sc.w / 2, BANNER_Y, text, "big", AMBER, "middle", sc.shown([(on, off)], 0.2)))

    def send(label, color, pts):
        sc.add(sc.chip(label, color, sc.path(pts), w=22 if len(label) == 1 else 30))

    def note(p, text, on, off, color=TEXT):
        sc.add(sc.text(mid(p), ROWS[p] + 22, text, "hud", color, "middle", sc.shown([(on, off)])))

    out = PROC_X + PROC_W
    # 1. normal routing
    send("a", PINK, [(0.2, CLIENT_X, CY, 0), (0.4, CLIENT_X, CY), (1.0, mid("P1"), 150), (1.2, out, 150),
                     (1.8, STORE_X, 150), (1.95, STORE_X, 150, 0)])
    note("P1", "write if v4", 1.0, 2.0)
    sc.add(sc.pulse(STORE_X, 150, GREEN, 1.8, 16))
    send("✓", GREEN, [(2.0, STORE_X, 150, 0), (2.1, STORE_X, 150), (2.6, out, 150), (3.2, CLIENT_X, CY),
                      (3.4, CLIENT_X, CY, 0)])

    # 2. handover: both write k; the fence refuses the stale one
    send("b", PINK, [(4.0, CLIENT_X, CY, 0), (4.2, CLIENT_X, CY), (4.8, mid("P1"), 150), (5.0, out, 150),
                     (5.8, STORE_X, 150), (5.95, STORE_X, 150, 0)])
    note("P1", "write if v5", 4.8, 5.9)
    sc.add(sc.pulse(STORE_X, 150, GREEN, 5.8, 16))
    send("c", PINK, [(4.2, CLIENT_X, CY, 0), (4.4, CLIENT_X, CY), (5.0, mid("P2"), 300), (5.2, out, 300),
                     (6.2, STORE_X, 300), (6.6, out + 40, 300), (6.8, mid("P2"), 300), (7.3, mid("P2"), 300),
                     (7.45, out, 300), (7.9, STORE_X, 300), (8.05, STORE_X, 300, 0)])
    note("P2", "write if v5", 5.0, 6.2)
    sc.add(sc.pulse(STORE_X, 300, RED, 6.2, 16),
           sc.text(STORE_X - 14, 286, "✗ refused: store is at v6", "tag", RED, "end", sc.shown([(6.2, 7.2)])))
    send("v6", CYAN, [(6.6, STORE_X, 300, 0), (6.7, STORE_X, 300), (7.1, out, 300), (7.2, out, 300, 0)])
    note("P2", "re-read v6, write if v6", 6.8, 8.0, AMBER)
    sc.add(sc.pulse(STORE_X, 300, GREEN, 7.9, 16))
    send("✓", GREEN, [(6.0, STORE_X, 150, 0), (6.1, STORE_X, 150), (6.5, out, 150), (7.0, CLIENT_X, CY),
                      (7.2, CLIENT_X, CY, 0)])
    send("✓", GREEN, [(8.1, STORE_X, 300, 0), (8.2, STORE_X, 300), (8.6, out, 300), (9.0, CLIENT_X, CY),
                      (9.2, CLIENT_X, CY, 0)])

    # 3. a lost response, and a retry that is answered from what was stored
    send("d", PINK, [(9.2, CLIENT_X, CY, 0), (9.4, CLIENT_X, CY), (9.9, mid("P2"), 300), (10.05, out, 300),
                     (10.5, STORE_X, 300), (10.65, STORE_X, 300, 0)])
    note("P2", "write if v7", 9.9, 10.6)
    sc.add(sc.pulse(STORE_X, 300, GREEN, 10.5, 16))
    send("✓", GREEN, [(10.7, STORE_X, 300, 0), (10.8, STORE_X, 300), (11.1, out, 300), (11.3, mid("P2"), 300),
                      (11.6, 200, 262), (11.75, 190, 256, 0)])
    sc.add(sc.text(190, 280, "✗ response lost", "tag", RED, "middle", sc.shown([(11.55, 12.6)])))
    send("d", PINK, [(11.9, CLIENT_X, CY, 0), (12.0, CLIENT_X, CY), (12.5, mid("P1"), 150), (13.0, mid("P1"), 150),
                     (13.2, mid("P1"), 150, 0)])
    send("v8", CYAN, [(12.5, STORE_X, 150, 0), (12.6, STORE_X, 150), (13.0, out, 150), (13.1, out, 150, 0)])
    note("P1", "d is stored: answer, no write", 13.0, 13.95, GREEN)
    send("✓", GREEN, [(13.1, mid("P1"), 150, 0), (13.2, mid("P1"), 150), (13.7, CLIENT_X, CY), (13.9, CLIENT_X, CY, 0)])
    sc.add(sc.text(STORE_X + STORE_W / 2, 266, "d applied once", "tag", GREEN, "middle", sc.shown([(12.9, 13.95)])))
    return sc
