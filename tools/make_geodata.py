"""Builds the offline map data the app draws its charts from (app/src/main/assets/geo).

  land.bin     land/water mask, 1/20 degree cells (7200 x 3600), 1 bit per cell, zlib-compressed.
               Header: b"LAND", then version, width, height as big-endian int32; then the zlib stream.
               Rows run north to south starting at 90N, columns west to east starting at 180W.
  borders.bin  land borders between countries (coastlines left out: the dots show those).
  states.bin   borders between states and provinces (United States, Canada, Australia, Brazil).
               Both: header b"BRDR", version, line count (int32); each line: point count (int32), then
               lon*100 and lat*100 as big-endian int16 pairs.
  regions.bin  which country and which state each 1/4 degree cell is in (1440 x 720 cells), zlib-compressed.
               Header: b"RGNS", version, width, height (int32); then a zlib stream holding the country byte
               grid followed by the state byte grid (0 = none), rows north to south from 90N.
  regions.tsv  the names for those bytes: kind (C or S), index, country code, name.
  cities.tsv   cities of 15,000+ people, biggest first: name, lat, lon, population, country code.

Sources: the GLOBE 1 km land mask (via the global-land-mask package), Natural Earth 1:50m countries and
states (public domain, via plotly's sane-topojson, MIT, copied to tools/data) and GeoNames cities
(CC BY 4.0, via geonamescache).

Run with: python tools/make_geodata.py  (needs global-land-mask, geonamescache, numpy, pillow)
"""
import json
import os
import struct
import zlib
from collections import defaultdict

import numpy as np

HERE = os.path.dirname(__file__)
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "geo")
os.makedirs(OUT, exist_ok=True)

SUBUNIT_NAMES = {
    "USA": None,  # filled from geonamescache
    "CAN": {
        "AB": "Alberta", "BC": "British Columbia", "MB": "Manitoba", "NB": "New Brunswick",
        "NL": "Newfoundland and Labrador", "NS": "Nova Scotia", "NT": "Northwest Territories", "NU": "Nunavut",
        "ON": "Ontario", "PE": "Prince Edward Island", "QC": "Quebec", "SK": "Saskatchewan", "YT": "Yukon",
    },
    "AUS": {
        "CT": "Australian Capital Territory", "JB": "Jervis Bay Territory", "NS": "New South Wales",
        "NT": "Northern Territory", "QL": "Queensland", "SA": "South Australia", "TS": "Tasmania",
        "VI": "Victoria", "WA": "Western Australia",
    },
    "BRA": {
        "AC": "Acre", "AL": "Alagoas", "AM": "Amazonas", "AP": "Amapá", "BA": "Bahia", "CE": "Ceará",
        "DF": "Distrito Federal", "ES": "Espírito Santo", "GO": "Goiás", "MA": "Maranhão", "MG": "Minas Gerais",
        "MS": "Mato Grosso do Sul", "MT": "Mato Grosso", "PA": "Pará", "PB": "Paraíba", "PE": "Pernambuco",
        "PI": "Piauí", "PR": "Paraná", "RJ": "Rio de Janeiro", "RN": "Rio Grande do Norte", "RO": "Rondônia",
        "RR": "Roraima", "RS": "Rio Grande do Sul", "SC": "Santa Catarina", "SE": "Sergipe", "SP": "São Paulo",
        "TO": "Tocantins",
    },
}


def land_mask():
    import global_land_mask.globe as globe

    mask = globe._mask  # (21600, 43200) bool, True over water; row 0 = 90N, col 0 = 180W, 1/120 degree cells
    f = 6
    h, w = mask.shape[0] // f, mask.shape[1] // f
    coarse = (~mask).reshape(h, f, w, f).mean(axis=(1, 3)) >= 0.34  # _mask is True over water
    packed = np.packbits(coarse, axis=1)  # MSB first; each row is w / 8 bytes
    data = zlib.compress(packed.tobytes(), 9)
    with open(os.path.join(OUT, "land.bin"), "wb") as fh:
        fh.write(b"LAND")
        fh.write(struct.pack(">iii", 1, w, h))
        fh.write(data)
    print("land.bin", w, h, len(data), "bytes")


class Topo:
    """Just enough TopoJSON decoding for polygons and their shared edges."""

    def __init__(self, path):
        with open(path, encoding="utf-8") as fh:
            self.t = json.load(fh)
        sx, sy = self.t["transform"]["scale"]
        tx, ty = self.t["transform"]["translate"]
        self.arcs = []
        for arc in self.t["arcs"]:
            x = y = 0
            pts = []
            for dx, dy in arc:
                x += dx
                y += dy
                pts.append((x * sx + tx, y * sy + ty))
            self.arcs.append(pts)

    def arc(self, i):
        return self.arcs[i] if i >= 0 else list(reversed(self.arcs[~i]))

    def rings(self, geom):
        if geom["type"] == "Polygon":
            polys = [geom["arcs"]]
        elif geom["type"] == "MultiPolygon":
            polys = geom["arcs"]
        else:
            return []
        out = []
        for poly in polys:
            for k, ring in enumerate(poly):
                pts = []
                for i in ring:
                    a = self.arc(i)
                    pts.extend(a if not pts else a[1:])
                out.append((k == 0, pts))
        return out

    def shared_lines(self, layer, group=None):
        """Edges used by two different shapes in a layer: the borders between them. With [group], only edges
        between two shapes of the same group count (state lines inside one country, not along its border)."""
        users = defaultdict(set)
        geoms = self.t["objects"][layer]["geometries"]
        for n, geom in enumerate(geoms):
            arcs = []
            if geom["type"] == "Polygon":
                arcs = [i for ring in geom["arcs"] for i in ring]
            elif geom["type"] == "MultiPolygon":
                arcs = [i for poly in geom["arcs"] for ring in poly for i in ring]
            for i in arcs:
                users[i if i >= 0 else ~i].add(n)
        lines = []
        for i, who in sorted(users.items()):
            if len(who) < 2:
                continue
            if group is not None and len({group(geoms[n]) for n in who}) > 1:
                continue
            lines.append(self.arcs[i])
        return lines


def write_lines(name, lines):
    out = bytearray(b"BRDR")
    lines = [ln for ln in lines if len(ln) >= 2]
    out += struct.pack(">ii", 1, len(lines))
    pts = 0
    for ln in lines:
        out += struct.pack(">i", len(ln))
        for lon, lat in ln:
            out += struct.pack(">hh", int(round(lon * 100)), int(round(lat * 100)))
        pts += len(ln)
    with open(os.path.join(OUT, name), "wb") as fh:
        fh.write(out)
    print(name, len(lines), "lines", pts, "points", len(out), "bytes")


def borders_and_regions():
    import geonamescache
    from PIL import Image, ImageDraw

    topo = Topo(os.path.join(HERE, "data", "world_50m.json"))
    write_lines("borders.bin", topo.shared_lines("countries"))
    write_lines("states.bin", topo.shared_lines("subunits", group=lambda g: g["properties"].get("gu")))

    gc = geonamescache.GeonamesCache()
    by_iso3 = {c["iso3"]: (code, c["name"]) for code, c in gc.get_countries().items()}
    us_states = {code: s["name"] for code, s in gc.get_us_states().items()}
    names = dict(SUBUNIT_NAMES)
    names["USA"] = us_states

    step = 0.25
    w, h = int(360 / step), int(180 / step)

    def px(lon, lat):
        return ((lon + 180.0) / step, (90.0 - lat) / step)

    def paint(layer, label_of):
        img = Image.new("L", (w, h), 0)
        draw = ImageDraw.Draw(img)
        table = []
        for geom in topo.t["objects"][layer]["geometries"]:
            label = label_of(geom)
            if label is None:
                continue
            index = len(table) + 1
            if index > 255:
                raise SystemExit("too many shapes for one byte")
            table.append((index,) + label)
            rings = topo.rings(geom)
            for outer, pts in rings:
                if outer and len(pts) >= 3:
                    draw.polygon([px(*p) for p in pts], fill=index)
            for outer, pts in rings:
                if not outer and len(pts) >= 3:
                    draw.polygon([px(*p) for p in pts], fill=0)
        return np.array(img, dtype=np.uint8), table

    def country_label(geom):
        iso3 = geom.get("id")
        if iso3 not in by_iso3:
            return None
        iso2, name = by_iso3[iso3]
        return (iso2, name)

    def state_label(geom):
        gu = geom["properties"].get("gu")
        code = geom.get("id")
        name = (names.get(gu) or {}).get(code)
        if name is None:
            return None
        iso2 = by_iso3[gu][0]
        return (iso2, name)

    countries, ctable = paint("countries", country_label)
    states, stable = paint("subunits", state_label)
    data = zlib.compress(countries.tobytes() + states.tobytes(), 9)
    with open(os.path.join(OUT, "regions.bin"), "wb") as fh:
        fh.write(b"RGNS")
        fh.write(struct.pack(">iii", 1, w, h))
        fh.write(data)
    with open(os.path.join(OUT, "regions.tsv"), "w", encoding="utf-8") as fh:
        for index, iso2, name in ctable:
            fh.write(f"C\t{index}\t{iso2}\t{name}\n")
        for index, iso2, name in stable:
            fh.write(f"S\t{index}\t{iso2}\t{name}\n")
    print("regions.bin", w, h, len(data), "bytes;", len(ctable), "countries,", len(stable), "states")


def cities():
    import math

    import geonamescache

    rows = list(geonamescache.GeonamesCache().get_cities().values())  # 15,000+ people
    rows.sort(key=lambda c: -c["population"])
    # GeoNames lists big cities' districts as cities of their own (Berlin-Mitte, Manhattan...). Leave out any
    # place within 10 km of one at least four times its size, so the chart names the city, not its parts.
    grid = defaultdict(list)
    kept = []
    for c in rows:
        lat, lon = c["latitude"], c["longitude"]
        gx, gy = int((lon + 180) // 0.25), int((lat + 90) // 0.25)
        swallowed = False
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for big in grid.get((gx + dx, gy + dy), ()):
                    if big["population"] < 4 * c["population"]:
                        continue
                    dlat = math.radians(big["latitude"] - lat)
                    dlon = math.radians(big["longitude"] - lon) * math.cos(math.radians(lat))
                    if 6371 * math.hypot(dlat, dlon) < 10:
                        swallowed = True
                        break
                if swallowed:
                    break
            if swallowed:
                break
        if not swallowed:
            kept.append(c)
            grid[(gx, gy)].append(c)
    print("cities: left out", len(rows) - len(kept), "districts of bigger cities")
    rows = kept
    with open(os.path.join(OUT, "cities.tsv"), "w", encoding="utf-8") as fh:
        for c in rows:
            name = c["name"].replace("\t", " ").strip()
            fh.write(f"{name}\t{c['latitude']:.4f}\t{c['longitude']:.4f}\t{c['population']}\t{c['countrycode']}\n")
    print("cities.tsv", len(rows), "cities")


if __name__ == "__main__":
    land_mask()
    borders_and_regions()
    cities()
