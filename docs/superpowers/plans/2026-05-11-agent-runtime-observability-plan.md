# Agent Runtime Observability Plan

Date: 2026-05-11
Workspace: `/Users/ghj/work/auraboot/.worktrees/agent-runtime-unification-oss`
Branch: `codex/agent-runtime-unification`

## Problems

- 事故链路已经修复，但运行时缺少明确的在线证据：工具发现、执行、授权、ResultContract emission 的指标还分散在日志/DB 行里。
- `ToolLoopService` 遇到已发现但不支持的 tool type 时只返回普通错误字符串，缺少稳定 error code 和结构化告警线索。
- Replay detail 可以按 runId 看完整链路，但缺一个面向排障的只读审计查询：按 `runId` / `conversationId` / `toolName` 快速拉出 action、authorization decision、approval、result-contract projection。

## Target

- 复用现有 Micrometer、`ab_agent_action`、`ab_agent_authorization_decision`、`ab_agent_approval`、Replay read model；不新增表。
- 增加 agent runtime metrics：
  - tool discovery call/result signal
  - tool execution outcome by tool type
  - runtime authorization decision
  - result contract emitted / skipped
- Unsupported tool type fail-fast：返回稳定 `unsupported_tool_type` code，并写结构化 log/metric。
- 增加 admin read-only audit endpoint：`GET /api/admin/agent-runs/audit?runId=&conversationId=&toolName=`。

## Non-Goals

- 不做新前端页面。
- 不新增告警平台或 Prometheus rule 文件。
- 不改变真实工具执行语义，不引入 fallback。
- 不用手工 SQL 修测试数据。

## Task List

### D2.1 Metrics Service

- [x] 新增 `AgentRuntimeObservabilityService`，封装 Micrometer counters。
- [x] 覆盖 tool discovery、tool execution、authorization decision、result contract emission。
- [x] 单测验证关键 meter name / tags。

### D2.2 Runtime Signal Wiring

- [x] `ToolDiscoveryPortImpl` 记录 discovery metrics。
- [x] `ToolLoopService` 记录 execution metrics。
- [x] `DefaultRuntimeAuthorizationService` 记录 authorization metrics。
- [x] `ResultContractEmitter` 记录 emitted/skipped metrics。
- [x] unsupported tool type 返回结构化错误并记录 metric/log。

### D2.3 Audit Query

- [x] 新增 DTO：`AgentRuntimeAuditTrail`、approval/authorization item。
- [x] `AgentRunController` 增加 `/audit` 只读接口，支持 `runId` / `conversationId` / `toolName`。
- [x] tenant scope 必须覆盖 action、approval、authorization、conversation lookup。
- [x] result contracts 从 action deterministic projection 生成。

### D2.4 Verification

- [x] 后端目标测试：observability、ToolLoop safety、AgentRunController integration。
- [x] `git diff --check`。

## Verification Log

- 2026-05-11: `cd platform && ./gradlew :test --tests com.auraboot.framework.agent.observability.AgentRuntimeObservabilityServiceTest --tests com.auraboot.framework.agent.service.ToolLoopServiceSafetyTest --tests com.auraboot.framework.integration.agent.AgentRunControllerIntegrationTest --tests com.auraboot.framework.integration.agent.RuntimeAuthorizationServiceIntegrationTest -x jacocoTestReport` -> `BUILD SUCCESSFUL in 33s`.
- 2026-05-11: audit approval deep-link hardening red/green:
  - RED: `./gradlew :test --tests com.auraboot.framework.integration.agent.AgentRunControllerIntegrationTest.audit_linksApprovalByAuthorizationApprovalId -x jacocoTestReport` -> failed because tool-filtered audit did not include approval rows linked only through `authorization_decision.approval_id`.
  - GREEN: same command -> `BUILD SUCCESSFUL in 32s` after querying approvals by linked `approval_id`.
- 2026-05-11: extended backend target gate: `cd platform && ./gradlew :test --tests com.auraboot.framework.agent.observability.AgentRuntimeObservabilityServiceTest --tests com.auraboot.framework.agent.service.ToolLoopServiceSafetyTest --tests com.auraboot.framework.integration.agent.AgentRunControllerIntegrationTest --tests com.auraboot.framework.integration.agent.RuntimeAuthorizationServiceIntegrationTest --tests com.auraboot.framework.architecture.AgentRuntimeArchitectureTest --tests com.auraboot.framework.integration.agent.AiTraceControllerIntegrationTest -x jacocoTestReport` -> `BUILD SUCCESSFUL in 27s`.
- 2026-05-11: `git diff --check` -> no whitespace errors.

## Acceptance Criteria

- 可以通过 metrics 判断：发现了多少工具、执行了什么类型工具、授权结果是什么、ResultContract 是否发出。
- unsupported tool type 不再只是自由文本错误。
- 运维可用一个 admin audit API 按 runId/conversationId/toolName 找到 action、approval、authorization decision、result-contract projection。
