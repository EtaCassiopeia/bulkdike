"""Generates docs/assets/ferry.svg, the animation in the README and on the docs home page.

Run from the repository root: python3 scripts/ferry-svg.py
"""

T = 12.0  # loop length, seconds
H = 380
LANE_X = 262          # left edge of a key's lane (its waiting inputs)
DOCK_X = 400          # shuttle's left edge when docked
CROSS = 270           # distance the shuttle travels
SHUTTLE_W, SHUTTLE_H = 122, 30
STORE_X = DOCK_X + CROSS + SHUTTLE_W + 22
LOG_X = STORE_X + 84
W = LOG_X + 236
MONO = "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace"

css: list[str] = []
body: list[str] = []
uid = 0


def pct(t: float) -> str:
    return f"{max(0.0, min(100.0, t / T * 100)):.3f}%"


def anim(frames: list[tuple[float, str]], ease: str = "linear") -> str:
    global uid
    uid += 1
    name = f"a{uid}"
    frames = sorted(frames, key=lambda f: f[0])
    css.append(f"@keyframes {name}{{{''.join(f'{pct(t)}{{{d}}}' for t, d in frames)}}}")
    return f"animation:{name} {T}s {ease} infinite;"


def shown(windows: list[tuple[float, float]], fade: float = 0.15) -> str:
    frames = [(0.0, "opacity:0"), (T, "opacity:0")]
    for on, off in windows:
        frames += [(on, "opacity:0"), (on + fade, "opacity:1"), (off - fade, "opacity:1"), (off, "opacity:0")]
    return anim(frames)


def chip(x: float, y: float, n: int, color: str, style: str) -> str:
    return (
        f'<g style="{style}"><rect x="{x - 9}" y="{y - 9}" width="18" height="18" rx="4" fill="{color}" '
        f'fill-opacity="0.18" stroke="{color}" stroke-width="1.5" filter="url(#glow)"/>'
        f'<text x="{x}" y="{y + 4}" class="chip" text-anchor="middle" fill="{color}">{n}</text></g>'
    )


def lane(y: int, key: str, mode: str, color: str, trips, batches, arrivals):
    """trips: (load, depart, arrive, leave_store, back); batches: input numbers per trip;
    arrivals: (number, arrive_at, board_at, slot in the lane)."""
    body.append(f'<text x="28" y="{y - 4}" class="key">key = {key}</text>')
    body.append(f'<text x="28" y="{y + 15}" class="mode" fill="{color}">{mode}</text>')
    # the lane: a bracketed buffer of waiting inputs
    lx, lw = LANE_X, DOCK_X - LANE_X - 14
    body.append(
        f'<path d="M{lx + 8} {y - 16} h-8 v32 h8 M{lx + lw - 8} {y - 16} h8 v32 h-8" class="bracket"/>'
        f'<text x="{lx + lw / 2}" y="{y + 32}" class="tiny" text-anchor="middle">lane</text>'
    )
    # the track to the store, with flowing dashes
    body.append(f'<line x1="{DOCK_X}" y1="{y}" x2="{STORE_X}" y2="{y}" class="rail"/>')
    body.append(f'<line x1="{DOCK_X}" y1="{y}" x2="{STORE_X}" y2="{y}" class="flow" stroke="{color}"/>')
    # waiting inputs
    for n, arrive, board, slot in arrivals:
        x = DOCK_X - 26 - slot * 22
        style = anim([
            (0.0, "opacity:0;transform:translateX(-30px)"),
            (arrive, "opacity:0;transform:translateX(-30px)"),
            (arrive + 0.3, "opacity:1;transform:translateX(0px)"),
            (board - 0.1, "opacity:1;transform:translateX(0px)"),
            (board, "opacity:0;transform:translateX(20px)"),
            (T, "opacity:0;transform:translateX(20px)"),
        ])
        body.append(chip(x, y, n, color, style))
    # the shuttle, its passengers and its batch label
    track = [(0.0, "transform:translateX(0px)"), (T, "transform:translateX(0px)")]
    for _, depart, arrive, leave, back in trips:
        track += [
            (depart, "transform:translateX(0px)"),
            (arrive, f"transform:translateX({CROSS}px)"),
            (leave, f"transform:translateX({CROSS}px)"),
            (back, "transform:translateX(0px)"),
        ]
    body.append(f'<g style="{anim(track, "ease-in-out")}">')
    body.append(
        f'<rect x="{DOCK_X}" y="{y - SHUTTLE_H / 2}" width="{SHUTTLE_W}" height="{SHUTTLE_H}" rx="15" '
        f'class="shuttle" stroke="{color}" filter="url(#glow)"/>'
    )
    for (load, _, _, leave, _), inputs in zip(trips, batches):
        start = DOCK_X + SHUTTLE_W / 2 - (len(inputs) - 1) * 10
        for i, n in enumerate(inputs):
            body.append(chip(start + i * 20, y, n, color, shown([(load, leave)])))
        label = "call [1 input]" if len(inputs) == 1 else f"call [batch of {len(inputs)}]"
        body.append(
            f'<text x="{DOCK_X + SHUTTLE_W / 2}" y="{y - 24}" class="tag" text-anchor="middle" fill="{color}" '
            f'style="{shown([(load, leave)])}">{label}</text>'
        )
    body.append("</g>")
    # a pulse where each call lands
    for _, _, arrive, _, _ in trips:
        style = anim([
            (0.0, "opacity:0;transform:scale(0.4)"),
            (arrive, "opacity:0;transform:scale(0.4)"),
            (arrive + 0.05, "opacity:0.9;transform:scale(0.4)"),
            (arrive + 0.7, "opacity:0;transform:scale(2.2)"),
            (T, "opacity:0;transform:scale(2.2)"),
        ])
        body.append(
            f'<circle cx="{STORE_X}" cy="{y}" r="12" fill="none" stroke="{color}" stroke-width="2" '
            f'class="pulse" style="{style}"/>'
        )


QUIET, BUSY = "#22d3ee", "#e879f9"

lane(
    y=150, key="a", mode="idle → sent at once, no queue", color=QUIET,
    trips=[(1.0, 1.3, 2.8, 3.3, 4.8), (7.0, 7.3, 8.8, 9.3, 10.8)],
    batches=[[1], [2]],
    arrivals=[(1, 0.6, 1.0, 0), (2, 6.6, 7.0, 0)],
)
lane(
    y=268, key="b", mode="busy → batched while in flight", color=BUSY,
    trips=[(0.0, 0.5, 2.0, 2.5, 4.0), (4.0, 4.5, 6.0, 6.5, 8.0), (8.0, 8.5, 10.0, 10.5, 12.0)],
    batches=[[1], [2, 3, 4], [5, 6, 7, 8, 9]],
    arrivals=[
        (1, 11.4, 12.0, 0),
        (2, 0.9, 4.0, 0), (3, 1.7, 4.0, 1), (4, 2.8, 4.0, 2),
        (5, 4.7, 8.0, 0), (6, 5.3, 8.0, 1), (7, 6.0, 8.0, 2), (8, 6.7, 8.0, 3), (9, 7.4, 8.0, 4),
    ],
)

# The store log: one line per call, in the order calls land.
log = [
    (2.0, "b", "[1]", BUSY),
    (2.8, "a", "[1]", QUIET),
    (6.0, "b", "[2 3 4]", BUSY),
    (8.8, "a", "[2]", QUIET),
    (10.0, "b", "[5 6 7 8 9]", BUSY),
]
for i, (t, key, inputs, color) in enumerate(log):
    body.append(
        f'<text x="{LOG_X + 14}" y="{128 + i * 24}" class="log" style="{shown([(t, 11.8)], 0.2)}">'
        f'<tspan fill="#64748b">{t:04.1f}s</tspan>  <tspan fill="{color}">{key} ← {inputs}</tspan></text>'
    )

svg = f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" role="img"
  aria-label="Ferry.perKey: an idle key's input is sent at once; a busy key's inputs gather in its lane while its call is in flight and travel together in its next call, in order, one call per key at a time.">
<defs>
  <filter id="glow" x="-50%" y="-50%" width="200%" height="200%">
    <feGaussianBlur stdDeviation="2.5" result="blur"/>
    <feMerge><feMergeNode in="blur"/><feMergeNode in="SourceGraphic"/></feMerge>
  </filter>
  <pattern id="grid" width="24" height="24" patternUnits="userSpaceOnUse">
    <path d="M24 0H0V24" fill="none" stroke="#1e293b" stroke-width="1"/>
  </pattern>
  <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="#070b16"/><stop offset="1" stop-color="#0b1222"/>
  </linearGradient>
  <linearGradient id="store" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#1e293b"/><stop offset="1" stop-color="#0f172a"/>
  </linearGradient>
</defs>
<style>
  text {{ font-family: {MONO}; }}
  .title {{ font-size: 15px; font-weight: 700; fill: #e2e8f0; letter-spacing: .5px; }}
  .hud {{ font-size: 12px; fill: #64748b; }}
  .key {{ font-size: 15px; font-weight: 700; fill: #e2e8f0; }}
  .mode {{ font-size: 12px; }}
  .tiny {{ font-size: 10px; fill: #475569; letter-spacing: 1px; }}
  .tag {{ font-size: 12px; font-weight: 700; }}
  .chip {{ font-size: 10px; font-weight: 700; }}
  .log {{ font-size: 13px; }}
  .bracket {{ fill: none; stroke: #334155; stroke-width: 1.5; }}
  .rail {{ stroke: #1e293b; stroke-width: 3; }}
  .flow {{ stroke-width: 1.5; stroke-dasharray: 2 10; stroke-opacity: .55; animation: flow 1s linear infinite; }}
  .shuttle {{ fill: #0b1222; stroke-width: 1.6; }}
  .pulse {{ transform-box: fill-box; transform-origin: center; }}
  @keyframes flow {{ to {{ stroke-dashoffset: -12; }} }}
  {"".join(css)}
</style>
<rect width="{W}" height="{H}" rx="14" fill="url(#bg)"/>
<rect width="{W}" height="{H}" rx="14" fill="url(#grid)" opacity=".55"/>
<rect x=".75" y=".75" width="{W - 1.5}" height="{H - 1.5}" rx="14" fill="none" stroke="#1e3a5f"/>
<text x="28" y="40" class="title">Ferry.perKey</text>
<text x="{W - 28}" y="40" class="hud" text-anchor="end">≤ 1 call in flight per key · batch size follows load · no timer</text>
<line x1="28" y1="58" x2="{W - 28}" y2="58" stroke="#1e293b"/>
<text x="28" y="88" class="tiny">CALLERS</text>
<text x="{STORE_X + 30}" y="88" class="tiny" text-anchor="middle">STORE</text>
<text x="{LOG_X + 14}" y="88" class="tiny">STORE LOG</text>
<rect x="{STORE_X}" y="104" width="60" height="{268 - 150 + 92}" rx="10" fill="url(#store)" stroke="#334155"/>
<text x="{STORE_X + 30}" y="{(150 + 268) / 2 + 4}" class="tiny" text-anchor="middle">write</text>
<rect x="{LOG_X}" y="104" width="{W - 28 - LOG_X}" height="{268 - 150 + 92}" rx="10" fill="#070b16" fill-opacity=".7" stroke="#1e293b"/>
{"".join(body)}
<line x1="28" y1="{H - 50}" x2="{W - 28}" y2="{H - 50}" stroke="#1e293b"/>
<text x="28" y="{H - 24}" class="hud">inputs that arrive while a key's call is in flight wait in its lane, in order, and leave together in its next call</text>
</svg>
"""

open("docs/assets/ferry.svg", "w").write(svg)
print(len(svg), "bytes")
