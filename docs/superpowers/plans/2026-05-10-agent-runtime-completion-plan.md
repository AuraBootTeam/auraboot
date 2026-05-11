# Agent Runtime Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 彻底关闭 AuraBot/ACP runtime 事故暴露的剩余企业级治理缺口：统一执行、统一授权、统一审计、真实 E2E 和全量 gate。

**Architecture:** 所有入口只能进入 `ConversationTurnService -> AgentChatPort / AgentRunService -> ToolLoopService`。`ToolLoopService` 是唯一执行控制面，负责 Tool ACL、RuntimeAuthorization、Approval/Confirmation、Provider/Skill/DSL 执行、Action/ResultContract/Trace。历史 chat 路径只能是 adapter，不能拥有独立执行语义。

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Mockito, Gradle, Playwright, Docker isolated stacks, PostgreSQL, Redis.

---

## 当前基线

- P0 已完成：provider-backed tool / AuraBot skill 进入 `ToolLoopService`，named-agent stub 移除，AuraBot skill 权限改为 canonical `MetaPermission`，isolated API E2E 已验证。
- P1 已完成：`AgentRuntimeArchitectureTest` 静态防线，禁止重新引入 second runtime / fake stub / discovery execute fallback。
- P1 已完成：`ToolLoopService` 调用 `RuntimeAuthorizationService`，provider-backed tool 与 AuraBot skill confirm 统一写入 ResultContract / Action / effects。
- P1 已完成：isolated API E2E 已从手工 Redis pending 升级为真实 chat stream 生成 pending，再通过 `/api/ai/aurabot/execute` resume。
- 后续补齐：enterprise PCBA E2E、replay viewer、Memory/Learning/Shadow real-backend E2E 已完成目标验证；只剩最终轻量 gate 收口。
- 状态口径：本文件是执行过程计划，最终状态以 `2026-05-10-agent-runtime-master-task-list.md` 为准；下方步骤已按 2026-05-10 实际执行结果同步为完成态。

## 任务总表

| ID | 优先级 | 状态 | 任务 | 验收 |
|---|---|---|---|---|
| T1 | P1 | DONE | `ToolLoopService` 接入 `RuntimeAuthorizationService` | mutating/read provider/skill/DSL 执行前写 authorization decision；reject/approval-required 不执行工具 |
| T2 | P1 | DONE | provider-backed tool 生成 ResultContract / Action | `platform.list_models`/`platform.execute_sql` 等 read tool 有 ResultContract；provider mutating tool 有 Action 或 rejected audit |
| T3 | P1 | DONE | AuraBot skill confirm 生成 ResultContract / Action/actual_effects | skill preview/confirm 的真实执行有统一 contract 和 action/effect audit |
| T4 | P1 | DONE | isolated API E2E 从手工 Redis pending 升级到产品 API 创建 pending | 不直接写 Redis；通过真实 dry-run/chat/resume API 完成 pending lifecycle |
| T5 | P1 | DONE | enterprise PCBA agent write E2E | isolated `pcba-procurement-agent-write.spec.ts` 已跑通 `3 passed` |
| T6 | P1 | DONE | 全量 gate 与文档收口 | OSS backend target + isolated API/UI + docs drift + permission drift + diff check 已记录；最终轻量 gate 本轮末尾复跑 |
| T7 | P2 | DONE | 可回放 `Conversation -> BIF -> Skill -> Action -> Tool -> ResultContract` 链 | `/api/admin/agent-runs` + `/admin/agent-runs` 已验证 run/action/interrupt/child-run/BIF、conversation turn 全量回放和 result-contract 深链 |
| T8 | P2 | DONE | Memory / Learning Loop / SkillDraft / Shadow Promotion | backend target、shadow viewer unit、learning/memory real E2E 已验证 |

## T1: RuntimeAuthorization 接入 ToolLoopService

**Files:**
- Modify: `/Users/ghj/work/auraboot/.worktrees/agent-runtime-unification-oss/platform/src/main/java/com/auraboot/framework/agent/service/ToolLoopService.java`
- Test: `/Users/ghj/work/auraboot/.worktrees/agent-runtime-unification-oss/platform/src/test/java/com/auraboot/framework/agent/service/ToolLoopServiceSafetyTest.java`
- Existing: `/Users/ghj/work/auraboot/.worktrees/agent-runtime-unification-oss/platform/src/main/java/com/auraboot/framework/agent/authorization/RuntimeAuthorizationService.java`

- [x] **Step 1: Write failing tests**

Add tests to `ToolLoopServiceSafetyTest`:

```java
@Mock private RuntimeAuthorizationService runtimeAuthorizationService;

@Test
@DisplayName("runtime authorization rejects mutating provider tools before execution")
void runtimeAuthorizationRejectsMutatingProviderToolBeforeExecution() {
    AgentToolDefinition tool = AgentToolDefinition.builder()
            .name("platform.create_model")
            .description("Create model")
            .toolType("platform")
            .sourceCode("platform.create_model")
            .riskLevel("L3")
            .requiresApproval(false)
            .build();
    when(runtimeAuthorizationService.authorizeIncremental(any()))
            .thenReturn(RuntimeAuthorizationService.IncrementalAuthorization.reject(
                    "WRITE_PLATFORM_STATE is forbidden", "tenant_policy"));

    String result = service.executeToolCall(1L, "run-authz", "task-authz", "agent",
            tool.getName(), Map.of("description", "Customer model"), List.of(tool), null);

    assertThat(result).contains("Runtime authorization denied")
            .contains("WRITE_PLATFORM_STATE is forbidden");
    verifyNoInteractions(toolProviderRegistry, commandExecutor, namedQueryService);
}

@Test
@DisplayName("runtime authorization records read provider effects before execution")
void runtimeAuthorizationRunsForReadProviderTools() {
    AgentToolDefinition tool = AgentToolDefinition.builder()
            .name("platform.list_models")
            .description("List models")
            .toolType("platform")
            .sourceCode("platform.list_models")
            .riskLevel("L0")
            .build();
    when(runtimeAuthorizationService.authorizeIncremental(any()))
            .thenReturn(RuntimeAuthorizationService.IncrementalAuthorization.grant());
    when(toolProviderRegistry.execute(eq(1L), eq("platform.list_models"), anyMap()))
            .thenReturn(ProviderExecutionResult.builder()
                    .success(true)
                    .data(Map.of("models", List.of()))
                    .durationMs(3)
                    .build());

    String result = service.executeToolCall(1L, "run-read", "task-read", "agent",
            tool.getName(), Map.of(), List.of(tool), null);

    assertThat(result).contains("\"success\":true");
    verify(runtimeAuthorizationService).authorizeIncremental(argThat(intent ->
            intent.requiredEffects().contains(EffectClass.READ_PLATFORM_DATA)
                    && intent.blastRadius() == BlastRadius.REVERSIBLE
                    && "platform.list_models".equals(intent.toolRef())));
}
```

- [x] **Step 2: Verify RED**

Run:

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-runtime-unification-oss/platform
./gradlew :test --tests ToolLoopServiceSafetyTest -x jacocoTestReport
```

Expected: compile or test failure because `ToolLoopService` constructor has no `RuntimeAuthorizationService` and no authorization call exists.

- [x] **Step 3: Implement minimal runtime auth**

Modify `ToolLoopService`:

```java
private final RuntimeAuthorizationService runtimeAuthorizationService;
```

Before approval/confirmation/execution, call:

```java
RuntimeAuthorizationService.IncrementalAuthorization authorization =
        runtimeAuthorizationService.authorizeIncremental(buildToolCallIntent(
                tenantId, runPid, toolName, toolDef, input));
if (!authorization.granted()) {
    return "Error: Runtime authorization denied for tool '" + toolName + "': "
            + authorization.rejectedReason();
}
if (authorization.requireApproval()) {
    return toJsonResult(Map.of(
            "success", false,
            "approvalRequired", true,
            "approvalPid", authorization.approvalRequestId() != null
                    ? authorization.approvalRequestId() : "",
            "message", "Runtime authorization requires human approval."));
}
```

Add helpers:

```java
private RuntimeAuthorizationService.ToolCallIntent buildToolCallIntent(
        Long tenantId, String runPid, String toolName,
        AgentToolDefinition toolDef, Map<String, Object> input) { ... }

private Set<EffectClass> deriveEffects(String toolName, AgentToolDefinition toolDef) { ... }

private BlastRadius deriveBlastRadius(String toolName, AgentToolDefinition toolDef) { ... }

private String hashArgs(Map<String, Object> input) { ... }
```

Effect mapping for P1:

- `dsl_query`, `platform.list_models`, `platform.execute_sql`, `platform.model_suggest` -> `READ_PLATFORM_DATA`
- `AURABOT_SKILL` with `echo` -> `READ_CONTEXT`
- `AURABOT_SKILL` with `model:create` or `field:add` -> `WRITE_PLATFORM_STATE`
- `dsl_command`, `platform.create_model`, `platform.delegate_task` -> `WRITE_PLATFORM_STATE`
- `custom:*`, `mcp:*`, `api_call` provider tools -> `EXTERNAL_NETWORK` plus `WRITE_PLATFORM_STATE` when not read-only
- default unknown provider -> `WRITE_PLATFORM_STATE`

Blast radius mapping:

- read effects only -> `REVERSIBLE`
- `WRITE_DRAFT` -> `REVERSIBLE`
- `WRITE_PLATFORM_STATE` -> `SHARED_STATE`
- `TERMINAL_EXEC`, `SECRET_ACCESS`, `FILE_WRITE`, high risk `L4/R4` -> `IRREVERSIBLE`

- [x] **Step 4: Verify GREEN**

Run:

```bash
./gradlew :test --tests ToolLoopServiceSafetyTest -x jacocoTestReport
./gradlew :test --tests AgentChatPortImplToolLoopTest --tests ChatToolExecutorCanonicalRuntimeTest -x jacocoTestReport
```

Expected: `BUILD SUCCESSFUL`.

## T2: Provider-backed ResultContract / Action

**Files:**
- Modify: `ToolLoopService.java`
- Modify: `ResultContractEmitter.java`
- Modify: `ActionRecorder.java`
- Test: `ToolLoopServiceSafetyTest.java`

- [x] **Step 1: Write failing tests**

Add assertions:

```java
verify(resultContractEmitter).emitProviderResult(
        eq("platform.list_models"), same(tool), anyString(), anyLong(), eq(true));
verify(actionRecorder).recordProviderAction(
        eq(1L), eq("run-platform"), eq("platform.list_models"), same(tool),
        eq(input), anyMap(), eq(null), eq(Set.of(EffectClass.READ_PLATFORM_DATA)));
```

- [x] **Step 2: Verify RED**

Run:

```bash
./gradlew :test --tests ToolLoopServiceSafetyTest -x jacocoTestReport
```

Expected: failure because `emitProviderResult` / `recordProviderAction` do not exist.

- [x] **Step 3: Implement ResultContractEmitter.emitProviderResult**

Provider result rules:

- If response contains `records` list -> `renderHint=table`, `outputType=structured_result`
- If response contains `models` list -> convert to table-like list
- If mutating success -> `renderHint=card`, `outputType=action_proposal`
- Failure -> `renderHint=summary`, `status=failed`

- [x] **Step 4: Implement ActionRecorder.recordProviderAction**

P1 minimal row:

- `action_code`: `<toolRef>.provider`
- `action_type`: `read` for read effects only, otherwise `execute`
- `transaction_scope`: `read_only` or `shared_state`
- `side_effect_type`: `none` for read, `provider_tool` for mutating
- `target_model`: infer from `input.modelCode`, `input.code`, provider rows, else `platform`
- `command_code`: provider tool code
- `actual_effects`: JSONB array of effect class names

- [x] **Step 5: Verify GREEN**

Run:

```bash
./gradlew :test --tests ToolLoopServiceSafetyTest -x jacocoTestReport
```

Expected: `BUILD SUCCESSFUL`.

## T3: AuraBot skill ResultContract / Action/effects

**Files:**
- Modify: `ToolLoopService.java`
- Modify: `ResultContractEmitter.java`
- Modify: `ActionRecorder.java`
- Test: `ToolLoopServiceSafetyTest.java`
- Test: `AuraBotChatSkillResumeIntegrationTest.java`

- [x] **Step 1: Write failing tests**

Assert `confirmAuraBotSkill` emits provider-style result contract and records action with `actual_effects=["WRITE_PLATFORM_STATE"]` for `model:create`.

- [x] **Step 2: Implement**

Reuse `recordProviderAction` with `toolType=AURABOT_SKILL`, `toolRef=aurabot:model:create`, `skillCode=model:create`.

- [x] **Step 3: Verify**

Run:

```bash
docker compose -f docker-compose.yml -f docker-compose.skills-c2.override.yml -p auraboot-skills-c2 --profile skills-c2-stack up -d postgres redis
./gradlew :test --tests ToolLoopServiceSafetyTest --tests AuraBotChatSkillResumeIntegrationTest -x jacocoTestReport
docker compose -f docker-compose.yml -f docker-compose.skills-c2.override.yml -p auraboot-skills-c2 --profile skills-c2-stack down
```

Expected: `BUILD SUCCESSFUL`.

## T4: isolated API E2E pending lifecycle

**Files:**
- Modify: `web-admin/tests/api/agent/aurabot-skill-resume-runtime.spec.ts`
- Possibly modify backend only if no product API can create pending turn deterministically.

- [x] **Step 1: Try product path without Redis write**

Use actual dry-run/chat API to create pending state. If unavailable, document the missing API as a product gap and keep Redis write as a targeted lower-level E2E until API exists.

- [x] **Step 2: Verify**

Run:

```bash
AGENT_LLM_STUB_MODE=true scripts/dev/start-isolated.sh --slug=agent-runtime-e2e --rebuild
docker exec auraboot-agent-runtime-e2e-frontend bash -lc '
  cd /repo/web-admin &&
  BACKEND_URL=http://backend:6443 BE_PORT=6443 \
  PLAYWRIGHT_BASE_URL=http://isolated-frontend:5173 \
  BFF_URL=http://127.0.0.1:3500 \
  PG_HOST=postgres PG_PORT=5432 PG_USER=auraboot PG_DB=aura_boot \
  PGPASSWORD=auraboot_dev REDIS_HOST=redis REDIS_PORT=6379 \
  PW_SKIP_WEBSERVER=1 PW_PROFILE=full \
  pnpm exec playwright test -c playwright.noweb.config.ts \
    tests/api/agent/aurabot-skill-resume-runtime.spec.ts \
    --project=api --reporter=line --no-deps
'
scripts/dev/stop-isolated.sh --slug=agent-runtime-e2e
```

Expected: `1 passed`.

## T5: enterprise PCBA agent write E2E

**Files:**
- Enterprise docs/status only unless test drift is discovered.
- Target spec: `web-admin/tests/e2e/aurabot/pcba-procurement-agent-write.spec.ts`

- [x] **Step 1: Start enterprise isolated stack**

Use enterprise stack with `pcba-base` and `pcba-procurement` imported. Do not run this spec against OSS stack.

- [x] **Step 2: Run spec**

Expected product assertions:

- PCBA ERP menu exists
- `pe:create_procurement_comparison_draft` command exists
- Agent write path produces approval/confirmation and real command result

- [x] **Step 3: Classify failures**

If failure is plugin import or seed gap, fix source plugin/bootstrap, not test-side DB hot patch.

**Current status (2026-05-10): DONE.**

- `docker-compose.isolated.yml` now supports explicit enterprise plugin mount.
- `scripts/dev/import-isolated-plugins.sh --profile=pcba-agent` imports the PCBA dependency chain inside the isolated stack.
- `pcba-procurement-agent-write.spec.ts` seeds deterministic product/supplier/quotation fixtures and runs under stubbed LLM mode.
- Isolated result: `pcba-procurement-agent-write.spec.ts --project=critical --no-deps` -> `3 passed`.

## T6: final gates

- [x] `./gradlew :compileJava :compileTestJava -x jacocoTestReport`
- [x] target backend tests:

```bash
./gradlew :test \
  --tests AgentRuntimeArchitectureTest \
  --tests ToolLoopServiceSafetyTest \
  --tests AgentChatPortImplToolLoopTest \
  --tests ChatToolExecutorCanonicalRuntimeTest \
  --tests AuraBotSkillPermissionContractTest \
  -x jacocoTestReport
```

- [x] C-2 skill gates:

```bash
docker compose -f docker-compose.yml -f docker-compose.skills-c2.override.yml -p auraboot-skills-c2 --profile skills-c2-stack up -d postgres redis
./gradlew :test \
  --tests AuraBotSkillToolProviderIntegrationTest \
  --tests SkillToolExecutorIntegrationTest \
  --tests AuraBotChatSkillResumeIntegrationTest \
  -x jacocoTestReport
docker compose -f docker-compose.yml -f docker-compose.skills-c2.override.yml -p auraboot-skills-c2 --profile skills-c2-stack down
```

- [x] `node scripts/validate-permission-codes.mjs --oss-only`
- [x] `git diff --check` in OSS and enterprise worktrees
- [x] enterprise `./scripts/check-docs-drift.sh`
- [x] isolated API E2E

**Executed evidence (2026-05-10):**

- [x] `./gradlew :compileJava :compileTestJava -x jacocoTestReport` -> `BUILD SUCCESSFUL`
- [x] `./gradlew :test --tests AgentRuntimeArchitectureTest --tests ToolLoopServiceSafetyTest --tests AgentChatPortImplToolLoopTest --tests ChatToolExecutorCanonicalRuntimeTest --tests AuraBotSkillPermissionContractTest --tests StubLlmProviderTest -x jacocoTestReport` -> `BUILD SUCCESSFUL`
- [x] C-2 skill gates (`AuraBotSkillToolProviderIntegrationTest`, `SkillToolExecutorIntegrationTest`, `AuraBotChatSkillResumeIntegrationTest`) -> `BUILD SUCCESSFUL`
- [x] isolated API E2E `tests/api/agent/aurabot-skill-resume-runtime.spec.ts --project=api --no-deps` -> `1 passed`
- [x] `node scripts/validate-permission-codes.mjs --oss-only` -> `total drift: 0; new: 0`
- [x] OSS `git diff --check` -> pass
- [x] enterprise `git diff --check` -> pass
- [x] enterprise `./scripts/check-docs-drift.sh` -> `0 violations`

## T7/T8: P2 closure

P2 replay 与 Memory/Learning 本轮已按现有 MVP/产品面完成验证：

- Replay API/UI：`AgentRunControllerIntegrationTest` -> `9 passed`；replay viewer vitest -> `3 files / 11 tests passed`；`admin-agent-runs.spec.ts` -> `5 passed`。
- Shadow run comparison：backend `AdminShadowRunControllerIntegrationTest` included in Memory/Learning target gate；shadow viewer vitest -> `2 files / 5 tests passed`；`admin-shadow-runs.spec.ts` -> `2 passed`。
- Learning drafts / memory promotions：backend target gate `BUILD SUCCESSFUL`；`ai-learning-drafts-real.spec.ts` + `ai-memory-promotions-real.spec.ts` -> `7 passed`。
- New E2E helper debt fixed: aurabot real-backend helper now uses Node `pg`, not shell `psql`.
