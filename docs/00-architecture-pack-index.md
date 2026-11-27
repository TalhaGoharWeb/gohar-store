# GOHAR Store — Architecture v1 (First Deliverable)

**Status:** DRAFT FOR APPROVAL — 26 November 2026
**Brand:** GOHAR Store — "Where Great Apps Belong."
**No code has been built.** Nothing here claims an implementation, test result, or deployed service exists. Implementation starts only after Talha approves this architecture (Milestone 1 exit).

## What is in this pack (A–J)

| # | Section | File |
|---|---------|------|
| A | Repository audit | `docs/01-audit-architecture.md` |
| B | System architecture diagram + design system | `docs/01-audit-architecture.md` |
| C | Database relationship diagram + schema (41 brief tables + 2 RBAC pivots) | `docs/02-database-api.md` |
| D | Android screen map | `docs/01-audit-architecture.md` |
| E | API specification (`/api/v1/...`) | `docs/02-database-api.md` |
| F | APK security pipeline design | `docs/03-pipelines-milestones-risks.md` |
| G | Payment and ledger design | `docs/03-pipelines-milestones-risks.md` |
| H | Development milestones M1–M10 with dependencies | `docs/03-pipelines-milestones-risks.md` |
| I | Infrastructure and deployment plan | `docs/01-audit-architecture.md` |
| J | Risk register + threat model | `docs/03-pipelines-milestones-risks.md` |

Total: 2,857 lines across the three docs.

## Headline findings — read these first

1. **No existing repo exists to audit.** No GitHub URL was supplied, no `gohar-store` folder existed in the workspace, memory search found nothing. Section A records exactly what was checked and what access is needed if a repo does exist. Recommended path: clean project (brief §5 anticipates this).
2. **F-Droid cannot be the base.** Overall AGPL-3.0, client GPL-3.0, fdroidserver AGPL-3.0. It re-signs apps with its own key and hosts only FOSS built from source — incompatible with preserve-developer-signing + paid/proprietary apps. Borrow concepts only (signed index, update checks).
3. **Zero-spend vs paid infra is an open conflict.** The brief asks for VPS + Cloudflare + R2 + CDN, all of which cost money. Talha's hard rule is zero spend. Milestones 1–6 are scoped to zero-spend local work (Docker, MinIO, Mailpit, sandbox payments). No spend is assumed approved.
4. **Money rules are locked in the design:** integer minor units + currency code (never float), 15% commission configurable in `system_settings` (never hardcoded), no entitlement from a client-side success message — server verification + idempotent webhook only.
5. **APK rule is fail-closed:** auto-approve only if ALL mandatory checks pass. Unknown / ambiguous = manual review, never "safe". Untrusted APKs are never executed on production. Developer signing is preserved; GOHAR never re-signs APKs. Repository metadata signing uses a separate GOHAR key.
6. **Honest effort:** M2–M10 estimated 36–53 weeks solo (~7–11 months sustained), to be re-baselined after M2. This is a multi-month commercial build, not a weekend project.

## Decisions needed from Talha before M2

See §"Decisions Needed" at the end of `docs/03-pipelines-milestones-risks.md` (D-1 to D-9). The biggest ones:

1. Approve this architecture (or request changes — say which section).
2. Infra spend: named budget vs reduced free-tier vs defer to M10.
3. Repo location / visibility (new GitHub repo? name? private or public?).
4. First-release rule for unverified developers (auto-approve eligible vs always manual review first).
5. Money defaults: commission basis (gross vs gross-minus-provider-fee), min payout (suggested PKR 5,000), refund window (suggested 14 days), payout schedule (suggested weekly).
6. Payment-provider evaluation order + who pursues Easypaisa / JazzCash merchant access.
7. Second-opinion malware scanner: yes / no.
8. Dynamic-analysis pilot: yes / no.
9. Controlled-launch shape.

## How to use this pack

- Read `docs/01-audit-architecture.md` first for the verdict, architecture, screens, and infra.
- Use `docs/02-database-api.md` as the schema + API reference.
- Use `docs/03-pipelines-milestones-risks.md` for the security pipeline, money flows, plan, and risks.
- All diagrams are Mermaid — renderable in GitHub / VS Code / any Mermaid viewer.
- `https://api.gohar.store` in the API spec is a PLACEHOLDER domain to be confirmed, not a live service.
