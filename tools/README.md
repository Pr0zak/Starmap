# Catalog build tools

`build_catalog.py` turns the upstream astronomical data into the compact catalogs
the app ships and downloads.

## Regenerating the catalogs

```bash
# 1. Fetch the source data (~33 MB, not committed)
mkdir -p tools/data
curl -L -o tools/data/hygdata_v41.csv \
  https://raw.githubusercontent.com/astronexus/HYG-Database/main/hyg/CURRENT/hygdata_v41.csv
curl -L -o tools/data/constellations.lines.json \
  https://raw.githubusercontent.com/ofrohn/d3-celestial/master/data/constellations.lines.json
curl -L -o tools/data/constellations.json \
  https://raw.githubusercontent.com/ofrohn/d3-celestial/master/data/constellations.json

# 2. Build the catalogs
python3 tools/build_catalog.py
```

Outputs (all committed to the repo):

| File | Contents | Used as |
| --- | --- | --- |
| `app/src/main/assets/stars.json` | 8,920 stars, mag ≤ 6.5 | bundled in the APK |
| `app/src/main/assets/constellations.json` | 89 constellation figures | bundled in the APK |
| `catalog/stars_ext.json` | 41,487 stars, mag ≤ 8.0 | optional in-app download |

The in-app "Offline downloads" screen fetches `catalog/stars_ext.json` straight from
this repository's `main` branch.

## Data sources & licenses

- **HYG database v4.1** — David Nash / astronexus, licensed **CC BY-SA 4.0**.
- **Constellation lines/names** — `d3-celestial` by Olaf Frohn, **BSD-2-Clause**.
