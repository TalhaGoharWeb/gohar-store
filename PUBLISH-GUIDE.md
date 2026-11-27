# Publishing an app in Talha Gohar Apps — the 3-minute guide

There are two ways to publish. The **developer console inside the
app** is the fast one; editing `catalog.json` by hand (below) still
works as the alternative.

## The developer console (recommended)

Open the store app → ⋮ menu → **Developer console**.

- **First time:** paste a GitHub Personal Access Token. Create one
  at GitHub → Settings → Developer settings → Personal access
  tokens → Tokens (classic), scope `repo` — or a fine-grained token
  with Contents read/write on the `TalhaGoharWeb/gohar-store` repo.
  The token stays only on this phone (never in any file) and can be
  revoked anytime from GitHub. The console shows only its last 4
  characters; **Clear token / sign out** in the console menu removes
  it.
- **Add / Edit / Delete:** the console lists every app with its
  icon, name, package and version. **Add app** opens a form for all
  the catalogue fields (name, package, version, sizes, links,
  screenshots, Urdu text…); **Edit** opens the same form filled in;
  **Delete** asks for confirmation. A ticked **Featured** box moves
  the home-screen hero to that app.
- **Publish changes:** edits are held on the phone until you tap
  **Publish changes** (⋮ menu). That writes the new `catalog.json`
  straight to the `gohar-store` repo on GitHub, and within seconds
  every installed Talha Gohar Apps refreshes from it — pull down on
  the home screen, or tap **Check for updates**. No new store APK is
  needed when only the catalogue changes. If someone changed the
  catalogue on GitHub in the meantime, the console says so and asks
  you to reload first.
- **Version codes:** when you edit an app, the console warns you if
  the new `versionCode` is not higher than the current one — users
  only see an update when it rises.
- The bundled copy of `catalog.json` inside the store APK is only a
  fallback for offline first launch. After publishing, also update
  `android/app/src/main/assets/catalog.json` before the *next* store
  release, so fresh installs start from the latest list.

## Owner sign-in (who sees the developer console)

The console is owner-only. In the ⋮ menu, **Developer console**
only appears while you are signed in as the owner; everyone else
just sees **Owner sign-in**.

- **First time:** open ⋮ → **Owner sign-in**. There is no owner yet,
  so it asks you to set one up: an email and a password (8+
  characters) that only you know. You can also tap **Link Google
  account** and pick one of your Google accounts — after that,
  **Continue with Google** signs you in with that account. Only the
  salted hash of the password is stored on the phone, never the
  password itself.
- **Later:** ⋮ → **Owner sign-in** → email + password, or Continue
  with Google. The console item appears right away; **Sign out** in
  the ⋮ menu hides it again.
- **Forgot the password, or a new phone:** on the sign-in screen,
  **Reset owner sign-in** erases the owner setup on that phone
  (after a confirmation) and you set it up again from scratch.
- **Honest limit:** the gate is enforced on the device — it hides
  the console from other users of the store. The GitHub token is
  what actually authorises a publish, so keep the token private and
  revoke it from GitHub if the phone is ever lost.

## Manual publishing (alternative — no console needed)

Publishing is two steps: upload the APK, then edit one file.

### 1. Upload the APK to GitHub Releases

- **Sett:** repo `TalhaGoharWeb/sett-browser` — already has releases.
  Upload the new `Sett-Browser-vX.Y.Z-arm64.apk` (and armv7) to a new
  release tagged `vX.Y.Z`.
- **PRIVACY X / Nigaarish / a new app:** create a release in the app's
  own repo (or in the central `TalhaGoharWeb/releases` repo, the way
  Maliki Fiqh Hub v1.0.0 was published) and upload the APK there.
- Copy the asset's download link. It looks like:
  `https://github.com/<owner>/<repo>/releases/download/<tag>/<file>.apk`

### 2. Edit `catalog.json` (in this folder)

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

### 3. Push the file

Push `catalog.json` to the `gohar-store` repo on GitHub (`main`
branch). Within seconds, every installed Talha Gohar Apps refreshes from
`https://raw.githubusercontent.com/TalhaGoharWeb/gohar-store/main/catalog.json`
— pull down on the home screen, or tap **Check for updates**.
No new store APK is needed when only the catalogue changes.

> The bundled copy of `catalog.json` inside the store APK is only a
> fallback for offline first launch. After you push a new catalogue,
> also update `android/app/src/main/assets/catalog.json` before the
> *next* store release, so fresh installs start from the latest list.

## App icons & screenshots

The store shows each app's **real launcher icon** in the list, the
featured hero and the detail header — no more letter tiles. It also
shows a Play Store-style **screenshot strip** on the detail page:
swipe sideways through the shots, tap one to view it full screen.

- **Icon:** put the app's launcher PNG in `icons/<app-id>.png` in
  this repo (192×192 is enough; the file is the `ic_launcher.png`
  from the app's `mipmap-xxxhdpi` folder). Then set
  `"iconUrl": "https://raw.githubusercontent.com/TalhaGoharWeb/gohar-store/main/icons/<app-id>.png"`
  in the app's `catalog.json` entry.
- **Screenshots:** take real screenshots on your phone (or the app's
  own `docs/screenshots/` folder if it has one) and save them as
  JPGs in `screenshots/<app-id>/`, e.g.
  `screenshots/maliki-fiqh-hub/01-home-urdu.jpg`. Keep each one
  under ~500 KB. Then list them in order in the app's entry:
  `"screenshots": ["https://raw.githubusercontent.com/TalhaGoharWeb/gohar-store/main/screenshots/<app-id>/01-home-urdu.jpg", ...]`.
- Apps with `"screenshots": []` simply show no gallery — nothing
  fake is ever shown. Never use mock-ups or another app's shots.
- Icons and screenshots are cached on the phone after the first
  download, so they load instantly on later visits.

## Urdu text for a new app

Every app entry can carry Urdu alongside English: `taglineUr`,
`descriptionUr`, `categoryUr`, `whatsNewUr` (a list, same order as
`whatsNew`) and `comingSoonUr`. When the store is in Urdu, those are
shown instead of the English text; when any Urdu field is missing, the
English text is used, so you can add Urdu bit by bit.

## Checklist before announcing an update

1. `versionCode` rose (check with `aapt2`, don't guess).
2. The download link opens in a browser and the file size matches.
3. Open Talha Gohar Apps on your phone, pull to refresh, confirm the
   UPDATE badge appears, then update and open the app.
