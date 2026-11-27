# Talha Gohar Apps

![Talha Gohar Apps logo](brand/logo-mark.png)

**Talha Gohar Apps** is Talha's own personal app store for Android — one place where
people can see, download and update all of his apps, just like a small Play Store.
No server, no accounts, no payments, no tracking. Free forever.

**Latest release:** [v1.3.0](https://github.com/TalhaGoharWeb/gohar-store/releases/download/v1.3.0/Talha-Gohar-Apps-v1.3.0.apk)
· Package `com.gohar.store` · Android 8.0+ · ~7 MB

## What's inside

- **Discover / Updates tabs** — browse all apps, or just the ones installed on your phone with an update badge
- **Real app icons, Play Store-style screenshots** — swipeable gallery on each app's page, tap to view full screen
- **Search, categories, pull-to-refresh**
- **Install / Update / Open** — downloads straight from Talha's GitHub Releases, then the system installer (Android asks once to allow installs from this store)
- **Urdu + English** — full Urdu version built in (اردو / English switch in the ⋮ menu, or follows the phone language). The store's Urdu name is **طلحہ گوہر ایپس**
- **Developer console** — Talha manages the whole catalogue from inside the app: add, edit or remove apps and publish the change so every user's store picks it up (see [PUBLISH-GUIDE.md](PUBLISH-GUIDE.md))

## Apps in the store

| App | Version | Status |
|-----|---------|--------|
| Maliki Fiqh Hub | 1.0 | Download |
| Nigaarish | 1.5.70 | Coming soon |

## How it works

The store is catalogue-driven. Everything it shows comes from one file,
[`catalog.json`](catalog.json), fetched live from this repo (with a bundled copy as
offline fallback). Icons live in [`icons/`](icons/), screenshots in
[`screenshots/<app-id>/`](screenshots/). To publish or update an app:

1. Upload the APK to a GitHub Release in that app's repo.
2. Edit `catalog.json` — **the versionCode must rise** or users see no update —
   or use the developer console inside the app (⋮ → Developer console).
3. Push. Users get the new version on their next refresh.

Full steps, including the console's token setup, are in
[PUBLISH-GUIDE.md](PUBLISH-GUIDE.md). Build and verification notes are in
[android/README.md](android/README.md).

## Brand

The logo is Talha's avatar mark — navy, teal and gold. The clean mark in
use is the **cream variant** from the brand board,
[`brand/logo-mark.png`](brand/logo-mark.png) (cream disc on cream
`#F5F1E6`, launcher + toolbar); the dark variant is kept as
[`brand/logo-mark-dark.png`](brand/logo-mark-dark.png). The full brand
board is [`brand/logo-board.jpg`](brand/logo-board.jpg).

Urdu type: Noto Naskh Arabic (SIL Open Font License 1.1).

## Note on the older docs

The `docs/` folder and `backend/` hold the **paused** GOHAR marketplace
architecture (a separate, bigger multi-developer store). That project is stopped
for now — this repo and this app are the personal store only. The old pack index
is preserved at [`docs/00-architecture-pack-index.md`](docs/00-architecture-pack-index.md).
