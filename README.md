# Starmap

A point-and-identify star map for Android. Hold your phone up to the sky and
Starmap shows the stars, constellations, the Sun and the Moon in the exact
direction you're pointing — it moves with the phone using the compass,
accelerometer and GPS. Built and tuned for the **Google Pixel 9a** (works on any
phone running Android 8.0+ with the standard orientation sensors).

> Naming: the GitHub repo is **starmap**; the app's display name is **Starmap**
> with the Android application id `com.starmap.app`.

## Features

- **Live sky that tracks the phone** — fused rotation-vector sensor + GPS so the
  view pans as you move, with true-north correction from your local magnetic
  declination.
- **8,920 naked-eye stars** (to magnitude 6.5) with names, colour-true rendering
  from each star's B–V index, and magnitude-scaled brightness.
- **89 constellation figures** with optional names.
- **Sun & Moon** computed with a low-precision ephemeris (the Moon is corrected
  for topocentric parallax and drawn with its current phase).
- **Horizon line + compass markers** (N/E/S/W and intercardinals); objects below
  the horizon can be hidden so the ground reads as solid.
- **Settings** — magnitude limit, label density, field of view, every overlay
  toggle, **night mode** (red, preserves dark adaptation), and manual location.
- **Pinch to zoom** the field of view.
- **Offline first** — the naked-eye catalog and constellations are bundled in the
  APK, so the app is fully usable with no network. An **extended catalog**
  (41,487 stars to magnitude 8.0) can be downloaded on demand.
- **In-app self-update** — checks GitHub Releases for a newer version and, when
  found, downloads the APK and launches the installer *from inside the app*. Because
  every release is signed with the same key, the new version installs **over the
  top** — no uninstall, settings preserved.

## How it works

Each star is stored as a fixed unit vector in the equatorial frame. A couple of
times per second the app builds an East/North/Up basis from the local sidereal
time and latitude and rotates every object into the observer's horizontal frame.
The render loop then only needs the live device orientation: it converts the
phone's basis vectors to true north (via `GeomagneticField`) and projects the
cached vectors through a perspective camera. The maths is validated against known
references in `app/src/test` (e.g. Polaris altitude ≈ latitude; solar-noon
altitude = 90 − lat + dec).

## Building

Everything builds with the Gradle wrapper — no local Android Studio required.

```bash
./gradlew assembleDebug        # debug APK -> app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # run the astronomy unit tests
./gradlew assembleRelease      # release APK (debug-signed unless you add a keystore)
```

Requirements: JDK 17, Android SDK with platform 35 / build-tools 35.0.0.
Minimum device: Android 8.0 (API 26); target: API 35.

### Signing (and why it matters for updates)

Android only lets an APK update an installed app **in place** if both are signed
with the **same certificate**; otherwise it refuses with `INSTALL_FAILED_UPDATE_
INCOMPATIBLE` and the user must uninstall first (losing data). So every Starmap
release must use one stable key.

Signing key resolution (highest priority first):

1. **CI secrets** — set `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`,
   `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` (e.g. GitHub Actions secrets).
   Preferred if you ever make the app public.
2. **Committed keystore** — `app/starmap-release.jks` described by
   `keystore.properties`. Committed on purpose so releases are stably signed with
   zero setup (fine for a private repo; rotate later if needed).
3. **Debug key** — fallback for local dev only; APKs signed this way will *not*
   update an existing install in place.

The committed key's certificate fingerprint is fixed, so all releases are
mutually update-compatible.

## Self-update: how it works and how to verify

1. **About → Check for updates** queries `…/releases/latest` and compares the tag
   to `BuildConfig.VERSION_NAME` (`UpdateChecker.isNewer`, unit-tested).
2. If newer, **Download & install** fetches the release's `.apk` asset to
   `filesDir/updates/` and launches the system installer via a `FileProvider`
   (the app holds `REQUEST_INSTALL_PACKAGES`; the user grants "install unknown
   apps" once).
3. Same signature ⇒ Android installs it **as an update**, preserving app data.

**Requirements:** the repository (its Releases API + release assets) must be
reachable by the app. For a **public** repo this works with no token. For a
private repo the in-app check/download need auth, so the app falls back to opening
the release page in your browser.

**To verify end-to-end on a device:**

```bash
# 1. Cut v1.0.0 (Actions ▸ Release ▸ bump=none) and install that APK on the phone.
# 2. Cut v1.0.1 (Actions ▸ Release ▸ bump=patch).
# 3. In the app: About ▸ Check for updates ▸ Download & install.
#    It should update in place — open About again and confirm build 1.0.1,
#    with your settings still intact.
```

The signing half is verifiable without a device — every release carries the same
certificate; confirm with:

```bash
keytool -list -v -keystore app/starmap-release.jks -storepass starmap | grep SHA256
```

## Continuous integration, versioning & releases

- **`Android CI`** (`.github/workflows/android-ci.yml`) builds the debug APK, runs
  unit tests and lint on every push/PR, and uploads the APK as an artifact.
- **`Release`** (`.github/workflows/release.yml`) publishes a GitHub Release with
  the APK attached. Two ways to trigger it:
  1. **Bump & release from the Actions tab** — run the *Release* workflow and pick
     `patch` / `minor` / `major`. It increments `version.properties`, commits it to
     `main`, tags `vX.Y.Z`, builds and publishes the release.
  2. **Tag it yourself** — `git tag v1.2.3 && git push origin v1.2.3`.

`version.properties` is the single source of truth for `VERSION_NAME` /
`VERSION_CODE`; release builds receive the version via Gradle properties and the
`VERSION_CODE` is set from the CI run number so it always increases.

## Catalog data

The catalogs are generated from open data by `tools/build_catalog.py` — see
[`tools/README.md`](tools/README.md). Sources: the **HYG database v4.1**
(astronexus, CC BY-SA 4.0) and **d3-celestial** constellation figures (Olaf Frohn,
BSD-2-Clause).

## Project layout

```
app/src/main/java/com/starmap/app/
  astro/      coordinate maths, Sun/Moon ephemeris, catalog parsing
  sensors/    rotation-vector orientation + location providers
  sky/        per-update sky model, projection, the Compose canvas, ViewModel
  settings/   DataStore-backed preferences
  update/     GitHub release update checker
  catalog/    bundled + downloadable catalog manager
  ui/         Compose screens (sky HUD, settings, downloads, about)
app/src/main/assets/   bundled stars.json + constellations.json
catalog/               downloadable stars_ext.json
tools/                 catalog build script
```

## License

MIT for the app code; bundled catalog data retains its upstream licenses. See
[`LICENSE`](LICENSE).
