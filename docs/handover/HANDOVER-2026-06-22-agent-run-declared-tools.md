---
type: handover
status: active
created: 2026-06-22
relates_to:
  - docs/backlog/2026-06-22-agent-run-path-declared-tool-discovery.md
  - docs/core-concepts/agent-readiness.md
---

# Session Handover - 2026-06-22 16:31 CST

## Session Summary

This session finished the agent dispatch/run-path declared-tool fix and proved the customer-service
approval flow on a host-first DeepSeek live stack. The branch is functionally complete but still
needs normal git收口: review diff, commit, push, and PR.

## Tasks Completed

- [x] Fixed dispatch/run tool discovery so explicitly declared cross-model tools are always merged
  into the grounded tool set.
- [x] Fixed custom tool discovery metadata so approval/schema/risk are visible before execution.
- [x] Fixed generic `get:` / `list:` DSL read tools to execute through the provider registry.
- [x] Fixed approval pause/resume so the approved tool input is replayed exactly after approval.
- [x] Fixed seeded CS-agent approval policy role drift from `TENANT_ADMIN` to `tenant_admin`.
- [x] Proved live gold: inbound email -> agent -> approval -> `send_customer_reply` -> sent log ->
  `crm:create_activity` -> `mt_crm_activity`.
- [x] Destroyed the temporary live runtime `cs-inbound-gold-77`; port `6477` is no longer listening.

## Key Decisions

| Decision | Chosen Approach | Rationale | Alternatives Considered |
|---|---|---|---|
| Declared tool discovery | Shared `DeclaredAgentToolResolver`, merged additively into run-path tools | Keeps run behavior aligned with chat discovery while minimizing blast radius | Rebuild all discovery around agent declarations |
| Approval resume | Replay the exact approved tool input | Preserves the human approval boundary and avoids LLM drift on resume | Re-enter the LLM after approval |
| Custom tool metadata | Include schema, approval, and risk in discovery SQL/mapping | Prevents approved custom tools from executing with `{}` or bypassing approval | Infer metadata later during execution |
| CS-agent integration assertion | Assert run terminal state and log send/action rows as diagnostics | Real LLM routing is nondeterministic; deterministic contract is approval resume and no duplicate effect | Hard assert every LLM-selected side effect in the Spring IT |

## Files Changed

### Backend

- `platform/src/main/java/com/auraboot/framework/agent/service/DeclaredAgentToolResolver.java` -
  shared declared-tool resolver for chat/run parity.
- `platform/src/main/java/com/auraboot/framework/agent/service/StepLoopService.java` - approval
  pause/resume handling and duplicate future-step completion.
- `platform/src/main/java/com/auraboot/framework/agent/service/AgentApprovalPendingException.java` -
  carries approval pid, tool name, and approved input.
- `platform/src/main/java/com/auraboot/framework/agent/service/ToolLoopService.java` - generic model
  reads route through the provider registry.
- `platform/src/main/java/com/auraboot/framework/agent/provider/CustomToolProvider.java` - discovery
  includes `input_schema`, `requires_approval`, and `risk_level`.
- `platform/src/main/resources/database/schema.sql` - schema support for seeded/runtime contracts.

### Tests / Scripts

- `platform/src/test/java/com/auraboot/framework/agent/service/DeclaredAgentToolResolverTest.java`
- `platform/src/test/java/com/auraboot/framework/agent/service/AgentRunServiceSyncTest.java`
- `platform/src/test/java/com/auraboot/framework/agent/service/StepLoopServiceLlmResponseGuardTest.java`
- `platform/src/test/java/com/auraboot/framework/agent/service/ToolLoopServiceSafetyTest.java`
- `platform/src/test/java/com/auraboot/framework/agent/provider/CustomToolProviderTest.java`
- `platform/src/test/java/com/auraboot/framework/agent/provider/CustomToolProviderExecutionTest.java`
- `platform/src/test/java/com/auraboot/framework/agent/CustomerServiceAgentIntegrationTest.java`
- `scripts/reset-init-contracts.test.mjs`
- `scripts/seed-cs-agent.sql`
- `scripts/dev/plugin-import-profiles.json`

### Documentation

- `docs/backlog/2026-06-22-agent-run-path-declared-tool-discovery.md` - updated from active bug
  note to resolved backlog with gold evidence.
- `docs/core-concepts/agent-readiness.md` - documented declared-tool materialization and approval
  resume semantics.
- `docs/handover/HANDOVER-2026-06-22-agent-run-declared-tools.md` - this handover.

## Pitfalls & Workarounds

1. **Custom tool discovery initially returned too little metadata**
   - **Root Cause**: `CustomToolProvider.discover()` did not select/map approval and schema columns.
   - **Solution**: Discover and map `input_schema`, `requires_approval`, and `risk_level`.
   - **Prevention**: Provider discovery tests now assert metadata, not just tool names.

2. **Approval resume re-entered the LLM and could drift**
   - **Root Cause**: pending approval state stored the approval id but not the exact tool call.
   - **Solution**: Store `approvalToolName` and `approvalInput` in the step output and replay them.
   - **Prevention**: Step-loop tests cover approval-required pause and approved-input resume.

3. **Seeded approver role drifted**
   - **Root Cause**: `scripts/seed-cs-agent.sql` used stale `TENANT_ADMIN` instead of `tenant_admin`.
   - **Solution**: Update seed and add reset-init contract coverage.
   - **Prevention**: `bash scripts/check-reset-init-contracts.sh` now covers this contract.

## Reflection & Codify

### 本会话弯路 / 返工 / 翻车

1. **先修了声明工具发现,但 live gold 又暴露 custom tool metadata 缺失** - 代价:多一轮
   live/单测定位 - 本可如何更早避免:provider discovery 测试一开始就断言 schema/approval/risk - 根因:
   `[A 门禁质量, D 验证纪律]`
2. **审批 resume 初版没有固定 approved input** - 代价:多一轮 StepLoop 重构和 RED/GREEN - 本可如何更早避免:
   把 approval 当作 durable checkpoint 设计,不是 transient exception - 根因:`[D 验证纪律]`

### 为什么会发生

本会话主要卡在 provider metadata 和 approval checkpoint 的测试粒度不够细。单纯发现 tool name
不等于运行时可安全执行,审批 pending 也不等于审批后能以同一语义继续。

### 应该有哪些改进

- Provider discovery 相关改动必须断言完整工具描述符: name、schema、approval、risk。
- 审批/人工确认路径的测试必须覆盖 resume 后使用的精确输入,不能只测 pending 状态。
- Live gold 证据要同时查 action log 和业务表,避免只用 run status 当完成判据。

### 已固化 / 待固化

- [x] 已写入 `docs/core-concepts/agent-readiness.md`: declared tools are materialized in both
  chat and dispatch/run paths, and approval resume replays approved input.
- [x] 已写入 `docs/backlog/2026-06-22-agent-run-path-declared-tool-discovery.md`: root cause,
  resolution, verification, and gold evidence.
- [ ] 待 owner 拍板 `markdownlint` MD025 config: consider `MD025: { front_matter_title: "" }`
  to stop frontmatter title from counting as a duplicate H1.

## Operational State

### 分支 / Worktree / PR

- **当前分支**: `fix/agent-run-declared-tools`
- **相对 main**: `origin/main...HEAD` = ahead `1`, behind `1`
- **Worktree**: `/Users/ghj/work/auraboot/auraboot-declfix`
- **PR**: no PR for `fix/agent-run-declared-tools` as of this handover
- **Stash**: existing unrelated stashes remain untouched:
  `billing-meta-permission-constants`, `oss pre-existing changes`, `wip workbench backlog`
- **未提交改动**: backend/tests/scripts/docs are dirty; nothing staged

### Runtime / 端口

- **Live runtime used**: `cs-inbound-gold-77`, slot `77`, backend `6477`, DB `auraboot_77`
- **Current state**: runtime destroyed; `6477` has no listener
- **Host-first**: no Docker stack was used for this verification

### Verification Already Run

- `bash scripts/check-reset-init-contracts.sh`
- `node scripts/check-agent-eval-boundary.mjs`
- `bash scripts/check-oss-boundary.sh`
- `git diff --check`
- `cd platform && ./gradlew :test --tests com.auraboot.framework.agent.provider.CustomToolProviderTest --tests com.auraboot.framework.agent.service.StepLoopServiceLlmResponseGuardTest --tests com.auraboot.framework.agent.service.ToolLoopServiceSafetyTest --tests com.auraboot.framework.agent.service.DeclaredAgentToolResolverTest --tests com.auraboot.framework.agent.service.AgentRunServiceSyncTest --no-daemon`
- `cd platform && ./gradlew :test --tests com.auraboot.framework.agent.CustomerServiceAgentIntegrationTest --no-daemon`
- `cd platform && ./gradlew bootJar -x test --no-daemon`

## Next Steps

1. Run document gates after these doc edits.
2. Review `git diff`, especially the production changes in `StepLoopService` and
   `DeclaredAgentToolResolver`.
3. Run the targeted Java/doc gates after rebasing if files change.
4. Push and open PR.
