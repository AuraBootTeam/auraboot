---
type: backlog
status: active
created: 2026-06-20
owner: diqi
related: 2026-06-18-fail-open-controller-triage.md
---

# OSS E2E authz gap — `model.meta_models.read` (default-deny rollout input)

> Empirical input for the **default-deny authorization rollout** (#820 /
> [[2026-06-18-fail-open-controller-triage]]). Surfaced while running the full
> OSS E2E against a freshly-bootstrapped isolated stack during the
> `@auraboot/runtime-kernel` extraction regression (PR #895). Not a kernel issue
> — the kernel branch changes zero backend files; recorded here so the role
> matrix work has the exact gap.

## Environment
- demo plugin profile, fresh `/api/bootstrap/setup`, `AURA_AUTHZ_UNANNOTATED_MODE=shadow`
- E2E admin / platform_admin user

## Real gaps — annotated permission checks the admin role isn't granted
Counts = denials in one full OSS E2E run (`Access denied: required permission not found, permissionCode: …`):

| permissionCode | denials | blast radius |
|----------------|--------:|--------------|
| `model.meta_models.read` | 178 | meta-model list, dict management, most `/meta` admin pages — **widest** |
| `model.meta_fields.read`  | 2 | field-level meta reads |

**Role-matrix decision (for #820, not auto-applied):** `meta_models` / `meta_fields`
are platform-system models admins inherently browse → almost certainly grant
`model.meta_models.read` + `model.meta_fields.read` to `platform_admin`
(and likely `tenant_admin`). Confirm against the intended role matrix before granting.

## NOT authz gaps — ignore for grants (separate test fix)
These 403s are for **enterprise model names that don't exist in OSS**
(`crm_*_common`; OSS crm-starter uses bare `crm_account` / `crm_lead` / …):

`model.crm_account_common.read` (8), `crm_opportunity_common` (2),
`crm_lead_common` (2), `crm_contact_common` (2), `crm_activity_common` (2)

Root cause: `web-admin/tests/api/setup/seed-showcase-extended.spec.ts` (the
full-CRM extended seed) seeds + verifies `crm_*_common` models unguarded; in OSS
those models are absent, so it no-ops + 403s. **Fix is a capability gate on that
spec** (skip when `crm_account_common` is absent / not full-CRM), independent of
the grant matrix. Tracked here as a follow-up, low priority (noise only — the OSS
base showcase seed populates `crm_account` with 60 rows fine).
