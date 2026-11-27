-- Talha Gohar Apps — Supabase backend, migration 001 (DRAFT, not yet applied)
-- Design decisions:
--  * Supabase is the source of truth for the catalogue. catalog.json on
--    GitHub stays as the offline fallback / migration source, not the master.
--  * APK files stay on GitHub Releases (free, large, already proven).
--    Supabase Storage holds only icons + screenshots (small files).
--  * No user tracking: download counts are anonymous daily counters,
--    no device id, no IP, no account needed to browse/download.
--  * Only Talha (is_admin in profiles) can write. Everyone can read
--    listed apps. Unlisted / draft apps are invisible to the public.
--  * Per the 2026-10-30 platform rule, new public tables get explicit
--    GRANTs in this same migration, or the Data API returns
--    "permission denied".

-- ---------------------------------------------------------------- admin
create table if not exists public.profiles (
  id uuid primary key references auth.users (id) on delete cascade,
  email text,
  is_admin boolean not null default false,
  created_at timestamptz not null default now()
);
alter table public.profiles enable row level security;

create or replace function public.is_store_admin()
returns boolean
language sql stable security definer
set search_path = public
as $$
  select exists (
    select 1 from public.profiles
    where id = auth.uid() and is_admin = true
  );
$$;

-- ---------------------------------------------------------------- apps
create table if not exists public.apps (
  id text primary key,                       -- never changes, e.g. 'nigaarish'
  name text not null,
  name_ur text,
  tagline text not null default '',
  tagline_ur text,
  description text not null default '',
  description_ur text,
  category text not null default 'Apps',
  category_ur text,
  developer text not null default 'Muhammad Talha Bin Fareed',
  package_name text not null unique,        -- e.g. com.talhagohar.nigaarish
  icon_url text,
  icon_color text not null default '#0E9594',
  icon_color2 text,
  featured boolean not null default false,
  is_listed boolean not null default true,  -- false = draft / hidden
  min_sdk int not null default 26,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
alter table public.apps enable row level security;

-- ---------------------------------------------------------------- versions
-- One row per published release. The highest version_code per app is
-- the current release; the store compares it with the installed code.
create table if not exists public.app_versions (
  id uuid primary key default gen_random_uuid(),
  app_id text not null references public.apps (id) on delete cascade,
  version_name text not null,
  version_code bigint not null,
  apk_url text,                             -- single APK, or null
  apk_url_arm64 text,
  apk_url_armv7 text,
  size_bytes bigint not null default 0,
  whats_new text[] not null default '{}',
  whats_new_ur text[] not null default '{}',
  coming_soon text,                         -- set when apk_url is null
  coming_soon_ur text,
  released_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  unique (app_id, version_code)
);
alter table public.app_versions enable row level security;
create index if not exists app_versions_app_code_idx
  on public.app_versions (app_id, version_code desc);

-- ---------------------------------------------------------------- screenshots
create table if not exists public.app_screenshots (
  id uuid primary key default gen_random_uuid(),
  app_id text not null references public.apps (id) on delete cascade,
  image_url text not null,
  sort_order int not null default 0,
  created_at timestamptz not null default now()
);
alter table public.app_screenshots enable row level security;
create index if not exists app_screenshots_app_order_idx
  on public.app_screenshots (app_id, sort_order);

-- ---------------------------------------------------------------- downloads
-- Anonymous daily counters only. No device id, no user id, no IP.
create table if not exists public.app_download_counts (
  app_id text not null references public.apps (id) on delete cascade,
  day date not null default current_date,
  downloads bigint not null default 0,
  primary key (app_id, day)
);
alter table public.app_download_counts enable row level security;

-- Increment today's counter for one app (called by the store app).
create or replace function public.record_download(p_app_id text)
returns void
language sql security definer
set search_path = public
as $$
  insert into public.app_download_counts (app_id, day, downloads)
  values (p_app_id, current_date, 1)
  on conflict (app_id, day)
  do update set downloads = public.app_download_counts.downloads + 1;
$$;

-- ---------------------------------------------------------------- policies
-- Public read: listed apps only, and their versions/screenshots/counts.
drop policy if exists "public read listed apps" on public.apps;
create policy "public read listed apps" on public.apps
  for select to anon, authenticated
  using (is_listed = true);

drop policy if exists "admin write apps" on public.apps;
create policy "admin write apps" on public.apps
  for all to authenticated
  using (public.is_store_admin())
  with check (public.is_store_admin());

drop policy if exists "public read versions of listed apps" on public.app_versions;
create policy "public read versions of listed apps" on public.app_versions
  for select to anon, authenticated
  using (exists (select 1 from public.apps a where a.id = app_id and a.is_listed = true));

drop policy if exists "admin write versions" on public.app_versions;
create policy "admin write versions" on public.app_versions
  for all to authenticated
  using (public.is_store_admin())
  with check (public.is_store_admin());

drop policy if exists "public read screenshots of listed apps" on public.app_screenshots;
create policy "public read screenshots of listed apps" on public.app_screenshots
  for select to anon, authenticated
  using (exists (select 1 from public.apps a where a.id = app_id and a.is_listed = true));

drop policy if exists "admin write screenshots" on public.app_screenshots;
create policy "admin write screenshots" on public.app_screenshots
  for all to authenticated
  using (public.is_store_admin())
  with check (public.is_store_admin());

drop policy if exists "public read download counts" on public.app_download_counts;
create policy "public read download counts" on public.app_download_counts
  for select to anon, authenticated using (true);

drop policy if exists "admin write download counts" on public.app_download_counts;
create policy "admin write download counts" on public.app_download_counts
  for all to authenticated
  using (public.is_store_admin())
  with check (public.is_store_admin());

drop policy if exists "own profile read" on public.profiles;
create policy "own profile read" on public.profiles
  for select to authenticated using (id = auth.uid());

drop policy if exists "admin read profiles" on public.profiles;
create policy "admin read profiles" on public.profiles
  for select to authenticated using (public.is_store_admin());

-- ---------------------------------------------------------------- grants
-- (2026-10-30 rule: explicit GRANTs in the same migration.)
grant select on public.apps, public.app_versions, public.app_screenshots,
  public.app_download_counts to anon, authenticated;
grant select on public.profiles to authenticated;
grant insert, update, delete on public.apps, public.app_versions,
  public.app_screenshots, public.app_download_counts to authenticated;
grant insert, update, delete on public.profiles to authenticated;
grant execute on function public.record_download(text) to anon, authenticated;
grant execute on function public.is_store_admin() to anon, authenticated;

-- ---------------------------------------------------------------- storage
-- Run in the Dashboard (Storage cannot be created by SQL here):
--   buckets (public): app-icons, app-screenshots
--   policy: public read; admin-only upload/update/delete.
--   (Create the buckets first, then add the matching storage policies.)

-- ---------------------------------------------------------------- seed
-- Import the current catalog.json (5 apps) after applying. Keep the
-- GitHub file as fallback until the Android app reads Supabase.
