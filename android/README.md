# Talha Gohar Apps — personal app store (v1.4.3)

**Status:** v1.4.3 built, verified and **released 2026-11-28**:
package `com.gohar.store` v1.4.3 (code 22), targetSdk 36, minSdk 26.
Author bio: Maliki asaneed line added — education in Maliki fiqh from
authoritative teachers, asanid (chains of transmission) obtained.
In-app self-update (checks `store-version.json` on launch / "Check for
updates", downloads its own APK from R2, verifies SHA-256, installs
from inside the app; all updates optional). Verified:
17,257,791 B, apksigner PASS (same cert), MainActivity and SelfUpdate
in classes.dex, zipalign OK, SHA-256 `0e5ad31176f8c909f7e1104bde7765cd9d56794dcd4d4341e5bdc60ebf36996b`.
Repo: `TalhaGoharWeb/talha-gohar-apps` (public, source + `catalog.json`).
**Phone acceptance still pending** — install it on a real phone and
confirm the tagline change (design docs are untracked; they live in
`design/`, not in git).

v1.3.4 changes, over v1.3.3 (in progress, not released yet):
**manual Update in the developer console** — each app row now has an
**Update** button next to Edit/Delete. It opens a small dialog with
only the fields a version bump needs (version name/code, APK URLs,
size in MB, what's new in English + Urdu), prefilled from the app.
It stages the change like any console edit — **Publish changes**
still makes it live. Also the catalogue now lists 5 apps: Maliki
Fiqh Hub v2.0.0 (coming soon — no public release APK yet, and note
its package is `com.hijazi.maliki_fiqh_admin`, not the v1 Hub
package, so v1 users get a fresh install rather than an update),
Nigaarish 1.5.71, Kisan Dost 1.0.15, Maliki Prayer Times 1.3.0
(latest *published* release — local 1.3.9 has no public link yet),
and Mera Roznamcha 1.1.4 (coming soon).

v1.3.3 changes, over v1.3.2: **new launcher icon** from Talha's latest
brand image — full-bleed dark navy (no rounded-square inset, no white
corners at all); legacy mipmaps are the full mark scaled, the adaptive
foreground is the central emblem only (no text). The previous
rounded-square mark is kept as `brand/logo-icon-v1.png`. Nothing else
changed — font, toolbar, hero and catalogue are untouched.

v1.3.2 adds, over v1.3.1: **Urdu font → Jameel Noori Nastaleeq** (the
classic flowing Urdu script the user asked for; v1.3.1's Naskh was
identical to Android's system font, so the user saw no change), with
locale dimens so the tall script doesn't clip — toolbar 72dp + 15sp
title, hero and download buttons 44dp, app-row icons 96dp, chips 40dp
min height (all in `values-ur/dimens.xml`; English unchanged), and the
ur `lineSpacingMultiplier` back to 1.0. The 13.8 MB font grows the APK
(the release build is 14 MB — the font compresses; the debug build was
17 MB). **new appbar logo** (Talha's
avatar portrait, transparent background, `ic_store_logo`
densities); **new launcher icon** from `brand/logo-icon.png` (dark
navy `#00163B`; adaptive foreground is the central emblem only, no
text; the cream mark is kept as `brand/logo-mark-cream.png`); **toolbar
title fixed** (MainActivity now calls `setDisplayShowTitleEnabled(false)`
— the ActionBar title duplicated the custom logo + name layout); hero
buttons raised to 44dp in Urdu so descenders stop clipping.

Signing key: new `gohar-store.jks` in `android/` (git-ignored, keep
it — future store updates must use the same key).

v1.3.1 adds, over v1.3.0: **compact, dismissible featured hero** (×
hides it, saved in prefs; ⋮ → "Show featured app" brings it back),
**Urdu font** (Noto Naskh Arabic variable font, values-ur theme with
1.15 line spacing; Latin UI unchanged), **pause / resume / cancel
downloads** (new resumable `AppDownloader`: `Range` requests, `.part`
files in cacheDir/downloads/, state persisted in "downloads" prefs;
replaces DownloadManager), and the **cream logo** (cream avatar disc
from the brand board — launcher, adaptive background `#F5F1E6`,
toolbar; dark variant kept as `brand/logo-mark-dark.png`). Sett and
Nigaarish catalogue icons were also refreshed (new icon files, same
URLs, no code change).

v1.3.0 adds, over v1.2.0: **owner-only sign-in** for the developer
console — the console menu item is hidden unless the owner is signed
in (email + password, or Continue with Google via the system account
chooser; first run sets the owner up; SHA-256 salted password, stored
only on the phone; gate enforced on-device, the GitHub token is what
actually authorizes a publish). PRIVACY X v1.1.0 was also published to
`TalhaGoharWeb/releases` and activated in the catalogue in the same
window, so the store now has three downloadable apps.

v1.2.0 adds, over v1.1.0: the **new logo and brand identity** (Talha's
avatar mark from his brand board — launcher icon, toolbar logo,
`brand/` assets), **Play Store-style screenshots** (swipeable gallery
+ full-screen viewer; Maliki Fiqh Hub has 10 real screenshots, others
none yet) with **real app icons** (`iconUrl` in `catalog.json`,
`icons/` in the repo), the **developer console** (⋮ → Developer
console: add / edit / delete apps, publish to GitHub via a PAT stored
only on the phone), the **Urdu app name fixed to طلحہ گوہر ایپس** with
Urdu app names in the catalogue (`nameUr`), and **live download
progress** in app rows.

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
  `https://raw.githubusercontent.com/TalhaGoharWeb/talha-gohar-apps/main/catalog.json`
  first (8 s timeout), falls back to the copy bundled in the APK.
  The source is shown at the bottom of the home screen.
- Downloads use Android's DownloadManager into the store's own folder,
  then install through the system installer via FileProvider. First
  install asks the user to allow installs from Talha Gohar Apps once —
  normal for stores outside Google Play. It never updates silently.

## Apps in v1 (all verified 2026-11-26)

| App | Version | Download |
|---|---|---|
| Maliki Fiqh Hub | 2.0.0 (code 4) | **Coming soon** — no public release APK published yet (package changed to `com.hijazi.maliki_fiqh_admin`, so v1 users install fresh) |
| Nigaarish | 1.5.71 (code 80) | R2 arm64 APK, 75,396,480 B — https://pub-068c48d4151d4dd293c2f7554cca1a5b.r2.dev/nigaarish-1.5.71-80-arm64.apk |
| Kisan Dost | 1.0.15 (code 16) | R2 APK, 77,509,484 B — https://pub-3ff3b8ab882d499aacd31ce326d647f9.r2.dev/kisan-dost-v1.0.15.apk |
| Maliki Prayer Times | 1.3.0 (code 6) | GitHub Release, 64,643,343 B — TalhaGoharWeb/maliki-prayer-times-app v1.3.0+6 |
| Mera Roznamcha | 1.1.4 (code 7) | **Coming soon** |

Package names and version codes were read from the real APKs/pages with
`curl -I -L` (HTTP 200 + Content-Length) on 2026-11-27, and the
Nigaarish/Kisan Dost links from their live download pages.

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
  `talha-gohar-apps` GitHub repo, and every installed store refreshes
  from it. Same content is bundled in the APK as a fallback.
- `PUBLISH-GUIDE.md` — how to publish / update one of your apps.
- `release/` — built APKs for sharing.
