# Publishing an app in GOHAR Store — the 3-minute guide

There is no admin panel and no server. Publishing is two steps:
upload the APK, then edit one file.

## 1. Upload the APK to GitHub Releases

- **Sett:** repo `TalhaGoharWeb/sett-browser` — already has releases.
  Upload the new `Sett-Browser-vX.Y.Z-arm64.apk` (and armv7) to a new
  release tagged `vX.Y.Z`.
- **PRIVACY X / Nigaarish / a new app:** create a release in the app's
  own repo (or in the central `TalhaGoharWeb/releases` repo, the way
  Maliki Fiqh Hub v1.0.0 was published) and upload the APK there.
- Copy the asset's download link. It looks like:
  `https://github.com/<owner>/<repo>/releases/download/<tag>/<file>.apk`

## 2. Edit `catalog.json` (in this folder)

One entry per app. To **update** an app, change only these fields:

```json
{
  "versionName": "0.26.0",
  "versionCode": 32,
  "sizeBytes": 248000000,
  "apkUrlArm64": "https://github.com/.../Sett-Browser-v0.26.0-arm64.apk",
  "apkUrlArmv7": "https://github.com/.../Sett-Browser-v0.26.0-armv7.apk",
  "whatsNew": ["What changed in this version"]
}
```

To **add a new app**, copy a whole `{ ... }` block and fill in:

- `id` — short, no spaces, e.g. `"my-new-app"` (never change it later;
  the store uses it to remember downloads).
- `packageName` — from the APK: run
  `aapt2 dump badging YourApp.apk | head -1` and copy the
  `name='...'` and `versionCode='...'`.
- `versionCode` — **must go up with every release.** The store decides
  "update available" purely by comparing this number with the one
  installed on the phone. If it doesn't rise, nobody sees an update.
- `apkUrl` — the direct download link from step 1. For apps with
  separate arm64/armv7 builds, use `apkUrlArm64` / `apkUrlArmv7`
  instead and leave `apkUrl` out.
- `iconColor` — a hex colour, `iconLetter` — one letter.
- `comingSoon` — if the APK isn't published yet, set `"apkUrl": null`
  and put the reason in `comingSoon`. The store shows "Coming soon"
  instead of a dead Install button.

## 3. Push the file

Push `catalog.json` to the `gohar-store` repo on GitHub (`main`
branch). Within seconds, every installed GOHAR Store refreshes from
`https://raw.githubusercontent.com/TalhaGoharWeb/gohar-store/main/catalog.json`
— pull down on the home screen, or tap **Check for updates**.
No new store APK is needed when only the catalogue changes.

> The bundled copy of `catalog.json` inside the store APK is only a
> fallback for offline first launch. After you push a new catalogue,
> also update `android/app/src/main/assets/catalog.json` before the
> *next* store release, so fresh installs start from the latest list.

## Checklist before announcing an update

1. `versionCode` rose (check with `aapt2`, don't guess).
2. The download link opens in a browser and the file size matches.
3. Open GOHAR Store on your phone, pull to refresh, confirm the
   UPDATE badge appears, then update and open the app.
