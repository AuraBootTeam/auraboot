---
type: retro
status: active
created: 2026-08-23
---

# CRM W1 governance T09 testing gate acceptance report

`allowed_claim: targeted pass for the file direct-access authorization fix; PAR-26/28 and W1 remain partial pending real-stack verification and T10 integration.`

## Scope and decision

This slice audits the existing platform capabilities used by CRM for organization/role data scope,
record ownership and collaboration, field masking, record attachments, comments, audit trail, login
and operation logs. It fixes one confirmed platform wiring gap: direct file metadata and download APIs
did not inherit the authorization of the business record associated through `ab_file_relation`.

Development-stage data migration is out of scope. The already VERIFIED CRM activity-comment journey
is not reimplemented; its existing DSL contract was only checked for drift. This report does not claim
PAR-26, PAR-28, W1, or Cordys full-product parity.

## Feature/action matrix

| Concern | Required evidence | Current evidence | Verdict |
| --- | --- | --- | --- |
| Menu/button permission | Role-derived menu and action enforcement | Existing CRM golden/config assets inventoried; no fresh browser runtime | partial |
| Data scope | owner/self, department hierarchy, crafted filter, cross-tenant | Existing CRM scope contracts pass; fresh real-stack journey not run | partial |
| Owner/collaborator | grant, read/update, expiry, revoke, old session | Existing `crm-ownership-sharing.spec.ts`; not rerun in this slice | partial |
| Field masking | list/detail/export plus explicit unmask permission | 4 CRM masking/config tests pass | pass (contract only) |
| Attachment relation creation/list | target record update/read authorization | Existing controller contract plus targeted unit pass | pass (hermetic) |
| Direct attachment metadata | unlinked owner or related-record read authorization | New targeted deny/allow tests pass | pass (hermetic) |
| Direct attachment bytes | authorize before opening storage stream | New targeted deny/allow and byte-read ordering tests pass | pass (hermetic) |
| Revoked/old-token attachment access | permission reevaluated for each download | Controller calls record authorization on every request; real old-token journey not run | partial |
| Cross-tenant attachment access | tenant-scoped file/relation query plus record authorization | Static implementation evidence; real PostgreSQL/API case not run | partial |
| Comments | unified record comments | Existing VERIFIED activity-comment chain intentionally not redone | excluded-existing-verified |
| Audit before/after/actor | field-change rows and actor identity | Existing audit unit contracts pass; real CRM mutation/DB assertion not run | partial |
| Login/operation logs | unified navigation and permission | Platform assets inventoried; no fresh browser/API journey | untested |

## Commands and results

```text
node --test plugins/crm/tests/crm-data-scope-config.test.mjs \
  plugins/crm/tests/crm-contact-channel-masking-config.test.mjs \
  plugins/crm/tests/crm-public-boundary.test.mjs \
  plugins/crm/tests/crm-followup-comments-config.test.mjs
result: 15 pass / 0 fail

cd platform && ./gradlew :test \
  --tests com.auraboot.framework.file.controller.FileUploadControllerTest \
  --tests com.auraboot.framework.file.service.impl.FileServiceImplTest \
  --tests com.auraboot.framework.meta.controller.config.AuditTrailControllerTest \
  --tests com.auraboot.framework.meta.service.impl.AuditTrailEventListenerTest \
  --tests com.auraboot.framework.rbac.service.impl.UserRoleServiceImplEvictEventTest
result: BUILD SUCCESSFUL; targeted tests pass
```

The first attempted Gradle invocation used the aggregate `test` task and failed because
`platform-plugin-api:test` had no tests matching the global filter. The corrected root-module `:test`
invocation passed. The aggregate failure is runner-selection evidence, not a product failure.

## Trust audit

- The deny test makes direct linked-file access fail before `StorageProvider.download`; it therefore
  demonstrates that the critical assertion can turn red.
- The allow test proves the same endpoint opens actual returned bytes only after record authorization.
- The unlinked-file negative test prevents a same-tenant PID holder from reading another uploader's file.
- No retry, threshold, skip, `waitForTimeout`, API fallback, or swallowed assertion was introduced.
- No fresh runtime, real PostgreSQL relation lookup, browser download, or controlled source mutation was
  executed. The implementation is therefore a targeted candidate, not a trusted golden gate.

## Shared-file and migration ledger

- Shared JSON/manifest pointers changed: none.
- Schema/Flyway changes: none.
- Data migration: none (explicit development-stage non-goal).
- Expected T10 conflict surface: platform file controller/service/mapper tests only; no CRM shared JSON.

## Final Evidence Pack

```text
acceptance_report: docs/retro/2026-08-23-crm-w1-governance-t09-testing-gate-acceptance-report.md
claim_level: targeted-tested
current_sot: delegated T09 contract; 2026-08-23 CRM W1 parallel execution plan; enterprise AGENTS.md
business_scope: CRM permission/governance inventory plus direct attachment metadata/download authorization
integration_tests: did_not_run (no T09 runtime allocation acquired)
integration_coverage: coverage_not_measured
e2e_specs: existing CRM governance specs inventoried; not rerun
feature_action_matrix: embedded above; pass/partial/untested/excluded rows retained
browser_evidence: did_not_run
backend_evidence: targeted Gradle :test BUILD SUCCESSFUL; Node contract tests 15 pass
artifact_evidence: hermetic download test asserts returned bytes; real browser-downloaded file did_not_run
permission_negative: unlinked non-owner deny; linked record deny before storage read; per-request reauthorization
visual_feedback: did_not_run
skip_fixme_threshold_retry_audit: no new skip/fixme/threshold/retry; no waitForTimeout or fallback
did_not_run: fresh PostgreSQL/API/browser; menu/button visual journeys; old-token live journey; login/operation-log UI; CRM audit DB before/after/actor
remaining_blockers: runtime allocation and T10 integrated real-stack verification
allowed_claim: targeted pass for direct attachment authorization only; PAR-26/28 and W1 remain partial
```
