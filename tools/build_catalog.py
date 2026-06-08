#!/usr/bin/env python3
"""Build compact Starmap catalogs from the HYG v4.1 database.

Outputs:
  app/src/main/assets/stars.json          naked-eye catalog (mag <= 6.5)
  app/src/main/assets/constellations.json constellation stick figures + names
  catalog/stars_ext.json                  extended catalog (mag <= 8.0) for download

The HYG database is licensed CC-BY-SA 4.0 (David Nash / astronexus).
Constellation line data from d3-celestial (ofrohn), BSD licensed.

Run from the repository root:
    python3 tools/build_catalog.py
"""
import csv
import json
import math
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HYG = os.path.join(ROOT, "tools", "data", "hygdata_v41.csv")
LINES = os.path.join(ROOT, "tools", "data", "constellations.lines.json")
NAMES = os.path.join(ROOT, "tools", "data", "constellations.json")

ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
CATALOG = os.path.join(ROOT, "catalog")

BUNDLED_MAG = 6.5   # shipped inside the APK (works fully offline)
EXTENDED_MAG = 8.0  # optional download

# HYG Bayer abbreviations -> Greek letters for nice labels (e.g. "Alp" -> "α").
GREEK = {
    "Alp": "α", "Bet": "β", "Gam": "γ", "Del": "δ", "Eps": "ε", "Zet": "ζ",
    "Eta": "η", "The": "θ", "Iot": "ι", "Kap": "κ", "Lam": "λ", "Mu": "μ",
    "Nu": "ν", "Xi": "ξ", "Omi": "ο", "Pi": "π", "Rho": "ρ", "Sig": "σ",
    "Tau": "τ", "Ups": "υ", "Phi": "φ", "Chi": "χ", "Psi": "ψ", "Ome": "ω",
}


def bayer_label(bayer, con):
    """Turn a HYG Bayer designation like 'Alp1' + 'CMa' into 'α¹ CMa'."""
    if not bayer or not con:
        return None
    base = bayer[:3]
    suffix = bayer[3:]
    letter = GREEK.get(base)
    if not letter:
        return None
    sup = {"1": "¹", "2": "²", "3": "³", "4": "⁴", "5": "⁵"}
    if suffix in sup:
        letter += sup[suffix]
    return f"{letter} {con}"


def load_stars(max_mag):
    out = {"ra": [], "dec": [], "mag": [], "ci": [], "labels": {}}
    with open(HYG, newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            if row["id"] == "0":  # the Sun (id 0) is computed at runtime
                continue
            try:
                mag = float(row["mag"])
                ra = float(row["ra"]) * 15.0   # hours -> degrees
                dec = float(row["dec"])
            except ValueError:
                continue
            if mag > max_mag:
                continue
            try:
                ci = float(row["ci"])
            except ValueError:
                ci = 0.0
            idx = len(out["ra"])
            out["ra"].append(round(ra, 4))
            out["dec"].append(round(dec, 4))
            out["mag"].append(round(mag, 2))
            out["ci"].append(round(ci, 2))
            proper = row["proper"].strip()
            label = proper or bayer_label(row["bayer"].strip(), row["con"].strip())
            # Keep labels for named stars, plus Bayer stars bright enough to matter.
            if proper or (label and mag <= 4.5):
                out["labels"][str(idx)] = label
    return out


def centroid(points):
    """Spherical centroid of [ra_deg, dec_deg] points (handles RA wrap)."""
    x = y = z = 0.0
    for ra, dec in points:
        r = math.radians(ra)
        d = math.radians(dec)
        x += math.cos(d) * math.cos(r)
        y += math.cos(d) * math.sin(r)
        z += math.sin(d)
    n = len(points)
    x, y, z = x / n, y / n, z / n
    ra = math.degrees(math.atan2(y, x)) % 360.0
    dec = math.degrees(math.atan2(z, math.hypot(x, y)))
    return [round(ra, 2), round(dec, 2)]


def build_constellations():
    lines = json.load(open(LINES))
    names = {f["id"]: f["properties"]["name"] for f in json.load(open(NAMES))["features"]}
    out = []
    for feat in lines["features"]:
        con = feat["id"]
        segs = feat["geometry"]["coordinates"]
        polylines = [[[round(p[0], 3), round(p[1], 3)] for p in seg] for seg in segs]
        allpts = [p for seg in polylines for p in seg]
        out.append({
            "con": con,
            "name": names.get(con, con),
            "label": centroid(allpts),
            "lines": polylines,
        })
    return out


def main():
    os.makedirs(ASSETS, exist_ok=True)
    os.makedirs(CATALOG, exist_ok=True)

    bundled = load_stars(BUNDLED_MAG)
    with open(os.path.join(ASSETS, "stars.json"), "w") as f:
        json.dump(bundled, f, separators=(",", ":"), ensure_ascii=False)
    print(f"stars.json: {len(bundled['ra'])} stars, {len(bundled['labels'])} labels")

    extended = load_stars(EXTENDED_MAG)
    with open(os.path.join(CATALOG, "stars_ext.json"), "w") as f:
        json.dump(extended, f, separators=(",", ":"), ensure_ascii=False)
    print(f"stars_ext.json: {len(extended['ra'])} stars, {len(extended['labels'])} labels")

    cons = build_constellations()
    with open(os.path.join(ASSETS, "constellations.json"), "w") as f:
        json.dump(cons, f, separators=(",", ":"), ensure_ascii=False)
    print(f"constellations.json: {len(cons)} constellations")


if __name__ == "__main__":
    if not os.path.exists(HYG):
        sys.exit("Missing tools/data/hygdata_v41.csv — see tools/README.md")
    main()
