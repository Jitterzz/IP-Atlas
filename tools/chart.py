"""Python twin of the app's chart drawing (ui/ChartMap.kt), used to render the screenshot previews.

Same data files, same projections, same constants, so the preview shows what the phone draws.
"""
import math
import os
import struct
import zlib

GEO = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "geo")


class Geo:
    def __init__(self):
        with open(os.path.join(GEO, "land.bin"), "rb") as fh:
            assert fh.read(4) == b"LAND"
            _, self.w, self.h = struct.unpack(">iii", fh.read(12))
            self.bits = zlib.decompress(fh.read())
        self.cells = self.w // 360
        self.borders = []
        with open(os.path.join(GEO, "borders.bin"), "rb") as fh:
            data = fh.read()
        assert data[:4] == b"BRDR"
        _, n = struct.unpack(">ii", data[4:12])
        p = 12
        for _ in range(n):
            (k,) = struct.unpack(">i", data[p : p + 4])
            p += 4
            pts = []
            for _ in range(k):
                lon, lat = struct.unpack(">hh", data[p : p + 4])
                p += 4
                pts.append((lon / 100.0, lat / 100.0))
            self.borders.append(pts)
        self.cities = []
        with open(os.path.join(GEO, "cities.tsv"), encoding="utf-8") as fh:
            for line in fh:
                name, lat, lon, pop, cc = line.rstrip("\n").split("\t")
                self.cities.append((name, float(lat), float(lon), int(pop), cc))

    def land(self, lat, lon):
        r = min(self.h - 1, max(0, int((90.0 - lat) * self.cells)))
        c = int(math.floor((lon + 180.0) * self.cells)) % self.w
        i = r * self.w + c
        return (self.bits[i >> 3] >> (7 - (i & 7))) & 1 == 1


# Equal Earth projection (Savric, Patterson & Jenny 2018), in radians.
A1, A2, A3, A4 = 1.340264, -0.081106, 0.000893, 0.003796
M = math.sqrt(3) / 2
EE_X = 2 * math.sqrt(3) * math.pi / (3 * A1)  # x at lon 180, lat 0
EE_Y = 1.3173627591574  # y at lat 90


def ee_forward(lat, lon):
    t = math.asin(M * math.sin(math.radians(lat)))
    t2 = t * t
    t6 = t2 * t2 * t2
    x = 2 * math.sqrt(3) * math.radians(lon) * math.cos(t) / (3 * (9 * A4 * t6 * t2 + 7 * A3 * t6 + 3 * A2 * t2 + A1))
    y = t * (A1 + A2 * t2 + t6 * (A3 + A4 * t2))
    return x, y


def ee_theta(y):
    t = y / A1
    for _ in range(8):
        t2 = t * t
        t6 = t2 * t2 * t2
        f = t * (A1 + A2 * t2 + t6 * (A3 + A4 * t2)) - y
        fp = A1 + 3 * A2 * t2 + t6 * (7 * A3 + 9 * A4 * t2)
        d = f / fp
        t -= d
        if abs(d) < 1e-9:
            break
    return t


def world_dots(geo, w, h, spacing, top_lat=84.0, bottom_lat=-58.0):
    """Dots on a screen grid over an Equal Earth world, cropped to top_lat..bottom_lat."""
    y_top = ee_forward(top_lat, 0)[1]
    y_bot = ee_forward(bottom_lat, 0)[1]
    scale = min(w / (2 * EE_X), h / (y_top - y_bot))
    ox = w / 2
    oy = (h - (y_top - y_bot) * scale) / 2 + y_top * scale
    dots = []
    rows = int(h / spacing)
    cols = int(w / spacing)
    x0 = (w - (cols - 1) * spacing) / 2
    y0 = (h - (rows - 1) * spacing) / 2
    for r in range(rows):
        sy = y0 + r * spacing
        y = (oy - sy) / scale
        if y > y_top or y < y_bot:
            continue
        t = ee_theta(y)
        lat = math.degrees(math.asin(max(-1.0, min(1.0, math.sin(t) / M))))
        t2 = t * t
        t6 = t2 * t2 * t2
        denom = 2 * math.sqrt(3) * math.cos(t)
        shift = spacing / 2 if r % 2 == 1 else 0
        for c in range(cols):
            sx = x0 + c * spacing + shift
            x = (sx - ox) / scale
            lon = math.degrees(3 * x * (9 * A4 * t6 * t2 + 7 * A3 * t6 + 3 * A2 * t2 + A1) / denom)
            if abs(lon) > 180:
                continue
            if geo.land(lat, lon):
                dots.append((sx, sy))

    def project(lat, lon):
        x, y = ee_forward(lat, lon)
        return ox + x * scale, oy - y * scale

    return dots, project


class Region:
    """Equidistant cylindrical view centred on (lat0, lon0), km_across wide."""

    def __init__(self, w, h, lat0, lon0, km_across):
        self.w, self.h, self.lat0, self.lon0 = w, h, lat0, lon0
        self.s = w / (km_across / 111.32)  # px per degree of latitude
        self.c = max(0.2, math.cos(math.radians(lat0)))

    def project(self, lat, lon):
        dlon = (lon - self.lon0 + 540) % 360 - 180
        return self.w / 2 + dlon * self.s * self.c, self.h / 2 - (lat - self.lat0) * self.s

    def unproject(self, x, y):
        return self.lat0 - (y - self.h / 2) / self.s, self.lon0 + (x - self.w / 2) / (self.s * self.c)


def region_dots(geo, reg, spacing):
    dots = []
    cols = int(reg.w / spacing) + 1
    rows = int(reg.h / spacing) + 1
    x0 = (reg.w - (cols - 1) * spacing) / 2
    y0 = (reg.h - (rows - 1) * spacing) / 2
    for r in range(rows):
        shift = spacing / 2 if r % 2 == 1 else 0
        for c in range(cols):
            x, y = x0 + c * spacing + shift, y0 + r * spacing
            lat, lon = reg.unproject(x, y)
            if -90 <= lat <= 90 and geo.land(lat, ((lon + 540) % 360) - 180):
                dots.append((x, y))
    return dots


def grid_step(span_deg):
    for step in (0.25, 0.5, 1, 2, 5, 10, 15, 30):
        if span_deg / step <= 4.5:
            return step
    return 30


def fmt_deg(v, pos, neg):
    hemi = pos if v >= 0 else neg
    a = abs(v)
    s = f"{a:.0f}" if abs(a - round(a)) < 1e-6 else f"{a:.1f}".rstrip("0").rstrip(".") if a % 1 else f"{a:.0f}"
    if abs(a - round(a)) > 1e-6:
        s = f"{a:.2f}".rstrip("0").rstrip(".")
    return f"{s}°{hemi}"
