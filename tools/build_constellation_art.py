#!/usr/bin/env python3
"""Build the bundled constellation artwork set from Stellarium's modern sky culture.

Outputs:
  - app/src/main/assets/constellation_art.json  (per figure: image file + 3 anchors)
  - app/src/main/assets/constellation_art/*.png  (the artwork)

Each anchor is [fx, fy, ra, dec]: a fractional position in the image (0..1, top-left
origin) tied to a star's J2000 RA/Dec, so the app can warp the artwork onto the sky
by an affine map from the three image points to the three projected star positions.

Star positions (HIP -> RA/Dec) come from d3-celestial; the artwork and anchors come
from Stellarium's modern sky culture (both GPL/CC, GitHub-hosted).

Run from the repository root:
    python3 tools/build_constellation_art.py
"""
import json
import os
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SC = "https://raw.githubusercontent.com/Stellarium/stellarium/master/skycultures/modern"
STARS = "https://raw.githubusercontent.com/ofrohn/d3-celestial/master/data/stars.8.json"
OUT_JSON = os.path.join(ROOT, "app", "src", "main", "assets", "constellation_art.json")
OUT_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "constellation_art")


def fetch(url, timeout=120):
    return urllib.request.urlopen(url, timeout=timeout).read()


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    hip = {}
    stars = json.loads(fetch(STARS).decode("utf-8"))
    for ft in stars["features"]:
        i = ft.get("id")
        if isinstance(i, int):
            hip[i] = ft["geometry"]["coordinates"]  # [ra, dec] degrees

    idx = json.loads(fetch(SC + "/index.json").decode("utf-8"))
    out = []
    total = 0
    for c in idx["constellations"]:
        img = c.get("image")
        if not img:
            continue
        w, h = img["size"]
        anchors = []
        ok = True
        for a in img["anchors"]:
            rd = hip.get(a["hip"])
            if rd is None:
                ok = False
                break
            x, y = a["pos"]
            anchors.append([round(x / w, 4), round(y / h, 4), round(rd[0], 5), round(rd[1], 5)])
        if not ok or len(anchors) != 3:
            continue
        base = os.path.basename(img["file"])
        # Download the artwork.
        try:
            data = fetch(SC + "/" + img["file"])
        except Exception as e:
            print("skip", base, e)
            continue
        with open(os.path.join(OUT_DIR, base), "wb") as f:
            f.write(data)
        total += len(data)
        out.append({"f": base, "a": anchors})

    out.sort(key=lambda e: e["f"])
    with open(OUT_JSON, "w") as f:
        json.dump(out, f, separators=(",", ":"))
    print(f"wrote {len(out)} figures, artwork {total / 1_000_000:.1f} MB -> {OUT_DIR}")


if __name__ == "__main__":
    main()
