#!/usr/bin/env python3
"""Build the bundled Messier deep-sky catalog from d3-celestial data.

Outputs app/src/main/assets/messier.json — the 110 Messier objects with common
name, type, magnitude and J2000 RA/Dec. Source: d3-celestial (Olaf Frohn), BSD.

    python3 tools/build_messier.py
"""
import json
import os
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
URL = "https://raw.githubusercontent.com/ofrohn/d3-celestial/master/data/messier.json"
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "messier.json")

TYPE_NAME = {
    "gc": "Globular cluster", "oc": "Open cluster",
    "s": "Spiral galaxy", "e": "Elliptical galaxy", "i": "Irregular galaxy",
    "sfr": "Nebula", "pn": "Planetary nebula", "rn": "Reflection nebula",
    "snr": "Supernova remnant", "pos": "Star cloud", "n": "Nebula",
}
CATEGORY = {
    "gc": "cluster", "oc": "cluster",
    "s": "galaxy", "e": "galaxy", "i": "galaxy",
    "sfr": "nebula", "pn": "nebula", "rn": "nebula", "snr": "nebula", "n": "nebula",
    "pos": "other",
}


def main():
    data = json.load(urllib.request.urlopen(URL, timeout=60))
    out = []
    for f in data["features"]:
        p = f["properties"]
        lon, lat = f["geometry"]["coordinates"]
        t = p.get("type", "")
        mag = p.get("mag")
        out.append({
            "n": p["name"],
            "c": p.get("alt", "") or "",
            "t": TYPE_NAME.get(t, "Deep-sky object"),
            "cat": CATEGORY.get(t, "other"),
            "m": round(float(mag), 1) if mag not in (None, "") else 99.0,
            "ra": round(lon % 360.0, 4),
            "dec": round(float(lat), 4),
        })
    out.sort(key=lambda x: int(x["n"][1:]))
    with open(OUT, "w") as fh:
        json.dump(out, fh, separators=(",", ":"), ensure_ascii=False)
    print(f"wrote {len(out)} Messier objects to {OUT}")


if __name__ == "__main__":
    main()
