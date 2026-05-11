# Agent Runtime Replay Deep Link Plan

Date: 2026-05-10

## Background

The previous agent-runtime cleanup removed the legacy generic tool execution fallback and made `ToolLoopService` the canonical runtime for AuraBot, provider, and platform tools. Unit, integration, and E2E coverage now proves the main execution path, permission boundaries, result-contract rendering, replay listing, shadow viewer, memory, and learning surfaces.

The remaining operator gap is observability continuity: a failed or suspicious agent run can be opened in Replay UI, but the operator still has to manually locate the matching AI trace. Conversely, trace detail pages do not expose the run context that generated the trace. During this review we also found a security gap: `GET /api/ai/traces/{traceId}` loaded trace and spans by `trace_id` only, while list/stats endpoints were tenant-scoped.

## Target

Build a complete run-to-trace investigation chain:

1. Agent run detail returns a tenant-scoped `traceId` when a matching trace exists.
2. Replay drawer renders an explicit trace deep link.
3. Trace detail renders a related run link when the trace was produced by an agent run.
4. Trace detail endpoint is tenant-scoped for both trace row and spans.
5. Backend integration, frontend unit, and E2E tests cover the link and tenant boundary.

This is not a legacy-compatibility task. No generic runtime fallback, duplicate executor, or legacy entrypoint should be reintroduced.

## Task List

- [x] Backend contract: extend `AgentRunDetail` with optional `traceId`.
- [x] Backend query: resolve trace id by tenant, preferring `ab_agent_run.metadata.traceId`, then `ab_ai_trace.session_id = runId`.
- [x] Backend security: make `AiTraceController.getTrace` tenant-scoped and return 404 for cross-tenant or missing trace.
- [x] Backend mapper/service: add tenant-aware trace/span selectors.
- [x] Backend integration tests: cover metadata trace id, session-id fallback, and cross-tenant non-leak.
- [x] Frontend API type: expose `AgentRunDetail.traceId`.
- [x] Frontend Replay UI: show an `Open Trace` link in the run drawer when `traceId` exists.
- [x] Frontend Trace UI: show an `Open Run` link only for traces whose metadata/session indicate an agent run.
- [x] Frontend unit tests: assert drawer trace link and trace-detail run link behavior.
- [x] E2E: seed a run + trace and verify Replay UI can navigate to Trace detail.
- [x] Verification: run targeted backend tests, frontend unit tests, TypeScript, E2E, and drift checks.
- [x] Final review: inspect diff for legacy reintroduction, tenant leakage, and fake-test patterns.

## Acceptance Criteria

- `GET /api/admin/agent-runs/{runId}` includes `data.traceId` for a trace in the caller tenant.
- A trace in another tenant is never linked or returned.
- `GET /api/ai/traces/{traceId}` returns 404 when the trace does not belong to the caller tenant.
- The Replay drawer provides a working trace link without requiring manual search.
- The Trace detail page provides a working run link when the trace was created by `AgentRunService`.
- Tests fail before the implementation and pass after it; no skip/threshold relaxation is used.

## Verification Results

- Backend integration: `./gradlew :test --tests AgentRunControllerIntegrationTest --tests AiTraceControllerIntegrationTest -x jacocoTestReport` -> `BUILD SUCCESSFUL`, 14 tests passed.
- Frontend unit: `pnpm --dir web-admin exec vitest run app/plugins/core-aurabot/__tests__/AgentRunDetailDrawerLiveStream.test.tsx app/plugins/core-aurabot/__tests__/TraceDetailPage.test.tsx` -> 2 files / 7 tests passed.
- Frontend typecheck: `pnpm --dir web-admin typecheck` -> passed.
- E2E: `PLAYWRIGHT_BASE_URL=http://localhost:15174 ... admin-agent-runs.spec.ts --project=chromium --no-deps` against this worktree's temporary backend/BFF/web stack -> 5 passed.
- Drift/format: `git diff --check` -> passed.
- E2E truth review: no skip/fixme, no waitForTimeout, no direct `/p/*` page.goto, no PUT/POST/DELETE product bypass. The three `toBeGreaterThanOrEqual(1)` assertions are semantic "at least one seeded row" checks on detail payload arrays, not baseline thresholds.
