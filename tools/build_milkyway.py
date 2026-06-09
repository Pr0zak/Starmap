#!/usr/bin/env python3
"""Build the bundled Milky Way dot cloud from d3-celestial's brightness contours.

The Milky Way is rendered as a field of faint dots whose density/brightness
follows five nested isophote contours (ol1 faint … ol5 bright). Sampling the
contours into points lets the app draw the galactic glow by reusing the same
cheap per-point projection it uses for stars — no polygon clipping required.

Outputs app/src/main/assets/milkyway.json: a flat array [ra,dec,level, …] with
ra/dec in degrees (J2000 equatorial) and level 1–5. d3-celestial's data is
MIT-licensed and derived from the Mellinger all-sky panorama.

Run from the repository root:
    python3 tools/build_milkyway.py
"""
import json
import math
import os
import random
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
URL = "https://raw.githubusercontent.com/ofrohn/d3-celestial/master/data/mw.json"
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "milkyway.json")

STEP = 1.5            # base grid spacing in degrees
JITTER = 0.6         # random offset so the cloud isn't an obvious grid
KEEP = {1: 0.30, 2: 0.55, 3: 0.80, 4: 1.0, 5: 1.0}  # thin the broad faint band


def rings_per_level(geo):
    levels = []
    for f in geo["features"]:
        rings = []
        for poly in f["geometry"]["coordinates"]:
            for ring in poly:
                xs = [p[0] for p in ring]
                ys = [p[1] for p in ring]
                rings.append((ring, min(xs), max(xs), min(ys), max(ys)))
        levels.append(rings)
    return levels


def inside_ring(x, y, ring):
    n = len(ring)
    inside = False
    j = n - 1
    for i in range(n):
        xi, yi = ring[i]
        xj, yj = ring[j]
        if ((yi > y) != (yj > y)) and \
                (x < (xj - xi) * (y - yi) / ((yj - yi) or 1e-12) + xi):
            inside = not inside
        j = i
    return inside


def level_of(lon, lat, levels):
    for i in range(4, -1, -1):  # brightest (5) down to faintest (1)
        for ring, x0, x1, y0, y1 in levels[i]:
            if x0 <= lon <= x1 and y0 <= lat <= y1 and inside_ring(lon, lat, ring):
                return i + 1
    return 0


def main():
    geo = json.loads(urllib.request.urlopen(URL, timeout=60).read().decode("utf-8"))
    levels = rings_per_level(geo)
    random.seed(7)
    out = []
    hist = [0] * 6
    lat = -85.0
    while lat <= 85.0:
        lon = -180.0
        # keep dots roughly equal-area by widening RA spacing toward the poles
        dlon = STEP / max(0.2, math.cos(math.radians(lat)))
        while lon < 180.0:
            lv = level_of(lon, lat, levels)
            if lv > 0 and random.random() <= KEEP[lv]:
                jx = lon + random.uniform(-JITTER, JITTER) / max(0.2, math.cos(math.radians(lat)))
                jy = lat + random.uniform(-JITTER, JITTER)
                ra = (jx + 360.0) % 360.0
                out.append(round(ra, 1))
                out.append(round(jy, 1))
                out.append(lv)
                hist[lv] += 1
            lon += dlon
        lat += STEP
    with open(OUT, "w") as f:
        json.dump(out, f, separators=(",", ":"))
    print(f"wrote {len(out)//3} Milky Way dots to {OUT}  byLevel={hist[1:]}")


if __name__ == "__main__":
    main()
