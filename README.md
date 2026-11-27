<div align="center">

<img src="brand/logo-icon.png" alt="Talha Gohar Apps logo" width="180" />

# Talha Gohar Apps

**One store for all of Talha's Android apps — browse, download and update, like a small Play Store.**

[![Latest release](https://img.shields.io/badge/release-v1.3.3-0B2545)](https://github.com/TalhaGoharWeb/talha-gohar-apps/releases/tag/v1.3.3)
[![Android](https://img.shields.io/badge/Android-8.0%2B_API_26-brightgreen)](https://developer.android.com/about/versions/oreo)
[![Kotlin](https://img.shields.io/badge/Kotlin-AGP_9.1-7F52FF)](https://kotlinlang.org/)
[![Material 3](https://img.shields.io/badge/Material_3-1.12.0-6750A4)](https://m3.material.io/)
[![Target SDK](https://img.shields.io/badge/targetSdk-36-blue)](https://developer.android.com/about/versions/16)
[![Urdu](https://img.shields.io/badge/Urdu-Jameel_Noori_Nastaleeq-gold)](#features)

**Latest release:** [v1.3.3](https://github.com/TalhaGoharWeb/talha-gohar-apps/releases/download/v1.3.3/Talha-Gohar-Apps-v1.3.3.apk) · Package `com.gohar.store` · Android 8.0+ · ~14 MB · Urdu name: **طلحہ گوہر ایپس**

</div>

---

## What this is

**Talha Gohar Apps** is a personal Android app store. No server, no accounts, no payments, no tracking, no ads — the entire store runs off one JSON file and GitHub Releases. It is free forever, and it works offline from a bundled catalogue when there is no connection.

## Apps in the store

| App | Version | Status |
|-----|---------|--------|
| Maliki Fiqh Hub | 2.0.0 | Coming soon — no public release APK yet |
| Nigaarish | 1.5.71 | Download |
| Kisan Dost | 1.0.15 | Download |
| Maliki Prayer Times | 1.3.9 | Download |
| Mera Roznamcha | 1.1.4 | Coming soon |

**Design principles**

| Principle | What it means in practice |
|---|---|
| **Catalogue-driven** | Everything on screen comes from [`catalog.json`](catalog.json). No app is hard-coded into the UI. |
| **Zero backend** | GitHub is the server: `catalog.json` for data, Releases for APKs, the Contents API for publishing. |
| **Honest UI** | No fake ratings, download counts or placeholder screenshots. An app is either downloadable or marked *Coming soon*. |
| **Owner-only publishing** | The developer console appears only after the owner signs in. Everyone else gets a read-only store. |
| **Offline-first** | A bundled copy of the catalogue ships inside the APK; the live copy is fetched when online. |

---

## Features

- **Discover / Updates tabs** — browse every app, or only the ones installed on the phone, with an update badge when a newer `versionCode` appears in the catalogue.
- **Play Store-style app pages** — real icons, swipeable screenshot gallery with a full-screen viewer, What's New, About, category and size.
- **Install / Update / Open** — APKs download straight from GitHub Releases, then the system installer takes over (Android asks once to allow installs from this store).
- **Resumable downloads** — downloads are fetched with HTTP `Range` requests into `.part` files, so they can be **paused, resumed and cancelled** and survive an interrupted connection. Download state is persisted, so a resume works even after the app restarts.
- **Search, categories, pull-to-refresh** — filter by name or category; swipe down to re-fetch the live catalogue.
- **Urdu + English** — a full Urdu version in the same APK (اردو / English switch in the ⋮ menu, or follows the phone language and Android 13 per-app language settings). Urdu text is set in **Jameel Noori Nastaleeq**, with per-locale dimensions so the tall Nastaliq script never clips.
- **Featured hero** — the featured app gets a compact hero card that can be dismissed (⋮ → *Show featured app* brings it back).
- **Developer console** — the owner manages the whole catalogue from inside the app: add, edit or remove apps, then publish. The change goes live for every user within seconds. See [PUBLISH-GUIDE.md](PUBLISH-GUIDE.md).

---

## Architecture

```mermaid
flowchart LR
    subgraph Phone [Talha Gohar Apps]
        UI[Discover / Updates / Detail]
        DL[AppDownloader<br/>HTTP Range + .part resume]
        CAT[Catalog<br/>live + bundled fallback]
        CON[Developer console<br/>owner-only]
    end
    GH[(GitHub)]
    CAT -- GET --> RAW[catalog.json<br/>raw.githubusercontent.com]
    UI -- images --> RAW
    DL -- apkUrl --> REL[GitHub Releases<br/>APK files]
    DL --> PI[System Package Installer]
    CON -- GET / PUT --> API[Contents API<br/>catalog.json]
    CON -- PAT --> API
```

**Data flow**

1. On launch the app fetches `catalog.json` from `raw.githubusercontent.com/TalhaGoharWeb/talha-gohar-apps/main/catalog.json`. If the network fails, it falls back to the bundled copy in `assets/catalog.json`.
2. Icons and screenshots are loaded from the same repo (`icons/`, `screenshots/<app-id>/`) with a disk + memory cache and downsampling.
3. Tapping **Install / Update** downloads the APK from the app's `apkUrl` (a GitHub Release asset) with resumable HTTP Range requests, then hands the file to the system installer through a `FileProvider`.
4. The owner signs in (⋮ → *Owner sign-in*), opens the **Developer console**, edits an app, and taps **Publish** — the app writes the updated `catalog.json` back to the repo through the GitHub Contents API. Every user's store picks the change up on its next refresh.

**Update detection** — the Updates tab compares the installed app's `versionCode` (from `PackageManager`) against the catalogue's `versionCode`. An update is offered only when the catalogue's code is higher.

---

## Tech stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | Classic Views + Material 3 (`material:1.12.0`, `appcompat:1.7.1`, `core-ktx:1.19.1`) |
| Build | Android Gradle Plugin 9.1.0 · Gradle 9.3.1 · JDK 17 |
| SDK | `compileSdk 36` · `targetSdk 36` · `minSdk 26` (Android 8.0+) |
| Networking | `HttpURLConnection` — no networking libraries, no tracking SDKs |
| Downloads | Custom `AppDownloader`: HTTP `Range`, `.part` files, persisted state |
| Images | Custom `ImageLoader`: disk + memory cache, downsampling, no new dependencies |
| Urdu type | **Jameel Noori Nastaleeq** (freeware), bundled in `res/font/` |
| Signing | Release keystore `gohar-store.jks` (owner-held, git-ignored) |
| Distribution | GitHub Releases · catalogue at `raw.githubusercontent.com` |

**No dependencies beyond AndroidX.** The app deliberately avoids third-party SDKs: no analytics, no ads, no crash reporting, no accounts.

---

## The catalogue — `catalog.json`

The store is data, not code. Every app is one JSON object; the store renders whatever the file contains.

```json
{
  "catalogVersion": 1,
  "storeName": "Talha Gohar Apps",
  "tagline": "All my apps, in one place.",
  "updated": "2026-11-27",
  "apps": [
    {
      "id": "maliki-fiqh-hub",
      "name": "Maliki Fiqh Hub",
      "nameUr": "مالکی فقہ ہب",
      "packageName": "com.hijaziapps.maliki_fiqh_user",
      "developer": "Talha Gohar",
      "category": "Education",
      "categoryUr": "تعلیم",
      "tagline": "Maliki fiqh, articles and quizzes.",
      "taglineUr": "مالکی فقہ، مضامین اور کوئز۔",
      "description": "Maliki Fiqh Hub brings the bilingual Maliki fiqh articles and quiz database together in one app …",
      "descriptionUr": "مالکی فقہ ہب میں دو لسانی مالکی فقہ مضامین اور کوئز ڈیٹا بیس یکجا ہیں …",
      "versionName": "1.0",
      "versionCode": 1,
      "minSdk": 21,
      "sizeBytes": 47091736,
      "apkUrl": "https://github.com/TalhaGoharWeb/releases/releases/download/v1.0.0/Maliki.Fiqh.Hub.apk",
      "iconUrl": "https://raw.githubusercontent.com/TalhaGoharWeb/talha-gohar-apps/main/icons/maliki-fiqh-hub.png",
      "iconColor": "#B45309",
      "iconColor2": "#D97706",
      "iconLetter": "M",
      "featured": true,
      "whatsNew": ["First public release"],
      "whatsNewUr": ["پہلی عوامی ریلیز"],
      "screenshots": ["https://raw.githubusercontent.com/TalhaGoharWeb/talha-gohar-apps/main/screenshots/maliki-fiqh-hub/01-home-urdu.jpg"]
    }
  ]
}
```

**Field rules**

| Field | Required | Notes |
|---|---|---|
| `id`, `name`, `packageName` | Yes | `id` is unique; `packageName` is the Android package |
| `versionName`, `versionCode` | Yes | **The `versionCode` must rise** or users see no update |
| `apkUrl` | For downloads | `null` + `comingSoon` text = *Coming soon* (no fake download button) |
| `sizeBytes` | Yes | Exact byte size of the APK; shown on the app page |
| `iconUrl` / `iconColor` + `iconColor2` / `iconLetter` | — | Real icon if `iconUrl` is set; otherwise a gradient tile with the letter |
| `nameUr`, `taglineUr`, `descriptionUr`, `categoryUr`, `whatsNewUr`, `comingSoonUr` | For Urdu | The Urdu UI renders these; English falls back when a field is absent |
| `featured` | — | Exactly one app should be featured; it gets the hero card |
| `screenshots` | — | Real screenshots only — never placeholders. Empty list hides the gallery |
| `minSdk` | — | Minimum Android API the app needs (informational) |

The two copies — repo-root `catalog.json` and `android/app/src/main/assets/catalog.json` — must stay **byte-identical**.

---

## Build from source

**Requirements:** JDK 17, Android SDK with API 36, Gradle 9.3.1, Android Gradle Plugin 9.1.0. There is no Gradle wrapper in this repo; invoke Gradle directly.

```bash
cd android
gradle assembleDebug      # debug APK, unsigned
gradle assembleRelease    # release APK — needs the owner's key (below)
```

**Release signing** — `android/key.properties` (git-ignored) points at the owner's keystore:

```properties
storeFile=gohar-store.jks
storePassword=<owner only>
keyAlias=gohar-store
keyPassword=<owner only>
```

The keystore is **not** in this repo. A release build without it cannot be signed with the store key, and an APK signed with any other key will not install as an update over the published app — Android requires the same signing certificate for updates.

### Verify a release

Every release is verified before publishing — signature, alignment, content and the served file:

```bash
# Signature (v2 + v3) and the signing certificate
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk

# 16 KB alignment
zipalign -c -v 4 app/build/outputs/apk/release/app-release.apk

# The file GitHub serves must be byte-identical to the file that was built
sha256sum app/build/outputs/apk/release/app-release.apk
curl -sL -o /tmp/served.apk https://github.com/TalhaGoharWeb/talha-gohar-apps/releases/download/v1.3.3/Talha-Gohar-Apps-v1.3.3.apk
sha256sum /tmp/served.apk
```

**v1.3.3 reference values**

| Check | Value |
|---|---|
| Version | 1.3.3 (code 7), package `com.gohar.store` |
| Size | 14,575,598 bytes |
| SHA-256 | `c34fccd32d8e5c4f7303f76162ef0747f440bbca80a3387f85f74ba807f4a8e0` |
| Signature | APK Signature Scheme v2 + v3, 1 signer, CN=Talha Gohar |
| Certificate SHA-256 | `b5ac0d1812458dd006ecfde3f462fc71b7319b9d5df3c54bc0bad190c10addf8` |
| Alignment | `zipalign` verified |

---

## Publishing an app

1. Upload the APK to a GitHub Release in that app's own repo.
2. Edit `catalog.json` — **the `versionCode` must rise** — or use the developer console inside the app (⋮ → *Developer console*), which validates the entry and writes the file for you.
3. Push. Users get the new version on their next refresh.

Full steps, including the console's token setup, are in [PUBLISH-GUIDE.md](PUBLISH-GUIDE.md). Build and verification notes live in [android/README.md](android/README.md).

---

## Project structure

```
.
├── catalog.json                  # The store itself — live catalogue (source of truth)
├── README.md                     # This file
├── PUBLISH-GUIDE.md              # Publishing flow, console token setup
├── brand/                        # Logo assets
│   ├── logo-icon.png             # Current launcher icon (full-bleed navy brand mark)
│   ├── logo-icon-v1.png          # Previous rounded-square mark
│   ├── logo-mark-cream.png       # Earlier cream avatar mark
│   ├── logo-mark-dark.png        # Earlier dark avatar mark
│   └── logo-board.jpg            # Full brand board
├── icons/                        # Per-app icons (real artwork, 512 px)
├── screenshots/<app-id>/         # Real Play Store-style screenshots, 10 per app
├── android/
│   ├── README.md                 # Build + release notes per version
│   ├── app/src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/gohar/store/
│   │   │   ├── MainActivity.kt          # Tabs, search, chips, featured hero
│   │   │   ├── DetailActivity.kt        # App page: gallery, About, downloads
│   │   │   ├── Catalog.kt               # Catalogue model + live/bundled loading
│   │   │   ├── GitHubCatalog.kt         # Contents API: read/write catalog.json
│   │   │   ├── AppDownloader.kt         # Resumable HTTP Range downloads
│   │   │   ├── ImageLoader.kt           # Disk + memory image cache
│   │   │   ├── AuthManager.kt / AuthActivity.kt   # Owner sign-in (email/password, Google)
│   │   │   ├── AdminActivity.kt / AdminEditActivity.kt  # Developer console
│   │   │   ├── ScreenshotViewerActivity.kt
│   │   │   └── StoreActions.kt          # Install / update / open intents
│   │   ├── res/
│   │   │   ├── font/jameel_noori_nastaleeq.ttf   # Urdu typeface (13.8 MB)
│   │   │   ├── values-ur/               # Urdu strings, theme + dimensions
│   │   │   ├── mipmap-*/                # Launcher icons (legacy + adaptive)
│   │   │   └── drawable-*/              # Toolbar avatar, chips, badges
│   │   └── assets/catalog.json          # Bundled offline catalogue (== root copy)
└── release/                      # Local release APKs (git-ignored)
```

---

## Security & privacy

- **Signing key** — release APKs are signed with the owner's keystore, which never leaves the owner's machine and is never committed. The certificate fingerprint above is the public check that an APK is a genuine update.
- **Owner gate** — the developer console is hidden unless the owner is signed in (email + salted SHA-256 password, or a linked Google account). The gate is enforced **on-device**; the GitHub personal access token is what actually authorises publishing, and it lives only in the app's private `SharedPreferences` (last 4 characters shown, clear-token option in the console).
- **No data collection** — the app sends nothing anywhere except: fetching `catalog.json`, icons and screenshots from GitHub, and downloading APKs from GitHub Releases. No analytics, no ads, no accounts.
- **No server to attack** — there is no backend. The worst case for a compromised catalogue is a wrong listing, and the catalogue is versioned in git.

---

## Version history

| Version | Code | What changed |
|---|---|---|
| 1.0.0 | 1 | First store: Sett + Maliki Fiqh Hub downloadable; PRIVACY X + Nigaarish *Coming soon* |
| 1.1.0 | 2 | Renamed **Talha Gohar Apps**; app-store redesign; full Urdu version in the same APK |
| 1.2.0 | 3 | Correct Urdu name طلحہ گوہر ایپس; brand-board logo; screenshot gallery; developer console |
| 1.3.0 | 4 | Owner-only sign-in gating the developer console |
| 1.3.1 | 5 | Compact dismissible hero; pause / resume / cancel downloads; cream avatar logo |
| 1.3.2 | 6 | **Jameel Noori Nastaleeq** Urdu font; new dark brand icon + appbar avatar; toolbar title fix; taller buttons; repo renamed to `talha-gohar-apps` |
| **1.3.3** | **7** | **New full-bleed app icon** from the latest brand mark (adaptive foreground = central emblem, no text) |

---

## Brand

The logo is Talha's avatar mark — navy, teal and gold. The launcher icon is the **dark navy brand mark** [`brand/logo-icon.png`](brand/logo-icon.png) (avatar + rings + *Talha Gohar APPS*, full-bleed navy, no white corners at all); the adaptive icon uses the central emblem only (no text). The toolbar shows the plain avatar portrait (transparent background, from Talha's own image). Earlier marks are kept in `brand/` (`logo-icon-v1.png`, `logo-mark-cream.png`, `logo-mark-dark.png`); the full board is [`brand/logo-board.jpg`](brand/logo-board.jpg).

Urdu type: **Jameel Noori Nastaleeq** (freeware — free of charge for Urdu lovers; personal use).
