"""Renders the widget at several real sizes into an HTML check page.

Why this exists: the layout is a *function of the tile's height*, and the thing that goes wrong when
it is wrong is that content is clipped - which is invisible in a single screenshot and cannot be
checked at all without a device. Since this project is developed with no device attached, this is how
the row budget gets eyeballed at 70 / 140 / 210 / 280dp before a build is shipped.

**It duplicates the layout arithmetic from `WidgetSizing.layoutFor`.** Python cannot call Kotlin, so
that duplication is unavoidable here - but it means this file must be updated in lockstep with
`WidgetSizing`. If the two disagree, this page is lying. It is a verification aid, not shipped code:
it writes to the gitignored `build/` directory.

Run from the project root:  python tools/make_widget_check_page.py
"""

import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WIDTH = 250

ROW_PADDING = 8.0
ROW_TEXT_BASE = 30.0
ROW_SPACING = 4.0
ROW_SPACING_TIGHT = 2.0
ROW_SINGLE_LINE_TEXT = 15.0
HEADER_CONTENT = 18.0
HEADER_GAP = 6.0
OVERFLOW_HINT = 16.0
DEFAULT_ITEM_LIMIT = 4

BG = "#FFFFFBF7"
TITLE = "#FF1B1C1E"
MUTED = "#FF5C5F66"
STATUS = {
    "DUE_SOON": "#FF8A6100",
    "MISSED": "#FFB3261E",
    "LATER_TODAY": "#FF1D5FA8",
    "TAKEN": "#FF1F7A45",
    "SKIPPED": "#FF5A5F68",
}
TINT = {"DUE_SOON": "#1A8A6100", "MISSED": "#1AB3261E"}

# One entry per row, in planner order.
ROWS = [
    ("即将服用", "08:00 · 1 片", "DUE_SOON"),
    ("未服药", "07:00 · 1 片", "MISSED"),
    ("稍后", "12:30 · 1 片", "LATER_TODAY"),
    ("已服用", "22:00 · 1 片", "TAKEN"),
    ("已跳过", "23:00 · 1 片", "SKIPPED"),
]


def padding_for(height):
    if height >= 140:
        return 12
    if height >= 100:
        return 8
    return 4


def layout(height, item_count, item_limit=DEFAULT_ITEM_LIMIT, font_scale=1.0):
    """Port of WidgetSizing.layoutFor."""
    pad = padding_for(height)
    available = height - 2 * pad
    two = ROW_PADDING + ROW_TEXT_BASE * font_scale + ROW_SPACING
    header_block = HEADER_CONTENT + HEADER_GAP
    show_header = int((available - header_block) / two) >= 1
    usable = available - (header_block if show_header else 0.0)

    single = (not show_header) and int(usable / two) < 1
    unit = (ROW_PADDING + ROW_SINGLE_LINE_TEXT * font_scale + ROW_SPACING_TIGHT) if single else two

    rows_no_hint = max(1, int(usable / unit))
    slack = usable - rows_no_hint * unit
    ceiling = min(rows_no_hint, item_limit)
    show_hint = show_header and not single and slack >= OVERFLOW_HINT and item_count > min(rows_no_hint, item_limit)
    reserved_hint = OVERFLOW_HINT if show_hint else 0.0
    fit = max(1, int((usable - reserved_hint) / unit))
    row_limit = min(fit, item_limit)
    return {
        "show_header": show_header,
        "single": single,
        "row_limit": row_limit,
        "show_hint": show_hint,
        "pad": pad,
        "unit": unit,
        "header_block": header_block if show_header else 0.0,
        "reserved_hint": reserved_hint,
        "usable": usable,
    }


def css(argb):
    """Android `#AARRGGBB` -> a CSS colour.

    Not cosmetic: CSS reads an 8-digit hex as `#RRGGBBAA`, so handing it an Android colour silently
    turns the alpha into a blue channel and renders a translucent pink instead. Every colour in this
    file is written the Android way and converted here.
    """
    h = argb.lstrip("#")
    if len(h) == 8:
        a, r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4, 6))
        return f"rgba({r},{g},{b},{a / 255:.4f})"
    return argb


def rr(x, y, w, h, r, color):
    r = max(0, min(r, w / 2, h / 2))
    return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{css(color)}"/>'


def circle(cx, cy, r, color):
    return f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{css(color)}"/>'


def build(height, item_count=4):
    L = layout(height, item_count)
    pad, unit, hint = L["pad"], L["unit"], L["reserved_hint"]
    out = [rr(0, 0, WIDTH, height, 16, BG)]

    y = pad
    if L["show_header"]:
        out.append(circle(pad + 8, y + 8, 5, STATUS["LATER_TODAY"]))
        out.append(rr(pad + 20, y + 5, 46, 6, 3, TITLE))
        out.append(rr(WIDTH - pad - 34, y + 5, 34, 6, 3, MUTED))
        y += L["header_block"]

    rows_top = y
    rows_area = L["usable"] - hint
    slot = rows_area / L["row_limit"]
    for i in range(L["row_limit"]):
        label, detail, prio = ROWS[i % len(ROWS)]
        color = STATUS[prio]
        top = rows_top + i * slot
        band = slot - (ROW_SPACING_TIGHT if L["single"] else ROW_SPACING)
        if prio in TINT:
            out.append(rr(pad, top, WIDTH - 2 * pad, band, 12, TINT[prio]))
        cy = top + band / 2
        if L["single"]:
            out.append(circle(pad + 9, cy, 4, color))
            out.append(rr(pad + 18, cy - 4, 110, 7, 3.5, TITLE))
            out.append(rr(WIDTH - pad - 78, cy - 3, 26, 5, 2.5, MUTED))
            out.append(rr(WIDTH - pad - 46, cy - 3, 40, 5, 2.5, color))
        else:
            out.append(circle(pad + 13, cy, 5, color))
            out.append(circle(pad + 30, cy, 8, color))
            out.append(rr(pad + 44, cy - 8, 92, 6, 3, TITLE))
            out.append(rr(pad + 44, cy + 1, 56, 5, 2.5, MUTED))
            out.append(rr(WIDTH - pad - 44, cy - 3, 38, 5, 2.5, color))

    if L["show_hint"]:
        out.append(rr(pad + 2, rows_top + rows_area + 3, 56, 5, 2.5, MUTED))

    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{WIDTH}" height="{height}" '
            f'viewBox="0 0 {WIDTH} {height}">' + "".join(out) + "</svg>")


CASES = [
    ("4×1（最小）", 70, 4),
    ("4×2（默认，组件落地尺寸）", 140, 4),
    ("4×3", 210, 6),
    ("4×4", 280, 8),
]

cards = "".join(
    f'<div class="card"><div class="lbl">{name}<span class="dim">{h}dp</span></div>'
    f'<div class="art">{build(h, n)}</div></div>'
    for name, h, n in CASES
)

page = f"""<!doctype html><meta charset=utf-8>
<style>
 *{{box-sizing:border-box}}
 body{{margin:0;background:linear-gradient(155deg,#40506b,#191f29 70%);padding:34px 38px 40px;
       font-family:system-ui,'Microsoft YaHei',sans-serif}}
 h1{{color:#f4f6fa;font-size:20px;font-weight:650;margin:0 0 6px;letter-spacing:.2px}}
 p.sub{{color:#a3b3c8;font-size:13px;margin:0 0 24px;line-height:1.6}}
 .key{{display:inline-flex;align-items:center;gap:6px;margin-right:18px}}
 .dot{{width:9px;height:9px;border-radius:50%;display:inline-block}}
 .row{{display:flex;gap:22px;align-items:flex-start;flex-wrap:wrap}}
 .card{{background:#161b23cc;border:1px solid #ffffff14;border-radius:18px;padding:15px 16px 17px;
        box-shadow:0 14px 34px #0009}}
 .lbl{{color:#cfd9e6;font-size:12.5px;font-weight:600;margin-bottom:11px;display:flex;
       justify-content:space-between;gap:14px;align-items:baseline}}
 .dim{{color:#7d8da3;font-weight:400;font-size:11px}}
 .art{{display:inline-block;line-height:0;border-radius:14px;overflow:hidden}}
</style>
<h1>药准时 · 桌面小组件（v1.4.0）</h1>
<p class="sub">
  组件列表里只有一个条目，落地为 4×2，拖动边缘即可放大。
  <span style="color:#6f8098">|</span> 排序与配色：
  <span class="key"><span class="dot" style="background:#8A6100"></span>即将服用（30 分钟内）</span>
  <span class="key"><span class="dot" style="background:#B3261E"></span>未服药</span>
  <span class="key"><span class="dot" style="background:#1D5FA8"></span>今日稍后</span>
  <span class="key"><span class="dot" style="background:#1F7A45"></span>已服用</span>
  <span class="key"><span class="dot" style="background:#5A5F68"></span>已跳过</span>
</p>
<div class="row">{cards}</div>
"""

out = os.path.join(ROOT, "build", "widget_v140.html")
with open(out, "w", encoding="utf-8") as fh:
    fh.write(page)
print(f"wrote {out}")
