---
type: backlog
status: active
created: 2026-06-18
---

# Prompt-to-App M3 (pillar ②) — v4 prompt rewrite + deterministic V2→V4 page normalizer + config-as-product provenance

> Productionizes Prompt-to-App so NL-generated DSL pages pass the strict platform v4 import
> validator (`PageSchemaImportGate` / `PageSchemaValidator`), and tags generated config with
> block-level provenance so hand-edits survive re-generation (FR-E4). OSS (`auraboot`).
> Branch `feat/qr-m3-prompt-to-app-v4`, base `832a46fed`.

## Background — what was already there vs. the residual gap (§15, verified by reading)

The chain NL → `NlModelingService.generate` → `buildPluginManifestJson` → `PluginImportService.executeFromManifest`
is built, and a lot of deterministic conformance post-processing already landed (prior slices, docs
`2026-06-13-prompt-to-app-import-conformance.md` / `2026-06-13-prompt-to-app-llm-gated-verification.md`):
`lowercaseStringKey` (command.type / field.dataType), `deriveDynamicMenuPageKeys`, `synthesizeBindings`,
`downgradeOrphanEnumFields`, `synthesizePages`/`synthesizeMenus`, `conformModels`, `conformFieldLabels`,
and the `dicts` channel on `NlModelingResponse.Resources` (so ENUM fields need not downgrade to STRING).
The few-shot example was already migrated to the v4 flat shape.

**The residual Seam-5 gap this slice closes:**
1. The system prompt's "Page Schema" / "Form Page Schema" REFERENCE sections still taught the
   **legacy V2** shape (`layout.areas`/`areasConfig` flex, blocks nested under
   `areas.<region>.blocks[]`, capitalized `kind`, no `schemaVersion`) — contradicting the v4
   few-shot. A capable model that follows the reference emits V2-shaped pages.
2. `synthesizePages` only fired when the LLM emitted NO pages — there was **no normalizer for
   pages the LLM DID emit** in V2 shape. Those pages reached the v4 gate untouched → rejected by
   `S-PAGE-VERSION` (schemaVersion≠4) / `S-PAGE-LAYOUT-TYPE` (flex) / `S-PAGE-BLOCKS` (nested).
3. No config-as-product provenance: generated blocks carried no `source`/`locked`, so a
   re-generation could silently clobber a user's hand-edit.

## Delivered (TDD, deterministic core — fully unit-tested, no LLM / no DB)

### 1. `normalizePageToV4(page)` — the deterministic V2→V4 safety net
`NlModelingService.normalizePageToV4` (+ private `hoistAreaBlocks`) normalizes any generated page to v4:
- `schemaVersion` → `DslRegistry.PAGE_SCHEMA_CURRENT_VERSION` (=4)
- `kind` lower-cased (`List`→`list`); unknown kinds left for the validator (no silent coercion)
- `layout.type` not in {grid,stack} (e.g. flex) → `stack`; missing layout → `{type:stack}`; the V2
  `areas`/`areasConfig` wrapper config is dropped
- nested `areas.<region>.blocks[]` (and any wrapper block carrying `areas`) hoisted into a flat
  top-level `blocks[]`
- every hoisted block missing an `id` gets a synthesized stable id (`<blockType>_<n>`)
- idempotent on an already-v4 page

Wired into `synthesizePages`: every LLM-provided page is normalized in place (synthesized-from-scratch
pages are already v4). So generation no longer depends on the model producing perfect v4 output.

### 2. `dicts` channel — verified (already present)
`NlModelingResponse.Resources.dicts` exists and is carried through `buildPluginManifestJson`
(`manifest.put("dicts", ...)`); `downgradeOrphanEnumFields` keeps an ENUM field when its `dictCode`
matches a defined dict and only downgrades a dangling reference. Covered by
`buildManifest_lowercasesTypes_derivesMenuPageKey_carriesDicts` + `downgradeOrphanEnumFields_keepsEnumWithMatchingDict`.

### 3. Config-as-product provenance (FR-E4)
New pure class `PageConfigProvenance`:
- block-level `source ∈ {ai, manual, template}` + `locked` (boolean)
- `tagGenerated(page)`: marks untagged blocks `source=ai`, `locked=false` (preserves an explicit
  `manual`/`template` source + lock). Wired into `buildPluginManifestJson` so every generated page
  block carries provenance.
- `mergeRegeneration(existing, regenerated)`: pure merge that (a) keeps a `locked` block verbatim
  even when regen omits it, (b) does NOT overwrite a `manual` (hand-edited) block — default preserve,
  (c) lets a fresh AI generation overwrite its own previous unlocked AI output. Block order:
  regenerated first, surviving protected blocks appended.

### 4. System prompt rewritten to v4
The "Page Schema" / "Form Page Schema" reference sections + the "Important Rules" #7 now teach the
v4 flat format (lower-case kind, schemaVersion:4, layout:{type:stack}, flat top-level blocks[] with
blockType+id+area), explicitly forbidding the legacy `areas`/`areasConfig` wrapper. Post-processor #1
is the safety net for imperfect model output.

## Test evidence (focused unit, JUnit XML counts — not pipe exit code)

Command (run from `platform/`):
```
./gradlew :test \
  --tests 'com.auraboot.framework.agent.nlmodeling.NlModelingManifestPostProcessingTest' \
  --tests 'com.auraboot.framework.agent.nlmodeling.PageConfigProvenanceTest'
```
- `NlModelingManifestPostProcessingTest` — **tests=22 skipped=0 failures=0 errors=0** (17 prior + 5 new
  V4-normalizer: hoist+version+layout+kind, already-v4 pass-through, flex→stack, missing-layout default
  + null-safe, end-to-end `buildManifest` normalizes an LLM-emitted V2 page).
- `PageConfigProvenanceTest` — **tests=8 skipped=0 failures=0 errors=0** (tag defaults, preserve explicit
  source/lock, isValidSource, locked-survives-regen, manual-not-overwritten, unlocked-ai-replaced,
  null-existing pass-through, ordering).

## 🟡 LLM-key block point (DID-NOT-RUN — live NL→app end-to-end)

The end-to-end live path — real NL description → `generate` (real LLM) → `buildPluginManifestJson` →
`executeFromManifest` → platform v4 validator `success:true` with a v4-shaped LLM page — **cannot be
proven without a real LLM key**, and is NOT faked here. The deterministic post-processor (#1) is the
safety net so even imperfect/V2-shaped LLM output normalizes to v4 before the gate, and the prompt (#4)
biases the model toward v4 directly. The deterministic core (#1/#2/#3) is fully unit-verified above.

**To verify once keyed (host-first, zero docker — per the prior conformance doc's runbook):**
1. Provision a key (e.g. `DEEPSEEK_API_KEY` env → `CloudConfigSeeder`), host-first stack
   (backend ← BFF ← Vite, DB `aura_boot_<runtime>`).
2. `POST /api/agent/nl-modeling/generate` with a description whose capable model emits V2-shaped pages
   (the v4 prompt should now make it emit v4 directly; if it still emits V2, the normalizer fixes it).
3. `POST /api/agent/nl-modeling/apply` → expect **200 `success:true` status:SUCCESS** with
   `PAGE:2` in resourceCounts (no `S-PAGE-VERSION` / `S-PAGE-LAYOUT-TYPE` / `S-PAGE-BLOCKS` blocking).
4. Confirm the persisted page is v4 (`/api/pages/key/<model>_list` assembles toolbar+table) and a
   re-generate-then-`apply` with a hand-locked block preserves the hand-edit (FR-E4 provenance).
5. Rotate any chat-exposed key and scrub `ab_cloud_config` after (per prior doc).

## Residual / non-goals
- Live generation quality (weak model richness) = prompt/model tuning, independent item (per prior docs).
- Frontend designer copilot (`AiPageGenerateDialog`) already wired; its render-time canonicalizer
  (`web-admin/app/framework/meta/utils/canonicalizePageDsl.ts`) is a separate runtime path, not the
  backend NL→apply import normalizer this slice adds.
