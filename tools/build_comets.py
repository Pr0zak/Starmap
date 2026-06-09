#!/usr/bin/env python3
"""Build the bundled bright-comet element set from Stellarium's data.

Outputs app/src/main/assets/comets.json (osculating Keplerian elements for a
curated set of famous and currently-notable comets). Comets use perihelion
distance q + time of perihelion Tp (rather than a + mean anomaly), so the app
propagates them with a universal-variable two-body solver that copes with the
near-parabolic orbits typical of great comets.

Stellarium's ssystem_minor.ini is CC-BY / GPL data derived from MPC/JPL orbits.

Run from the repository root:
    python3 tools/build_comets.py
"""
import json
import os
import re
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
URL = "https://raw.githubusercontent.com/Stellarium/stellarium/master/data/ssystem_minor.ini"
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "comets.json")

# Curated comets worth pointing at: great comets of history plus recent and
# upcoming naked-eye / binocular apparitions. Numbered periodic comets are
# matched by their designation prefix; the rest by exact base name (so prolific
# discoverer surnames don't drag in dozens of faint, obscure objects).
PERIODIC = {
    "1P", "2P", "12P", "13P", "17P", "21P", "46P", "55P", "67P", "103P",
    "109P", "144P",
}
EXACT = {
    "Hale-Bopp", "Hyakutake", "NEOWISE", "West", "Bennett", "Ikeya-Seki",
    "C/2023 A3", "C/2025 A6",
}


def wanted(base):
    return base.split("/")[0] in PERIODIC or base in EXACT


def main():
    text = urllib.request.urlopen(URL, timeout=60).read().decode("utf-8", "replace")
    parts = re.split(r"(?m)^\[(.+?)\]\s*$", text)
    out = []
    seen = set()
    for i in range(1, len(parts), 2):
        body = parts[i + 1]
        d = dict(re.findall(r"(?m)^([\w]+)\s*=\s*(.+?)\s*$", body))
        if d.get("type", "") != "comet":
            continue
        name = d.get("name", "")
        # Keep the first (best) match per base designation, skip dupes.
        base = name.split("(")[0].strip()
        if not wanted(base) or base in seen:
            continue
        try:
            entry = dict(
                name=base,
                q=float(d["orbit_PericenterDistance"]),
                e=float(d["orbit_Eccentricity"]),
                i=float(d["orbit_Inclination"]),
                om=float(d["orbit_AscendingNode"]),
                w=float(d["orbit_ArgOfPericenter"]),
                tp=float(d["orbit_TimeAtPericenter"]),
                epoch=float(d.get("orbit_Epoch", d["orbit_TimeAtPericenter"])),
                M1=float(d.get("absolute_magnitude", "12")),
                k=float(d.get("slope_parameter", "4")),
            )
        except KeyError:
            continue
        seen.add(base)
        out.append(entry)
    out.sort(key=lambda x: x["M1"])
    with open(OUT, "w") as f:
        json.dump(out, f, separators=(",", ":"))
    print(f"wrote {len(out)} comets to {OUT}")
    for c in out:
        print(f"  M1={c['M1']:<5} e={c['e']:<8} q={c['q']:<7} {c['name']}")


if __name__ == "__main__":
    main()
