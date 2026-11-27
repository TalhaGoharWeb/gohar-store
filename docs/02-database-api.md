# GOHAR Store — Architecture v1

## C. Database Relationship Diagram & Schema · E. API Specification

| Field | Value |
|---|---|
| Document | `02-database-api.md` |
| Deliverable | GOHAR Store — First Deliverable (Architecture v1) |
| Date | 26 November 2026 |
| Status | **Design — for approval. No code, no database, no endpoints exist yet.** |
| Companion docs | `01-audit-architecture.md` (A, B, D, F, G, H, I, J) — repository audit, system architecture, screen map, APK pipeline, payment/ledger design, milestones, infrastructure, risk register |
| Stack (locked) | Laravel REST API · PostgreSQL · Redis · Queues · Sanctum · Filament · Python APK workers · R2 private storage + CDN · Android Kotlin / Jetpack Compose / MVVM |
| Brand | GOHAR Store — "Where Great Apps Belong" · Navy + teal · Material 3 · English first, Urdu-ready |
| Commission | **15% of eligible paid sales, configurable in `system_settings` — never hardcoded** |

> **Reading note.** Every table, endpoint and field in this document is a *design decision to be approved*, not a description of existing code. There is no existing GOHAR repository (see `01-audit-architecture.md` §A). Nothing here may be quoted as "already implemented". Where a choice is still open it is listed under **Open decisions** at the end.

---

# C. Database Relationship Diagram & Schema

## C.1 Conventions (apply to every table)

| Rule | Decision | Rationale |
|---|---|---|
| Primary keys | `BIGINT GENERATED ALWAYS AS IDENTITY` surrogate PK `id`, except where noted | Stable, compact FKs; public-facing identifiers are separate (see below) |
| Public identifiers | `uuid UUID NOT NULL DEFAULT gen_random_uuid()` on `users`, `apps`, `app_versions`, `orders`, `payments`, `payouts` | Never expose sequential IDs in URLs/APIs (IDOR hardening); sequential `id` stays internal |
| Timestamps | `created_at`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()` on all mutable tables; `deleted_at TIMESTAMPTZ NULL` where soft-deleted | All times UTC in the DB; presentation timezone is a client concern |
| Money | **Integer minor units only** — `amount_minor BIGINT NOT NULL` + `currency CHAR(3) NOT NULL` (ISO 4217, e.g. `PKR`, `USD`). **Never `FLOAT` / `DOUBLE` / `DECIMAL` for money in application code**; `NUMERIC(10,4)` only for the *commission rate* itself (e.g. `0.1500`) | Exact arithmetic; no rounding drift in ledgers. Paisa/cents are the unit, never rupees/dollars |
| Status fields | `VARCHAR` + `CHECK` constraint enumerating allowed values, or PostgreSQL `ENUM` only where the set is provably closed (e.g. `payout_status`) | CHECK constraints are easier to extend in migrations than ENUMs |
| Soft delete | `deleted_at` on user-facing content (`apps`, `app_versions`, `reviews`, `categories`, `announcements`, `developer_profiles`) | Preserves purchase/entitlement history; see §C.4 |
| Hard delete | Only for genuinely ephemeral rows (`user_sessions` on logout/expiry, expired idempotency records after retention window) | Nothing financial or evidentiary is ever hard-deleted |
| Audit | `audit_logs` is **append-only** (see §C.4); no `updated_at`, no soft delete, no `UPDATE`/`DELETE` grant to the app role | Tamper-evidence for admin/financial/security actions |
| Foreign keys | All FKs explicit, `ON DELETE RESTRICT` by default; `CASCADE` only where the child has no independent meaning (e.g. `app_tags` → `apps`) | Prevents accidental cascade loss of financial history |
| Text search | `tsvector` generated column on `apps` (`search_vector`) for Postgres FTS; GIN index | Zero-spend search at launch; see Open decisions |
| Files | DB stores **object-storage keys, hashes and metadata only** — never file bytes, never public URLs, never secrets | R2 keys are private; downloads go through signed URLs (§E.9) |
| Card data | **Nowhere in this schema.** No card number, CVV, expiry, token-from-card, or full PAN in any table. Payment-provider references (`provider_payment_id`, `provider_customer_id`) only | PCI scope: card data lives only with the licensed payment provider |
| Secrets | No API keys, webhook secrets, signing keys or passwords in any table except `users.password_hash` (Argon2id/bcrypt via Laravel) and *encrypted* provider credential references in `system_settings` (Laravel encrypted cast, KMS/env-backed key) | Secrets belong in env / secret manager, never in plaintext columns |
| Character set | `UTF8`, collation `und-x-icu` (or DB default); all user text `TEXT`/`VARCHAR` | Urdu/Arabic script support from day one |
| Migrations | One migration per table/group, reversible; seeders for `roles`, `permissions`, `categories`, `system_settings` only | Reproducible builds; no seed data masquerading as production data |

### C.1.1 Domain grouping

| Domain | Tables |
|---|---|
| **Identity & access** | `users`, `user_sessions`, `roles`, `permissions`, `role_user`, `permission_role`, `developer_profiles`, `developer_verifications`, `developer_payout_accounts` |
| **Catalogue** | `apps`, `categories`, `app_categories`, `app_tags`, `app_screenshots`, `app_versions`, `app_files`, `app_permissions` |
| **Submissions & security** | `submissions`, `validation_results`, `security_scans`, `risk_assessments`, `moderation_logs`, `app_reports` |
| **Commerce** | `orders`, `order_items`, `payments`, `payment_events`, `refunds`, `entitlements`, `commission_entries`, `developer_ledger`, `payouts`, `payout_items` |
| **Engagement** | `reviews`, `review_reports`, `favourites`, `downloads`, `notifications` |
| **Operations** | `repository_releases`, `repository_signatures`, `announcements`, `audit_logs`, `system_settings` |

> The brief lists 42 tables by name. This schema adds two pivot tables the brief implies but does not name — `role_user` and `permission_role` — required for a real many-to-many RBAC model. They are flagged as additions, not inventions of existing tables.

---

## C.2 Entity–Relationship Diagram

```mermaid
erDiagram
    %% ============ IDENTITY & ACCESS ============
    users ||--o{ user_sessions : has
    users ||--o{ role_user : has
    roles ||--o{ role_user : assigned_via
    roles ||--o{ permission_role : has
    permissions ||--o{ permission_role : granted_via
    users ||--o| developer_profiles : "may be"
    developer_profiles ||--o{ developer_verifications : verified_by
    developer_profiles ||--o{ developer_payout_accounts : pays_to
    developer_profiles ||--o{ apps : publishes
    developer_profiles ||--o{ payouts : receives

    %% ============ CATALOGUE ============
    apps ||--o{ app_categories : in
    categories ||--o{ app_categories : contains
    apps ||--o{ app_tags : tagged
    apps ||--o{ app_screenshots : shows
    apps ||--o{ app_versions : has
    app_versions ||--o| app_files : ships_as
    app_versions ||--o{ app_permissions : requests
    apps ||--o{ submissions : submitted_as
    apps ||--o{ reviews : reviewed_in
    apps ||--o{ favourites : favourited_in
    apps ||--o{ downloads : downloaded_as
    apps ||--o{ repository_releases : published_in
    apps ||--o{ app_reports : reported_as

    %% ============ SUBMISSIONS & SECURITY ============
    submissions ||--o| app_versions : proposes
    submissions ||--o{ validation_results : produces
    submissions ||--o{ security_scans : produces
    submissions ||--o| risk_assessments : produces
    submissions ||--o{ moderation_logs : decided_in
    apps ||--o{ moderation_logs : moderated_in
    users ||--o{ moderation_logs : "admin acts"

    %% ============ COMMERCE ============
    users ||--o{ orders : places
    orders ||--o{ order_items : contains
    apps ||--o{ order_items : sold_as
    app_versions ||--o{ order_items : version_sold
    orders ||--o| payments : paid_by
    payments ||--o{ payment_events : emits
    payments ||--o{ refunds : reversed_by
    orders ||--o{ refunds : refunded_from
    users ||--o{ entitlements : owns
    apps ||--o{ entitlements : grants_access_to
    payments ||--o{ entitlements : "grants on success"
    orders ||--o{ commission_entries : yields
    developer_profiles ||--o{ commission_entries : earns_from
    developer_profiles ||--o{ developer_ledger : balanced_in
    commission_entries ||--o| developer_ledger : posts_to
    payouts ||--o{ payout_items : contains
    developer_ledger ||--o{ payout_items : settled_by
    payouts ||--o{ developer_profiles : paid_to

    %% ============ ENGAGEMENT ============
    users ||--o{ reviews : writes
    reviews ||--o{ review_reports : reported_in
    users ||--o{ favourites : saves
    users ||--o{ downloads : performs
    app_versions ||--o{ downloads : version_downloaded
    users ||--o{ notifications : receives
    users ||--o{ app_reports : files
    users ||--o{ review_reports : files

    %% ============ OPERATIONS ============
    repository_releases ||--o| repository_signatures : signed_by
    users ||--o{ audit_logs : "actor in"
    system_settings ||--|| system_settings : "singleton config"

    %% ============ TABLE STUBS (columns in §C.3) ============
    users {
        bigint id PK
        uuid uuid UK
        varchar email UK
        varchar password_hash
        varchar display_name
        varchar locale
        varchar timezone
        timestamptz email_verified_at
        timestamptz deleted_at
    }
    user_sessions {
        bigint id PK
        bigint user_id FK
        varchar token_hash UK
        varchar device_label
        inet ip_address
        timestamptz last_used_at
        timestamptz expires_at
    }
    roles {
        bigint id PK
        varchar slug UK
        varchar name
    }
    permissions {
        bigint id PK
        varchar slug UK
        varchar name
    }
    role_user {
        bigint role_id FK
        bigint user_id FK
    }
    permission_role {
        bigint permission_id FK
        bigint role_id FK
    }
    developer_profiles {
        bigint id PK
        bigint user_id FK_UK
        varchar slug UK
        varchar legal_name
        varchar country_code
        varchar verification_status
        timestamptz deleted_at
    }
    developer_verifications {
        bigint id PK
        bigint developer_profile_id FK
        varchar type
        varchar status
        varchar reviewer_note
        bigint reviewed_by FK
    }
    developer_payout_accounts {
        bigint id PK
        bigint developer_profile_id FK
        varchar provider
        varchar account_ref_encrypted
        boolean is_default
        varchar status
    }
    apps {
        bigint id PK
        uuid uuid UK
        bigint developer_profile_id FK
        varchar package_name
        varchar name
        varchar status
        bigint current_version_id FK
        tsvector search_vector
        timestamptz deleted_at
    }
    categories {
        bigint id PK
        varchar slug UK
        varchar name_en
        varchar name_ur
        bigint parent_id FK
        int sort_order
    }
    app_categories {
        bigint app_id FK
        bigint category_id FK
    }
    app_tags {
        bigint id PK
        bigint app_id FK
        varchar tag
    }
    app_screenshots {
        bigint id PK
        bigint app_id FK
        varchar storage_key
        int sort_order
    }
    app_versions {
        bigint id PK
        uuid uuid UK
        bigint app_id FK
        varchar version_name
        int version_code
        varchar min_sdk
        varchar target_sdk
        varchar sha256 UK
        varchar signing_fingerprint
        varchar status
    }
    app_files {
        bigint id PK
        bigint app_version_id FK_UK
        varchar storage_key
        bigint size_bytes
        varchar sha256
        varchar mime_type
    }
    app_permissions {
        bigint id PK
        bigint app_version_id FK
        varchar permission
        boolean is_dangerous
    }
    submissions {
        bigint id PK
        bigint app_id FK
        bigint app_version_id FK_UK
        bigint submitted_by FK
        varchar status
        varchar decision_reason
    }
    validation_results {
        bigint id PK
        bigint submission_id FK
        varchar check_name
        varchar outcome
        jsonb evidence
    }
    security_scans {
        bigint id PK
        bigint submission_id FK
        varchar scanner
        varchar verdict
        jsonb findings
    }
    risk_assessments {
        bigint id PK
        bigint submission_id FK_UK
        int score_0_100
        varchar band
        jsonb factors
    }
    moderation_logs {
        bigint id PK
        bigint app_id FK
        bigint submission_id FK
        bigint actor_id FK
        varchar action
        text reason
    }
    reviews {
        bigint id PK
        bigint app_id FK
        bigint user_id FK
        smallint rating_1_5
        text body
        boolean verified_purchase
        timestamptz deleted_at
    }
    review_reports {
        bigint id PK
        bigint review_id FK
        bigint reporter_id FK
        varchar reason
        varchar status
    }
    app_reports {
        bigint id PK
        bigint app_id FK
        bigint reporter_id FK
        varchar reason
        varchar status
    }
    favourites {
        bigint user_id FK
        bigint app_id FK
    }
    orders {
        bigint id PK
        uuid uuid UK
        bigint user_id FK
        bigint total_minor
        varchar currency
        varchar status
        varchar idempotency_key UK
    }
    order_items {
        bigint id PK
        bigint order_id FK
        bigint app_id FK
        bigint app_version_id FK
        bigint price_minor
        varchar currency
    }
    payments {
        bigint id PK
        uuid uuid UK
        bigint order_id FK_UK
        varchar provider
        varchar provider_payment_id UK
        bigint amount_minor
        varchar currency
        varchar status
        varchar idempotency_key UK
    }
    payment_events {
        bigint id PK
        bigint payment_id FK
        varchar type
        jsonb payload
        varchar provider_event_id UK
    }
    refunds {
        bigint id PK
        bigint order_id FK
        bigint payment_id FK
        bigint amount_minor
        varchar currency
        varchar status
        varchar reason
    }
    entitlements {
        bigint id PK
        bigint user_id FK
        bigint app_id FK
        bigint order_id FK
        timestamptz granted_at
        timestamptz revoked_at
    }
    commission_entries {
        bigint id PK
        bigint order_id FK
        bigint developer_profile_id FK
        bigint gross_minor
        bigint commission_minor
        bigint developer_minor
        numeric commission_rate
        varchar currency
    }
    developer_ledger {
        bigint id PK
        bigint developer_profile_id FK
        varchar entry_type
        bigint amount_minor_signed
        varchar currency
        bigint running_balance_minor
        uuid reference_uuid
    }
    payouts {
        bigint id PK
        uuid uuid UK
        bigint developer_profile_id FK
        bigint payout_account_id FK
        bigint amount_minor
        varchar currency
        varchar status
    }
    payout_items {
        bigint id PK
        bigint payout_id FK
        bigint ledger_entry_id FK_UK
        bigint amount_minor
    }
    downloads {
        bigint id PK
        bigint user_id FK
        bigint app_id FK
        bigint app_version_id FK
        varchar sha256_verified
        varchar status
        inet ip_address
    }
    repository_releases {
        bigint id PK
        bigint app_id FK
        bigint app_version_id FK_UK
        varchar repo_index_version
        timestamptz published_at
    }
    repository_signatures {
        bigint id PK
        bigint repository_release_id FK_UK
        varchar key_id
        varchar signature
        varchar algorithm
    }
    notifications {
        bigint id PK
        bigint user_id FK
        varchar type
        jsonb data
        timestamptz read_at
    }
    announcements {
        bigint id PK
        varchar title
        text body
        varchar audience
        timestamptz starts_at
        timestamptz ends_at
        timestamptz deleted_at
    }
    audit_logs {
        bigint id PK
        bigint actor_id FK
        varchar action
        varchar target_type
        bigint target_id
        jsonb before
        jsonb after
        inet ip_address
    }
    system_settings {
        varchar key PK
        jsonb value
        varchar updated_by
    }
```

### How to read the diagram

- `||--o{` = one-to-many; `||--o|` = one-to-one (or one-to-zero-or-one).
- `developer_profiles` is the commercial identity of a developer; `users` is the login identity. One user has at most one developer profile (`user_id UNIQUE`).
- `apps.current_version_id` → `app_versions` is a denormalised pointer to the currently published version, maintained transactionally on publish; the authoritative version list is `app_versions.app_id`.
- `submissions` is the *workflow* record (one per upload); `app_versions` is the *artefact* record (one per version code). A submission proposes exactly one version (`app_version_id UNIQUE` on `submissions`).
- Money flows one way and is never mutated in place: `orders` → `payments` → `commission_entries` → `developer_ledger` (append-only) → `payout_items` → `payouts`. Refunds post *reversing* ledger entries; they never edit the original entries.
- `audit_logs` has no FK back from any table — nothing depends on it, so it can never block a delete, and it is never itself deleted.

---

## C.3 Schema by domain

> Column lists are **key columns**, not exhaustive DDL. Every table also carries `id`, `created_at`, `updated_at` (except append-only tables, noted) and the conventions of §C.1. Full DDL is a Milestone 4 deliverable, generated from Laravel migrations — this document is the source of truth the migrations must match.

### C.3.1 Identity & access

#### `users`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `uuid` | UUID | `UNIQUE`, `DEFAULT gen_random_uuid()` — public identifier |
| `email` | VARCHAR(255) | `UNIQUE`, `CITEXT`-style case-insensitive handling at app layer; lowercased on write |
| `password_hash` | VARCHAR(255) | Argon2id (Laravel default); never reversible |
| `display_name` | VARCHAR(100) | |
| `locale` | VARCHAR(10) | Default `'en'`; `'ur'` when Urdu ships |
| `timezone` | VARCHAR(64) | IANA name, default `'Asia/Karachi'` for PK users, user-selectable |
| `email_verified_at` | TIMESTAMPTZ NULL | NULL = unverified; gates developer submission |
| `is_active` | BOOLEAN | Default true; admin suspension sets false (login blocked, data retained) |
| `deleted_at` | TIMESTAMPTZ NULL | Soft delete; anonymise `email`/`display_name` on erasure request, keep `id` for ledger integrity |
| **Indexes** | `email` (unique), `uuid` (unique) | |

#### `user_sessions`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `user_id` | BIGINT | FK → `users.id`, `ON DELETE CASCADE` (sessions die with the account) |
| `token_hash` | VARCHAR(64) | `UNIQUE` — SHA-256 of the Sanctum token; **raw token never stored** |
| `device_label` | VARCHAR(255) NULL | e.g. "Pixel 8 · Android 15" (client-supplied, untrusted — display only) |
| `ip_address` | INET NULL | For audit / anomaly detection |
| `last_used_at` | TIMESTAMPTZ NULL | Updated on use (throttled writes) |
| `expires_at` | TIMESTAMPTZ NULL | Absolute expiry; Sanctum token abilities enforced at app layer |
| **Indexes** | `user_id`, `token_hash` (unique), `expires_at` (sweeper) | |

#### `roles` / `permissions` / `role_user` / `permission_role`
| Table | Key columns | Constraints / notes |
|---|---|---|
| `roles` | `id`, `slug VARCHAR(50) UNIQUE` (`user`, `developer`, `moderator`, `finance`, `admin`, `super_admin`), `name`, `description` | Seeded; `slug` is the code contract — never rename a slug in place, add a new one |
| `permissions` | `id`, `slug VARCHAR(100) UNIQUE` (e.g. `apps.approve`, `apps.suspend`, `payouts.approve`, `settings.manage`, `reviews.moderate`, `users.suspend`), `name` | Seeded; checked by Laravel Gates/Policies, never by string-matching in controllers |
| `role_user` | `role_id` FK → `roles`, `user_id` FK → `users` | **PK (`role_id`, `user_id`)**; `ON DELETE CASCADE` both sides |
| `permission_role` | `permission_id` FK → `permissions`, `role_id` FK → `roles` | **PK (`permission_id`, `role_id`)**; `ON DELETE CASCADE` both sides |
| **Indexes** | Composite PKs above serve as indexes | |

> Seeded roles (minimum): `user` (buy/download/review), `developer` (implies `user` + submit/manage own apps), `moderator` (review queue, app approve/reject/suspend, review moderation), `finance` (refunds, payouts, commission settings — **cannot** approve apps), `admin` (all moderation + finance + users + settings except key rotation), `super_admin` (key rotation, role assignment). Separation of `moderator` vs `finance` is deliberate — no single role both approves an app and pays its developer.

#### `developer_profiles`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `user_id` | BIGINT | FK → `users.id`, **`UNIQUE`**, `ON DELETE RESTRICT` |
| `slug` | VARCHAR(100) | `UNIQUE` — public storefront handle, URL-safe, immutable after first publish |
| `display_name` | VARCHAR(150) | Public developer name |
| `legal_name` | VARCHAR(255) NULL | For verification/payouts; not public |
| `country_code` | CHAR(2) NULL | ISO 3166-1 alpha-2; drives country-specific requirements |
| `website_url` | VARCHAR(500) NULL | Validated URL |
| `support_email` | VARCHAR(255) NULL | Public support contact |
| `privacy_policy_url` | VARCHAR(500) NULL | Required before first paid app |
| `verification_status` | VARCHAR(20) | CHECK `IN ('unverified','pending','verified','rejected','suspended')`, default `'unverified'` |
| `bio` | TEXT NULL | |
| `deleted_at` | TIMESTAMPTZ NULL | Soft delete; apps stay visible only if already published and not suspended — see §C.4 |
| **Indexes** | `user_id` (unique), `slug` (unique), `verification_status` | |

#### `developer_verifications`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `developer_profile_id` | BIGINT | FK → `developer_profiles.id`, `ON DELETE CASCADE` |
| `type` | VARCHAR(30) | CHECK `IN ('identity_document','business_registration','address','tax_id','other')` |
| `status` | VARCHAR(20) | CHECK `IN ('pending','approved','rejected','expired')` |
| `document_storage_key` | VARCHAR(500) NULL | R2 **private** key only — never a URL; access via short-lived signed URL for reviewers only |
| `reviewer_note` | TEXT NULL | Shown to the developer on rejection |
| `reviewed_by` | BIGINT NULL | FK → `users.id`, `ON DELETE SET NULL` |
| `reviewed_at` | TIMESTAMPTZ NULL | |
| **Indexes** | `developer_profile_id`, `status` | |

#### `developer_payout_accounts`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `developer_profile_id` | BIGINT | FK → `developer_profiles.id`, `ON DELETE CASCADE` |
| `provider` | VARCHAR(50) | e.g. `bank_transfer`, `easypaisa`, `jazzcash`, `paypal`, `payoneer` — **provider name only; availability is per-country and per-approval, never assumed** |
| `account_ref_encrypted` | TEXT | Laravel-encrypted account identifier (account number / wallet ID / IBAN). **Encrypted at rest, never logged, never returned by any API** |
| `account_label` | VARCHAR(100) NULL | Display label only, e.g. "JazzCash ••• 4821" (last digits only) |
| `currency` | CHAR(3) | Payout currency for this account |
| `is_default` | BOOLEAN | Default false; at most one default per developer (partial unique index `WHERE is_default`) |
| `status` | VARCHAR(20) | CHECK `IN ('pending','verified','rejected','disabled')`, default `'pending'` |
| **Indexes** | `developer_profile_id`, partial unique on (`developer_profile_id`) `WHERE is_default` | |

---

### C.3.2 Catalogue

#### `apps`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `uuid` | UUID | `UNIQUE` — public identifier |
| `developer_profile_id` | BIGINT | FK → `developer_profiles.id`, `ON DELETE RESTRICT` |
| `package_name` | VARCHAR(255) | **See §C.4 — ownership rule.** Indexed, not globally unique alone (see composite below) |
| `name` | VARCHAR(150) | |
| `short_description` | VARCHAR(500) NULL | |
| `description` | TEXT NULL | Localised descriptions live here at v1 (`description` + `description_ur TEXT NULL`); a `app_translations` table is the Milestone 9+ evolution — do not add it prematurely |
| `description_ur` | TEXT NULL | Urdu description, nullable until Urdu ships |
| `status` | VARCHAR(20) | CHECK `IN ('draft','in_review','published','suspended','removed')`, default `'draft'` |
| `price_minor` | BIGINT | Default `0` (free); `> 0` = paid. Currency is per-app `currency CHAR(3)` — see note below |
| `currency` | CHAR(3) | Default `'PKR'` at launch for PK storefront; multi-currency pricing is an Open decision |
| `current_version_id` | BIGINT NULL | FK → `app_versions.id`, `ON DELETE SET NULL`; set only on publish, in the same transaction as the status change |
| `icon_storage_key` | VARCHAR(500) NULL | R2 key (public-read via CDN after publish; private before) |
| `search_vector` | TSVECTOR | Generated: `to_tsvector('english', name \|\| ' ' \|\| coalesce(short_description,''))` + developer name via trigger — see note |
| `average_rating` | NUMERIC(3,2) NULL | Denormalised, recomputed on review write (transactional); NULL until first review |
| `review_count` | INT | Default `0`, denormalised with `average_rating` |
| `download_count` | BIGINT | Default `0`, incremented via queued job (never on the download hot path) |
| `deleted_at` | TIMESTAMPTZ NULL | Soft delete |
| **Unique** | **`UNIQUE (package_name, developer_profile_id)`** — see §C.4 | |
| **Indexes** | `package_name`, `developer_profile_id`, `status`, `search_vector` (GIN), `(status, created_at DESC)` for catalogue listing, `current_version_id` | |

> **Currency note.** `price_minor` + `currency` on `apps` is the *list* price at publish time. The price actually charged is snapshotted on `order_items.price_minor`/`currency` at order time, so later price changes never rewrite history.

#### `categories`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `slug` | VARCHAR(50) | `UNIQUE` — stable code (`productivity`, `education`, `business`, `utilities`, `finance`, `communication`, `photography`, `entertainment`, `games`, `security`, `development-tools`) |
| `name_en` | VARCHAR(100) | |
| `name_ur` | VARCHAR(100) NULL | Urdu label, nullable until Urdu ships |
| `parent_id` | BIGINT NULL | FK → `categories.id` (self-referencing, for future sub-categories; NULL at launch) |
| `sort_order` | INT | Default `0` |
| `is_active` | BOOLEAN | Default true |
| `deleted_at` | TIMESTAMPTZ NULL | Soft delete |
| **Indexes** | `slug` (unique), `parent_id`, `sort_order` | |

Seeded with the 11 launch categories from the brief. New categories are admin-created; slugs are never reused after deletion.

#### `app_categories` (pivot)
| Column | Type | Constraints / notes |
|---|---|---|
| `app_id` | BIGINT | FK → `apps.id`, `ON DELETE CASCADE` |
| `category_id` | BIGINT | FK → `categories.id`, `ON DELETE RESTRICT` |
| **PK** | **(`app_id`, `category_id`)** | One app may sit in several categories; at least one category is required at submission (validated at app layer) |

#### `app_tags`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `app_id` | BIGINT | FK → `apps.id`, `ON DELETE CASCADE` |
| `tag` | VARCHAR(50) | Lowercased, trimmed on write; max 10 tags per app (app-layer rule) |
| **Unique** | `UNIQUE (app_id, tag)` | |
| **Indexes** | `tag` (for tag-browsing) | |

#### `app_screenshots`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `app_id` | BIGINT | FK → `apps.id`, `ON DELETE CASCADE` |
| `storage_key` | VARCHAR(500) | R2 key; CDN-served after publish |
| `width_px` / `height_px` | INT NULL | Recorded at upload; used for responsive `srcset` |
| `sort_order` | INT | Default `0` |
| **Indexes** | `app_id`, `(app_id, sort_order)` | |

#### `app_versions`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `uuid` | UUID | `UNIQUE` — public identifier |
| `app_id` | BIGINT | FK → `apps.id`, `ON DELETE RESTRICT` (a published app's history is never cascade-deleted) |
| `version_name` | VARCHAR(50) | e.g. `1.4.2` — display only, never used for ordering |
| `version_code` | INT | **Monotonic per app — see §C.4.** Ordering and update detection use this, never `version_name` |
| `min_sdk` | SMALLINT NULL | From manifest (`minSdkVersion`) |
| `target_sdk` | SMALLINT NULL | From manifest (`targetSdkVersion`) |
| `sha256` | CHAR(64) | `UNIQUE` — SHA-256 of the APK file; the integrity anchor for downloads |
| `signing_fingerprint` | CHAR(64) | SHA-256 of the signing certificate (see §C.4); **history is preserved per version, never overwritten** |
| `signing_scheme` | VARCHAR(20) NULL | e.g. `v2`, `v3`, `v3.1` — from inspection |
| `apk_size_bytes` | BIGINT | |
| `release_notes` | TEXT NULL | Changelog for this version |
| `status` | VARCHAR(20) | CHECK `IN ('quarantined','in_review','approved','rejected','published','superseded','revoked')`, default `'quarantined'` |
| `published_at` | TIMESTAMPTZ NULL | Set once, on publish |
| **Unique** | **`UNIQUE (app_id, version_code)`** and `sha256` | |
| **Indexes** | `(app_id, version_code DESC)`, `sha256` (unique), `signing_fingerprint`, `status` | |

#### `app_files`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `app_version_id` | BIGINT | FK → `app_versions.id`, **`UNIQUE`**, `ON DELETE RESTRICT` — one file row per version at v1 (APK only; expansion packs / splits are a later design, not a silent second row) |
| `storage_key` | VARCHAR(500) | R2 **private** key; never a public URL |
| `size_bytes` | BIGINT | Must match `app_versions.apk_size_bytes` (checked at ingest) |
| `sha256` | CHAR(64) | Must match `app_versions.sha256` (checked at ingest and again at download) |
| `mime_type` | VARCHAR(100) | Must be `application/vnd.android.package-archive` — validated by content sniffing, not by the client-supplied header |
| **Indexes** | `app_version_id` (unique), `sha256` | |

#### `app_permissions`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `app_version_id` | BIGINT | FK → `app_versions.id`, `ON DELETE CASCADE` (permissions belong to the version) |
| `permission` | VARCHAR(255) | e.g. `android.permission.CAMERA` — from the manifest, never from developer free text |
| `is_dangerous` | BOOLEAN | From the Android permission-protection-level table at inspection time |
| **Unique** | `UNIQUE (app_version_id, permission)` | |
| **Indexes** | `app_version_id` | |

---

### C.3.3 Submissions & security

#### `submissions`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `app_id` | BIGINT | FK → `apps.id`, `ON DELETE RESTRICT` |
| `app_version_id` | BIGINT | FK → `app_versions.id`, **`UNIQUE`**, `ON DELETE RESTRICT` — one submission per version |
| `submitted_by` | BIGINT | FK → `users.id`, `ON DELETE RESTRICT` (the submitting developer user) |
| `status` | VARCHAR(20) | CHECK `IN ('received','validating','awaiting_review','approved','rejected','withdrawn')`, default `'received'` |
| `decision_reason` | TEXT NULL | Required when `rejected`; shown to the developer verbatim |
| `decided_by` | BIGINT NULL | FK → `users.id`, `ON DELETE SET NULL` (moderator/admin) |
| `decided_at` | TIMESTAMPTZ NULL | |
| **Indexes** | `app_id`, `status`, `(status, created_at)` for the review queue, `submitted_by` | |

#### `validation_results`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `submission_id` | BIGINT | FK → `submissions.id`, `ON DELETE CASCADE` |
| `check_name` | VARCHAR(100) | Stable machine name, e.g. `apk.structure`, `manifest.package`, `version.progression`, `signing.match`, `hash.sha256`, `permissions.dangerous_count` |
| `outcome` | VARCHAR(20) | CHECK `IN ('pass','fail','warn','error','skipped')` — **`error`/`skipped` never count as `pass`** (see pipeline doc) |
| `evidence` | JSONB NULL | Structured evidence (values found, thresholds, file offsets); **never raw APK bytes, never secrets** |
| `checked_at` | TIMESTAMPTZ | Default `now()` |
| **Indexes** | `submission_id`, `(submission_id, check_name)` | |

#### `security_scans`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `submission_id` | BIGINT | FK → `submissions.id`, `ON DELETE CASCADE` |
| `scanner` | VARCHAR(100) | e.g. `clamav`, `apkid`, `androguard-static`, `quark`, `manual` — scanner name + version recorded in `findings` metadata |
| `verdict` | VARCHAR(20) | CHECK `IN ('clean','suspicious','malicious','inconclusive','error')` — **`inconclusive`/`error` block auto-approval** |
| `findings` | JSONB NULL | Normalised findings array; raw scanner output truncated/capped (max ~64 KB per scan) |
| `scanned_at` | TIMESTAMPTZ | Default `now()` |
| **Indexes** | `submission_id`, `verdict` | |

#### `risk_assessments`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `submission_id` | BIGINT | FK → `submissions.id`, **`UNIQUE`**, `ON DELETE CASCADE` — one risk assessment per submission (re-assessment creates a new submission, never overwrites) |
| `score_0_100` | SMALLINT | CHECK `BETWEEN 0 AND 100`; higher = riskier |
| `band` | VARCHAR(20) | CHECK `IN ('low','medium','high','critical')` — thresholds in `system_settings` (`risk.bands`), not hardcoded |
| `factors` | JSONB | Array of `{factor, weight, value, note}` — the auditable reason for the score |
| `auto_approvable` | BOOLEAN | Computed: true only if every mandatory check passed **and** band is `low` **and** no scanner verdict is worse than `clean` |
| `assessed_at` | TIMESTAMPTZ | Default `now()` |
| **Indexes** | `submission_id` (unique), `band` | |

#### `moderation_logs`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `app_id` | BIGINT NULL | FK → `apps.id`, `ON DELETE SET NULL` (log survives app removal) |
| `submission_id` | BIGINT NULL | FK → `submissions.id`, `ON DELETE SET NULL` |
| `actor_id` | BIGINT | FK → `users.id`, `ON DELETE RESTRICT` — the moderator/admin who acted |
| `action` | VARCHAR(50) | e.g. `submission.approved`, `submission.rejected`, `app.suspended`, `app.reinstated`, `app.removed`, `review.hidden` |
| `reason` | TEXT | **Required** — empty reasons are rejected at validation |
| `metadata` | JSONB NULL | Structured context (previous status, new status, risk score at decision time) |
| **Indexes** | `app_id`, `submission_id`, `actor_id`, `(created_at DESC)` | |

> `moderation_logs` is the *human-decision* record; `audit_logs` (§C.3.6) is the *system-wide* record. Moderation actions are written to **both**: the moderation log is the reviewable narrative, the audit log is the tamper-evident trail. They are never merged into one table — their retention and access rules differ.

#### `app_reports`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `app_id` | BIGINT | FK → `apps.id`, `ON DELETE CASCADE` |
| `reporter_id` | BIGINT NULL | FK → `users.id`, `ON DELETE SET NULL` — NULL = anonymous report (allowed, but weighted lower) |
| `reason` | VARCHAR(50) | CHECK `IN ('malware','fraud','misleading','copyright','inappropriate','privacy','other')` |
| `details` | TEXT NULL | |
| `status` | VARCHAR(20) | CHECK `IN ('open','investigating','resolved','dismissed')`, default `'open'` |
| **Indexes** | `app_id`, `status`, `(status, created_at)` | |

---

### C.3.4 Commerce

> **All money in this domain is `BIGINT` minor units + `CHAR(3)` currency. No exceptions.** Every write that moves money is wrapped in a DB transaction that also writes the ledger/audit row; a money write without its ledger row is a bug, caught by tests (Milestone 8).

#### `orders`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `uuid` | UUID | `UNIQUE` — public order reference shown to the buyer |
| `user_id` | BIGINT | FK → `users.id`, `ON DELETE RESTRICT` |
| `total_minor` | BIGINT | Sum of `order_items.price_minor`; `CHECK (total_minor >= 0)` |
| `currency` | CHAR(3) | Single currency per order (v1: one app per order — see `order_items` note) |
| `status` | VARCHAR(20) | CHECK `IN ('created','pending_payment','paid','failed','cancelled','refunded','partially_refunded')`, default `'created'` |
| `idempotency_key` | VARCHAR(100) | **`UNIQUE`** — client-supplied (UUID v4); replaying the same key returns the original order, never a second one |
| **Indexes** | `uuid` (unique), `user_id`, `idempotency_key` (unique), `status` | |

#### `order_items`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `order_id` | BIGINT | FK → `orders.id`, `ON DELETE CASCADE` |
| `app_id` | BIGINT | FK → `apps.id`, `ON DELETE RESTRICT` |
| `app_version_id` | BIGINT | FK → `app_versions.id`, `ON DELETE RESTRICT` — the version actually purchased (snapshot) |
| `price_minor` | BIGINT | Snapshot of the app price at order time; `CHECK (price_minor >= 0)` |
| `currency` | CHAR(3) | Snapshot; must equal `orders.currency` |
| **Unique** | `UNIQUE (order_id, app_id)` | **v1: one app per order.** Multi-item carts are a later design; the table shape already supports them, but the API enforces one item until carts are designed |
| **Indexes** | `order_id`, `app_id` | |

#### `payments`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `uuid` | UUID | `UNIQUE` — public payment reference |
| `order_id` | BIGINT | FK → `orders.id`, **`UNIQUE`**, `ON DELETE RESTRICT` — one payment attempt-set per order at v1; retries create `payment_events`, not new `payments` rows, until a new attempt is explicitly designed |
| `provider` | VARCHAR(50) | e.g. `easypaisa`, `jazzcash`, `card_stripe` — provider key from `system_settings.payments.providers`; **no provider is active until credentials + merchant approval + payment testing are complete** |
| `provider_payment_id` | VARCHAR(255) NULL | `UNIQUE` where not null — the provider's transaction reference; NULL until the provider returns one |
| `amount_minor` | BIGINT | Must equal `orders.total_minor` (checked at creation) |
| `currency` | CHAR(3) | |
| `status` | VARCHAR(20) | CHECK `IN ('initiated','pending','succeeded','failed','cancelled','refunded','partially_refunded')`, default `'initiated'` |
| `provider_fee_minor` | BIGINT NULL | Fee charged by the provider, recorded when known; NULL until the provider reports it |
| `idempotency_key` | VARCHAR(100) | **`UNIQUE`** — distinct from the order's key; replay-safe payment creation |
| `paid_at` | TIMESTAMPTZ NULL | Set only on verified provider success (webhook or verified poll — never on client callback) |
| **Indexes** | `uuid` (unique), `order_id` (unique), `provider_payment_id` (unique), `idempotency_key` (unique), `status` | |

#### `payment_events`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `payment_id` | BIGINT | FK → `payments.id`, `ON DELETE CASCADE` |
| `type` | VARCHAR(50) | e.g. `payment.initiated`, `payment.succeeded`, `payment.failed`, `webhook.received`, `webhook.verified`, `webhook.rejected` |
| `provider_event_id` | VARCHAR(255) NULL | **`UNIQUE` where not null** — provider's event ID; the duplicate-webhook guard |
| `payload` | JSONB | Raw provider payload (redacted: no card data, no secrets — redaction at ingestion, tested) |
| `signature_valid` | BOOLEAN NULL | NULL = not a webhook; true/false = HMAC verification result |
| **Indexes** | `payment_id`, `provider_event_id` (unique), `(payment_id, created_at)` | |

#### `refunds`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `order_id` | BIGINT | FK → `orders.id`, `ON DELETE RESTRICT` |
| `payment_id` | BIGINT | FK → `payments.id`, `ON DELETE RESTRICT` |
| `amount_minor` | BIGINT | `CHECK (amount_minor > 0)`; sum of refunds per payment must never exceed the payment amount (enforced by a transaction-level check + test) |
| `currency` | CHAR(3) | Must equal the payment currency |
| `status` | VARCHAR(20) | CHECK `IN ('requested','approved','processing','succeeded','failed','rejected')`, default `'requested'` |
| `reason` | VARCHAR(50) | e.g. `app_not_as_described`, `accidental_purchase`, `technical_issue`, `fraud`, `other` |
| `reason_detail` | TEXT NULL | |
| `requested_by` | BIGINT | FK → `users.id` (buyer or admin) |
| `decided_by` | BIGINT NULL | FK → `users.id`, `ON DELETE SET NULL` |
| `provider_refund_id` | VARCHAR(255) NULL | Provider's refund reference |
| **Indexes** | `order_id`, `payment_id`, `status` | |

#### `entitlements`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `user_id` | BIGINT | FK → `users.id`, `ON DELETE RESTRICT` |
| `app_id` | BIGINT | FK → `apps.id`, `ON DELETE RESTRICT` |
| `order_id` | BIGINT | FK → `orders.id`, `ON DELETE RESTRICT` — the order that granted it |
| `granted_at` | TIMESTAMPTZ | Set only after **server-side** payment verification |
| `revoked_at` | TIMESTAMPTZ NULL | Set on refund/chargeback/takedown; a revoked entitlement blocks download, it does not delete the row |
| `revoke_reason` | VARCHAR(50) NULL | e.g. `refund`, `chargeback`, `app_removed`, `fraud` |
| **Unique** | **`UNIQUE (user_id, app_id)`** — one live entitlement per user per app; re-purchase after revocation updates the row (new `order_id`, cleared `revoked_at`), it does not insert a duplicate | |
| **Indexes** | `(user_id, app_id)` (unique), `order_id`, `user_id` | |

> **Entitlement rule (non-negotiable).** An entitlement is created **only** inside the transaction that processes a *verified* provider webhook (or a verified server-side poll). A client-side "payment succeeded" callback, a deep link, or any unsigned signal **never** creates an entitlement. This is enforced in code and covered by a dedicated test (Milestone 8).

#### `commission_entries`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `order_id` | BIGINT | FK → `orders.id`, `ON DELETE RESTRICT` |
| `developer_profile_id` | BIGINT | FK → `developer_profiles.id`, `ON DELETE RESTRICT` |
| `gross_minor` | BIGINT | Sale price (== `order_items.price_minor`) |
| `commission_rate` | NUMERIC(10,4) | **Snapshot** of the rate at sale time (from `system_settings.commission_rate`, default `0.1500`) — later rate changes never rewrite history |
| `commission_minor` | BIGINT | `FLOOR(gross × rate)` — rounding rule fixed here: **round down the commission, remainder to the developer**; documented, tested, never changed silently |
| `provider_fee_minor` | BIGINT | Default `0`; provider fee deducted from the developer share when the fee policy says so (policy in `system_settings`, see payment design doc) |
| `developer_minor` | BIGINT | `gross − commission − provider_fee`; `CHECK (developer_minor >= 0)` |
| `currency` | CHAR(3) | |
| `reversed_at` | TIMESTAMPTZ NULL | Set on refund — the entry is never edited; reversal posts a compensating ledger entry (see below) |
| **Unique** | `UNIQUE (order_id)` | One commission entry per order at v1 (one app per order) |
| **Indexes** | `order_id` (unique), `developer_profile_id`, `(developer_profile_id, created_at)` | |

#### `developer_ledger`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `developer_profile_id` | BIGINT | FK → `developer_profiles.id`, `ON DELETE RESTRICT` |
| `entry_type` | VARCHAR(30) | CHECK `IN ('sale','commission','provider_fee','refund_reversal','payout','payout_reversal','adjustment')` |
| `amount_minor_signed` | BIGINT | **Signed**: positive = credit to developer, negative = debit. Never zero |
| `currency` | CHAR(3) | One ledger per currency in practice — balances are per (`developer_profile_id`, `currency`); mixed-currency payouts are rejected |
| `running_balance_minor` | BIGINT | Balance **after** this entry; recomputed in the same transaction; the reconciliation anchor |
| `reference_type` / `reference_id` | VARCHAR(50) / BIGINT NULL | Polymorphic link to the source row (`commission_entries`, `refunds`, `payouts`) |
| `reference_uuid` | UUID NULL | Public reference for statements |
| `note` | TEXT NULL | Human-readable line for the developer statement |
| **Indexes** | `(developer_profile_id, currency, created_at)`, `(developer_profile_id, currency, id)` — the balance query path | |

> **Ledger immutability.** Rows in `developer_ledger` are **never updated or deleted**. Corrections are new rows of type `adjustment` (admin-only, with a mandatory `audit_logs` entry and a reason). `running_balance_minor` makes any tampering or missed entry detectable by recomputation — a nightly reconciliation job verifies it (Milestone 8).

#### `payouts`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `uuid` | UUID | `UNIQUE` — public payout reference |
| `developer_profile_id` | BIGINT | FK → `developer_profiles.id`, `ON DELETE RESTRICT` |
| `payout_account_id` | BIGINT | FK → `developer_payout_accounts.id`, `ON DELETE RESTRICT` — snapshot of the destination at request time |
| `amount_minor` | BIGINT | `CHECK (amount_minor > 0)`; must be ≤ available balance at request time and ≥ `system_settings.payouts.minimum_minor` |
| `currency` | CHAR(3) | |
| `status` | VARCHAR(20) | CHECK `IN ('requested','approved','processing','paid','failed','rejected','cancelled')`, default `'requested'` |
| `requested_by` | BIGINT | FK → `users.id` (the developer user) |
| `approved_by` | BIGINT NULL | FK → `users.id`, `ON DELETE SET NULL` — **a payout cannot be approved by its requester** (enforced at app layer + test) |
| `provider_reference` | VARCHAR(255) NULL | Bank/provider transfer reference once paid |
| `failure_reason` | TEXT NULL | |
| **Indexes** | `uuid` (unique), `developer_profile_id`, `status`, `(status, created_at)` | |

#### `payout_items`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `payout_id` | BIGINT | FK → `payouts.id`, `ON DELETE CASCADE` |
| `ledger_entry_id` | BIGINT | FK → `developer_ledger.id`, **`UNIQUE`**, `ON DELETE RESTRICT` — a ledger entry settles into **at most one** payout |
| `amount_minor` | BIGINT | Portion of that ledger entry included in this payout |
| **Indexes** | `payout_id`, `ledger_entry_id` (unique) | |

---

### C.3.5 Engagement

#### `reviews`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `app_id` | BIGINT | FK → `apps.id`, `ON DELETE CASCADE` |
| `user_id` | BIGINT | FK → `users.id`, `ON DELETE RESTRICT` |
| `rating` | SMALLINT | CHECK `BETWEEN 1 AND 5` |
| `body` | TEXT NULL | Max 2,000 chars (app-layer validation); empty body allowed (rating-only review) |
| `verified_purchase` | BOOLEAN | Default false; true only if an entitlement (or a free download) exists at review time — computed server-side, never client-supplied |
| `developer_reply` | TEXT NULL | Single reply per review at v1; `developer_replied_at` alongside |
| `developer_replied_at` | TIMESTAMPTZ NULL | |
| `is_hidden` | BOOLEAN | Default false; moderation hides, never deletes |
| `deleted_at` | TIMESTAMPTZ NULL | Soft delete (user deletes own review; history retained for aggregates until recomputed) |
| **Unique** | **`UNIQUE (app_id, user_id)`** — one review per user per app; editing updates the row | |
| **Indexes** | `app_id`, `(app_id, created_at DESC)`, `(app_id, rating)`, `user_id` | |

#### `review_reports`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `review_id` | BIGINT | FK → `reviews.id`, `ON DELETE CASCADE` |
| `reporter_id` | BIGINT | FK → `users.id`, `ON DELETE CASCADE` |
| `reason` | VARCHAR(50) | CHECK `IN ('spam','abuse','fake','off_topic','other')` |
| `status` | VARCHAR(20) | CHECK `IN ('open','resolved','dismissed')`, default `'open'` |
| **Unique** | `UNIQUE (review_id, reporter_id)` | One report per user per review |
| **Indexes** | `review_id`, `status` | |

#### `favourites`
| Column | Type | Constraints / notes |
|---|---|---|
| `user_id` | BIGINT | FK → `users.id`, `ON DELETE CASCADE` |
| `app_id` | BIGINT | FK → `apps.id`, `ON DELETE CASCADE` |
| **PK** | **(`user_id`, `app_id`)** | |
| **Indexes** | PK serves both directions; add `app_id` index for "how many favourited this app" | |

#### `downloads`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `user_id` | BIGINT NULL | FK → `users.id`, `ON DELETE SET NULL` — NULL only for anonymous free downloads if guest download is ever enabled; **at v1 downloads require an account**, so this is normally NOT NULL (enforced at app layer) |
| `app_id` | BIGINT | FK → `apps.id`, `ON DELETE RESTRICT` |
| `app_version_id` | BIGINT | FK → `app_versions.id`, `ON DELETE RESTRICT` |
| `status` | VARCHAR(20) | CHECK `IN ('authorised','started','completed','failed','cancelled')`, default `'authorised'` — `authorised` = signed URL issued; `completed` = client confirmed SHA-256 match (see §E.9) |
| `sha256_verified` | BOOLEAN NULL | NULL = not yet confirmed; true/false = client-reported verification result (server re-checks the file hash independently at authorisation time) |
| `ip_address` | INET NULL | Abuse / anomaly detection |
| `user_agent` | VARCHAR(500) NULL | |
| **Indexes** | `(app_id, created_at)`, `(user_id, created_at)`, `(app_version_id, created_at)` — analytics + abuse queries; **never** a unique constraint (repeat downloads are legitimate) | |

> Download *counting* for the public "N downloads" figure uses an aggregated counter on `apps.download_count` (queued increment), not `COUNT(*) over downloads` on the hot path. `downloads` is the audit/abuse record, not the counter.

#### `notifications`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `user_id` | BIGINT | FK → `users.id`, `ON DELETE CASCADE` |
| `type` | VARCHAR(100) | e.g. `submission.approved`, `submission.rejected`, `app.updated`, `payout.approved`, `order.paid`, `announcement.published` |
| `title` | VARCHAR(255) | Localised at send time; store the rendered string + `locale` |
| `body` | TEXT NULL | |
| `data` | JSONB NULL | Deep-link payload (`{app_uuid, submission_id, ...}`) — no secrets |
| `read_at` | TIMESTAMPTZ NULL | NULL = unread |
| **Indexes** | `(user_id, created_at DESC)`, `(user_id, read_at)` | |

---

### C.3.6 Operations

#### `repository_releases`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `app_id` | BIGINT | FK → `apps.id`, `ON DELETE RESTRICT` |
| `app_version_id` | BIGINT | FK → `app_versions.id`, **`UNIQUE`**, `ON DELETE RESTRICT` — one repository entry per published version |
| `repo_index_version` | VARCHAR(50) | The signed repository index version this release was published under |
| `published_at` | TIMESTAMPTZ | Default `now()` |
| `withdrawn_at` | TIMESTAMPTZ NULL | Set on takedown; the index is regenerated without this release |
| **Indexes** | `app_id`, `app_version_id` (unique), `published_at` | |

#### `repository_signatures`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `repository_release_id` | BIGINT | FK → `repository_releases.id`, **`UNIQUE`**, `ON DELETE CASCADE` |
| `key_id` | VARCHAR(100) | GOHAR repository key fingerprint — identifies *which* key signed, for rotation |
| `signature` | TEXT | Detached signature over the repository index entry |
| `algorithm` | VARCHAR(50) | e.g. `ed25519`, `rsa-pss-sha256` — algorithm recorded per signature so rotation can change it |
| **Indexes** | `repository_release_id` (unique), `key_id` | |

> Repository metadata signing uses a **separate GOHAR-controlled key** from any developer APK signing key (brief §9). The private repository key never touches the application server's filesystem in plaintext; it lives in the secret manager / HSM-backed store, with rotation and recovery documented in the deployment runbook (Milestone 10).

#### `announcements`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK |
| `title` | VARCHAR(255) | |
| `body` | TEXT | |
| `audience` | VARCHAR(20) | CHECK `IN ('all','users','developers','admins')`, default `'all'` |
| `starts_at` / `ends_at` | TIMESTAMPTZ NULL | Scheduling window; NULL `ends_at` = until withdrawn |
| `is_active` | BOOLEAN | Default true |
| `created_by` | BIGINT | FK → `users.id`, `ON DELETE RESTRICT` |
| `deleted_at` | TIMESTAMPTZ NULL | Soft delete |
| **Indexes** | `(audience, starts_at)`, `is_active` | |

#### `audit_logs`
| Column | Type | Constraints / notes |
|---|---|---|
| `id` | BIGINT | PK (append-only sequence — gaps are acceptable, re-use is not) |
| `actor_id` | BIGINT NULL | FK → `users.id`, `ON DELETE SET NULL` — NULL = system/queue actor (record `actor_type = 'system'` in that case) |
| `actor_type` | VARCHAR(20) | CHECK `IN ('user','admin','system','worker')`, default `'user'` |
| `action` | VARCHAR(100) | Stable machine name, e.g. `admin.app.suspend`, `finance.payout.approve`, `security.submission.reject`, `auth.login_failed` |
| `target_type` | VARCHAR(50) NULL | e.g. `app`, `submission`, `payout`, `user` |
| `target_id` | BIGINT NULL | Internal ID of the target (never a UUID — audit is internal) |
| `before` / `after` | JSONB NULL | State diff; **redacted** — no passwords, tokens, secrets, or full account numbers |
| `ip_address` | INET NULL | |
| `user_agent` | VARCHAR(500) NULL | |
| `created_at` | TIMESTAMPTZ | Default `now()`; **no `updated_at`, no `deleted_at`** |
| **Indexes** | `(actor_id, created_at)`, `(target_type, target_id, created_at)`, `(action, created_at)`, `created_at` (retention sweeper) | |
| **Immutability** | App DB role gets `INSERT` + `SELECT` only — **no `UPDATE`/`DELETE` grant**; enforced at migration level and verified by a Milestone 10 test | |

#### `system_settings`
| Column | Type | Constraints / notes |
|---|---|---|
| `key` | VARCHAR(100) | **PK** — dotted name, e.g. `commission.rate`, `payouts.minimum_minor`, `risk.bands`, `payments.providers`, `downloads.link_ttl_seconds`, `security.max_apk_bytes` |
| `value` | JSONB | Typed value; schema-validated at write time (admin UI + API validation) |
| `description` | TEXT NULL | Human-readable purpose, shown in Filament |
| `updated_by` | BIGINT NULL | FK → `users.id`, `ON DELETE SET NULL` |
| `updated_at` | TIMESTAMPTZ | |
| **Seeded keys (minimum)** | `commission.rate = 0.1500` · `commission.rounding = "floor"` · `payouts.minimum_minor = 500000` (PKR 5,000 — **to be confirmed**) · `payouts.currencies = ["PKR"]` · `risk.bands = {"low": 30, "medium": 60, "high": 85}` · `downloads.link_ttl_seconds = 900` · `security.max_apk_bytes = 524288000` (500 MB — to be confirmed) · `payments.providers = {}` (empty until a provider is approved) · `catalogue.page_size_default = 20` · `catalogue.page_size_max = 100` | |

> `system_settings` is a key–value table, not a row per setting, so adding a tunable never requires a migration. Every read is cached in Redis with invalidation on write. **Provider credentials are never stored here in plaintext** — only provider *names* and *enabled flags*; credentials live in env/secret manager.

---

## C.4 Cross-cutting rules (the decisions this schema enforces)

### C.4.1 Package-name ownership

- **First claim wins, per developer.** `UNIQUE (package_name, developer_profile_id)` on `apps` means one developer owns a given package name. A *different* developer submitting the same `package_name` gets a `409 Conflict` with error code `package_name_taken` — never a silent overwrite, never a shared listing.
- **Package identity is checked at submission, not at publish.** The APK's manifest `package` must equal the `apps.package_name` the submission was created for; a mismatch fails validation (`validation_results.check_name = 'manifest.package'`, outcome `fail`) and blocks the submission.
- **Package-name transfer** (developer A → developer B) is a manual admin operation with an `audit_logs` entry and a `moderation_logs` entry. There is no self-service transfer endpoint at v1.
- **Impersonation guard.** A new app whose `package_name` is a close variant of a published app's (e.g. `com.whatsapp` vs `com.whatsApp`) is flagged by the risk assessment (`factors` includes `package.lookalike`) and forced to manual review. The similarity rule lives in the Python worker, its threshold in `system_settings`.

### C.4.2 Version-code monotonicity

- `UNIQUE (app_id, version_code)` on `app_versions` makes a duplicate version code impossible.
- **Monotonicity is enforced at the application layer *and* by a database check at publish time:** a version may be published only if `version_code > MAX(version_code)` among that app's *published* versions. The check runs inside the publish transaction (`SELECT … FOR UPDATE` on the `apps` row) so two concurrent publishes cannot both pass.
- `version_name` is display-only. Update detection, ordering and "is this newer?" logic use `version_code` exclusively. A submission with a higher `version_name` but a lower-or-equal `version_code` is rejected at validation (`version.progression` → `fail`).
- Downgrades are never published. A developer who needs to "go back" submits a *new, higher* `version_code` containing the older code.

### C.4.3 Signing-fingerprint history

- **Every version records its own `signing_fingerprint`.** History is never overwritten: the fingerprint lives on `app_versions`, not on `apps`.
- **Comparison rule at submission:** the new version's fingerprint must equal the fingerprint of the app's latest *approved/published* version. A mismatch:
  1. fails validation (`signing.match` → `fail`),
  2. forces the submission to manual review regardless of risk band,
  3. writes a `security_scans`-adjacent `validation_results` evidence entry and a `moderation_logs` entry when a human decides.
- **Legitimate key rotation** (lost keystore, Play-style upgrade) is a manual admin flow: developer requests rotation with proof of ownership, admin approves, the *new* fingerprint is recorded as the expected one from that version onward, and the rotation itself is written to `audit_logs` + `moderation_logs`. There is no self-service "change my signing key" endpoint.
- **GOHAR never re-signs third-party APKs.** The developer's original APK bytes (identified by `sha256`) are what is distributed. Repository *metadata* signing (`repository_signatures`) is a separate key and a separate concern.

### C.4.4 Soft delete vs hard delete

| Category | Treatment | Why |
|---|---|---|
| `apps`, `app_versions`, `reviews`, `categories`, `announcements`, `developer_profiles`, `users` | **Soft delete** (`deleted_at`) | Purchase history, entitlements, ledger and audit trails reference these rows; deleting them would orphan financial evidence |
| `orders`, `payments`, `refunds`, `entitlements`, `commission_entries`, `developer_ledger`, `payouts`, `audit_logs`, `moderation_logs`, `downloads`, `payment_events` | **Never deleted** (no `deleted_at` column at all) | Financial and evidentiary records; retention is a legal/operational matter, not a user preference |
| `user_sessions`, `notifications` (after retention), `favourites`, `app_tags`, `app_screenshots` (on version replace) | **Hard delete allowed** | Ephemeral or fully derived; no downstream financial dependency |
| `validation_results`, `security_scans`, `risk_assessments` | **Never deleted independently**; cascade only with their `submissions` row, which itself is never deleted | A submission's evidence must survive as long as the app does |

**Erasure (GDPR-style / user request).** Deleting a user account means: soft-delete the `users` row, *anonymise* PII columns (`email` → hashed placeholder, `display_name` → "Deleted user"), revoke sessions, and **retain** `id`, ledger references, order history and audit rows. The anonymisation procedure is a documented admin runbook step (Milestone 10), not an API endpoint.

### C.4.5 Audit-log immutability

- `audit_logs` has **no `updated_at`, no `deleted_at`, and no `UPDATE`/`DELETE` grant** for the application database role. The migration that creates the table also revokes those privileges; a Milestone 10 test asserts the revoke holds.
- Retention: hot retention 13 months in PostgreSQL, then archival to cold storage (R2, WORM-style object lock if available) — archival is a Milestone 10 operational decision, not a schema change.
- Every sensitive action writes **both** its domain log (`moderation_logs` for moderation, `payment_events` for payments) **and** `audit_logs`. If the audit write fails, the whole transaction fails — an action that cannot be audited does not happen.

### C.4.6 Where card data is NOT stored

Nowhere in this database. Specifically:

- No column in any table holds a PAN, CVV, expiry date, cardholder name as payment data, or a card token that could be replayed.
- `payments.provider_payment_id` and `developer_payout_accounts.account_ref_encrypted` are the only payment-adjacent references, and the latter is encrypted with Laravel's encryption (key in env/secret manager, never in the repo).
- `payment_events.payload` is **redacted at ingestion**: a denylist filter strips `card`, `cvv`, `pan`, `number`, `expiry`, `cvc` keys (case-insensitive, recursive) before insert. A Milestone 8 test posts a webhook containing card fields and asserts they do not appear in the stored payload.
- If a future provider requires storing a reusable payment token, that is a **new design decision** requiring a PCI-scope review — it is not smuggled in by adding a column.

### C.4.7 Referential-integrity notes

- `ON DELETE RESTRICT` is the default. Anything that would orphan money, entitlements or evidence is `RESTRICT`, forcing an explicit human/administrative decision instead of a silent cascade.
- `ON DELETE CASCADE` appears only where the child row has no meaning without the parent (`app_tags`, `app_screenshots`, `app_permissions`, `validation_results`, `security_scans`, `favourites`, `user_sessions`, `payout_items`).
- All FKs are enforced by PostgreSQL, not merely by Eloquent. Application code must not assume it can insert a child before its parent.

### C.4.8 Indexing & query-path summary

| Hot path | Index that serves it |
|---|---|
| Catalogue browse (published, newest) | `apps (status, created_at DESC)` |
| Catalogue search | `apps.search_vector` GIN + `status` filter |
| App detail + current version | `apps.current_version_id`, `app_versions (app_id, version_code DESC)` |
| Update check (is there a newer version?) | `app_versions (app_id, version_code DESC)` — single indexed lookup per app |
| Review queue (oldest awaiting first) | `submissions (status, created_at)` |
| My orders / my entitlements | `orders (user_id)`, `entitlements (user_id, app_id)` |
| Developer balance | `developer_ledger (developer_profile_id, currency, id)` |
| Payout queue | `payouts (status, created_at)` |
| Download authorisation | `entitlements (user_id, app_id)`, `app_versions.sha256` (unique) |
| Audit trail for a target | `audit_logs (target_type, target_id, created_at)` |

---


---

# E. API Specification

## E.1 Principles

| # | Principle | What it means in practice |
|---|---|---|
| 1 | **Versioned** | Every path starts `/api/v1/`. Breaking changes ship as `/api/v2/`; v1 is supported for at least 12 months after v2 launches. No unversioned endpoints. |
| 2 | **REST + JSON only** | `Content-Type: application/json` (except multipart APK/media uploads). No XML, no form-encoded business endpoints. |
| 3 | **One envelope, always** | Success: `{ "data": …, "meta": {…} }`. Error: `{ "data": null, "errors": […] }` — see §E.3. No bare arrays, no bare scalars. |
| 4 | **Auth is Sanctum, opaque** | Personal access tokens (Sanctum) for the Android app and developer portal; session cookies only for Filament (admin web). Tokens are opaque, hashed at rest, scoped by abilities, revocable per device. No JWTs containing claims, no long-lived API keys for clients. |
| 5 | **Money is exact** | All money fields are integers in minor units + `currency` (see §C.1). Any response containing a float money value is a bug. |
| 6 | **Idempotent where it matters** | All payment/order/payout-creation endpoints accept `Idempotency-Key` (§E.7). Replaying a key returns the original response with `200 OK` (not a second resource). |
| 7 | **No entitlement from the client** | Purchase state is derived server-side from verified provider events only (§C.3.4, §E.9). The Android app never tells the server "payment succeeded". |
| 8 | **Pagination is explicit** | Cursor pagination for catalogue/search feeds; offset pagination where total counts are meaningful (admin lists). Clients must not assume a default page size. |
| 9 | **Rate-limited by group** | Different limits per group (§E.6). `429` responses carry `Retry-After`. |
| 10 | **Errors are machine-readable** | Stable `code` strings (never just HTTP status + free text). Codes are listed in §E.4 and are part of the contract. |
| 11 | **No secrets in the app** | The Android app holds no API secrets, no provider keys, no signing keys. It holds one Sanctum token per login, stored in EncryptedSharedPreferences / Keystore. |
| 12 | **Documented by OpenAPI** | The OpenAPI 3.1 document is generated from the implementation in Milestone 4 (Laravel Scribe or `darkaonline/l5-swagger` — tool choice is a Milestone 4 decision; the *contract* in this document is what the generated spec must match). This document is the human-readable contract; the OpenAPI file is the machine-readable one. |

## E.2 Base URL, versioning & transport

| Item | Value |
|---|---|
| Base URL | `https://api.gohar.store` — **PLACEHOLDER, to be confirmed.** No production domain is registered or configured yet. Do not hardcode this URL in any client until the domain is confirmed and DNS/TLS are live. Development uses `https://api.gohar.test` (local) and a staging host TBD in Milestone 2. |
| Version prefix | `/api/v1` on every endpoint in this document |
| Transport | HTTPS only (HSTS, TLS 1.2+). Plain HTTP is rejected (301 to HTTPS at the edge, Cloudflare). |
| Headers (client → server) | `Accept: application/json` · `Authorization: Bearer <sanctum-token>` (when authenticated) · `Idempotency-Key: <uuid>` (payment/order/payout creation) · `X-Client-Version: <app-version>` (Android; used for forced-upgrade signalling) |
| Headers (server → client) | `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset` on rate-limited groups · `Retry-After` on `429` · `Cache-Control: private, no-store` on authenticated responses |
| Date format | ISO 8601 UTC (`2026-11-26T13:38:00Z`) everywhere |
| Money format | `{ "amount_minor": 129700, "currency": "PKR" }` — never a decimal string, never a float |

### E.2.1 Authentication — Sanctum token rules

| Rule | Detail |
|---|---|
| Token type | Laravel Sanctum personal access tokens (opaque, `id\|random` format; only the hash is stored — see `user_sessions.token_hash`) |
| Issue | `POST /api/v1/auth/login` and `POST /api/v1/auth/register` (after email verification for full access; see below) |
| Abilities | Tokens carry abilities: `app:read`, `app:write` (reviews/favourites), `purchase`, `developer` (developer-portal scopes), `admin` (never issued to a Sanctum token — admin uses Filament session auth only) |
| Per-device tokens | One token per login/device. `GET /api/v1/auth/sessions` lists them; `DELETE /api/v1/auth/sessions/{id}` revokes one; `POST /api/v1/auth/logout` revokes the current token; `POST /api/v1/auth/logout-all` revokes all |
| Expiry | Access tokens expire after **30 days of inactivity** (`last_used_at` + 30d) and absolutely after **90 days**; the client must re-login, not silently refresh — there is no refresh-token flow at v1 (deliberate: fewer token-theft windows) |
| Email verification | Registration returns a token with ability `app:read` only until `email_verified_at` is set. Purchase, review, favourite and developer actions return `403 email_not_verified` until then |
| Failed logins | 5 failures per email per 15 minutes → `429` + account lockout messaging that does not reveal whether the email exists (generic "invalid credentials" either way) |
| Password rules | Minimum 12 characters, breached-password check (HIBP k-anonymity, server-side) at registration/change; Argon2id hashing |
| Admin access | **No Sanctum token grants admin abilities.** Filament admin uses session-cookie auth + 2FA (TOTP, required for `finance` and `super_admin` roles) |

## E.3 Response envelope & error format

### Success

```json
{
  "data": { "id": "9f1c…", "type": "app", "attributes": { "name": "…", "price_minor": 0, "currency": "PKR" } },
  "meta": { "cursor": { "next": "eyJpZCI6…", "has_more": true } }
}
```

- `data` is an object, an array, or `null` (for `204`-equivalent actions returned as `200` with `data: null` — the API never returns a bare `204` with an empty body, so clients have one parsing path).
- `meta` is present on list endpoints (pagination) and omitted otherwise, except where noted.
- Resource objects use `{ "id", "type", "attributes": {…}, "relationships": {…} }` (JSON:API-inspired, not strict JSON:API — no `included` sideloading at v1).

### Error

```json
{
  "data": null,
  "errors": [
    { "code": "validation_failed", "title": "Validation failed", "detail": "The price must be a non-negative integer.", "field": "price_minor", "status": 422 }
  ]
}
```

- `errors` is always an array, always non-empty on error. `status` mirrors the HTTP status.
- `code` is stable and documented in §E.4. Clients switch on `code`, never on `detail` text.

## E.4 Error codes

| HTTP | Code | Meaning | When it is returned |
|---|---|---|---|
| 400 | `bad_request` | Malformed JSON / unknown query parameter | Unparseable body, invalid enum value in query |
| 401 | `unauthenticated` | Missing, expired or revoked token | Any authenticated endpoint without a valid `Authorization` header |
| 403 | `forbidden` | Authenticated but not allowed | Wrong role/ability; accessing another user's resource (IDOR attempt); `email_not_verified` is a distinct code below |
| 403 | `email_not_verified` | Email not yet verified | Purchase/review/favourite/developer action before verification |
| 403 | `developer_not_verified` | Developer profile not verified | Submitting an app or requesting a payout before identity verification passes |
| 404 | `not_found` | Resource does not exist (or is not visible to the caller) | Unknown UUID; unpublished app requested by a non-owner (same code — no existence leak) |
| 409 | `conflict` | State conflict | `package_name_taken`; duplicate `version_code`; re-purchase of an already-owned app; duplicate review (use PATCH) |
| 409 | `package_name_taken` | Package name already owned by another developer | App creation / submission with a taken package name |
| 409 | `version_code_not_monotonic` | `version_code` ≤ latest published `version_code` | Submission with a non-increasing version code |
| 409 | `signing_fingerprint_mismatch` | Signing fingerprint differs from the published history | Submission with an unexpected signing identity |
| 409 | `idempotency_key_reused` | Key replayed with a *different* payload | Same `Idempotency-Key`, different body — a client bug; returns `409`, never executes |
| 410 | `app_removed` | App was published then removed/suspended | Detail/download of a removed app (distinct from `404` so clients can show "removed" rather than "not found") |
| 422 | `validation_failed` | Field validation failed | `errors[]` carries one entry per failing field (`field` set) |
| 429 | `rate_limited` | Too many requests | Any rate-limited group; `Retry-After` header set |
| 500 | `server_error` | Unexpected server error | Never leaks stack traces or SQL; the `detail` is generic, the incident ID is logged server-side |
| 503 | `service_unavailable` | Dependency down (DB/Redis/storage) | Health-gated; clients retry with back-off |

**Security note on errors.** `404` and `403` are deliberately indistinguishable for resources the caller may not see (e.g. an unpublished app returns `404` to a stranger, `200` to its owner). Error `detail` strings never include SQL, file paths, or internal hostnames.

## E.5 Pagination

| Style | Where | Contract |
|---|---|---|
| **Cursor** | Catalogue, search, category listings, reviews, notifications, downloads history | Query: `?cursor=<opaque>&limit=<1–100, default 20>`. Response `meta.cursor = { "next": "<opaque\|null>", "has_more": bool }`. Cursor is opaque (base64-encoded `id` + sort key); clients must not construct it. Sorting is fixed per endpoint (documented in the table) — no arbitrary `sort_by` on cursor endpoints |
| **Offset** | Admin lists, developer analytics tables, audit logs | Query: `?page=<1-based>&per_page=<1–100, default 20>`. Response `meta.pagination = { "current_page", "per_page", "total", "last_page" }` |
| Limits | All list endpoints | `limit`/`per_page` > 100 → clamped to 100 (not an error). `limit` < 1 → `422` |

## E.6 Rate limits (per group, per IP + per token where authenticated)

| Group | Limit | Endpoints |
|---|---|---|
| Auth (login/register/password-reset) | **5 / min / IP** (+ 5 failures / 15 min / email lockout, §E.2.1) | `POST /auth/*` |
| Public catalogue (unauthenticated) | **120 / min / IP** | `GET /apps`, `GET /categories`, `GET /apps/{uuid}`, search |
| Authenticated reads | **300 / min / token** | `GET` under `/me/*`, favourites, entitlements, orders |
| Writes (reviews, favourites, reports) | **60 / min / token** | `POST/PATCH/DELETE` reviews, favourites, reports |
| Downloads (authorisation) | **30 / min / token**; **10 / min / IP** for the same app | `POST /apps/{uuid}/downloads` — abuse guard, not a user-experience limit |
| Payments | **10 / min / token** | `POST /orders`, `POST /payments/*` |
| Developer writes (submissions, uploads) | **30 / min / token**; APK upload additionally limited by `security.max_apk_bytes` | `POST /developer/*` |
| Webhooks (inbound) | **600 / min / provider IP allowlist** | `POST /webhooks/{provider}` — provider IPs allowlisted where the provider publishes them; HMAC verification is the real gate |
| Admin (Filament + admin API) | **300 / min / session** | `/admin/*` |

Limits are enforced in Redis (Laravel `RateLimiter`), return `429 rate_limited` with `Retry-After`, and are configurable in `system_settings` (Milestone 4). Limits above are **starting values to be tuned from production telemetry**, not permanent constants.

## E.7 Idempotency

- **Header:** `Idempotency-Key: <UUID v4>` (client-generated, one per logical operation).
- **Applies to:** `POST /orders`, `POST /orders/{uuid}/payments`, `POST /developer/payouts`, `POST /developer/apps/{uuid}/submissions` (APK upload initiation).
- **Semantics:** Same key + same body → stored response replayed (`200`, original body, header `Idempotency-Replayed: true`). Same key + *different* body → `409 idempotency_key_reused`. Keys retained **24 hours** (Redis + `orders`/`payments`/`payouts` columns as the durable record).
- **Does not apply to:** `GET`s (safe by definition), review/favourite writes (use the natural unique constraints instead).

## E.8 Endpoint tables

> **Auth column:** `—` = public · `User` = Sanctum token, `app:read` minimum · `Dev` = `developer` role · `Mod` = `moderator` · `Fin` = `finance` · `Admin` = `admin`/`super_admin` (Filament session for web admin; the admin API rows below are the same authorisation, exposed for the Filament backend and future admin tooling — they never accept a plain user token). **"Errors"** lists the *characteristic* codes; `401/422/429/500` apply throughout and are not repeated per row.

### E.8.1 Authentication & profile

| Method & path | Auth | Request (key fields) | Response (key fields) | Errors / notes |
|---|---|---|---|---|
| `POST /api/v1/auth/register` | — | `email`, `password`, `password_confirmation`, `display_name`, `locale?`, `timezone?` | `user {uuid, email, display_name, email_verified_at: null}`, `token` (read-only ability until verified) | `validation_failed`; breached-password → `422` field `password` |
| `POST /api/v1/auth/login` | — | `email`, `password`, `device_label?` | `user`, `token`, `token_expires_at` | Generic `401` on bad credentials (no existence leak); rate-limited (§E.6) |
| `POST /api/v1/auth/logout` | User | — | `data: null` | Revokes current token only |
| `POST /api/v1/auth/logout-all` | User | `password` (confirmation) | `data: null` | Revokes all tokens for the user |
| `POST /api/v1/auth/email/verify` | User | `code` (6-digit, 15-min TTL) or signed URL token | `user.email_verified_at` | `422` expired/invalid code; resend via next row |
| `POST /api/v1/auth/email/resend` | User | — | `data: null` | Rate-limited: 1/min, 5/hour |
| `POST /api/v1/auth/password/forgot` | — | `email` | `data: null` (always — no existence leak) | Rate-limited |
| `POST /api/v1/auth/password/reset` | — | `email`, `token`, `password`, `password_confirmation` | `data: null` | `422` expired token |
| `GET /api/v1/auth/sessions` | User | — | `[{id, device_label, ip_address, last_used_at, expires_at, is_current}]` | Cursor pagination |
| `DELETE /api/v1/auth/sessions/{id}` | User | — | `data: null` | `404` for another user's session (IDOR-safe) |
| `GET /api/v1/me` | User | — | `user {uuid, email, display_name, locale, timezone, email_verified_at, roles[]}` | |
| `PATCH /api/v1/me` | User | `display_name?`, `locale?` (`en` at v1; `ur` rejected until Urdu ships), `timezone?` | Updated `user` | `validation_failed` |
| `POST /api/v1/me/password/change` | User | `current_password`, `password`, `password_confirmation` | `data: null` | `422` wrong current password; revokes other sessions |
| `DELETE /api/v1/me` | User | `password` (confirmation) | `data: null` | **Soft delete + anonymisation** (§C.4.4); entitlements/ledger retained |

### E.8.2 Catalogue, search & categories

| Method & path | Auth | Request (key fields) | Response (key fields) | Errors / notes |
|---|---|---|---|---|
| `GET /api/v1/apps/featured` | — | `limit?` (default 10, max 20) | `[{app summary}]` — admin-curated (`system_settings`/Filament featured list), ordered by admin `sort_order` | Cursor pagination if `limit` exceeded |
| `GET /api/v1/apps/trending` | — | `limit?`, `window?` (`7d`/`30d`, default `7d`) | `[{app summary, trend_score}]` — download-velocity over the window; **score is a rank, never a fake count** | |
| `GET /api/v1/apps/new` | — | `cursor?`, `limit?` | `[{app summary}]` sorted `published_at DESC` | Cursor pagination |
| `GET /api/v1/apps/updated` | — | `cursor?`, `limit?` | `[{app summary, current_version {version_name, version_code, published_at}}]` | Recently updated, `published_at DESC` |
| `GET /api/v1/apps` | — | `category?` (slug), `q?`, `price?` (`free`/`paid`/`all`), `sort?` (`relevance`/`rating`/`popularity`/`newest`), `cursor?`, `limit?` | `[{app summary}]` + `meta.cursor` | `sort=relevance` only valid with `q`; otherwise `422` |
| `GET /api/v1/apps/search` | — | `q` (min 2 chars), `category?`, `price?`, `sort?`, `cursor?`, `limit?` | `[{app summary, search_snippet?}]` + `meta.cursor` | Postgres FTS at v1 (§E.10); empty `q` → `422` |
| `GET /api/v1/apps/search/suggestions` | — | `q` (min 2 chars) | `[{suggestion, type: app\|developer\|category}]` max 8 | Cached 5 min in Redis; no personal data |
| `GET /api/v1/apps/{uuid}` | — | — | Full app detail: `attributes` (name, description, price, icon, screenshots, permissions, privacy_policy_url, support_email, security_status, rating, review_count, download_count), `relationships` (developer, categories, current_version, versions[]) | `404` if draft/unpublished and caller is not the owner; `410 app_removed` if removed |
| `GET /api/v1/apps/{uuid}/versions` | — | `cursor?`, `limit?` | `[{version_name, version_code, published_at, release_notes, apk_size_bytes, min_sdk, target_sdk, sha256}]` — **no download URL here** | Only published versions listed |
| `GET /api/v1/categories` | — | — | `[{slug, name_en, name_ur, app_count, sort_order}]` | Cached 1 h; `app_count` counts published apps only |
| `GET /api/v1/categories/{slug}` | — | `cursor?`, `limit?`, `sort?` | Category + `[{app summary}]` | `404` unknown/inactive slug |

**App summary** (used by every list endpoint): `{ id (uuid), type: "app", attributes: { name, package_name, short_description, icon_url, price_minor, currency, average_rating, review_count, download_count, current_version { version_name, version_code }, developer { slug, display_name, verification_status } } }`. List endpoints never return full descriptions, screenshots or permission lists — those are detail-only, to keep catalogue payloads small on slow networks.

### E.8.3 Reviews & favourites

| Method & path | Auth | Request (key fields) | Response (key fields) | Errors / notes |
|---|---|---|---|---|
| `GET /api/v1/apps/{uuid}/reviews` | — | `cursor?`, `limit?`, `sort?` (`newest`/`highest`/`lowest`, default `newest`) | `[{rating, body, verified_purchase, developer_reply, created_at, user {display_name}}]` + `meta.rating_summary {average, count, histogram {1..5}}` | Hidden/deleted reviews excluded; cursor pagination |
| `POST /api/v1/apps/{uuid}/reviews` | User | `rating` (1–5), `body?` (≤ 2,000 chars) | Created review | `409` if a review already exists (use PATCH); `verified_purchase` computed server-side |
| `PATCH /api/v1/apps/{uuid}/reviews/mine` | User | `rating?`, `body?` | Updated review | `404` if no review exists |
| `DELETE /api/v1/apps/{uuid}/reviews/mine` | User | — | `data: null` | Soft delete; aggregates recomputed |
| `POST /api/v1/apps/{uuid}/reviews/{id}/report` | User | `reason` (`spam`/`abuse`/`fake`/`off_topic`/`other`), `details?` | Created `review_report` | `409` duplicate report by same user |
| `POST /api/v1/apps/{uuid}/reviews/{id}/reply` | Dev (owner only) | `body` (≤ 2,000 chars) | Review with `developer_reply` | `403` if caller does not own the app; one reply per review (PATCH to edit — next row) |
| `PATCH /api/v1/apps/{uuid}/reviews/{id}/reply` | Dev (owner only) | `body` | Updated review | `403` non-owner |
| `GET /api/v1/me/favourites` | User | `cursor?`, `limit?` | `[{app summary}]` | Cursor pagination |
| `POST /api/v1/me/favourites/{app_uuid}` | User | — | `data: null` | Idempotent: favouriting twice is a no-op `200` |
| `DELETE /api/v1/me/favourites/{app_uuid}` | User | — | `data: null` | Idempotent |
| `GET /api/v1/me/favourites/{app_uuid}` | User | — | `{ favourited: bool }` | Cheap check for the app-detail heart state |

### E.8.4 Downloads, library & updates

| Method & path | Auth | Request (key fields) | Response (key fields) | Errors / notes |
|---|---|---|---|---|
| `POST /api/v1/apps/{uuid}/downloads` | User | `version_code?` (default: current published) | `{ download_url (short-lived signed URL), expires_at, sha256, size_bytes, version_code, version_name }` | **Authorisation point.** Free: any authenticated user. Paid: requires live `entitlements` row (else `402` — see below). Returns `410` if version revoked. Signed URL TTL from `system_settings.downloads.link_ttl_seconds` (default 900 s) |
| `POST /api/v1/apps/{uuid}/downloads/{version_code}/confirm` | User | `sha256_verified: bool`, `status: completed\|failed` | `data: null` | Client reports post-download verification; feeds `downloads` audit + abuse signals. A `false` result triggers a server-side re-check job |
| `GET /api/v1/me/downloads` | User | `cursor?`, `limit?` | `[{app summary, version_code, status, created_at}]` | Download history; cursor pagination |
| `GET /api/v1/me/library` | User | `cursor?`, `limit?` | `{ installed: […], purchased: […], favourites: […] }` — `installed` is client-reported (see note) | `installed` is populated from the client's installed-package report, not from server detection |
| `POST /api/v1/me/library/installed` | User | `packages: [{package_name, version_code}]` (max 200) | `{ recognised: [{package_name, app_uuid, update_available, latest_version_code}] }` | **Update check.** Server compares each `version_code` against the latest published version for that package; returns only GOHAR-listed apps. No data stored beyond the request unless the user opts into update notifications |
| `GET /api/v1/me/updates` | User | `cursor?`, `limit?` | `[{app summary, installed_version_code, latest_version {version_name, version_code, release_notes, apk_size_bytes}}]` | Derived from the last installed report + entitlements; empty until the client reports |

> **HTTP 402.** Download authorisation for a paid app without an entitlement returns `402 Payment Required` with code `entitlement_required` and `detail` pointing at the order flow. `402` is used deliberately — it is not a generic `403`, so clients can route it to the purchase screen.
>
> **Install handoff.** The API never installs anything. The Android app downloads the APK to its private storage, verifies SHA-256, then hands the file to the system `PackageInstaller` (user-consent flow). The server's job ends at the signed URL + hash.

### E.8.5 Orders, payments & entitlements

| Method & path | Auth | Request (key fields) | Response (key fields) | Errors / notes |
|---|---|---|---|---|
| `POST /api/v1/orders` | User | `app_uuid`, `idempotency_key` (header, required), `currency?` | `order {uuid, total_minor, currency, status: "created", items[]}` | `409` re-purchase of owned app (`already_owned`); `422` app not purchasable (free app → use download flow); idempotent (§E.7) |
| `GET /api/v1/orders/{uuid}` | User (owner) | — | `order` + `items[]` + `payment {status, provider}` | `404` for another user's order |
| `GET /api/v1/me/orders` | User | `page?`, `per_page?` | `[{order summary}]` + `meta.pagination` | Offset pagination |
| `POST /api/v1/orders/{uuid}/payments` | User (owner) | `provider` (must be enabled in `system_settings.payments.providers`), `idempotency_key` (header, required), `return_url?` | `payment {uuid, status: "initiated", provider, redirect_url? \| provider_payload}` | Creates the payment + provider session. **No entitlement is created here.** Provider redirect (Easypaisa/JazzCash hosted page) or SDK payload returned as data, never as a client-side success signal |
| `GET /api/v1/orders/{uuid}/payments/{payment_uuid}` | User (owner) | — | `payment {status, amount_minor, currency, provider, paid_at}` | Polling endpoint for the client while the provider page completes; final state comes from the webhook, this only *reads* it |
| `POST /api/v1/orders/{uuid}/cancel` | User (owner) | — | Updated `order` | Only while `status` is `created`/`pending_payment` |
| `GET /api/v1/me/entitlements` | User | `cursor?`, `limit?` | `[{app summary, granted_at, order_uuid}]` | Cursor pagination; revoked entitlements excluded |
| `GET /api/v1/me/entitlements/{app_uuid}` | User | — | `{ entitled: bool, granted_at?, order_uuid? }` | Cheap check for the app-detail buy/download button state |
| `POST /api/v1/orders/{uuid}/refunds` | User (owner) | `reason`, `reason_detail?`, `amount_minor?` (default: full) | `refund {id, status: "requested", amount_minor, currency}` | Creates a *request*; approval is admin/finance. Partial refunds allowed; total refunds per payment capped at the payment amount (§C.3.4) |
| `GET /api/v1/me/refunds` | User | `page?`, `per_page?` | `[{refund summary}]` + `meta.pagination` | Offset pagination |

### E.8.6 Developer — profiles, verification & payout accounts

| Method & path | Auth | Request (key fields) | Response (key fields) | Errors / notes |
|---|---|---|---|---|
| `POST /api/v1/developer/profile` | User | `slug`, `display_name`, `legal_name?`, `country_code`, `website_url?`, `support_email?`, `bio?` | `developer_profile` | Creates the profile + grants `developer` role. `slug` immutable after first publish |
| `GET /api/v1/developer/profile` | Dev | — | `developer_profile` + `verification_status` + `balance {available_minor, pending_minor, currency}` | Balance is derived from `developer_ledger`, never stored as a column |
| `PATCH /api/v1/developer/profile` | Dev | Any editable field except `slug` (after publish) and `verification_status` | Updated profile | |
| `POST /api/v1/developer/verifications` | Dev | `type`, `document` (multipart file: PDF/JPG/PNG, ≤ 10 MB, content-sniffed) | `developer_verification {id, type, status: "pending"}` | Document goes to **private** R2; never a public URL |
| `GET /api/v1/developer/verifications` | Dev | — | `[{verification}]` | |
| `GET /api/v1/developer/payout-accounts` | Dev | — | `[{id, provider, account_label, currency, is_default, status}]` — **never the full account reference** | |
| `POST /api/v1/developer/payout-accounts` | Dev | `provider`, `account_ref`, `currency`, `is_default?` | Created account (label only) | `account_ref` encrypted at rest (§C.3.1); `provider` must be allowed for the developer's `country_code` |
| `PATCH /api/v1/developer/payout-accounts/{id}` | Dev | `is_default?` | Updated account | Changing the account reference requires DELETE + re-create (no in-place edit of financial destinations) |
| `DELETE /api/v1/developer/payout-accounts/{id}` | Dev | — | `data: null` | Blocked (`409`) if the account is the destination of a pending payout |

### E.8.7 Developer — apps, submissions & releases

| Method & path | Auth | Request (key fields) | Response (key fields) | Errors / notes |
|---|---|---|---|---|
| `GET /api/v1/developer/apps` | Dev | `page?`, `per_page?`, `status?` | `[{app summary + status, current_version, latest_submission {status}}]` + `meta.pagination` | Offset pagination; only own apps |
| `POST /api/v1/developer/apps` | Dev | `package_name`, `name`, `short_description?`, `description?`, `category_slugs[]` (≥ 1), `price_minor` (default 0), `currency?`, `privacy_policy_url?`, `support_email?` | Created `app` (`status: "draft"`) | `409 package_name_taken`; `403 developer_not_verified` if profile unverified (creation allowed, *submission* blocked — see next rows) |
| `GET /api/v1/developer/apps/{uuid}` | Dev (owner) | — | Full app incl. draft fields + all versions + submissions | `404` for another developer's app |
| `PATCH /api/v1/developer/apps/{uuid}` | Dev (owner) | Any metadata field; `price_minor` change applies to *future* orders only | Updated app | Price change never rewrites `order_items` history |
| `POST /api/v1/developer/apps/{uuid}/icon` | Dev (owner) | Multipart `icon` (PNG/WebP, ≤ 2 MB, square, content-sniffed) | `{ icon_url (preview, signed, 1 h) }` | Stored private until publish |
| `POST /api/v1/developer/apps/{uuid}/screenshots` | Dev (owner) | Multipart `screenshots[]` (≤ 8, each ≤ 5 MB) | `[{screenshot id, sort_order}]` | Replaces the set if `replace=true` |
| `DELETE /api/v1/developer/apps/{uuid}` | Dev (owner) | — | `data: null` | Soft delete; blocked (`409`) if the app has paid orders (use admin takedown flow instead) |
| `POST /api/v1/developer/apps/{uuid}/submissions` | Dev (owner) | Multipart `apk` (≤ `security.max_apk_bytes`, content-sniffed as APK), `version_name`, `release_notes?`, `idempotency_key` (header) | `submission {id, status: "received", app_version {version_code (extracted), sha256}}` | **The upload endpoint.** `version_code` is *extracted from the APK*, never client-supplied. `409 version_code_not_monotonic` / `signing_fingerprint_mismatch` surface here or after async validation — the response states `status: "received"` and the client polls; synchronous rejection only for structural failures (not an APK, oversize, unauthorised) |
| `GET /api/v1/developer/apps/{uuid}/submissions` | Dev (owner) | `page?`, `per_page?` | `[{submission + decision_reason + validation_summary}]` + `meta.pagination` | |
| `GET /api/v1/developer/submissions/{id}` | Dev (owner) | — | Full submission: `validation_results[]`, `security_scans[]`, `risk_assessment {score_0_100, band, factors[]}`, `status`, `decision_reason` | The developer's view of *why* a release was held — evidence included, raw scanner dumps truncated |
| `POST /api/v1/developer/submissions/{id}/withdraw` | Dev (owner) | — | Updated submission (`status: "withdrawn"`) | Only while `received`/`validating`/`awaiting_review` |
| `GET /api/v1/developer/apps/{uuid}/versions` | Dev (owner) | — | All versions incl. quarantined/rejected (owner-only view) | Public version list is §E.8.2; this is the owner's full history |
| `POST /api/v1/developer/apps/{uuid}/versions/{version_id}/publish` | Dev (owner) | — | Updated `app` + `app_version` | **Only callable when the submission is `approved`.** Publishes atomically: version → `published`, previous → `superseded`, `apps.current_version_id` updated, `repository_releases` row created. `409` if not approved |
| `POST /api/v1/developer/apps/{uuid}/versions/{version_id}/revoke` | Dev (owner) | `reason` (required) | Updated version (`status: "revoked"`) | Revocation removes it from the repository index and blocks new downloads; existing installs are unaffected (Android cannot remotely uninstall) |

### E.8.8 Developer — analytics, earnings & payouts

| Method & path | Auth | Request (key fields) | Response (key fields) | Errors / notes |
|---|---|---|---|---|
| `GET /api/v1/developer/analytics/overview` | Dev | `range?` (`7d`/`30d`/`90d`, default `30d`) | `{ downloads, sales_minor, currency, new_reviews, average_rating, top_apps[] }` | Aggregated from queued counters, not live `COUNT(*)` |
| `GET /api/v1/developer/analytics/apps/{uuid}` | Dev (owner) | `range?`, `granularity?` (`day`/`week`) | `{ series: [{date, downloads, sales_minor}], by_version[], by_country[] (if collected) }` | |
| `GET /api/v1/developer/earnings` | Dev | `page?`, `per_page?` | `{ balance {available_minor, pending_minor, currency}, entries: [{type, amount_minor_signed, running_balance_minor, reference, created_at}] }` + `meta.pagination` | Straight from `developer_ledger`; offset pagination |
| `GET /api/v1/developer/earnings/statement` | Dev | `month` (`YYYY-MM`) | `{ month, opening_minor, sales_minor, commission_minor, provider_fees_minor, refunds_minor, payouts_minor, closing_minor, currency, entries[] }` | Monthly statement; PDF export is a later addition, not v1 |
| `GET /api/v1/developer/payouts` | Dev | `page?`, `per_page?` | `[{payout summary}]` + `meta.pagination` | |
| `POST /api/v1/developer/payouts` | Dev | `payout_account_id`, `amount_minor`, `currency`, `idempotency_key` (header, required) | `payout {uuid, status: "requested", amount_minor}` | `422` below minimum / above available balance; `403 developer_not_verified`; idempotent (§E.7) |
| `GET /api/v1/developer/payouts/{uuid}` | Dev (owner) | — | Full payout + `items[]` (which ledger entries it settles) | |
| `POST /api/v1/developer/payouts/{uuid}/cancel` | Dev (owner) | — | Updated payout | Only while `requested` (before approval) |

### E.8.9 Admin — moderation, security, finance, catalogue & settings

> Admin endpoints are consumed by Filament (session auth) and, where noted, by the admin API with a session token. **No Sanctum user token can call them.** All of them write `audit_logs` (actor, action, target, reason, before/after).

| Method & path | Auth | Request (key fields) | Response (key fields) | Errors / notes |
|---|---|---|---|---|
| `GET /api/v1/admin/dashboard` | Admin | — | `{ totals {users, developers, apps_published, submissions_pending, revenue_minor_30d, payouts_pending}, queues {submissions_awaiting_review, reports_open, refunds_requested}, alerts[] }` | |
| `GET /api/v1/admin/users` | Admin | `page?`, `q?`, `role?`, `status?` | `[{user + roles[]}]` + `meta.pagination` | Offset pagination |
| `POST /api/v1/admin/users/{id}/suspend` | Admin | `reason` (required) | Updated user | Writes `audit_logs`; sessions revoked |
| `POST /api/v1/admin/users/{id}/reinstate` | Admin | `reason` (required) | Updated user | |
| `GET /api/v1/admin/developers` | Admin | `page?`, `verification_status?`, `q?` | `[{developer_profile + user + balance}]` + `meta.pagination` | |
| `GET /api/v1/admin/developers/{id}/verifications` | Admin | — | `[{verification + document_url (signed, 15 min, reviewer-only)}]` | Document URLs are short-lived and reviewer-scoped |
| `POST /api/v1/admin/developers/{id}/verifications/{vid}/approve` | Admin | `note?` | Updated verification; profile `verification_status` → `verified` when all required types pass | |
| `POST /api/v1/admin/developers/{id}/verifications/{vid}/reject` | Admin | `note` (required — shown to developer) | Updated verification | |
| `GET /api/v1/admin/submissions` | Mod | `page?`, `status?` (`awaiting_review` default), `risk_band?` | `[{submission + app + risk_assessment + validation_summary}]` + `meta.pagination` | **The review queue.** Sorted oldest-first |
| `GET /api/v1/admin/submissions/{id}` | Mod | — | Full submission + all `validation_results`, `security_scans`, `risk_assessment`, APK metadata, signing history, prior versions | Reviewer workspace |
| `POST /api/v1/admin/submissions/{id}/approve` | Mod | `note?` | Updated submission (`approved`); version → `approved`; developer notified | Only from `awaiting_review`; auto-approved (`low` band) submissions never reach this queue |
| `POST /api/v1/admin/submissions/{id}/reject` | Mod | `reason` (required) | Updated submission (`rejected`); version → `rejected` | `reason` is shown to the developer verbatim |
| `POST /api/v1/admin/apps/{uuid}/suspend` | Mod | `reason` (required) | Updated app (`suspended`); removed from catalogue + repository index | Existing entitlements are **not** revoked by suspension alone |
| `POST /api/v1/admin/apps/{uuid}/reinstate` | Mod | `reason` (required) | Updated app (`published`) | |
| `POST /api/v1/admin/apps/{uuid}/remove` | Admin | `reason` (required) | Updated app (`removed`); entitlements revoked with `revoke_reason: "app_removed"` | Takedown; irreversible without a new submission |
| `GET /api/v1/admin/security/alerts` | Mod | `page?`, `severity?` | `[{alert: signing mismatch, lookalike package, scanner inconclusive, download anomaly, …}]` + `meta.pagination` | Derived from `validation_results`/`security_scans`/`downloads` — no separate alert table at v1 |
| `GET /api/v1/admin/security/scans/{id}` | Mod | — | Full scan + findings | |
| `GET /api/v1/admin/reviews` | Mod | `page?`, `status?` (reported/hidden) | `[{review + app + reports[]}]` + `meta.pagination` | Review moderation queue |
| `POST /api/v1/admin/reviews/{id}/hide` | Mod | `reason` (required) | Updated review (`is_hidden: true`) | |
| `POST /api/v1/admin/reviews/{id}/unhide` | Mod | `reason` (required) | Updated review | |
| `GET /api/v1/admin/reports` | Mod | `page?`, `type?` (`app`/`review`), `status?` | `[{app_reports + review_reports}]` + `meta.pagination` | |
| `POST /api/v1/admin/reports/{id}/resolve` | Mod | `resolution` (`action_taken`/`dismissed`), `note?` | Updated report | |
| `GET /api/v1/admin/orders` | Fin | `page?`, `status?`, `q?` | `[{order + payment}]` + `meta.pagination` | |
| `GET /api/v1/admin/refunds` | Fin | `page?`, `status?` | `[{refund + order + payment}]` + `meta.pagination` | |
| `POST /api/v1/admin/refunds/{id}/approve` | Fin | `note?` | Refund → `approved` → provider refund initiated (queued job) | Approval and provider execution are separate steps; failure of the provider call leaves the refund `processing` with a retry |
| `POST /api/v1/admin/refunds/{id}/reject` | Fin | `reason` (required) | Refund → `rejected` | |
| `GET /api/v1/admin/payouts` | Fin | `page?`, `status?` | `[{payout + developer + account_label}]` + `meta.pagination` | |
| `POST /api/v1/admin/payouts/{id}/approve` | Fin | `note?` | Payout → `approved` | **Cannot approve own request**; approver ≠ requester enforced (§C.3.4) |
| `POST /api/v1/admin/payouts/{id}/reject` | Fin | `reason` (required) | Payout → `rejected`; ledger entries released back to available balance | |
| `POST /api/v1/admin/payouts/{id}/mark-paid` | Fin | `provider_reference` (required) | Payout → `paid` | Records the external transfer reference |
| `GET /api/v1/admin/commission` | Fin | `page?`, `month?` | `[{commission_entries}]` + totals | |
| `GET /api/v1/admin/categories` | Admin | — | All categories incl. inactive | |
| `POST /api/v1/admin/categories` | Admin | `slug`, `name_en`, `name_ur?`, `parent_id?`, `sort_order?` | Created category | `slug` immutable after creation |
| `PATCH /api/v1/admin/categories/{id}` | Admin | `name_en?`, `name_ur?`, `sort_order?`, `is_active?` | Updated category | Deactivating a category hides it; apps keep their other categories |
| `GET /api/v1/admin/featured` | Admin | — | Featured list + sort order | |
| `PUT /api/v1/admin/featured` | Admin | `app_uuids[]` (ordered, max 20) | Updated featured list | Replaces the list atomically |
| `GET /api/v1/admin/announcements` | Admin | `page?` | `[{announcement}]` + `meta.pagination` | |
| `POST /api/v1/admin/announcements` | Admin | `title`, `body`, `audience`, `starts_at?`, `ends_at?` | Created announcement | Publishes a `notifications` row to the audience (queued) |
| `GET /api/v1/admin/settings` | Admin | — | `{ commission_rate, payout_minimum_minor, risk_bands, downloads_link_ttl_seconds, … }` (non-secret settings only) | Secrets/credentials are **never** returned by this endpoint |
| `PATCH /api/v1/admin/settings` | Admin | `{ key, value }` per setting | Updated setting | Writes `audit_logs`; commission changes apply to *future* sales only (see `commission_entries.commission_rate` snapshot, §C.3.4) |
| `GET /api/v1/admin/audit-logs` | Admin | `page?`, `actor_id?`, `action?`, `target_type?`, `from?`, `to?` | `[{audit_logs}]` + `meta.pagination` | Read-only; no endpoint can modify audit logs |
| `GET /api/v1/admin/roles` | Super-admin | — | `[{role + permissions[]}]` | |
| `POST /api/v1/admin/users/{id}/roles` | Super-admin | `role_slug` | Updated user roles | Role assignment is super-admin only; writes `audit_logs` |

### E.8.10 Security reporting (public / authenticated)

| Method & path | Auth | Request (key fields) | Response (key fields) | Errors / notes |
|---|---|---|---|---|
| `POST /api/v1/apps/{uuid}/report` | User | `reason` (`malware`/`fraud`/`misleading`/`copyright`/`inappropriate`/`privacy`/`other`), `details?` | Created `app_report` | `409` duplicate open report by same user for same app |
| `GET /api/v1/me/reports` | User | `page?` | `[{app_reports + review_reports}]` + `meta.pagination` | Own reports only |
| `GET /api/v1/security/disclosure` | — | — | `{ policy_url, contact, pgp_key_url? }` | Static disclosure info; the vulnerability-disclosure policy itself is a Milestone 10 document, not an API behaviour |

### E.8.11 System

| Method & path | Auth | Request | Response | Notes |
|---|---|---|---|---|
| `GET /api/v1/health` | — | — | `{ status: "ok", version: "1.x.y", checks: { database, redis, storage } }` | Load-balancer / uptime probe. Returns `503` if any check fails. No auth, no PII |
| `GET /api/v1/config` | — | — | `{ commission_rate (public, informational), currencies_supported, payment_providers_enabled[], min_app_version, force_upgrade: bool, maintenance: bool }` | Public client bootstrap config. **Never includes secrets.** `force_upgrade` drives the Android forced-update prompt |

---

## E.9 Webhooks (inbound, provider → GOHAR)

| Item | Contract |
|---|---|
| Endpoint | `POST /api/v1/webhooks/{provider}` where `{provider}` ∈ `easypaisa`, `jazzcash`, `card` (provider key from `system_settings.payments.providers`; unknown provider → `404`) |
| Auth | **HMAC signature** over the raw request body, using the provider-specific webhook secret (env/secret manager, per provider). Header name is provider-specific (e.g. `X-Signature`, `X-Easypaisa-Signature` — documented per provider in the Milestone 8 integration note). Signature verification happens **before** any JSON parsing or business logic |
| Replay window | Provider timestamp (where supplied) must be within **±5 minutes** of server time, else `401` (replay protection). Where the provider supplies no timestamp, the `provider_event_id` uniqueness check (§C.3.4, `payment_events.provider_event_id UNIQUE`) is the replay guard |
| Idempotency | Every webhook is recorded in `payment_events` **before** processing, keyed by `provider_event_id`. A duplicate delivery returns `200` with the original outcome and does no work |
| Response | `200 { "received": true }` on acceptance (including duplicates). `401` on bad signature. `400` on malformed payload. Processing is **asynchronous**: the endpoint validates + enqueues a job and returns immediately; the job does the state transition, so provider retries never double-process |
| State machine | `payment.succeeded` (verified) → `payments.status = succeeded` → `orders.status = paid` → **entitlement created** → `commission_entries` created → `developer_ledger` credited — all in **one transaction**. Any step failing rolls back the whole transition and the job retries with back-off |
| Failure handling | Invalid signature → `401`, event logged in `payment_events` with `signature_valid: false`, **no state change**, alert to admin. Unknown `provider_payment_id` → `200` (acknowledge, to stop provider retries) + `audit_logs` entry + admin alert — never `404` (a `404` would make some providers retry forever) |
| Outbound webhooks | **None at v1.** GOHAR does not push webhooks to developers at launch. Developer notifications are in-app + email. Outbound webhooks are a post-launch addition with their own signing design |

## E.10 Search design (v1)

- **Engine: PostgreSQL full-text search (FTS) — recommended first, zero-spend.** `apps.search_vector` (generated `tsvector`, GIN index) + `plainto_tsquery` / `websearch_to_tsquery` with `ts_rank_cd` ordering. Covers app name, short description, developer display name (via trigger-maintained column), and tags (joined, weighted lower).
- **Why not Meilisearch/Typesense at launch:** both are excellent, but each adds a service to operate, back up and keep in sync (dual-write risk). Postgres FTS handles the launch catalogue (hundreds to low-thousands of apps) with acceptable relevance. The API contract (`GET /apps/search`, `sort=relevance`) is engine-agnostic, so swapping the engine later does not change any client.
- **When to revisit:** catalogue > ~10,000 apps, or relevance complaints (typo tolerance, Urdu stemming), or faceted search demand. The trigger is a product decision, not a rewrite — the endpoint shape stays the same.
- **Suggestions** (`GET /apps/search/suggestions`) are prefix matches over app names + developer names + category names, cached 5 minutes in Redis, capped at 8 results. No per-user suggestion history at v1.

## E.11 What the OpenAPI document will contain (Milestone 4)

The generated OpenAPI 3.1 document (served at `/api/docs` in non-production, and as a versioned file in the repo at `docs/openapi/v1.yaml`) must be generated from the implementation and must match this document section-for-section. It will contain, for every endpoint above:

1. Path, method, tags, summary, `operationId`.
2. Security scheme (Sanctum bearer) per endpoint.
3. Request/response schemas with types, required fields, enums, and examples — **money as `integer` + `currency` string, never `number`**.
4. The shared `Error` schema (`{ data: null, errors: [{ code, title, detail, field?, status }] }`).
5. Pagination parameters and the `meta` schemas for cursor and offset styles.
6. `Idempotency-Key` header on the endpoints listed in §E.7.
7. Webhook schemas per provider (request body + signature header).

**Generation rule.** The OpenAPI file is generated, never hand-edited. If this document and the generated spec disagree, the discrepancy is a bug to fix in whichever side is wrong — the fix is recorded in the milestone report, not silently resolved.

## E.12 API testing checklist (traceability to Milestone 4 / 8)

Every endpoint row above maps to at least one automated test. The minimum test list (executed in Milestones 4 and 8, results recorded in the milestone reports — **no test is claimed passed before it runs**):

| Area | Tests (minimum) |
|---|---|
| Auth | Register → verify → login → use token; wrong password → generic `401`; unverified user blocked from purchase (`403 email_not_verified`); token revocation ends access; expired token → `401` |
| Authorisation | User A cannot read user B's order (`404`); developer cannot read another developer's app (`404`); user token cannot call any `/admin/*` endpoint (`403`); moderator cannot approve payouts (`403`) |
| Catalogue | Unpublished app → `404` for strangers, `200` for owner; removed app → `410`; search returns ranked results; suggestions capped at 8 |
| Downloads | Free download authorised without purchase; paid download without entitlement → `402`; signed URL expires (TTL); tampered hash → confirm endpoint records `false` and triggers re-check |
| Payments | Order creation is idempotent (same key → same order); same key + different body → `409`; webhook with bad HMAC → `401` + no state change; duplicate webhook → single entitlement; client-side "success" callback alone creates **no** entitlement |
| Commerce | Commission math: `FLOOR(gross × rate)`, remainder to developer; refund reverses ledger (compensating entry, original untouched); payout cannot exceed balance; payout approval by requester rejected |
| Submissions | Non-monotonic `version_code` → `409`; signing mismatch → manual review forced; package mismatch → validation `fail`; unknown scanner result (`inconclusive`/`error`) blocks auto-approval |
| Pagination/rate limits | Cursor advances without duplicates or gaps; `429` with `Retry-After` when the limit is exceeded |

---

## Open decisions

| # | Decision | Recommendation | Owner / when |
|---|---|---|---|
| 1 | **Launch currencies** | `PKR` only at launch; `USD` added when the first international provider is approved. Multi-currency *pricing* (different price per currency) is deferred — v1 charges the app's single `currency` | Talha to confirm before Milestone 4 |
| 2 | **Search engine** | **Postgres FTS first (zero-spend)** — see §E.10. Meilisearch/Typesense only if the revisit triggers fire | Architecture decision — approved with this document unless challenged |
| 3 | **Payout minimum** | Seeded `payouts.minimum_minor = 500000` (PKR 5,000) — **placeholder, to be confirmed** | Talha to confirm before Milestone 8 |
| 4 | **Max APK size** | Seeded `security.max_apk_bytes = 524288000` (500 MB) — **placeholder, to be confirmed** against R2/CDN costs | Talha to confirm before Milestone 6 |
| 5 | **Production domain** | `https://api.gohar.store` is a **PLACEHOLDER**. Confirm the real domain (and whether the storefront/marketing site shares it) before Milestone 2 DNS/TLS work | Talha to confirm |
| 6 | **OpenAPI generator** | Laravel Scribe vs `l5-swagger` — pick in Milestone 4; the contract in §E is generator-agnostic | Engineering, Milestone 4 |
| 7 | **Urdu descriptions** | `description_ur` column now; a full `app_translations` table when a second localised field is needed. Do not build the translations table prematurely | Engineering, Milestone 4 |
| 8 | **Guest (unauthenticated) downloads** | **No** at v1 — downloads require an account. Revisit only with an abuse case (rate limits + signed URLs + hash verification make guest downloads feasible later, but they complicate entitlement and analytics) | Talha to confirm |
| 9 | **Multi-item carts** | **No** at v1 — one app per order (`UNIQUE (order_id, app_id)`). The schema already supports carts; the API enforces one item until carts are designed | Architecture decision — approved with this document unless challenged |
| 10 | **Notification delivery** | In-app + email at v1. Push notifications (FCM) are a Milestone 9 addition, not a v1 dependency | Engineering, Milestone 9 |
| 11 | **Risk-band thresholds** | Seeded `risk.bands = {"low": 30, "medium": 60, "high": 85}` — **starting values**, tuned from real submission data in Milestone 6 | Engineering, Milestone 6 |

---

*End of `02-database-api.md` — Sections C & E of the GOHAR Store First Deliverable (Architecture v1, 26 Nov 2026). Companion: `01-audit-architecture.md` (A, B, D, F, G, H, I, J).*
