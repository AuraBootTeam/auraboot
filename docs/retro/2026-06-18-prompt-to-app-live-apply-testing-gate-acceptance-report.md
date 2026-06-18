---
type: retro
status: shipped
created: 2026-06-18
---

# Testing Gate Acceptance Report — Prompt-to-App M3 live NL→app→v4-validator

> Closes the `DID-NOT-RUN` recorded in
> [`docs/backlog/2026-06-18-prompt-to-app-v4-prompt-and-page-normalizer.md`](../backlog/2026-06-18-prompt-to-app-v4-prompt-and-page-normalizer.md)
> §"LLM-key block point": the live chain real NL → `NlModelingService.generate` (real LLM) →
> `apply` → `PluginImportService.executeFromManifest` → strict v4 page import validator,
> now run with a real `DEEPSEEK_API_KEY`.

`allowed_claim`: **`targeted pass` (backend-integration, live LLM)** — the M3② live NL→app→v4
import-validator chain is proven end-to-end against real DeepSeek with wire evidence. Frontend
designer/browser golden of the generated pages is a separate, still-open item (page render
quality, esp. the detail-page `description` block field contract).

## What live verification surfaced (and why it mattered)

The #774 deterministic core (`normalizePageToV4` + provenance) was unit-tested in isolation, but
running the **live apply** chain surfaced two real productionization gaps no unit test caught:

1. **Menu → permission referential failure.** The system prompt biases the model to gate child
   menus on `dynamic.<model>.read` while emitting `permissions: []` (dynamic CRUD perms are *not*
   auto-created on model publish). `validateManifest` then rejected the menu:
   `Menu 'NL_EQUIP_INSPECTION_LIST' references missing permission: dynamic.equip_inspection.read`.
2. **Unknown blockType.** The few-shot only shows list/form pages, so the model invents
   `blockType: "detail"` on the detail page — not a registered `DslRegistry.BlockType` →
   `[S-PAGE-BLOCK-TYPE] Page 'device_inspection_detail' has unknown blockType: 'detail'`.

Both fixed deterministically in `NlModelingService` (the #774 "post-processor is the safety net"
philosophy): `synthesizePermissions()` (per-model dynamic CRUD perms, idempotent under OVERWRITE)
and `coerceBlockType()` in `normalizePageToV4` (alias table + kind default).

## Final Evidence Pack

```text
acceptance_report: docs/retro/2026-06-18-prompt-to-app-live-apply-testing-gate-acceptance-report.md
claim_level: completion-claim (for the DID-NOT-RUN closure scope only)
current_sot: docs/backlog/2026-06-18-prompt-to-app-v4-prompt-and-page-normalizer.md;
  src/main/.../agent/nlmodeling/NlModelingService.java; src/main/.../plugin/validation/PageSchemaValidator.java;
  src/main/.../plugin/service/impl/PluginImportServiceImpl.java (validateManifest)
business_scope: in-scope = backend NL→app generate→apply→v4 import gate success with a real LLM.
  non-goals = generation richness/quality tuning; frontend designer copilot render path; browser golden.
integration_tests: NlModelingApplyV4LiveIT (agent-eval-live, DEEPSEEK_API_KEY-gated) — 1/1 PASSED;
  pre-existing NlModelingLiveQualityIT (clean + hard) — 2/2 PASSED (live type-accuracy baseline).
integration_coverage: coverage_not_measured (targeted live IT, not a coverage run). Deterministic core
  unit-covered: NlModelingManifestPostProcessingTest 27/27 (+5 new: 2 perm-synthesis, 3 blockType);
  PageConfigProvenanceTest 8/8 (regression, unchanged).
e2e_specs: n/a (backend chain). Browser golden of generated pages = separate open item.
feature_action_matrix: n/a for this backend-chain closure; the live IT asserts the concrete chain
  (generate → apply → import-validator success + PAGE>=2).
browser_evidence: did_not_run (frontend designer / page render golden is the open follow-up).
backend_evidence: live apply ImportExecuteResult success=true status=SUCCESS
  resourceCounts={MODEL:1, FIELD:5, MODEL_FIELD_BINDING:5, COMMAND:3, PAGE:3, MENU:2, PERMISSION:4, I18N:12}
  error=null; generate models=1 pages=3 serviceValidationErrors=0.
  WIRE EVIDENCE (reactor-netty http client DEBUG, post-fix run):
    POST {uri=https://api.deepseek.com/v1/chat/completions, method=POST}
    R:api.deepseek.com/58.49.197.113:443 -> HTTP/1.1 200 OK; response header x-ds-trace-id present.
artifact_evidence: n/a.
permission_negative: n/a for this slice (perm-synthesis correctness covered by unit + live PERMISSION:4).
visual_feedback: n/a (backend).
skip_fixme_threshold_retry_audit: no skips/fixme/threshold/retry added. The live ITs self-skip
  (Assumptions.assumeTrue) only when DEEPSEEK_API_KEY is absent — L3 live-eval pattern, decoupled
  from every-commit CI; not a product-gap skip.
did_not_run: frontend/browser golden of generated DSL pages (detail-page description-block render
  contract); generation-quality tuning.
remaining_blockers: none for the DID-NOT-RUN closure. Open follow-ups (separate items):
  (a) detail-page description-block field render contract (verify renderer source before any few-shot
  detail example — §16 "don't guess DSL renderer contracts"); (b) browser golden.
allowed_claim: M3② live NL→app→v4-validator chain RAN and PASSED with real DeepSeek + wire evidence;
  two live-surfaced import gaps fixed deterministically (TDD). NOT claimed: page render quality / browser golden.
```

## Reproduce

Host-first, zero docker. Shared `aura_boot` DB + Redis (or an isolated runtime DB via
`SPRING_DATASOURCE_URL`). From `platform/`:

```bash
DEEPSEEK_API_KEY=… LOGGING_LEVEL_REACTOR_NETTY_HTTP_CLIENT=DEBUG \
  ./gradlew testAgent --tests "com.auraboot.framework.agent.NlModelingApplyV4LiveIT"
```

Note: the integration-test profile logs MyBatis SQL params, so the seeded LLM key lands in test
stdout — redact (`sk-…` → `sk-REDACTED`) any retained log before sharing.
