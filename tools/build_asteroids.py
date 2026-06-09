#!/usr/bin/env python3
"""Build the bundled bright-asteroid element set from Stellarium's data.

Outputs app/src/main/assets/asteroids.json (osculating Keplerian elements for a
curated set of the brightest minor planets). Stellarium's ssystem_minor.ini is
CC-BY / GPL data derived from MPC/JPL orbits.

Run from the repository root:
    python3 tools/build_asteroids.py
"""
import configparser
import json
import os
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
URL = "https://raw.githubusercontent.com/Stellarium/stellarium/master/data/ssystem_minor.ini"
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "asteroids.json")

# Curated bright asteroids worth pointing at.
WANT = {
    "ceres", "pallas", "juno", "vesta", "astraea", "hebe", "iris", "flora",
    "metis", "hygiea", "parthenope", "victoria", "egeria", "eunomia", "psyche",
    "massalia", "amphitrite", "melpomene", "fortuna", "thalia",
}


def main():
    text = urllib.request.urlopen(URL, timeout=60).read().decode("utf-8", "replace")
    cp = configparser.ConfigParser(strict=False)
    cp.read_string(text)
    out = []
    for sec in cp.sections():
        d = cp[sec]
        if d.get("name", "").lower() not in WANT or d.get("type", "") != "asteroid":
            continue
        try:
            out.append(dict(
                name=d["name"],
                a=float(d["orbit_SemiMajorAxis"]), e=float(d["orbit_Eccentricity"]),
                i=float(d["orbit_Inclination"]), om=float(d["orbit_AscendingNode"]),
                w=float(d["orbit_ArgOfPericenter"]), ma=float(d["orbit_MeanAnomaly"]),
                n=float(d["orbit_MeanMotion"]), epoch=float(d["orbit_Epoch"]),
                H=float(d.get("absolute_magnitude", "99")),
            ))
        except KeyError:
            pass
    out.sort(key=lambda x: x["H"])
    with open(OUT, "w") as f:
        json.dump(out, f, separators=(",", ":"))
    print(f"wrote {len(out)} asteroids to {OUT}")


if __name__ == "__main__":
    main()
