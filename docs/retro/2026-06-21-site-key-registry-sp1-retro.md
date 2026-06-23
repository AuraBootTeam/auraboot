---
type: retro
status: closed
created: 2026-06-21
slug: site-key-registry-sp1-retro
distilled_to:
  - docs/superpowers/specs/2026-06-21-site-key-registry-design.md  # §9.1 build outcome + decisions
  - docs/backlog/2026-06-21-site-key-anonymous-telemetry-subsystem-decomposition.md  # SP2 index prerequisite
related:
  - docs/superpowers/specs/2026-06-21-site-key-registry-design.md
  - docs/backlog/2026-06-21-site-key-anonymous-telemetry-subsystem-decomposition.md
---

# Site Key Registry (SP1) — Build Retro + Completion Review

SP1 of the anonymous-telemetry subsystem: the public site-key → tenant registry.
Built via `/aura-endgame` (design owner-locked in #976). Delivered as a config-only
plugin (`plugins/core-site-key`) + platform-side `behavior/sitekey/` service & handler.

## Completion Review (5 items, evidence-backed)

1. **Direction** — Aligned with SP1 spec: dynamic model `behavior_site_key` (dual id +
   server-generated `abk_` key + status dict + origin_allowlist) · `SiteKeyRegistry.resolveTenant`
   (the contract SP2 consumes) · DSL-first management (no React) · `behavior.site_key.{read,create,manage}`
   permissions. One justified deviation, recorded in spec §9.1: the create/disable handler is a
   platform `@Component CommandHandlerExtension` (not a PF4J plugin jar) because behavior telemetry
   is platform-native and the resolver must be platform-side for SP2's `/api/collect`.

2. **Progress** — All SP1 gaps DONE. SP1 does NOT touch `/api/collect` or the SDK (SP2/SP3, by design).

3. **Gap** — One documented, non-blocking for SP1: no DB-level unique/resolve **index** on `site_key`.
   Config-level field-feature indexing is systemically inert for `mt_` dynamic-model tables in this
   platform version (verified: 0/9 `mt_` tables carry feature-driven indexes). Uniqueness is enforced
   in the create handler (cross-tenant `existsAnyTenant` pre-check + retry; 190-bit keys → ~0 collision).
   The `(tenant_id, site_key)` unique index + resolve index is an explicit **SP2 prerequisite**
   (recorded in the decomposition backlog + spec §9.1) — SP2 owns the live hot path and must add it.

4. **UX (real-browser screenshots)** — `site-key-registry.golden.spec.ts` (1 passed, 50s):
   - List renders from DSL: sidebar 遥测管理 › 站点密钥, title 站点密钥列表, tabs 全部/启用/已禁用,
     columns 名称/站点密钥/状态/操作 (all localized, **no raw-code leak**), status pills 启用(green)/已禁用(gray).
   - Create: toolbar → form (name only) → submit → list shows a **server-generated `abk_` key** + 启用.
   - Disable: row action → confirm → status flips to 已禁用.
   - Zero product console errors. Screenshots: `web-admin/test-results/sitekey-0{1,2,3,4}-*.png`.

5. **Tests / coverage** (host-first, zero docker):
   - **Unit (18):** `SiteKeyGenerator` (format/uniqueness/base62), `SiteKeyRegistry`
     (hit/miss/disabled/cache/evict, mocked JDBC), `SiteKeyCommandHandler` (create server-gen,
     blank-name reject, collision-retry, retry-exhaustion, disable+evict, missing-id/not-found).
   - **Real-PG IT (6):** `SiteKeyRegistryIT` — resolveTenant cross-tenant isolation, disabled→empty,
     unknown→empty, existsAnyTenant, cache-serves-stale-until-evict.
   - **DSL validator:** `import-directory-sync` → `success:true` (1 model, 4 fields, 4 bindings, 1 dict,
     3 pages, 2 commands, 2 menus, 3 permissions, 1 role). Caught a real `S-PAGE-FIELD-REF` (created_at).
   - **Command-pipeline golden (real stack/DB):** create → `abk_…` row `active`; disable → row `disabled`.
   - **Browser golden:** see item 4.
   - **deny=403 (real stack):** read-only `e2e-viewer` → create → HTTP 403, 0 rows written.
   - **Static gates:** validate-permission-codes (0 drift), check-jsonb-typehandler, check-oss-boundary — all green.

## Reflection (root-cause classes A/B/C/D)

- **[D] Verify discipline — the win of the session.** The pre-build spike (code-reading) claimed
  `constraints.unique:true` → a `(tenant_id, site_key)` unique index via `MultiTenantIndexManager`.
  The real import proved it **inert** (feature column never persisted for imported fields; 0/9 `mt_`
  tables have feature-driven indexes). Because §9 mandated verifying this empirically before building
  on it, the wrong inherited claim was caught at the DB (`\d`), not shipped as a false guarantee.
  Lesson: for index-critical fields on dynamic models, **do not assume config `unique`/`searchable`
  yields a DB index** — verify on a real table, and design uniqueness enforcement at the app layer.

- **[B] Environment hazards (diagnosed, worked around — not my code).**
  1. The golden-stack `warm` step failed: proxy login 500 `Cannot find module '../encodings'` from a
     **broken `iconv-lite` in another worktree's (`auraboot-report-golden`) node_modules**. Root cause:
     **slot-64 frontend port collision** — that worktree's Vite/BFF squatted 5164/6164, so my stack's
     `pnpm dev:full` couldn't bind, but the health poll saw the *other* worktree's Vite and falsely
     reported "frontend UP". My backend (6464) was genuinely mine (the create command ran my handler).
     Fix: started my own frontend on free ports (5180/6180) against my backend, **without touching the
     other session's procs** (§20). Lesson: when a golden-stack frontend misbehaves, `lsof -p <pid> -d cwd`
     to confirm the listening proc belongs to *this* checkout before trusting "UP".
  2. The `setup` Playwright project bundles `02-test-pages`, which needs the full showcase seed (absent
     in the golden-stack's minimal bootstrap) → it failed and blocked `auth` (storageState). Fix:
     minted the admin storageState with `--project=auth --no-deps`, then ran the golden with `--no-deps`.

- **[A]/[C]** No gate/prompt failures. The DSL validator (`import-directory-sync`) did its job (caught
  the system-field page reference that static audit would miss). Subagent spike was thorough and
  correctly self-flagged the "untested-in-repo" risk that turned out to be the real gap.

## Durable lessons (candidates to codify)

1. **Dynamic-model config indexes are inert** (`mt_` tables): `constraints.unique`/`feature.searchable`
   do not produce DB unique/trgm indexes in this platform version. For a field needing a real index,
   either enforce at the app layer (this SP) or own the index in the consuming hot-path slice.
2. **Server-set-on-create for a dynamic model = platform `@Component CommandHandlerExtension` +
   `requiresDslPersistence()=false`** (handler owns the insert via `DataAccessor.create`), with the
   command declaring `type:create` and **no explicit `handler`** (so import skips `S-EXT-HANDLER`).
   This is cleaner than a PF4J plugin jar when the logic is platform-native.
3. **Golden-stack slot collision**: a stale frontend from another worktree on the same slot ports makes
   the health poll lie; verify proc cwd ownership, run your own frontend on free ports against your backend.
