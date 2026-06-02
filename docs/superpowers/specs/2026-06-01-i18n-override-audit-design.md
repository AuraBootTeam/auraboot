# I18n Override Audit (observability for multi-layer i18n)

Date: 2026-06-01
Repo: OSS `auraboot`
Scope: `platform/.../framework/i18n/` (+ bootstrap hook)

## Problem

i18n values resolve through a multi-layer override chain
(`I18nService.getI18nData`): YAML (low) → compiled JSON (mid) → **DB (wins)**.
Separately, `seed/i18n-base.json` is loaded **once** into the DB at bootstrap
via `I18nBaseSeeder`, which uses `batchInsertIgnore` — it never updates an
existing key. The override chain is a black box: when someone edits a low layer
(or the seed file) and the value doesn't change in the UI, there is no signal
telling them a higher layer already won. This caused the 2026-05-30 login-copy
incident: seed file updated, DB kept the old value, "I changed it, why no
effect" took a long investigation to trace to the wrong layer.

**Goal:** make override visible. Pure observability — no data mutation, no
change to the override architecture.

## Decisions (user-confirmed)

1. **Cover all 4 sources**: YAML, compiled JSON, DB (the 3 runtime layers) +
   `seed/i18n-base.json` (DB's seed origin, for drift detection).
2. **Two surfaces**: a diagnostic API (pull, full report) + a startup WARN
   (push, drift-only — does not spam on normal overrides).
3. **Startup WARN fires only on `SEED_DRIFT`** (seed JSON value ≠ DB value for a
   `source='system'` key). Normal user overrides are silent.
4. **OSS repo** (i18n core lives here).
5. **No mutation**: no upsert, no cleanup, no orphan deletion. Reporting only.

## Non-goals

- Not changing the override priority chain.
- Not auto-syncing seed→DB (that would be upsert — explicitly deferred).
- Not duplicating `OrphanKeyDetector` (that handles `model.*` DSL orphans whose
  source model was deleted — orthogonal concern, different keyspace).
- Not a write-time warning hook (option 2 from discussion — deferred to phase 2;
  it only catches in-system writes anyway).

## Design

### Service: `I18nOverrideAuditor`
`platform/.../framework/i18n/service/I18nOverrideAuditor.java`

Reads, for a given locale, the four sources as flat `key→value` maps:
- YAML  : `i18n.{locale}.yaml` (classpath)
- JSON  : `i18n/i18n.{locale}.json` (classpath)
- DB    : `i18nResourceService.getResourceMapByLang(locale)` (tenant-scoped)
- seed  : `seed/i18n-base.json` filtered to this locale's column

To avoid duplicating flatten/load logic, the YAML/JSON readers are extracted
from `I18nService` into a small reusable helper (or the auditor calls new
package-private methods on `I18nService`). DB read reuses the existing service
method. The seed read reuses the same `seed/i18n-base.json` parse shape as
`I18nBaseSeeder`.

For each key present in ≥1 source, classify:
- `winner` — effective source per priority (DB > JSON > YAML) + its value
- `CONSISTENT` — value present in ≥2 layers and all equal (info)
- `OVERRIDDEN` — present in ≥2 layers with differing values, higher layer wins
  (normal, info/debug)
- `SEED_DRIFT` — seed JSON value ≠ DB value for a key whose DB `source='system'`
  (the real "edited but no effect" smell)

Output record:
```
record OverrideAuditEntry(String key, String lang,
    String yaml, String json, String db, String seed,
    String winnerLayer, String winnerValue, String classification) {}
record OverrideAuditReport(String lang, int totalKeys, int overriddenCount,
    int driftCount, List<OverrideAuditEntry> entries) {}
```

### API: `GET /api/admin/i18n/override-audit`
Added to existing `I18nAdminController` (same no-explicit-annotation style as the
neighboring `orphan-keys` endpoints). Params:
- `lang` (default `zh-CN`)
- `onlyDrift` (default false) — when true, entries filtered to `SEED_DRIFT`
- `includeConsistent` (default false) — drop the noisy all-equal entries unless asked

Returns `OverrideAuditReport`.

### Startup hook
In `PlatformSeedRunner.run()`, immediately after `i18nBaseSeeder.seed()`:
run the auditor across `getDistinctLangs()` (or the known seed locales), collect
`SEED_DRIFT` entries, and if any exist emit a single WARN:
```
WARN i18n: {N} system keys drift from seed (DB is stale), e.g. {first 5 keys};
     see GET /api/admin/i18n/override-audit?onlyDrift=true
```
No WARN when drift count is 0. Wrapped in try/catch so an audit failure never
breaks bootstrap (log at WARN, continue).

## Tests
`platform/src/test/.../i18n/service/I18nOverrideAuditorTest.java` — unit tests
with stubbed source maps:
1. YAML-only key → winner=yaml, classification not overridden
2. DB overrides JSON (different values) → winner=db, OVERRIDDEN
3. seed≠DB on system key → SEED_DRIFT, counted in driftCount
4. seed==DB → CONSISTENT/no drift
5. multi-layer equal values → CONSISTENT, not flagged drift
Assert the startup WARN path triggers only when driftCount>0 (via auditor return,
not log assertion).

## Verification
- `./gradlew compileJava test` for the new test class green
- Manual: `curl .../override-audit?lang=zh-CN&onlyDrift=true` against the running
  5179 stack — on a DB already synced (post Plan-2) drift should be 0; can
  re-introduce a drift row to confirm it surfaces.
