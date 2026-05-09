# Bootstrap Unified — Phase 2 sub-design

> **Status**: 2026-05-09 — design complete, implementation **paused** with blocker analysis.
> Owner decision required before proceeding (see §6).

## 1. Goals

Phase 2 of `2026-05-09-env-scripts-testing-systematic-design.md` calls for merging the
two parallel bootstrap entry points (`AdminBootstrapRunner` first-run, `BootstrapStartupListener`
seed-config) into one idempotent, repair-anywhere `BootstrapStartupRunner` that works on
host / r2-isolated / docker-prod identically. Owner selected **Scheme C**.

## 2. 9-invariant matrix

The single startup runner + the new `BootstrapRepairService` must idempotently guarantee:

| # | Invariant | Source of truth | Repair step |
|---|-----------|-----------------|-------------|
| 1 | `system_config` row present (system.mode / system.platform_name / system.db_uuid / system.instance_url) | `ab_system_config` | `repairSystemConfig()` |
| 2 | System Tenant exists (id=1, name="System") | `ab_tenant` where name='System' | `repairSystemTenant()` |
| 3 | `platform_admin` role exists in System Tenant | `ab_role` where tenant_id=1 and code='platform_admin' | `repairPlatformAdminRole()` |
| 4 | admin user exists (`admin@example.com`) | `ab_user` where email=ADMIN_EMAIL | `repairAdminUser()` |
| 5 | admin → System Tenant membership | `ab_tenant_member` where tenant_id=1 and user_id=$admin | `repairAdminSystemMembership()` |
| 6 | admin → `platform_admin` grant | `ab_member_role` where member_id=$adminMember and role_id=$platformAdmin | `repairAdminPlatformAdminGrant()` |
| 7 | Business Tenant exists (default companyName) | `ab_tenant` where name=$company | `repairBusinessTenant()` |
| 8 | Builtin plugins imported (org-management, platform-admin) | `ab_plugin_installation` where tenant_id=$businessTenant | `repairBuiltinPlugins()` |
| 9 | JWT signing key consistent (`system_config.jwt_secret` non-empty + cached in JwtKeyProvider) | `ab_system_config` where config_key='jwt_secret' | `repairJwtSecret()` |

Each `repairXxx()` is **idempotent** — checks existence first; if present, log + skip; if missing, create.

## 3. API design

```java
public interface BootstrapRepairService {
  /** Run all 9 repair steps. Safe to call any number of times. */
  RepairReport repairAll(RepairOptions opts);

  /** Run a single named step (used by /api/admin/bootstrap/repair). */
  RepairStepResult repair(String stepName, RepairOptions opts);

  record RepairOptions(
      String adminEmail,         // default admin@example.com
      String adminPassword,      // default Test2026x (only used if creating)
      String adminDisplayName,
      String companyName,        // default "AuraBoot Dev"
      String systemMode          // single | multi | hybrid
  ) {}

  record RepairStepResult(String step, String action, String message) {
    // action ∈ {"present", "created", "failed"}
  }

  record RepairReport(List<RepairStepResult> steps, boolean ok) {}
}
```

`BootstrapStartupRunner`:

```java
@Component
@Order(2)
@ConditionalOnProperty(
    name = "auraboot.bootstrap.enabled",
    havingValue = "true",
    matchIfMissing = true   // dev-default-on; prod sets to false
)
class BootstrapStartupRunner implements ApplicationRunner {
  public void run(...) {
    bootstrapRepairService.repairAll(loadDefaults());
  }
}
```

Profile defaults:
- `application-dev.yml`: `auraboot.bootstrap.enabled: true`
- `application-prod.yml`: `auraboot.bootstrap.enabled: false`
- env override: `AURABOOT_BOOTSTRAP_ENABLED=false` to opt out.

`BootstrapEngineService.execute()` becomes a **thin wrapper** delegating to
`bootstrapRepairService.repairAll()`. The "already initialized" guard is removed
from the **service layer** but kept in the controller for the **public** `/api/bootstrap/setup`
endpoint (back-compat: the wizard UI expects the "already initialized" error).

The new `/api/admin/bootstrap/repair` (admin-only via `@RequirePermission(MetaPermission.SYS_ADMIN)`)
calls `bootstrapRepairService.repair(step, opts)` and returns the `RepairStepResult`.

## 4. Migration strategy

1. Extract `BootstrapRepairService` from `BootstrapEngineService` private methods (each
   private `createXxx` / `bootstrapXxx` becomes a public idempotent step on the new service).
2. `BootstrapEngineService.execute()` keeps its public signature for back-compat but
   internally dispatches to `BootstrapRepairService.repairAll`.
3. Delete `AdminBootstrapRunner` (replaced by `BootstrapStartupRunner`).
4. `BootstrapStartupListener` — keep `bootstrap/bootstrap-seed-config.json` loader logic but
   re-route to `bootstrapRepairService.repairAll(loadFromJson())` on `mode=seed`. Or fold
   the seed-config-loading into `BootstrapStartupRunner` itself (simpler — single runner).

## 5. Test plan (IT)

`BootstrapRepairServiceIT` — 9 invariants × 2 states (missing / present):
- start with empty DB, call `repairAll`, assert all 9 invariants are now true
- call `repairAll` a 2nd time, assert no duplicate rows + no exceptions

`BootstrapStartupRunnerIT`:
- `AURABOOT_BOOTSTRAP_ENABLED=true` (default) on empty DB → all 9 invariants present
- `AURABOOT_BOOTSTRAP_ENABLED=false` → no rows created (runner skipped)
- DB pre-seeded with 5/9 invariants → runner repairs the missing 4 only

`BootstrapAdminRepairControllerIT`:
- `/api/admin/bootstrap/repair` with admin token → 200, single-step result
- without auth / non-admin → 403

Hard rule: real Postgres (testcontainers or dev stack on isolated port).

## 6. Blocker analysis — implementation paused 2026-05-09

The systematic design intent is sound, but implementing Phase 2 in this session hits
multiple **realistic-cost / runtime-risk** blockers that warrant owner alignment before
investing the full 8h:

### 6.1 Missing test infrastructure

The platform module has **only one IT** (`PluginDashboardContractImportIT`) — there is
no shared `IntegrationTestBase`, no testcontainers wiring, no `@SpringBootTest` profile
for IT, no fixture for "fresh empty DB". Building real-Postgres IT for 9-invariant
matrix from scratch is itself a 3–4h scaffolding investment **on top of** the 1.5h
estimated for the IT cases. Plan estimate (1.5h IT) is unrealistic without prior IT
infrastructure investment.

**Decision needed**: Do we (a) build IT scaffolding first as a separate sub-task, or
(b) accept Playwright contract test (`00-bootstrap.spec.ts` upgraded to 9 invariants)
as the hard test gate and skip backend IT? Plan says IT is required.

### 6.2 `BootstrapEngineService.execute()` contract inversion

Today's contract: "fail if already initialized." The bootstrap wizard UI relies on this
(it's the only way to re-prompt the operator). Inverting to "repair anything missing"
means:

- `/api/bootstrap/setup` controller behavior must NOT change (still rejects already-init);
- `repairAll()` must NOT touch the `system.initialized` flag if invariants 1–9 are all
  already satisfied (else it logs spurious "completed" each restart);
- Both behaviors must coexist cleanly. Doable but each touch-point needs review.

### 6.3 publishToMavenLocal multi-worktree contention

Per AGENTS.md §11: ≥2 worktrees touching shared `~/.m2` must use isolated docker. This
worktree (`oss-suite-r2`) shares `~/.m2` with main `auraboot/`. The plan says "use
isolated docker stack per AGENTS.md §11", which means **building inside a docker
container with a stack-scoped m2 volume** — that adds 5–10 min cold start the first
time + requires verifying the isolated stack picks up the new jars.

### 6.4 Cross-cutting verification gauntlet

Hard rules require: backend IT pass + host smoke + r2 smoke + admin/non-admin repair
endpoint test. The plan budget (2h verification) is tight if any single step regresses.

### 6.5 Existing `BuiltinPluginImportService` may not be idempotent

Step 8 invariant ("builtin plugins imported") needs `BuiltinPluginImportService` to be
idempotent re-call-safe. Not yet verified. Re-import attempts currently throw on
duplicate plugin_installation rows in the worst case.

## 7. Recommended next-session plan

Split Phase 2 into 4 commit-sized sub-PRs to make verification tractable:

- **2.1** Build IT scaffolding (`IntegrationTestBase` with testcontainers Postgres) —
  3–4h, separately reviewable.
- **2.2** Extract `BootstrapRepairService` (no behavior change yet — pure refactor),
  add unit tests for each `repairXxx()` step — 2h.
- **2.3** Add `BootstrapStartupRunner` + delete `AdminBootstrapRunner`, behind a feature
  flag for safety — 1.5h.
- **2.4** Add `/api/admin/bootstrap/repair` controller + delete script line 152 + flip
  docker-compose default + upgrade `00-bootstrap.spec.ts` — 2h.

Total: 8.5h matches plan estimate but split into reviewable units, each with its own
host+r2 smoke gate.

## 8. Outstanding decisions for owner

1. Build IT scaffolding first or rely on Playwright contract test? (§6.1)
2. Confirm the back-compat behavior of `/api/bootstrap/setup` (still reject when initialized)? (§6.2)
3. Approve splitting Phase 2 into 4 sub-PRs (§7)?
