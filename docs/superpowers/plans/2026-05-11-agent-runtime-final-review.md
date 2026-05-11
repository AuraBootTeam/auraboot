# Agent Runtime Final Review

Date: 2026-05-11
Workspace: `/Users/ghj/work/auraboot/.worktrees/agent-runtime-unification-oss`
Branch: `codex/agent-runtime-unification`

## Scope

This review packages the current branch for merge review after the runtime
unification, replay/result-contract deep links, isolated E2E support, and D2
runtime observability/audit work.

## Review Summary

- No remaining unchecked task items in the active agent-runtime plan documents.
- No generated Playwright storage, reports, screenshots, videos, or trace
  artifacts are present in the working tree.
- `ToolExecutionPort` is deleted and protected by architecture tests.
- `ToolLoopService` remains the canonical tool execution control plane.
- `/api/admin/agent-runs/audit` is tenant-scoped and can link approvals through
  `authorization_decision.approval_id`, not only through user-facing approval text.
- `scripts/dev/import-isolated-plugins.sh` is executable locally and is intended
  to be added as a new developer helper.

## Fresh Verification

- `node scripts/validate-permission-codes.mjs --oss-only` -> `total drift: 0; new: 0`.
- Enterprise `./scripts/check-docs-drift.sh` -> `drift-audit passed (0 violations)`.
- `pnpm --dir web-admin typecheck` -> exit 0.
- `pnpm --dir web-admin exec vitest run app/plugins/core-aurabot/__tests__/AgentRunDetailDrawerLiveStream.test.tsx app/plugins/core-aurabot/__tests__/AgentRunsPage.test.tsx app/plugins/core-aurabot/__tests__/TraceDetailPage.test.tsx app/plugins/core-aurabot/components-internal/__tests__/ChatBiResultCard.test.tsx` -> `4 files / 14 tests passed`.
- Backend D2/merge target gate is recorded in `2026-05-11-agent-runtime-observability-plan.md`: observability, ToolLoop safety, replay controller, runtime authorization, architecture guard, and AI trace integration -> `BUILD SUCCESSFUL in 27s`.
- E2E truth static grep on `admin-agent-runs.spec.ts`, `pcba-procurement-agent-write.spec.ts`, and `aurabot-skill-resume-runtime.spec.ts`: no executable `test.only`, `test.skip`, `test.fixme`, `waitForTimeout`, `retries:N`, or `page.request.put|delete|patch` hits.
- `git diff --check` -> pass.
- `bash -n scripts/dev/start-isolated.sh scripts/dev/import-isolated-plugins.sh` -> pass.
- Production-source grep:
  - `rg "skillToolExecutor\\.(dispatch|confirm)" platform/src/main/java` -> only `ToolLoopService`.
  - `rg "ToolExecutionPort|Tool executed:|executeTool fallback|generic executeTool|ToolDiscoveryPort\\.executeTool|ToolExecutionPort\\.executeTool" platform/src/main/java` -> no hits.

## Diff Groups For Reviewer

1. Runtime control plane:
   `ToolLoopService`, `ToolDiscoveryPort`, `ToolDiscoveryPortImpl`,
   `ChatToolExecutor`, `AgentChatPortImpl`, `ActionRecorder`,
   `ResultContractEmitter`, `DefaultRuntimeAuthorizationService`.
2. Replay, trace, audit:
   `AgentRunController`, replay DTOs, `AiTraceController`, trace mappers,
   `AgentRunDetailDrawer`, `TraceDetailPage`, `agentRunsApi`.
3. Isolated stack and PCBA target:
   `docker-compose.isolated.yml`, `start-isolated.sh`,
   `import-isolated-plugins.sh`, PCBA E2E helpers/spec.
4. Schema drift and fixture cleanup:
   `PluginResourceImporterImpl`, `MetaModelFieldBindingMapper`,
   `global-teardown.ts`, `_real-backend-helpers.ts`.
5. Guards and tests:
   `AgentRuntimeArchitectureTest`, `ToolLoopServiceSafetyTest`,
   `AgentRunControllerIntegrationTest`, trace/replay/frontend/E2E tests.

## Open Question

- Several repository-facing docs update the Discord invite from
  `https://discord.gg/auraboot` to `https://discord.gg/p2fW5A2MW6`. This is
  outside the agent-runtime change set. Keep it if it is an intentional community
  link correction; otherwise split or revert it before merge.

## Merge Boundary

- Time-travel replay, fork-from-step, and historical SSE replay remain future
  product capabilities.
- Real LLM nondeterminism is not covered by deterministic E2E; stub markers are
  test scaffolding only.
- Full enterprise cross-plugin regression remains broader than this targeted
  runtime/replay/PCBA path gate.
