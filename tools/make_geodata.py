"""Builds the offline map data the app draws its charts from (app/src/main/assets/geo).

  land.bin     land/water mask, 1/20 degree cells (7200 x 3600), 1 bit per cell, zlib-compressed.
               Header: b"LAND", then version, width, height as big-endian int32; then the zlib stream.
               Rows run north to south starting at 90N, columns west to east starting at 180W.
  borders.bin  land borders between countries (coastlines left out: the dots show those).
               Header: b"BRDR", version, line count (int32); each line: point count (int32), then
               lon*100 and lat*100 as big-endian int16 pairs.
  cities.tsv   cities of 100,000+ people, biggest first: name, lat, lon, population, country code.

Sources: the GLOBE 1 km land mask (via the global-land-mask package), Natural Earth 1:110m countries
(public domain, via geopandas' bundled copy) and GeoNames cities (CC BY 4.0, via geonamescache).

Run with: python tools/make_geodata.py  (needs geopandas==0.14.4, global-land-mask, geonamescache, numpy)
"""
import os
import struct
import zlib

import numpy as np

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "geo")
os.makedirs(OUT, exist_ok=True)


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


def borders():
    import geopandas as gpd
    from shapely.geometry import LineString, MultiLineString
    from shapely.ops import linemerge, unary_union

    world = gpd.read_file(gpd.datasets.get_path("naturalearth_lowres"))
    geoms = [g for g in world.geometry if g is not None]
    land = unary_union(geoms)
    every_edge = unary_union([g.boundary for g in geoms])
    inland = every_edge.difference(land.boundary.buffer(1e-4))
    merged = linemerge(inland) if not isinstance(inland, LineString) else inland
    lines = list(merged.geoms) if isinstance(merged, MultiLineString) else [merged]
    lines = [ln for ln in lines if isinstance(ln, LineString) and ln.length > 0.05]
    out = bytearray(b"BRDR")
    out += struct.pack(">ii", 1, len(lines))
    pts = 0
    for ln in lines:
        coords = list(ln.coords)
        out += struct.pack(">i", len(coords))
        for lon, lat in coords:
            out += struct.pack(">hh", int(round(lon * 100)), int(round(lat * 100)))
        pts += len(coords)
    with open(os.path.join(OUT, "borders.bin"), "wb") as fh:
        fh.write(out)
    print("borders.bin", len(lines), "lines", pts, "points", len(out), "bytes")


def cities():
    import geonamescache

    rows = [c for c in geonamescache.GeonamesCache().get_cities().values() if c["population"] >= 100_000]
    rows.sort(key=lambda c: -c["population"])
    with open(os.path.join(OUT, "cities.tsv"), "w", encoding="utf-8") as fh:
        for c in rows:
            name = c["name"].replace("\t", " ").strip()
            fh.write(f"{name}\t{c['latitude']:.4f}\t{c['longitude']:.4f}\t{c['population']}\t{c['countrycode']}\n")
    print("cities.tsv", len(rows), "cities")


if __name__ == "__main__":
    land_mask()
    borders()
    cities()
