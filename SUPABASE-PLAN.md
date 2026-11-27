# Talha Gohar Apps — Supabase plan (draft, 2026-11-27)

## LIVE — project created and backend set up (2026-11-27 ~08:50 PKT)

- **Project: "Talha Gohar Apps", ref `ksnsjyxeruobqwqsuvhq`, https://ksnsjyxeruobqwqsuvhq.supabase.co, region ap-northeast-1, ACTIVE_HEALTHY.** Created by the user in the dashboard.
- **Migration 001 applied** (via Management API database/query): tables `apps`, `app_versions`, `app_screenshots`, `app_download_counts`, `profiles`; 10 RLS policies; explicit GRANTs. Seeded from the polished catalog: **5 apps, 5 versions, 19 screenshots** (Prayer Times 1.3.9/15 included).
- **Storage buckets `app-icons` + `app-screenshots` created (public)**, with 4 storage policies (public read, admin-only write).
- **Verified end-to-end with the anon key:** public read returns all 5 apps and 5 versions; `record_download` RPC works (test count reset to 0 afterwards).
- **Keys:** saved only in `~/.supabase-store-keys.json` (mode 600, outside the repo) — anon key for the app, service_role never in the repo. App reads them from `local.properties` (gitignored) via BuildConfig.
- **Still owed:** the Android app switch — the store app itself still reads GitHub `catalog.json` for now. Admin account: user signs up once in the app, then `profiles.is_admin` is flipped for him via SQL.

## Where the store is today (v1.3.3, released today)

Native Kotlin store, `com.gohar.store`, minSdk 26. It works, and it is
already professional in the basics: featured hero, search, categories,
Updates tab, real icons, screenshot galleries, pause/resume downloads,
full Urdu (Jameel Noori Nastaleeq), and an in-app developer console.

But the catalogue lives in one file, `catalog.json` on GitHub, and the
"owner sign-in" is only a password hash on that one phone. The GitHub
token on the phone is what really authorises a publish. That is the
honest limit written in PUBLISH-GUIDE.md, and it is the main thing
Supabase fixes.

Two things to fix first, before any new feature:

1. **Live catalogue says 2 apps, README says 4.** `catalog.json` lists
   only Nigaarish (coming soon, no APK) and Maliki Fiqh Hub. Sett
   Browser and PRIVACY X have icons in `icons/` but no catalogue entry,
   so users cannot see or install them right now.
2. **Nigaarish has no downloadable release.** v1.5.70 is code only;
   until a release APK is published, the store's biggest app shows
   "Coming soon".

## Supabase design (migration 001 is drafted, not applied)

New, dedicated Supabase project — do not reuse Nigaarish / Kisan Dost
AI / Zivra, those are per-app backends. Note: this Supabase account
already has 4 projects (ApnaChat, kisan-dost-ai, nigaarish, zivra);
3 are paused/inactive and only nigaarish is active, so creating a
5th may hit the free-tier active-project limit — check in the
dashboard before creating, or resume/pause as needed.

- **Tables:** `apps`, `app_versions` (one row per release; highest
  `version_code` is current), `app_screenshots`,
  `app_download_counts` (anonymous daily counters only — no device
  id, no IP, keeping the store's "no tracking" promise),
  `profiles` (`is_admin` flag).
- **Auth:** Supabase Auth replaces the on-device owner password.
  Only `is_admin = true` can write; everyone can read listed apps.
  Draft/unlisted apps stay invisible.
- **Storage:** buckets `app-icons` and `app-screenshots` (public
  read, admin-only upload). **APKs stay on GitHub Releases** — free,
  large files, already proven. Supabase Storage is not for APKs.
- **Fallback:** the Android app reads Supabase first, falls back to
  the bundled `catalog.json` offline, exactly as it does today with
  the GitHub file. No new store APK is needed to publish an app —
  same as today, only the source changes from a file to a table.

## The new upload flow (the "easy" part)

Today: upload APK to a GitHub Release → edit `catalog.json` by hand
or in the console with a GitHub token → push.

After Supabase: in the store's developer console, signed in as
Talha (Supabase Auth) —

1. Pick the app, tap **New version**.
2. Paste the GitHub Release APK link (or pick arm64/armv7 links),
   version name/code, what's new in English + Urdu.
3. Tap **Publish**. The store updates for every user on next refresh.
   No GitHub token on the phone, no JSON editing, no repo push.

Adding a brand-new app is the same form plus icon + screenshots,
uploaded straight from the phone into Supabase Storage.

## Feature suggestions

### Phase 1 — make it trustworthy (build first)
- Supabase catalogue + admin auth + new publish flow above.
- Restore Sett + PRIVACY X to the catalogue (or confirm they should
  stay out), and publish a Nigaarish release APK.
- Download counts ("1,200 downloads") from the anonymous counter.
- "What's new" history: keep old versions visible, not just the latest.
- Share an app (link + text), and an update check that works even if
  the store app itself is old.

### Phase 2 — make it feel like a real store
- Ratings + short reviews (Talha approves before they show), and a
  "Report a problem" button on each app that emails Talha.
- Categories that actually filter, "New" and "Updated recently" rows.
- In-app update for the store itself (it already knows its own
  version; it should offer its own update like any other app).
- Search that also matches Urdu names.
- A simple web page for the store (same Supabase data) so a link
  shared on WhatsApp/Facebook shows the app and its download button.

### Phase 3 — later, only if you want it
- "Notify me" for coming-soon apps (no account needed — just a
  local reminder when the app appears).
- Per-app changelogs in Urdu first, English second.
- Optional accounts for users (favourites / my apps list) — only if
  users ask for it. Do not add accounts just to have them.

### Deliberately NOT doing
- Payments, paid apps, or other developers' apps. This stays your
  personal, free store — the paused multi-developer marketplace in
  `docs/` + `backend/` stays paused.
- Silent auto-updates. Android still asks once, and the user taps
  Install — same as today.
- Tracking, ads, or analytics SDKs.

## Decisions needed from Talha

1. **Supabase project:** create a new dedicated project in the
   dashboard (name: `talha-gohar-apps`, region ap-south-1 / Mumbai,
   like your other apps)? I can prepare everything, but project
   creation + DB password need you in the dashboard.
2. **Which apps go in?** Candidates from your work: Kisan Dost,
   Mera Roznamcha, Nigaarish, Maliki Fiqh Hub, Maliki Prayer Times,
   Zaratnama, Sett Browser, PRIVACY X, Radd al-Ilhad, Pakistan Zarai
   Ilm, Shirazi / FoneShirazi. Which are public-ready, and which
   stay out (private, unfinished, or Play-Store-only)?
3. **Phase 1 go-ahead?** If yes, I apply migration 001, seed it from
   the current catalogue, switch the Android app to read Supabase,
   replace owner sign-in with Supabase Auth, and rebuild the console
   around the new publish flow — then verify with tests and a
   release build on your "Upload".
