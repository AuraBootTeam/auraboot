# Agent Runtime Merge Readiness

Date: 2026-05-10
Workspace: `/Users/ghj/work/auraboot/.worktrees/agent-runtime-unification-oss`
Branch: `codex/agent-runtime-unification`

## 背景

本轮修复来自 AuraBot local-host 环境执行“统计客户信息”暴露出的核心链路缺口：工具发现、对话 turn、agent run、provider/skill 执行、审批恢复、Action/ResultContract 记录和 replay viewer 原本没有被一条真实产品链路完整覆盖。

当前合入目标不是增加第二套兼容 runtime，而是把旧入口收敛为 adapter，并把所有工具执行统一到 `ToolLoopService`：

```text
Entry adapters
  -> ConversationTurnService
  -> AgentChatPort / AgentRunService
  -> ToolLoopService
  -> Provider / Skill / Approval / Action / ResultContract / Trace
```

## 合入前任务列表

### D0: Merge Packaging

- [x] 确认当前工作区在隔离 worktree 分支，canonical 仓库不离开 `main`。
- [x] 梳理 diff 分组，形成 reviewable change groups。
- [x] 补齐 PR / merge note，说明架构决策、验证范围、风险边界。
- [x] 记录 fresh verification gate 输出。

### D1: Focused Review

- [x] 审查 `ToolLoopService` 是否仍是唯一工具执行控制面。
- [x] 审查 replay/detail read model 的 tenant scope、空对象语义和 result-contract 深链。
- [x] 审查 E2E truth：无 skip/fixme/only、无 waitForTimeout、无写 API 伪造业务链路、无 retry mask。
- [x] 审查 agent-runtime 文档是否仍把已完成能力错误标为未来工作。

### D2: Verification

- [x] Backend runtime/replay target tests。
- [x] Frontend replay/trace/result unit tests。
- [x] Frontend typecheck。
- [x] Targeted E2E truth grep。
- [x] `git diff --check`。

## Reviewable Change Groups

### Group 1: Canonical Runtime

- `ToolLoopService` 统一 provider-backed tools、DSL tools、AuraBot skills、named-agent tool calls。
- `ChatToolExecutor` 退化为 adapter，不再保留 chat-side 独立工具执行语义。
- `ToolDiscoveryPort` 不再暴露 execution；`ToolExecutionPort` 已删除，避免重新形成执行端口适配面。
- `RuntimeAuthorization` / `EffectClass` 在工具执行前统一判断。
- `ActionRecorder` / `ResultContractEmitter` 记录执行证据。

### Group 2: Conversation / Approval / Resume

- `ConversationTurnServiceImpl` 和 `AgentChatPortImpl` 通过统一 turn/resume 路径处理 pending approval。
- AuraBot skill preview / confirm 统一进入 `ToolLoopService.confirmAuraBotSkill`。
- Stub provider 的 deterministic marker 只用于测试造 tool_use，不进入生产 fake result fallback。

### Group 3: Replay / Trace / Result Deep Links

- `AgentRunController` 增加 detail projection：run、actions、interrupts、child runs、BIF、conversation turn、result contracts、traceId。
- `AiTraceController` detail/span 查询限定 tenant。
- `AgentRunDetailDrawer` 增加 Conversation / Results tabs、Open Trace、action-to-result-contract deep link。
- Trace detail 支持回跳相关 agent run。

### Group 4: Isolated E2E / Plugin Import / Schema Drift

- isolated stack 支持 enterprise plugin mount。
- `import-isolated-plugins.sh` 提供 PCBA profile 导入路径。
- `PluginResourceImporterImpl` 在 field metadata re-import 后同步已绑定 published model 的物理表。
- Playwright teardown 和 real-backend helper 移除 frontend 容器内 `psql` 依赖，改用 Node `pg`。

### Group 5: Tests / Docs / Guards

- 后端新增 runtime、architecture、permission、replay、trace、schema drift 覆盖。
- 前端新增 replay/trace/result 单测和 targeted E2E 覆盖。
- agent runtime master/followup/completion/delivery 文档同步为当前状态。

### Group 6: Runtime Observability / Audit

- 新增 `AgentRuntimeObservabilityService`，低基数记录 tool discovery、tool execution、authorization decision、ResultContract emission、unsupported tool type。
- `ToolDiscoveryPortImpl`、`ToolLoopService`、`DefaultRuntimeAuthorizationService`、`ResultContractEmitter` 接入 metrics。
- unsupported discovered tool type 返回稳定 `unsupported_tool_type` JSON 失败信号，并记录结构化 log/metric。
- 新增只读审计接口 `GET /api/admin/agent-runs/audit?runId=&conversationId=&toolName=`，汇总 action、authorization decision、approval、result-contract projection。
- audit approval 查询不依赖展示文案；当 toolName 过滤时会沿 `ab_agent_authorization_decision.approval_id -> ab_agent_approval.pid` 取回审批记录。

## PR / Merge Note Draft

### Summary

Unify AuraBot / ACP tool execution around `ToolLoopService`, remove legacy direct execution fallbacks, add authorization/effect/result-contract recording, and complete replay deep links from conversation turns to result contracts and traces.

### Key Decisions

- No legacy runtime remains in principle. Existing URLs, UI surfaces, and API entrypoints may stay only as adapters into the canonical runtime.
- Provider-backed tools, DSL tools, AuraBot skills, and named-agent tool calls must pass through `ToolLoopService`.
- Deterministic tool-use markers are allowed only in the stub provider test path and must not become production fallback results.
- Replay is a tenant-scoped read model. Runs without turn identity return `conversationTurn = null`, not an empty synthetic object.

### Verification Scope

- Backend target tests cover runtime routing, authorization/effects, approval resume, architecture guards, replay/detail, trace tenant scope, permission contract, and schema drift.
- Frontend unit tests cover replay drawer, trace links, result contract display, and result cards.
- Targeted E2E covers real chat stream pending -> execute resume, PCBA UI write flow in isolated stack, replay viewer, and sidebar/detail navigation.

### Risk Boundaries

- Time-travel replay, fork-from-step, and historical SSE replay are not part of this merge.
- Full enterprise cross-plugin regression is broader than this target gate; this merge focuses on agent runtime, replay, PCBA target path, and supporting isolated infrastructure.
- Real LLM nondeterminism remains outside E2E; deterministic stub markers are test scaffolding only.

## Verification Log

- Backend target gate: `./gradlew :test --tests ... -x jacocoTestReport` covering `AgentRuntimeArchitectureTest`, `AgentRunControllerIntegrationTest`, `AiTraceControllerIntegrationTest`, `AcpKernelServicesIntegrationTest`, `StubLlmProviderTest`, `ToolLoopServiceSafetyTest`, `AgentChatPortImplToolLoopTest`, `ChatToolExecutorCanonicalRuntimeTest`, `AuraBotSkillPermissionContractTest`, and `PluginResourceImporterImplApplyTest2` -> `BUILD SUCCESSFUL in 1m 6s`。
- Frontend unit: `pnpm --dir web-admin exec vitest run AgentRunDetailDrawerLiveStream.test.tsx AgentRunsPage.test.tsx TraceDetailPage.test.tsx ChatBiResultCard.test.tsx` -> 4 files / 14 tests passed。
- Frontend typecheck: `pnpm --dir web-admin typecheck` -> exit 0。
- E2E truth grep: no code-state `test.only` / `test.skip` / `test.fixme` / `waitForTimeout` / `retries:N` / `page.request.put|delete|patch` hits in the three target specs。
- PCBA E2E boundary: `page.request.post` remains only in setup/import/fixture/Agent Definition seeding; covered business writes still go through browser AuraBot confirmation, ToolLoopService, and approval UI. The spec header now states this boundary explicitly.
- Architecture hardening: `ToolExecutionPort` file removed; `AgentRuntimeArchitectureTest` now fails if it is reintroduced or if entry adapters call DSL shortcut execution outside `ToolLoopService` / `SkillEngine` internals。
- Result-contract hardening: `AgentRunControllerIntegrationTest` now verifies `action.resultContractId == contract.contractId == turn.resultContractIds[0]` plus contract `outputType`, `actionability`, `data.actionPid`, `beforeSnapshot`, and `fieldChanges` shape。
- Static docs audit: active master/followups/completion/delivery docs no longer describe completed conversation/result deep links as future work; remaining future-work wording is historical context inside the post-replay closeout plan。
- Runtime observability/audit gate: `./gradlew :test --tests AgentRuntimeObservabilityServiceTest --tests ToolLoopServiceSafetyTest --tests AgentRunControllerIntegrationTest --tests RuntimeAuthorizationServiceIntegrationTest --tests AgentRuntimeArchitectureTest --tests AiTraceControllerIntegrationTest -x jacocoTestReport` -> `BUILD SUCCESSFUL in 27s`。
- Audit approval deep-link TDD: `audit_linksApprovalByAuthorizationApprovalId` first failed when approval text did not contain tool name, then passed after approval lookup included linked `authorization_decision.approval_id`。
- 2026-05-11 final static refresh:
  - `node scripts/validate-permission-codes.mjs --oss-only` -> `total drift: 0; new: 0`。
  - enterprise `./scripts/check-docs-drift.sh` -> `drift-audit passed (0 violations)`。
  - `pnpm --dir web-admin typecheck` -> exit 0。
  - `pnpm --dir web-admin exec vitest run AgentRunDetailDrawerLiveStream.test.tsx AgentRunsPage.test.tsx TraceDetailPage.test.tsx ChatBiResultCard.test.tsx` -> `4 files / 14 tests passed`。
  - E2E truth grep on `admin-agent-runs.spec.ts`, `pcba-procurement-agent-write.spec.ts`, and `aurabot-skill-resume-runtime.spec.ts`: no executable `test.only` / `test.skip` / `test.fixme` / `waitForTimeout` / `retries:N` / `page.request.put|delete|patch` hits. `admin-agent-runs.spec.ts` retains three `toBeGreaterThanOrEqual(1)` existence assertions for returned audit sections; these are semantic lower-bound checks, not baseline drift thresholds.
- Hygiene: `git diff --check` -> pass。
