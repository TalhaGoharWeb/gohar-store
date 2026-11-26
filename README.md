# Talha Gohar Apps — personal app store (v1.0.0)

**Status:** v1.1.0 built 2026-11-26 and **verified**: package
`com.gohar.store` v1.1.0 (code 2), targetSdk 36, minSdk 26,
apksigner v2+v3 VERIFIED (signer CN=Talha Gohar), 16 KB alignment OK,
no preview-platform stamp (compileSdkVersionCodename `16`). Served
file re-downloaded from GitHub and SHA-256 matches local
(`219764ba…`). **Phone acceptance still pending** — install it on a
real phone and confirm the catalogue, a download and an update.
Repo: `TalhaGoharWeb/gohar-store` (public, source + `catalog.json`),
release: https://github.com/TalhaGoharWeb/gohar-store/releases/download/v1.1.0/Talha-Gohar-Apps-v1.1.0.apk
Signing key: new `gohar-store.jks` in `android/` (git-ignored, keep
it — future store updates must use the same key).

v1.1.0 adds, over v1.0.0: the **Talha Gohar Apps** name, the redesigned
store look (featured hero, category chips, Updates tab, gradient app
icons, richer detail pages) and a **full Urdu version** — every screen
switches to Urdu (RTL) from the top menu, or automatically when the
phone is set to Urdu; catalogue names, descriptions and what's-new
have Urdu text too (`taglineUr` / `descriptionUr` / `categoryUr` /
`whatsNewUr` / `comingSoonUr` in `catalog.json`).

This is the *small personal* store, not the big GOHAR marketplace
(architecture pack in `docs/`, Milestone 2 stopped on Talha's word).
Only Talha's own apps are listed. No accounts, no payments, no server,
no other developers.

## What it does

- Home screen: featured app (Sett), search, and a card for every app
  with Install / Update / Open / Coming soon.
- App detail: description, what's new, version, size, package name,
  and the same Install / Update / Open button with a download progress bar.
- Update check: compares the installed version code on the phone with
  `versionCode` in `catalog.json` and shows an **UPDATE** badge.
- Catalogue: tries
  `https://raw.githubusercontent.com/TalhaGoharWeb/gohar-store/main/catalog.json`
  first (8 s timeout), falls back to the copy bundled in the APK.
  The source is shown at the bottom of the home screen.
- Downloads use Android's DownloadManager into the store's own folder,
  then install through the system installer via FileProvider. First
  install asks the user to allow installs from Talha Gohar Apps once —
  normal for stores outside Google Play. It never updates silently.

## Apps in v1 (all verified 2026-11-26)

| App | Version | Download |
|---|---|---|
| Sett Browser | 0.25.0 (code 31) | Real GitHub Release URL, arm64 + armv7 |
| Maliki Fiqh Hub | 1.0 (code 1) | Real URL in `TalhaGoharWeb/releases` |
| PRIVACY X | 1.1.0 (code 2) | **Coming soon** — APK built locally, no public release yet |
| Nigaarish | 1.5.70 (code 79) | **Coming soon** — no release APK published yet |

Package names and version codes were read from the real APKs with
`aapt2 dump badging`, and both download URLs were checked live
(HTTP 302 to GitHub release assets).

## Build

```sh
export JAVA_HOME=~/workspace/toolchain/jdk17
export ANDROID_HOME=~/workspace/toolchain/android-sdk
export GRADLE_USER_HOME=~/workspace/.gradle-home
export TMPDIR=~/workspace/.tmp-build
export JAVA_TOOL_OPTIONS=-Djava.net.preferIPv6Addresses=true
cd android
~/workspace/toolchain/gradle-9.3.1/bin/gradle assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

Release signing uses `android/gohar-store.jks` + `android/key.properties`
(both git-ignored — **never commit them, never share the keystore**).
Keep that keystore: future store updates must be signed with the same
key or phones will refuse them as updates.

## Deliberate v1 choices

- **Classic Views + Material 3, not Compose.** Every dependency
  (appcompat 1.7.1, material 1.12.0, core-ktx 1.19.1, recyclerview
  1.4.0, swiperefreshlayout 1.2.0) is already proven in this VM's
  Gradle cache by the Sett build, so v1 never has to download a
  Compose compiler plugin through the sandbox proxy.
- **AGP 9.1 has Kotlin built in** — the `org.jetbrains.kotlin.android`
  plugin must *not* be declared (it fails the build).
- **compileSdk 36 with the AarMetadata gate disabled** (same as Sett):
  core-ktx 1.19.1 declares minCompileSdk 37, but the app calls no
  API-37 APIs. The APK is verified directly after the build instead.
- **No image loading library.** App icons are a coloured circle with
  the app's first letter (`iconColor` / `iconLetter` in the catalogue).
  Real icons/screenshots can come later from the catalogue.

## Files

- `android/` — the store app (Kotlin, minSdk 26, targetSdk 36).
- `catalog.json` — the catalogue. Edit this, push it to the
  `gohar-store` GitHub repo, and every installed store refreshes
  from it. Same content is bundled in the APK as a fallback.
- `PUBLISH-GUIDE.md` — how to publish / update one of your apps.
- `release/` — built APKs for sharing.
