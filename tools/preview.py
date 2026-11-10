"""Renders screenshot previews of IP Atlas (tools/out/*.png) from the app's own fonts, icons, colors and map data.

The layout numbers mirror the Compose code in app/src/main/java/app/ipatlas/ui (dp -> CSS px), and the charts
are drawn with tools/chart.py, the Python twin of ui/ChartMap.kt. Sample data stands in for live lookups.

Run with: python tools/preview.py   (needs playwright with Chromium, fonttools, and the chart.py requirements)
"""
import html
import math
import os
import re
import sys

from fontTools.ttLib import TTFont

sys.path.insert(0, os.path.dirname(__file__))
from chart import Geo, Region, region_dots, world_dots, ee_forward, grid_step  # noqa: E402

ROOT = os.path.join(os.path.dirname(__file__), "..")
RES = os.path.join(ROOT, "app", "src", "main", "res", "font")
OUT = os.path.join(os.path.dirname(__file__), "out")
os.makedirs(OUT, exist_ok=True)

W, H = 384, 832          # Galaxy S22+ screen in dp
STATUS, NAV = 28, 16     # status bar and gesture bar insets
SCALE = 1080 / W

NIGHT = dict(dark=True, bg="#0B1A2A", chart="#0F2438", raised="#15304A", rule="#213E5A", ink="#E6EDF2",
             ink2="#9FB2C4", ink3="#6B8197", land="#CDBB86", border="#9AAABB", graticule="#26476A",
             flare="#F0559E", depth="#6CB0E0")
DAY = dict(dark=False, bg="#F5F8FA", chart="#DCEAF3", raised="#FFFFFF", rule="#D5E0E8", ink="#0F2740",
           ink2="#4B6177", ink3="#7D90A3", land="#9C8240", border="#7D7158", graticule="#B7CDDC",
           flare="#C0177F", depth="#1F6FA8")

# ---- Icons: the same path strings the app draws (parsed from Icons.kt).
ICONS = {}
with open(os.path.join(ROOT, "app", "src", "main", "java", "app", "ipatlas", "ui", "Icons.kt"), encoding="utf-8") as fh:
    for m in re.finditer(r'^\s+(\w+)\("([^"]+)"(, filled = true)?\)', fh.read(), re.M):
        ICONS[m.group(1)] = (m.group(2), bool(m.group(3)))


def icon(name, color, size=22, stroke=1.75):
    d, filled = ICONS[name]
    paint = f'fill="{color}"' if filled else f'fill="none" stroke="{color}" stroke-width="{stroke}" stroke-linecap="round" stroke-linejoin="round"'
    return f'<svg width="{size}" height="{size}" viewBox="0 0 24 24" style="flex:none;display:block"><path d="{d}" {paint}/></svg>'


# ---- Text widths for the map labels, from the real fonts.
_fonts = {}


def text_w(text, font, size):
    if font not in _fonts:
        f = TTFont(os.path.join(RES, font + ".ttf"))
        _fonts[font] = (f.getBestCmap(), f["hmtx"].metrics, f["head"].unitsPerEm)
    cmap, hmtx, upm = _fonts[font]
    total = 0
    for ch in text:
        g = cmap.get(ord(ch))
        total += hmtx[g][0] if g else upm * 0.5
    return total * size / upm


def esc(s):
    return html.escape(s, quote=True)


GEO = Geo()


# ---- Charts (mirror ui/ChartMap.kt)

def world_svg(p, w, h, top, bottom, side=12, spacing=3.2, pin=None, scan=None):
    mw, mh = w - 2 * side, h - top - bottom
    dots, project = world_dots(GEO, mw, mh, spacing)
    out = [f'<svg width="{w}" height="{h}" style="position:absolute;left:0;top:0;display:block">',
           f'<rect width="{w}" height="{h}" fill="{p["chart"]}"/>']
    hair = 0.8

    def pr(lat, lon):
        x, y = project(lat, lon)
        return x + side, y + top

    lines = []
    for lon in range(-150, 151, 30):
        lines.append([pr(84 - 142 * i / 71, lon) for i in range(72)])
    for lat in (-30, 0, 30, 60):
        lines.append([pr(lat, -180 + i * 5) for i in range(73)])
    outline = [pr(84 - 142 * i / 72, 180) for i in range(73)] + [pr(-58 + 142 * i / 72, -180) for i in range(73)]
    for ln in lines + [outline]:
        out.append('<polyline fill="none" stroke="%s" stroke-width="%s" points="%s"/>' % (p["graticule"], hair, " ".join(f"{x:.1f},{y:.1f}" for x, y in ln)))
    a = 0.6 if p["dark"] else 0.8
    r = spacing * 0.5 / 2
    out.append(f'<g fill="{p["land"]}" fill-opacity="{a}">' + "".join(f'<circle cx="{x + side:.1f}" cy="{y + top:.1f}" r="{r:.2f}"/>' for x, y in dots) + "</g>")
    if scan is not None:
        sx = side + scan * mw
        out.append(f'<defs><linearGradient id="sg" x1="0" x2="1"><stop offset="0" stop-color="{p["flare"]}" stop-opacity="0"/><stop offset="1" stop-color="{p["flare"]}" stop-opacity="0.14"/></linearGradient></defs>')
        out.append(f'<rect x="{sx - 60}" y="{top}" width="60" height="{mh}" fill="url(#sg)"/><line x1="{sx}" y1="{top}" x2="{sx}" y2="{top + mh}" stroke="{p["flare"]}" stroke-opacity="0.55"/>')
    if pin:
        x, y = pr(*pin)
        out.append(pin_svg(p, x, y, 17, 3.5))
    out.append("</svg>")
    return "".join(out)


def flare_path(x, y, k):
    # Teardrop from the spot up and to the right (Glyph.Flare / flarePath()).
    p2 = (x + 5.44 * k, y - 12.06 * k)
    p1 = (x + 12.06 * k, y - 5.44 * k)
    return f"M{x:.2f} {y:.2f}L{p2[0]:.2f} {p2[1]:.2f}A{5 * k:.2f} {5 * k:.2f} 0 1 1 {p1[0]:.2f} {p1[1]:.2f}Z"


def pin_svg(p, x, y, flare_len, dot_r, label=None, ping=None):
    s = []
    if ping is not None:
        s.append(f'<circle cx="{x}" cy="{y}" r="{6 + 38 * ping}" fill="none" stroke="{p["flare"]}" stroke-opacity="{(1 - ping) * 0.8}" stroke-width="1.5"/>')
    s.append(f'<circle cx="{x}" cy="{y}" r="15" fill="none" stroke="{p["flare"]}" stroke-opacity="0.3" stroke-width="1"/>')
    s.append(f'<path d="{flare_path(x, y, flare_len / 19)}" fill="{p["flare"]}" fill-opacity="0.93"/>')
    s.append(f'<circle cx="{x}" cy="{y}" r="{dot_r + 2}" fill="{p["chart"]}"/><circle cx="{x}" cy="{y}" r="{dot_r}" fill="{p["ink"]}"/>')
    if label:
        tw = text_w(label, "barlow_semibold", 14)
        tx, ty = x + 11, y + 5
        s.append(f'<rect x="{tx - 4}" y="{ty - 1}" width="{tw + 8}" height="{17 + 2}" rx="4" fill="{p["chart"]}" fill-opacity="0.82"/>')
        s.append(f'<text x="{tx}" y="{ty + 13}" font-family="Barlow" font-weight="600" font-size="14" fill="{p["ink"]}">{esc(label)}</text>')
    return "".join(s)


def deg_label(v, is_lat):
    hemi = ("N" if v >= 0 else "S") if is_lat else ("E" if v >= 0 else "W")
    a = abs(v)
    num = str(round(a)) if abs(a - round(a)) < 1e-6 else f"{a:.2f}".rstrip("0").rstrip(".")
    return f"{num}°{hemi}"


def region_svg(p, w, h, lat, lon, km, label, top, bottom, ping=None):
    center_y = top + (h - top - bottom) * 0.5

    class R(Region):
        def project(self, la, lo):
            dlon = (lo - self.lon0 + 540) % 360 - 180
            return self.w / 2 + dlon * self.s * self.c, center_y - (la - self.lat0) * self.s

        def unproject(self, x, y):
            return self.lat0 - (y - center_y) / self.s, self.lon0 + (x - self.w / 2) / (self.s * self.c)

    reg = R(w, h, lat, lon, km)
    spacing = 4.2
    dots = region_dots(GEO, reg, spacing)
    out = [f'<svg width="{w}" height="{h}" style="position:absolute;left:0;top:0;display:block">',
           f'<rect width="{w}" height="{h}" fill="{p["chart"]}"/>']
    # Graticule (RegionFrame.graticule / drawRegionGrid)
    span_lat = h / reg.s
    span_lon = w / (reg.s * reg.c)
    lat_step, lon_step = grid_step(span_lat), grid_step(span_lon)

    def steps(a, b, st):
        v = math.floor(a / st) * st + st
        res = []
        while v < b:
            res.append(v)
            v += st
        return res

    lat_bottom = reg.unproject(0, h)[0]
    lat_top = reg.unproject(0, 0)[0]
    lat_lines = [x for x in steps(lat_bottom, lat_top, lat_step) if -90 < x < 90]
    lon_lines = steps(lon - span_lon / 2, lon + span_lon / 2, lon_step)
    labels = []
    for la in lat_lines:
        y = reg.project(la, lon)[1]
        out.append(f'<line x1="0" y1="{y:.1f}" x2="{w}" y2="{y:.1f}" stroke="{p["graticule"]}" stroke-width="0.8"/>')
        if top + 14 < y < h - bottom - 4:
            t = deg_label(la, True)
            tw = text_w(t, "barlow_italic", 11)
            labels.append(f'<text x="{w - tw - 6:.1f}" y="{y - 1 - 13 + 10.5:.1f}" font-family="Barlow" font-style="italic" font-size="11" fill="{p["ink3"]}">{t}</text>')
    for lo in lon_lines:
        x = reg.project(lat, lo)[0]
        out.append(f'<line x1="{x:.1f}" y1="0" x2="{x:.1f}" y2="{h}" stroke="{p["graticule"]}" stroke-width="0.8"/>')
        t = deg_label(((lo + 180) % 360) - 180, False)
        tw = text_w(t, "barlow_italic", 11)
        if x + tw + 4 < w:
            labels.append(f'<text x="{x + 3:.1f}" y="{h - bottom - 2 - 13 + 10.5:.1f}" font-family="Barlow" font-style="italic" font-size="11" fill="{p["ink3"]}">{t}</text>')
    # State lines (dotted) under country borders (dashed): drawLines()
    def lines(src, opacity, width, dash, halo_w):
        for line in src:
            lats = [pt[1] for pt in line]
            if max(lats) < lat - span_lat or min(lats) > lat + span_lat:
                continue
            pts = [reg.project(la, lo) for lo, la in line]
            if all(not (-60 < x < w + 60 and -60 < y < h + 60) for x, y in pts):
                continue
            ptxt = " ".join(f"{x:.1f},{y:.1f}" for x, y in pts)
            out.append(f'<polyline fill="none" stroke="{p["chart"]}" stroke-opacity="0.85" stroke-width="{halo_w}" stroke-linecap="round" stroke-linejoin="round" points="{ptxt}"/>')
            out.append(f'<polyline fill="none" stroke="{p["ink2"]}" stroke-opacity="{opacity}" stroke-width="{width}" stroke-dasharray="{dash}" points="{ptxt}"/>')

    a = 0.62 if p["dark"] else 0.82
    out.append(f'<g fill="{p["land"]}" fill-opacity="{a}">' + "".join(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="1.05"/>' for x, y in dots) + "</g>")
    lines(GEO.states, 0.7, 1, "4 2.5", 3)
    lines(GEO.borders, 0.9, 1.3, "7 2.5 1.5 2.5", 4)
    out.extend(labels)
    # Place names (drawPlaceNames): cities first, then states/countries in the room left.
    px, py = reg.project(lat, lon)
    placed = [(px - 8, py - 24, px + 22, py + 8)]
    if label:
        tw = text_w(label, "barlow_semibold", 14)
        placed.append((px + 8, py + 2, px + 14 + tw, py + 8 + 17))
    top_lim, bottom_lim = top + 8, h - bottom - 18

    def free(rc, pad=2):
        infl = (rc[0] - pad, rc[1] - pad, rc[2] + pad, rc[3] + pad)
        return not any(not (infl[2] <= r[0] or infl[0] >= r[2] or infl[3] <= r[1] or infl[1] >= r[3]) for r in placed)

    max_cities = 12 if km <= 300 else 10 if km <= 700 else 9 if km <= 1600 else 8
    city_labels = []
    for name, cla, clo, pop, cc in GEO.cities:
        if len(city_labels) >= max_cities:
            break
        x, y = reg.project(cla, clo)
        if x < 6 or x > w - 6 or y < top_lim or y > bottom_lim:
            continue
        if math.hypot(x - px, y - py) < 22:
            continue
        tw = text_w(name, "barlow_medium", 12)
        th = 14
        right_r = (x - 3, y - th / 2, x + 6 + tw, y + th / 2)
        left_r = (x - 6 - tw, y - th / 2, x + 3, y + th / 2)
        fits_right = right_r[2] <= w - 2 and free(right_r)
        fits_left = left_r[0] >= 2 and free(left_r)
        if not fits_right and not fits_left:
            continue
        left = not fits_right
        placed.append(left_r if left else right_r)
        city_labels.append((x, y, name, tw, x - 5 - tw if left else x + 5))
    step = 14
    show_states = km <= 3600
    show_all_countries = km >= 3000

    def region_key(x, y):
        la, lo = reg.unproject(x, y)
        if not (-85 < la < 85):
            return 0
        lo = ((lo + 540) % 360) - 180
        st = GEO.state_at(la, lo) if show_states else 0
        if st:
            return 1000 + st
        c = GEO.country_at(la, lo)
        return c if c and (show_all_countries or GEO.country_codes.get(c) not in GEO.with_states) else 0

    cells = {}
    y = top_lim + step / 2
    while y < bottom_lim:
        x = step / 2
        while x < w:
            key = region_key(x, y)
            if key:
                cells.setdefault(key, []).append((x, y))
            x += step
        y += step
    region_labels = []
    for key, pts in sorted(cells.items(), key=lambda kv: -len(kv[1])):
        if len(region_labels) >= 6 or len(pts) < 4:
            break
        name = GEO.state_names.get(key - 1000) if key > 1000 else GEO.country_names.get(key)
        if not name:
            continue
        text = name.upper()
        tw = text_w(text, "barlow_medium", 11) + 1.6 * len(text)
        th = 13
        mx = sum(pt[0] for pt in pts) / len(pts)
        my = sum(pt[1] for pt in pts) / len(pts)
        cands = ([(mx, my)] if region_key(mx, my) == key else []) + sorted(pts, key=lambda q: (q[0] - mx) ** 2 + (q[1] - my) ** 2)
        for cxy in cands:
            cx = min(max(cxy[0], tw / 2 + 4), w - tw / 2 - 4)
            cy = min(max(cxy[1], top_lim + th / 2), bottom_lim - th / 2)
            rect = (cx - tw / 2, cy - th / 2, cx + tw / 2, cy + th / 2)
            if not free(rect, 3):
                continue
            placed.append(rect)
            region_labels.append((rect[0], rect[1], text))
            break
    for x0, y0, text in region_labels:
        tw = text_w(text, "barlow_medium", 11) + 1.6 * len(text)
        out.append(f'<rect x="{x0 - 3:.1f}" y="{y0 - 1:.1f}" width="{tw + 6:.1f}" height="15" rx="3" fill="{p["chart"]}" fill-opacity="0.72"/>')
        out.append(f'<text x="{x0:.1f}" y="{y0 + 10.5:.1f}" font-family="Barlow" font-weight="500" font-size="11" letter-spacing="1.6" fill="{p["ink2"]}">{esc(text)}</text>')
    for x, y, name, tw, tx in city_labels:
        th = 14
        out.append(f'<rect x="{tx - 2:.1f}" y="{y - th / 2:.1f}" width="{tw + 4:.1f}" height="{th}" rx="3" fill="{p["chart"]}" fill-opacity="0.7"/>')
        out.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="2.2" fill="{p["ink2"]}" stroke="{p["chart"]}" stroke-width="1"/>')
        out.append(f'<text x="{tx:.1f}" y="{y + 4.2:.1f}" font-family="Barlow" font-weight="500" font-size="12" fill="{p["ink2"]}">{esc(name)}</text>')
    # Fades
    if bottom > 0:
        out.append(f'<defs><linearGradient id="fb" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{p["bg"]}" stop-opacity="0"/><stop offset="1" stop-color="{p["bg"]}" stop-opacity="1"/></linearGradient>'
                   f'<linearGradient id="ft" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{p["chart"]}" stop-opacity="0.92"/><stop offset="1" stop-color="{p["chart"]}" stop-opacity="0"/></linearGradient></defs>')
        out.append(f'<rect x="0" y="{h - bottom * 0.9}" width="{w}" height="{bottom * 0.9}" fill="url(#fb)"/>')
        out.append(f'<rect x="0" y="0" width="{w}" height="{top * 1.4}" fill="url(#ft)"/>')
    # Scale bar (drawScaleBar)
    km_px = km / w
    target = w * 0.24 * km_px
    mag = 10 ** math.floor(math.log10(target))
    nice = [v * mag for v in (1, 2, 5, 10) if v * mag <= target][-1]
    ln = nice / km_px
    x0, y = 16, h - bottom + 14
    out.append(f'<text x="{x0}" y="{y - 4 - 13 + 10.5}" font-family="Barlow" font-style="italic" font-size="11" fill="{p["ink2"]}">{int(nice)} km</text>')
    for (x1, y1, x2, y2, sw) in ((x0, y, x0 + ln, y, 1.5), (x0, y - 4, x0, y + 1, 1.5), (x0 + ln / 2, y - 2.5, x0 + ln / 2, y, 1), (x0 + ln, y - 4, x0 + ln, y + 1, 1.5)):
        out.append(f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{p["ink2"]}" stroke-width="{sw}"/>')
    out.append(pin_svg(p, px, py, 24, 4.5, label=label, ping=ping))
    out.append("</svg>")
    return "".join(out)


# ---- Page pieces (mirror ui/Components.kt)

def css(p):
    f = lambda n: "file://" + os.path.abspath(os.path.join(RES, n + ".ttf"))  # noqa: E731
    return f"""
@font-face {{ font-family: Barlow; font-weight: 400; src: url('{f("barlow_regular")}'); }}
@font-face {{ font-family: Barlow; font-weight: 500; src: url('{f("barlow_medium")}'); }}
@font-face {{ font-family: Barlow; font-weight: 600; src: url('{f("barlow_semibold")}'); }}
@font-face {{ font-family: Barlow; font-weight: 700; src: url('{f("barlow_bold")}'); }}
@font-face {{ font-family: Barlow; font-style: italic; font-weight: 400; src: url('{f("barlow_italic")}'); }}
@font-face {{ font-family: Plex; font-weight: 400; src: url('{f("plexmono_regular")}'); }}
@font-face {{ font-family: Plex; font-weight: 500; src: url('{f("plexmono_medium")}'); }}
* {{ box-sizing: border-box; margin: 0; padding: 0; }}
html, body {{ width: {W}px; background: {p["bg"]}; }}
body {{ font-family: Barlow, sans-serif; color: {p["ink"]}; -webkit-font-smoothing: antialiased; }}
.screen {{ position: relative; width: {W}px; min-height: {H}px; background: {p["bg"]}; }}
.row {{ display: flex; align-items: center; }}
.section {{ font: 600 19px/24px Barlow; padding: 28px 0 4px; display: flex; align-items: flex-end; }}
.section .t {{ flex: 1; }}
.section .a {{ font: 500 14px/18px Barlow; color: {p["depth"]}; padding: 4px 6px; }}
.info {{ display: flex; align-items: flex-start; padding: 11px 0; }}
.info .l {{ width: 128px; padding-right: 12px; font: 400 15px/20px Barlow; color: {p["ink2"]}; flex: none; }}
.info .v {{ flex: 1; text-align: right; font: 500 15px/20px Barlow; font-feature-settings: "tnum"; white-space: pre-line; overflow-wrap: anywhere; }}
.info .v.mono {{ font: 400 14px/20px Plex; font-feature-settings: "zero"; }}
.info .tr {{ margin-left: 6px; margin-top: 1px; }}
.hair {{ height: {1 / SCALE:.4f}px; background: {p["rule"]}; }}
.tag {{ display: inline-flex; align-items: center; gap: 6px; border: 1px solid {p["rule"]}; border-radius: 8px; padding: 5px 10px; font: 500 14px/18px Barlow; white-space: nowrap; }}
.tag.flag {{ border-color: {p["flare"]}b3; color: {p["flare"]}; }}
.rb {{ width: 42px; height: 42px; border-radius: 50%; display: flex; align-items: center; justify-content: center; flex: none; }}
.shim {{ height: 12px; border-radius: 4px; background: {p["rule"]}; }}
.small {{ font: 400 13px/17px Barlow; }}
.label {{ font: 400 15px/20px Barlow; }}
.statusbar {{ position: absolute; left: 0; top: 0; width: {W}px; height: {STATUS}px; display: flex; align-items: center; padding: 0 22px; font: 600 14px Barlow; z-index: 50; color: {p["ink"]}; }}
.gesture {{ position: absolute; left: 50%; bottom: 6px; width: 108px; height: 4px; margin-left: -54px; border-radius: 2px; background: {p["ink"]}; opacity: .55; z-index: 60; }}
"""


def statusbar(p, time="8:58"):
    sig = '<svg width="16" height="12" viewBox="0 0 16 12"><path d="M1 11h2V8H1zM5 11h2V6H5zM9 11h2V3H9zM13 11h2V0h-2z" fill="%s"/></svg>' % p["ink"]
    wifi = '<svg width="16" height="12" viewBox="0 0 16 12"><path d="M8 11.5 0 3.2C2.2 1.2 5 0 8 0s5.8 1.2 8 3.2z" fill="%s"/></svg>' % p["ink"]
    bat = '<svg width="24" height="12" viewBox="0 0 24 12"><rect x=".5" y=".5" width="20" height="11" rx="2.5" fill="none" stroke="%s"/><rect x="2" y="2" width="15" height="8" rx="1.2" fill="%s"/><rect x="21.5" y="4" width="1.8" height="4" rx=".8" fill="%s"/></svg>' % (p["ink"], p["ink"], p["ink"])
    return f'<div class="statusbar"><span>{time}</span><span style="flex:1"></span><span class="row" style="gap:6px">{sig}{wifi}{bat}</span></div>'


def info(p, label, value, mono=False, color=None, trailing=None, divider=True):
    if value is None:
        return ""
    tr = f'<span class="tr">{icon(trailing, p["ink3"], 18)}</span>' if trailing else ""
    st = f' style="color:{color}"' if color else ""
    v = esc(value)
    return (f'<div class="info"><div class="l">{esc(label)}</div><div class="v{" mono" if mono else ""}"{st}>{v}</div>{tr}</div>'
            + ('<div class="hair"></div>' if divider else ""))


def section(title, action=None):
    a = f'<span class="a">{esc(action)}</span>' if action else ""
    return f'<div class="section"><span class="t">{esc(title)}</span>{a}</div>'


def tag(p, text, glyph=None, flagged=False):
    c = p["flare"] if flagged else p["ink"]
    g = icon(glyph, c, 16) if glyph else ""
    return f'<span class="tag{" flag" if flagged else ""}">{g}{esc(text)}</span>'


def round_button(p, glyph, bg=None, tint=None, size=42, isize=22):
    bg = bg or (p["chart"] + "d9")
    return f'<div class="rb" style="width:{size}px;height:{size}px;background:{bg}">{icon(glyph, tint or p["ink"], isize)}</div>'


def bottom_bar(p, selected):
    items = [("LOOKUP", "Look up", "Globe"), ("HISTORY", "History", "Clock"), ("SETTINGS", "Settings", "Sliders")]
    cells = []
    for key, label, g in items:
        sel = key == selected
        c = p["ink"] if sel else p["ink3"]
        dot = f'<span style="position:absolute;right:-5px;top:-2px;width:6px;height:6px;border-radius:3px;background:{p["flare"]}"></span>' if sel else ""
        cells.append(f'<div style="flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center">'
                     f'<div style="position:relative">{icon(g, c, 24)}{dot}</div>'
                     f'<div style="margin-top:3px;font:500 13px/16px Barlow;color:{c}">{label}</div></div>')
    return (f'<div style="position:fixed;left:0;bottom:0;width:{W}px;background:{p["bg"]};z-index:40">'
            f'<div class="hair"></div><div style="display:flex;height:62px">{"".join(cells)}</div><div style="height:{NAV}px"></div></div>')


def history_row(p, flag, query, detail, when, divider=True):
    return (f'<div class="row" style="padding:11px 0"><div style="width:32px;font:400 16px/22px Barlow">{flag}</div>'
            f'<div style="flex:1;min-width:0"><div style="font:500 16px/22px Barlow;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">{esc(query)}</div>'
            f'<div class="small" style="color:{p["ink2"]};white-space:nowrap;overflow:hidden;text-overflow:ellipsis">{esc(detail)}</div></div>'
            f'<div class="small" style="margin-left:8px;color:{p["ink3"]}">{esc(when)}</div></div>'
            + ('<div class="hair"></div>' if divider else ""))


def page(p, body):
    return f'<!doctype html><html><head><meta charset="utf-8"><style>{css(p)}</style></head><body>{body}</body></html>'


# ---- Screens

def home(p):
    chart_h = STATUS + 292
    pin = (38.58, -121.49)
    world = world_svg(p, W, chart_h, top=STATUS + 50, bottom=92, pin=pin)
    header = (f'<div class="row" style="position:absolute;left:0;top:{STATUS}px;width:{W}px;height:52px;padding:0 12px 0 20px">'
              f'<div class="row" style="gap:7px">{icon("Flare", p["flare"], 20)}<span style="font:600 22px/26px Barlow">IP Atlas</span></div>'
              f'<span style="flex:1"></span>{round_button(p, "Refresh", size=40, isize=20)}</div>')
    addr = (f'<div style="position:absolute;left:20px;right:20px;top:{chart_h - 104}px">'
            f'<div class="small" style="color:{p["ink2"]}">Your public address</div>'
            f'<div style="font:500 26px/32px Plex;font-feature-settings:\'zero\'">73.162.88.21</div>'
            f'<div class="label" style="color:{p["ink2"]}">Comcast Cable Communications, Sacramento, California</div></div>')
    search = (f'<div class="row" style="position:absolute;left:16px;right:16px;top:{chart_h + 28 - 56}px;height:56px;border-radius:14px;background:{p["raised"]};border:1px solid {p["rule"]};padding:0 7px 0 14px">'
              f'{icon("Search", p["ink2"])}<span style="width:10px"></span>'
              f'<span style="flex:1;font:400 16px/22px Barlow;color:{p["ink3"]}">IP address or domain</span>'
              f'{round_button(p, "Paste", bg="transparent", tint=p["ink2"], size=40, isize=20)}<span style="width:4px"></span>'
              f'<span style="height:42px;border-radius:10px;background:{p["ink"]};color:{p["bg"]};font:500 16px/22px Barlow;display:flex;align-items:center;padding:0 14px">Look up</span></div>')
    top = f'<div style="position:relative;height:{chart_h + 28}px">{world}{header}{addr}{search}</div>'
    body = [top, '<div style="padding:0 20px">']
    body.append(f'<div class="row" style="gap:8px;padding-top:16px">{tag(p, "VPN off", "Lock")}{tag(p, "Wi-Fi", "Wifi")}{tag(p, "IPv6 ready", "Globe")}</div>')
    body.append(section("Your connection", "Full details"))
    body.append(info(p, "Public IPv4", "73.162.88.21", mono=True))
    body.append(info(p, "Public IPv6", "2601:204:c101:4a10::7c11", mono=True))
    body.append(info(p, "Provider", "Comcast Cable Communications"))
    body.append(info(p, "Location", "🇺🇸 Sacramento, California"))
    body.append(info(p, "Network", "AS7922  Comcast Cable Communications, LLC"))
    body.append(info(p, "Time zone", "America/Los_Angeles", divider=False))
    body.append(section("This phone"))
    body.append(info(p, "Connection", "Wi-Fi"))
    body.append(info(p, "Local address", "192.168.1.23/24", mono=True))
    body.append(info(p, "Gateway", "192.168.1.1", mono=True))
    body.append(info(p, "DNS servers", "192.168.1.1", mono=True))
    body.append(info(p, "Private DNS", "Automatic"))
    body.append(info(p, "Interface", "wlan0, MTU 1500"))
    body.append(info(p, "Metered", "No", divider=False))
    body.append(section("Recent", "History"))
    body.append(history_row(p, "🇺🇸", "google.com", "Mountain View, California, United States, 142.250.191.46", "12 min ago"))
    body.append(history_row(p, "🇬🇧", "bbc.co.uk", "London, England, United Kingdom, 151.101.0.81", "2 h ago"))
    body.append(history_row(p, "🇦🇺", "1.1.1.1", "Sydney, New South Wales, Australia", "Yesterday", divider=False))
    body.append('<div style="height:120px"></div></div>')
    return page(p, f'<div class="screen">{statusbar(p)}{"".join(body)}{bottom_bar(p, "LOOKUP")}<div class="gesture"></div></div>')


def result(p, ping=None, solid_bar=False):
    chart_h = STATUS + 286
    lat, lon = 37.4220, -122.0841
    chart = region_svg(p, W, chart_h, lat, lon, 600, "Mountain View", top=STATUS + 58, bottom=46, ping=ping)
    zoom = (f'<div class="row" style="position:absolute;right:16px;top:{chart_h - 6 - 38}px;gap:8px">'
            f'{round_button(p, "Minus", bg=p["raised"], size=38, isize=20)}{round_button(p, "Plus", bg=p["raised"], size=38, isize=20)}</div>')
    title = (f'<span style="flex:1;padding:0 12px;font:500 16px/22px Barlow;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">google.com</span>'
             if solid_bar else '<span style="flex:1"></span>')
    bar_bg = (f'<div style="position:fixed;left:0;top:0;width:{W}px;height:{STATUS + 58}px;background:{p["bg"]};z-index:44"></div>'
              f'<div class="hair" style="position:fixed;left:0;top:{STATUS + 58}px;width:{W}px;z-index:44"></div>') if solid_bar else ""
    topbar = (bar_bg + f'<div class="row" style="position:fixed;left:0;top:{STATUS}px;width:{W}px;padding:8px 12px;z-index:45">'
              f'{round_button(p, "Back")}{title}{round_button(p, "StarFilled", tint=p["flare"])}<span style="width:8px"></span>{round_button(p, "Share")}</div>')
    head = (f'<div style="padding-top:6px">'
            f'<div style="font:500 20px/25px Barlow;color:{p["ink2"]}">google.com</div>'
            f'<div class="row"><div style="flex:1;font:500 30px/36px Plex;font-feature-settings:\'zero\'">142.250.191.46</div>{round_button(p, "Copy", bg="transparent", tint=p["ink2"])}</div>'
            f'<div style="font:500 20px/25px Barlow;padding-top:2px">🇺🇸&nbsp;&nbsp;Mountain View, California, United States</div>'
            f'<div class="row" style="gap:8px;padding-top:12px">{tag(p, "IPv4")}{tag(p, "Public")}{tag(p, "Business", "Server")}</div></div>')
    dom = [section("Domain"), info(p, "Name", "google.com")]
    dom.append(f'<div class="label" style="color:{p["ink2"]};padding:11px 0 4px">Points to 2 addresses. Tap one to look it up.</div>')
    for a, showing in (("142.250.191.46", True), ("2607:f8b0:4005:80b::200e", False)):
        mark = icon("Diamond" if showing else "Ring", p["flare"] if showing else p["ink3"], 14)
        dom.append(f'<div class="row" style="padding:9px 0">{mark}<span style="width:10px"></span>'
                   f'<span style="flex:1;font:400 14px/20px Plex;color:{p["ink"] if showing else p["ink2"]}">{a}</span>'
                   f'<span class="small" style="color:{p["flare"] if showing else p["ink3"]}">{"Showing" if showing else "IPv6"}</span></div>')
    dom.append('<div class="hair"></div>')
    dom.append(info(p, "Mail servers", "10 smtp.google.com", mono=True))
    dom.append(info(p, "Name servers", "ns1.google.com\nns2.google.com\nns3.google.com\nns4.google.com", mono=True))
    dom.append(info(p, "Text records", "v=spf1 include:_spf.google.com ~all\ngoogle-site-verification=wD8N7i1JTN…"))
    dom.append(info(p, "Answered by", "Cloudflare DNS", divider=False))
    net = [section("Network"), info(p, "Provider", "Google LLC"), info(p, "ASN", "AS15169"),
           info(p, "Route", "142.250.0.0/15", mono=True), info(p, "Range", "142.250.0.0 - 142.251.255.255", mono=True),
           info(p, "Net name", "GOOGLE"), info(p, "Hostname", "sfo03s32-in-f14.1e100.net", mono=True),
           info(p, "Domain", "google.com"), info(p, "Type", "Business"), info(p, "Registry", "ARIN", divider=False)]
    loc = [section("Location"), info(p, "City", "Mountain View"), info(p, "Region", "California"),
           info(p, "Postal code", "94043"), info(p, "Country", "🇺🇸 United States (US)"), info(p, "Continent", "North America"),
           info(p, "Coordinates", "37.42200, −122.08410", trailing="External"), info(p, "In degrees", "37°25′19″N  122°05′03″W"),
           info(p, "Accuracy", "Very high"), info(p, "Calling code", "+1"), info(p, "Currency", "USD", divider=False)]
    tm = [section("Time"), info(p, "Time zone", "America/Los_Angeles"), info(p, "Local time", "8:58:41 PM, Mon Nov 9"),
          info(p, "UTC offset", "UTC−08:00"), info(p, "Daylight saving", "Not in effect"), info(p, "Compared to you", "Same time as you", divider=False)]
    checks = [("VPN", False), ("Proxy", False), ("Tor exit", False), ("Hosting", True), ("Known abuse", False), ("Mobile network", False), ("Crawler or bot", False), ("Satellite", False)]
    pc = [section("Privacy checks")]
    for i in range(0, len(checks), 2):
        cells = []
        for label, flag in checks[i:i + 2]:
            mark = icon("Diamond" if flag else "Ring", p["flare"] if flag else p["ink3"], 16)
            c = p["flare"] if flag else p["ink"]
            cells.append(f'<div class="row" style="flex:1">{mark}<span style="width:8px"></span><span class="label" style="flex:1;color:{c}">{label}</span>'
                         f'<span style="font:500 15px/20px Barlow;color:{p["flare"] if flag else p["ink3"]};padding-right:14px">{"Yes" if flag else "No"}</span></div>')
        pc.append(f'<div class="row" style="padding:9px 0">{"".join(cells)}</div><div class="hair"></div>')
    pc.append(info(p, "Abuse score", "0.0002 (Very low)"))
    pc.append(info(p, "ASN abuse score", "0.0001 (Very low)", divider=False))
    ab = [section("Abuse contact"), info(p, "Email", "network-abuse@google.com", trailing="Mail", divider=False)]
    reg = [section("Registration"), info(p, "Network name", "GOOGLE"), info(p, "Organization", "Google LLC"),
           info(p, "Range", "142.250.0.0 – 142.251.255.255", mono=True), info(p, "Block", "142.250.0.0/15", mono=True),
           info(p, "Type", "Direct allocation"), info(p, "Registered", "Apr 21, 2012"), info(p, "Last changed", "Apr 25, 2012"),
           info(p, "Registry", "ARIN (North America)"), info(p, "Status", "Active"), info(p, "Handle", "NET-142-250-0-0-1", mono=True, divider=False)]
    pc[0] = pc[0].replace('class="section"', 'class="section" id="privacy"')
    body = (f'<div style="position:relative;height:{chart_h}px">{chart}{zoom}</div>'
            f'<div style="padding:0 20px">{head}{"".join(dom)}{"".join(net)}{"".join(loc)}{"".join(tm)}{"".join(pc)}{"".join(ab)}{"".join(reg)}<div style="height:80px"></div></div>')
    return page(p, f'<div class="screen">{statusbar(p)}{topbar}{body}<div class="gesture"></div></div>')


def result_area(p, km):
    """A lookup in the Sacramento area (ipwho.is answering), for showing the chart's town and state names."""
    chart_h = STATUS + 286
    lat, lon = 38.5816, -121.4944
    chart = region_svg(p, W, chart_h, lat, lon, km, "Sacramento", top=STATUS + 58, bottom=46)
    zoom = (f'<div class="row" style="position:absolute;right:16px;top:{chart_h - 6 - 38}px;gap:8px">'
            f'{round_button(p, "Minus", bg=p["raised"], size=38, isize=20)}{round_button(p, "Plus", bg=p["raised"], size=38, isize=20)}</div>')
    topbar = (f'<div class="row" style="position:fixed;left:0;top:{STATUS}px;width:{W}px;padding:8px 12px;z-index:45">'
              f'{round_button(p, "Back")}<span style="flex:1"></span>{round_button(p, "Star")}<span style="width:8px"></span>{round_button(p, "Share")}</div>')
    head = (f'<div style="padding-top:6px">'
            f'<div class="row"><div style="flex:1;font:500 30px/36px Plex;font-feature-settings:\'zero\'">73.162.88.21</div>{round_button(p, "Copy", bg="transparent", tint=p["ink2"])}</div>'
            f'<div style="font:500 20px/25px Barlow;padding-top:2px">🇺🇸&nbsp;&nbsp;Sacramento, California, United States</div>'
            f'<div class="row" style="gap:8px;padding-top:12px">{tag(p, "IPv4")}{tag(p, "Public")}</div></div>')
    net = [section("Network"), info(p, "Provider", "Comcast Cable Communications, LLC"), info(p, "ASN", "AS7922"),
           info(p, "Hostname", "c-73-162-88-21.hsd1.ca.comcast.net", mono=True), info(p, "Domain", "comcast.net", divider=False)]
    loc = [section("Location"), info(p, "City", "Sacramento"), info(p, "Region", "California"), info(p, "Postal code", "95814"),
           info(p, "Country", "🇺🇸 United States (US)", divider=False)]
    body = (f'<div style="position:relative;height:{chart_h}px">{chart}{zoom}</div>'
            f'<div style="padding:0 20px">{head}{"".join(net)}{"".join(loc)}<div style="height:200px"></div></div>')
    return page(p, f'<div class="screen">{statusbar(p, "10:41")}{topbar}{body}<div class="gesture"></div></div>')


def choice(p, title, detail, selected, divider=True):
    ring = p["ink"] if selected else p["ink3"]
    dot = f'<span style="width:11px;height:11px;border-radius:6px;background:{p["ink"]}"></span>' if selected else ""
    return (f'<div class="row" style="padding:12px 0"><div style="flex:1;padding-right:12px">'
            f'<div style="font:500 16px/22px Barlow">{esc(title)}</div><div class="small" style="color:{p["ink2"]}">{esc(detail)}</div></div>'
            f'<span style="width:22px;height:22px;border-radius:11px;border:1.5px solid {ring};display:flex;align-items:center;justify-content:center;flex:none">{dot}</span></div>'
            + ('<div class="hair"></div>' if divider else ""))


def settings_screen(p):
    parts = [f'<div style="padding:{STATUS + 18}px 20px 28px">', '<div style="font:600 28px/32px Barlow">Settings</div>']
    parts.append(section("Look"))
    parts.append(choice(p, "Match phone", "Day or night, following the phone's dark theme.", True))
    parts.append(choice(p, "Day chart", "Light: white water and buff land, like a paper chart.", False))
    parts.append(choice(p, "Night chart", "Dark: deep water and dim land, easy on the eyes at night.", False, divider=False))
    parts.append('<div id="sources"></div>' + section("Where lookups come from"))
    parts.append(choice(p, "Automatic", "Asks ipwho.is, then FreeIPAPI, then ipapi.is, moving on when one is busy or out of free lookups, and fills in anything one leaves out.", True))
    parts.append(choice(p, "ipwho.is first", "City, state, coordinates, network and time zone. 1,000 lookups a day.", False))
    parts.append(choice(p, "FreeIPAPI first", "City, state, coordinates, network and a proxy check. 60 lookups a minute.", False))
    parts.append(choice(p, "ipapi.is first", "With a free key: everything, plus VPN, proxy, Tor and hosting checks and abuse contacts (1,000 a day). Without one: city, state and network only, 30 a day.", False, divider=False))
    parts.append(f'<div class="row" style="padding:12px 0"><div style="flex:1;padding-right:12px"><div style="font:500 16px/22px Barlow">ipapi.is key</div>'
                 f'<div class="small" style="color:{p["ink2"]}">Optional. A free key adds VPN, proxy, Tor and hosting checks and abuse contacts.</div></div>{icon("Chevron", p["ink3"], 20)}</div>')
    parts.append(section("History"))
    parts.append(f'<div class="row" style="padding:12px 0"><div style="flex:1"><div style="font:500 16px/22px Barlow">Keep a history of lookups</div>'
                 f'<div class="small" style="color:{p["ink2"]}">Starred lookups are always kept.</div></div>'
                 f'<span style="width:52px;height:32px;border-radius:16px;background:{p["ink"]};position:relative;flex:none"><span style="position:absolute;right:4px;top:4px;width:24px;height:24px;border-radius:12px;background:{p["bg"]}"></span></span></div>')
    parts.append('<div style="height:300px"></div></div>')
    scrim = f'<div style="position:fixed;left:0;top:0;width:{W}px;height:{STATUS}px;background:{p["bg"]};opacity:.96;z-index:49"></div>'
    return page(p, f'<div class="screen">{scrim}{statusbar(p, "10:41")}{"".join(parts)}{bottom_bar(p, "SETTINGS")}<div class="gesture"></div></div>')


def render():
    from playwright.sync_api import sync_playwright

    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    shots = [
        ("1-home-night.png", home(NIGHT), 0),
        ("2-lookup-night.png", result(NIGHT, ping=0.35), 0),
        ("3-lookup-details-night.png", result(NIGHT, solid_bar=True), "privacy"),
        ("4-lookup-day.png", result(DAY, ping=0.35), 0),
    ]
    if which == "1.1":
        shots = [
            ("1.1-area-600km.png", result_area(NIGHT, 600), 0),
            ("1.1-area-250km.png", result_area(NIGHT, 250), 0),
            ("1.1-area-day.png", result_area(DAY, 600), 0),
            ("1.1-settings.png", settings_screen(NIGHT), "sources"),
        ]
    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        pg = browser.new_page(viewport={"width": W, "height": H}, device_scale_factor=SCALE)
        for name, doc, scroll in shots:
            path = os.path.join(OUT, name.replace(".png", ".html"))
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(doc)
            pg.goto("file://" + os.path.abspath(path))
            pg.wait_for_timeout(250)
            if isinstance(scroll, str):
                # Scroll so that section sits just under the top bar.
                pg.evaluate(f"window.scrollTo(0, document.getElementById('{scroll}').offsetTop - {STATUS + 58 - 6})")
            else:
                pg.evaluate(f"window.scrollTo(0, {scroll})")
            pg.wait_for_timeout(100)
            # The status bar stays put while the page scrolls under it.
            pg.evaluate("document.querySelector('.statusbar').style.position='fixed'")
            pg.screenshot(path=os.path.join(OUT, name))
            print("wrote", name)
        browser.close()


if __name__ == "__main__":
    render()
