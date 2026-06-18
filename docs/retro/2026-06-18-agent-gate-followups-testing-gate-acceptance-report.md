---
type: retro
status: shipped
created: 2026-06-18
related:
  - docs/backlog/2026-06-18-platform-and-test-gaps-resolution.md
  - docs/retro/2026-06-18-s5s6-workbench-dashboard-golden-testing-gate-acceptance-report.md
---

# Testing Gate Acceptance Report — agent-quality campaign remaining follow-ups (2026-06-18)

`allowed_claim`: **targeted pass** for the three remaining test gaps (RuntimeAuth
deny enforcement, S7 multi-step convergence, quality CAPA real-plugin golden), all
merged. This completes the campaign's test/infra follow-up list; the only item
left open is the **F2-blocked CRM-complaint** real-plugin part (owner deferred F2).

## Scope (current SOT)

- SOT: `docs/backlog/2026-06-18-platform-and-test-gaps-resolution.md` §「🔧 纯测试/基建 gap」.
- Predecessor (same session): S5/S6 browser golden — see
  `docs/retro/2026-06-18-s5s6-workbench-dashboard-golden-testing-gate-acceptance-report.md`
  (merged as OSS #810).
- `business_scope`: agent runtime safety gates + multi-agent convergence + the
  hybrid quality plugin's CAPA lifecycle. Non-goal: F2 (demo-table shadowing) and
  the CRM-complaint real-plugin part it blocks.

## Items + evidence

| Item | Type | Evidence | PR |
|---|---|---|---|
| RuntimeAuth (gate 8) deny enforcement | unit | `ToolLoopServiceSafetyTest.runtimeAuthorizationDenyBlocksToolExecution` — forbidden effect → `reject` → loop returns "Runtime authorization denied" + `verifyNoInteractions(commandExecutor)`. Class 19/19. | OSS #817 (merged `c23309270`) |
| S7 multi-step real-model convergence | live IT | `AgentMultiStepConvergenceLiveIT` — real DeepSeek tool-use loop: lookup → (read 'stalled') → escalate → STOP within cap; ≥2 tool calls, non-empty final summary. `tests=1, failures=0`. Key redacted post-run (residual scan 0). | OSS #817 (merged `c23309270`) |
| quality CAPA real-plugin golden | host-first golden | `quality/golden/capa-lifecycle-golden.sh` — hot-loads the real `CloseCapaHandler` jar (PF4J, no restart) + imports config (materializes `mt_qc_capa`) + drives create→start→verify→close via the real command pipeline. effective ⇒ closed + closed_date; ineffective ⇒ reopen to in_progress, no closed_date. Verified PASS. | auraboot-plugins #95 (merged `f7e3d27`) |

## Why these were real gaps (verified, not phantom)

- **RuntimeAuth**: `DefaultRuntimeAuthorizationService.authorizeIncremental` is
  grant-all; the existing IT only covered `authorizePlan` persistence +
  `GrantScope.matches()` in isolation — the enforcement edge (deny → tool blocked)
  through `ToolLoopService` was untested.
- **S7**: `AgentCollaborationService` dispatch/child-task plumbing was verified
  deterministically; a real model driving the loop to convergence (no spin / no
  infinite loop) was never measured — all prior live ITs are single-step.
- **quality**: `CloseCapaHandlerTest` mocks `DataAccessor`; `QualityAutoCapaChainGoldenIT`
  uses synthetic models. Neither loads the real hybrid jar + real `qc_capa` model +
  command pipeline together — the §2.1 trap where a config-only import leaves
  `qc:close_capa` an unregistered `[S-EXT-HANDLER]`.

## Method notes

- Controlled-catalog technique (per `AgentArchetypeLiveQualityIT`) isolates the S7
  convergence behavior from the orthogonal "are plugins loaded" infra question:
  the loop drives the configured provider over a controlled toolset with synthetic
  tool results, mirroring `StepLoopService.executeAgentLoop`'s message format.
- The quality golden uses the runtime **hot-load** endpoint
  (`POST /api/plugins/hotload/upload`) so the hybrid jar loads into PF4J without a
  backend restart — making a host-first assembled-product golden practical.

## Final Evidence Pack

```text
acceptance_report: docs/retro/2026-06-18-agent-gate-followups-testing-gate-acceptance-report.md
claim_level: completion-claim (for the campaign's test/infra follow-up list)
current_sot: docs/backlog/2026-06-18-platform-and-test-gaps-resolution.md
business_scope: agent runtime gates + multi-agent convergence + hybrid quality CAPA lifecycle
integration_tests: ToolLoopServiceSafetyTest (19/19); AgentMultiStepConvergenceLiveIT (1/1 live, DeepSeek)
integration_coverage: coverage_not_measured (targeted)
e2e_specs: quality/golden/capa-lifecycle-golden.sh (host-first command-pipeline golden, verified PASS)
feature_action_matrix: RuntimeAuth {grant✓, deny-enforced✓}; S7 {multi-step✓, result-driven✓, converges✓}; CAPA {create→open, start→in_progress, verify→verification, close(effective)→closed+date, close(ineffective)→reopen}
browser_evidence: n/a (these three are backend/agent/command-pipeline; browser golden was S5/S6 in #810)
backend_evidence: ToolLoopService deny short-circuit; real DeepSeek wire (redacted); real CloseCapaHandler + mt_qc_capa in PostgreSQL
artifact_evidence: n/a
permission_negative: RuntimeAuth deny IS the negative-path test
visual_feedback: n/a
skip_fixme_threshold_retry_audit: S7 live IT @Tag("agent-eval-live") skips without DEEPSEEK_API_KEY (opt-in, not a masked product gap)
did_not_run: CRM-complaint real-plugin part (blocked by F2, owner deferred); full OSS gate (targeted only)
remaining_blockers: F2 (demo-table shadowing) gates the CRM-complaint real-plugin golden — owner decision
allowed_claim: targeted pass (3 follow-ups merged); campaign test/infra follow-up list complete except F2-blocked CRM
```

## Operational state

- Runtime `s5s6-workbench-dashboard-golden-52` (auraboot slot 52) still up
  (backend 6452 / Vite 5152 / BFF 6152 / DB `auraboot_52`); quality plugin
  hot-loaded + imported into it. Reusable or tear down via
  `./dev.sh infra cleanup` + `runtime destroy`.
- DeepSeek key used only by the S7 live IT; redacted from all logs after the run.
