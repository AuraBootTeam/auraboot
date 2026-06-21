# Final Review Fix Report — 2026-06-21

## Fix A — Boundary linter CWD guard (#10)

**File**: `scripts/check-agent-eval-boundary.mjs`

**Change**: Added `import fs from 'node:fs'` and an `fs.existsSync(ROOT)` guard immediately after `ROOT` is defined. If the scan root is absent (wrong CWD), the script now prints an actionable error and exits 2 instead of silently scanning nothing and printing "OK".

**Both run outcomes verified**:
- From worktree root (`/Users/ghj/work/auraboot/auraboot-agent-eval-boundary`): `agent-eval boundary OK` — exit 0 ✓
- From `/tmp` (wrong dir): `check-agent-eval-boundary: scan root not found: platform/src/main/java/com/auraboot/framework/agent (run from the OSS repo root)` — exit 2 ✓

---

## Fix B — All-unavailable eval run must not persist / must not gate (I1)

**File**: `platform/src/main/java/com/auraboot/framework/agent/service/CapabilityEvalService.java`

**Existing no_cases contract** (mirrored): The 2-arg `evaluateToolSelection(tenantId, evalMode)` overload already returns `Map.of("status", "no_cases", "message", "No capabilities found to evaluate")` immediately, without calling `persistEvalRun`, when `generateEvalCases` returns an empty list. This is intentional: a run with nothing to score must not pollute the `ab_capability_eval_run` table used by `CapabilityEvalRegressionGate.gateLatest`.

**New short-circuit**: In the 3-arg `evaluateToolSelection(tenantId, evalMode, cases)`, after the per-case loop, when `totalCases == 0 && unavailableCases > 0` (all cases were D3a-skipped), the method now returns early with:
```json
{ "status": "no_scoreable_cases", "evalMode": "...", "totalCases": 0, "unavailableCases": N, "cases": [...] }
```
without calling `persistEvalRun`. The per-case `status=unavailable` results are kept in `cases[]` for observability. The normal path (`totalCases > 0`) is unchanged.

**Why this was wrong before**: When all cases were unavailable, `totalParameterChecks` was 0, so `paramRate` defaulted to `1.0`. Combined with other Math.max guards, `weightedScore` was approximately `0.30` (20% from paramRate alone). This bogus run was persisted and became the latest baseline for `CapabilityEvalRegressionGate.gateLatest`, corrupting regression detection.

**ScheduledCapabilityEvalJob**: Verified — the job calls `evaluateToolSelection` and gates via `CapabilityEvalRegressionGate.gateLatest` which finds runs by DB query. A non-persisted run simply means `gateLatest` finds no new run — the job does not NPE. No modification to the job was needed.

---

## Tests Updated

### `CapabilityEvalUnavailableCaseTest` (test 1 — all-unavailable)
- Added `import com.auraboot.framework.agent.entity.AbCapabilityEvalRun`
- Added `import static org.mockito.Mockito.never` and `verify`
- Updated assertions: added `assertThat(result.get("status")).isEqualTo("no_scoreable_cases")`
- Added `verify(evalRunMapper, never()).insert(any(AbCapabilityEvalRun.class))` to prove no persistence
- Test 2 (mixed run, `totalCases==1`) left unchanged — unaffected by Fix B

### `MultiPluginEvalCaseCoexistenceIT` (Step 5 — D3a dependency skip)
- Updated Step 5 to assert `report.get("status")` equals `"no_scoreable_cases"` (Fix B contract)
- Retained `unavailableCases==1` and `totalCases==0` assertions
- Removed stale `correctSelections==0` assertion (field absent from `no_scoreable_cases` report shape)
- Steps 1–4 (coexistence + rollback isolation) untouched

---

## Full Suite Result

Command:
```
./gradlew :test --tests '*EvalCaseStructureValidator*' --tests '*AgentArchetypeEvalCasesTest*' \
  --tests '*AgentEvalCaseImportIT*' --tests '*MultiPluginEvalCaseCoexistenceIT*' \
  --tests '*CapabilityEvalUnavailableCaseTest*' --tests '*ScheduledCapabilityEvalJobTest*' \
  --tests '*CapabilityEvalLlmModeTest*'
```

Result: **BUILD SUCCESSFUL** — 20/20 tests pass, 0 failures, 0 errors (verified via `build/test-results/test/*.xml`)

| Test class | Tests | Failures | Errors |
|---|---|---|---|
| AgentEvalCaseImportIT | 3 | 0 | 0 |
| MultiPluginEvalCaseCoexistenceIT | 1 | 0 | 0 |
| AgentArchetypeEvalCasesTest | 1 | 0 | 0 |
| EvalCaseStructureValidatorTest | 5 | 0 | 0 |
| ScheduledCapabilityEvalJobTest | 4 | 0 | 0 |
| CapabilityEvalLlmModeTest | 4 | 0 | 0 |
| CapabilityEvalUnavailableCaseTest | 2 | 0 | 0 |
| **Total** | **20** | **0** | **0** |
