# Building & Releasing MultiRoute

[English](BUILDING.md) | [简体中文](BUILDING_CN.md) | [← Back to README](README.md)

---

## Requirements

- **JDK**: 17
- **Android SDK**: Platform 36 (`compileSdk = 36`, `targetSdk = 36`, `minSdk = 30`)
- **Python 3**: Optional, for running release-note checks locally

---

## Build & Test Commands

```bash
# Debug / Release APK
./gradlew assembleDebug
./gradlew assembleRelease

# Unit tests (JVM only)
./gradlew testDebugUnitTest

# Release Lint check
./gradlew lintVitalRelease
```

Append `--offline` when dependencies are already cached:

```bash
./gradlew testDebugUnitTest assembleRelease --offline
```

- Output APK: `app/build/outputs/apk/release/app-release.apk`.
- R8 minification (`isMinifyEnabled = true`) is required for release builds; on `minSdk 30+`, uncompressed DEX without R8 increases APK size above 40 MB (vs. ~4 MB minified). Keep rules are defined in [`app/proguard-rules.pro`](app/proguard-rules.pro).

---

## Signing

Signing is optional for local builds:

1. Copy [`keystore.properties.example`](keystore.properties.example) to `keystore.properties` (gitignored) and fill in `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`.
2. Without `keystore.properties` or the keystore file, `assembleRelease` emits a warning and falls back to debug signing so forks and contributors can build without secrets.

> Self-built APKs use your own certificate and cannot be installed directly over an official release; uninstall the official build first or sign with the same key.

---

## `tools/probe`

[`tools/probe`](tools/probe) is a standalone test app used during device checks to measure per-UID egress. It is not part of the LSPosed module and keeps package ID `com.multiroute.probe`.

---

## Versioning & Release Workflow

[`app/build.gradle.kts`](app/build.gradle.kts) derives version fields from Git history:

- **`versionName`**: Latest reachable git tag (`git describe --tags --abbrev=0`); appends `-local` when the working tree has uncommitted changes.
- **`versionCode`**: `10000` plus the higher commit count between `origin/master` and `HEAD` (defaults to `1` without Git metadata).
- **`HOOK_BUILD_ID` & `HOTSPOT_HOOK_BUILD_ID`**: Embedded Git short SHA (`-dirty` when modified) and hotspot hook digest in `BuildConfig` so the app can detect when `system_server` still executes an older build.

Override `versionName` and `versionCode` for custom builds:

```bash
./gradlew assembleRelease -PmultiRouteVersionName=1.8.0-fork -PmultiRouteVersionCode=19999
```

### CI Releases

Official releases run in [`.github/workflows/release.yml`](.github/workflows/release.yml):

1. Add one `## vX.Y.Z - YYYY-MM-DD` section to [`CHANGELOG.md`](CHANGELOG.md) with `**中文**` followed by `**English**` (both non-empty, not marked `Unreleased` / `待发布`), and update [`xposed_update.json`](xposed_update.json) to match `version`, `versionCode`, and `zipUrl`.
2. Run local script checks:
   ```bash
   python3 -m unittest discover -s .github/scripts -p 'test_*.py'
   python3 .github/scripts/release_notes.py X.Y.Z CHANGELOG.md /tmp/NOTES.md
   ```
3. Push an annotated tag named **`<versionCode>-<versionName>`** (for example `10173-1.8.0`).
4. The workflow builds and signs the APK, checks signing certificate parity, verifies APK size <= 20 MB and metadata parity across APK, tag, and `xposed_update.json`, publishes `MultiRoute-<VersionCode>-<VersionName>.apk` on GitHub Releases, and mirrors to `Xposed-Modules-Repo/io.github.linoleic.multiroute` when `LSPOSED_REPO_TOKEN` is set.
