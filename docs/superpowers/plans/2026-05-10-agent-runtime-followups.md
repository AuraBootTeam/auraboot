# Agent Runtime 后续任务清单

Date: 2026-05-10
Workspace: `/Users/ghj/work/auraboot/.worktrees/agent-runtime-unification-oss`
Enterprise docs worktree: `/Users/ghj/work/auraboot/.worktrees/agent-runtime-unification-enterprise`

## 背景

AuraBot local-host 请求“统计客户信息”暴露出工具发现与运行时执行之间的断层。修复目标是一个企业事务运行时：

```text
Entry points -> ConversationTurnService -> AgentChatPort / AgentRunService -> ToolLoopService -> Provider / Skill / Approval / Trace
```

本轮 review 已确认原则：不保留独立 legacy runtime，也不以“兼容入口”为理由保留第二套执行语义。历史 URL / UI / API 如果仍有产品价值，只能作为普通入口 adapter 存在，且必须把执行委托到 `ToolLoopService`。

## P0 收口清单

- [x] 将事故背景、根因、runtime 目标、测试缺口和业界对比沉淀到 enterprise agent 文档。
- [x] 删除 chat-side 直接工具执行：`ChatToolExecutor` 只委托 `ToolLoopService`。
- [x] 删除 `ToolDiscoveryPort.executeTool` 和 provider-wide `ToolExecutionPort.executeTool` 执行 fallback。
- [x] named-agent tool call 统一进入 `ToolLoopService`，删除 `Tool executed: ...` deterministic stub。
- [x] AuraBot skill confirm 统一进入 `ToolLoopService.confirmAuraBotSkill`。
- [x] 补齐 provider-backed tool、AuraBot skill routing、named-agent tool loop、approval resume、chat adapter delegation 的后端测试。
- [x] 增加 isolated HTTP/SSE E2E，覆盖 dry-run -> Redis pending -> `/api/ai/aurabot/execute` -> metadata 落库。
- [x] 将 AuraBot skill 旧权限字面量替换为 canonical `MetaPermission` 常量。
- [x] 增加 bootstrap/skill 权限契约测试，确保 skill 权限必须是 canonical 且已注册到 `default-bootstrap.json`。
- [x] 重新运行 backend compile、目标单测/集成测试、isolated HTTP/SSE E2E、permission validator、docs drift、diff check。

## 验证结果

- [x] `./gradlew :compileJava :compileTestJava -x jacocoTestReport` -> `BUILD SUCCESSFUL`
- [x] runtime / chat adapter / approval resume / permission contract / skill unit 目标测试 -> `BUILD SUCCESSFUL`
- [x] C-2 依赖启动后，`AuraBotSkillToolProviderIntegrationTest`、`SkillToolExecutorIntegrationTest`、`AuraBotChatSkillResumeIntegrationTest` -> `BUILD SUCCESSFUL`
- [x] `node scripts/validate-permission-codes.mjs --oss-only` -> `total drift: 0; new: 0`
- [x] isolated HTTP/SSE E2E `tests/api/agent/aurabot-skill-resume-runtime.spec.ts` -> `1 passed`
- [x] enterprise `./scripts/check-docs-drift.sh` -> `0 violations`
- [x] OSS / enterprise `git diff --check` -> pass

Note: fresh isolated frontend volume required `pnpm install --frozen-lockfile` before `pnpm exec playwright` was available.

## P1 后续任务

- [x] 在 `ToolLoopService` 内引入 `RuntimeAuthorization` / `EffectClass` enforcement，所有 mutating provider 或 skill 执行前必须经过统一授权。
- [x] provider-backed 工具也要发出 ResultContract / Action / trace 记录，不能只覆盖 DSL command/query。
- [x] 增加静态架构测试：禁止 `ToolLoopService` 之外直接调用 `SkillToolExecutor.dispatch/confirm`，禁止 discovery/execution port 再出现 generic `executeTool` fallback，禁止新增 deterministic tool stub。
- [x] 在 enterprise isolated stack 中导入 `pcba-base` 和 `pcba-procurement` 后运行 PCBA agent write E2E；`pcba-procurement-agent-write.spec.ts` 已在 isolated `--project=critical` 下跑通 `3 passed`。
- [x] 当 deterministic 请求路径能稳定制造同一审批状态后，把 isolated API E2E 中的手工 Redis pending 替换为 UI/API 创建的 pending turn。

## P1 已完成补充验证

- [x] `ToolLoopServiceSafetyTest` 覆盖 RuntimeAuthorization reject/read/mutating provider effects，以及 AuraBot skill confirm reject。
- [x] `AgentChatPortImplToolLoopTest` 覆盖 AuraBot skill preview 通过 `ToolLoopService` 创建 `confirm_required` pending turn，并验证 `ToolDiscoveryContext.userId` 传递。
- [x] `StubLlmProviderTest` 覆盖 `@@AURABOOT_STUB_TOOL_USE@@` marker 只在 stub provider 中生成 deterministic tool_use，且 tool_result 后不重复触发。
- [x] isolated API E2E 覆盖 chat stream 真实生成 pending，再通过 `/api/ai/aurabot/execute` resume；测试不再手工写 Redis pending。
- [x] `model:query` AuraBot skill 的 RuntimeAuthorization / Action effects 修正为 `READ_PLATFORM_DATA`，避免把只读模型查询误审计为 `WRITE_PLATFORM_STATE`。
- [x] C-2 resume 集成测试的 `turnId` fixture 修正为真实 26 字符 PID，匹配 `ab_agent_authorization_decision.run_id VARCHAR(26)`。

## P2/P3 完整任务列表

### C1：stale schema drift 自动收敛

- [x] 找到根因：plugin re-import 会更新 `ab_meta_field.data_type`，但已存在的 model-field binding 分支不会触发 `SchemaManagementService.updateTableByModel`，导致旧 volume 的物理列仍停在旧类型。
- [x] 增加 `MetaModelFieldBindingMapper.findPublishedModelCodesByFieldId`，按 field id 找到所有已绑定的 published model。
- [x] 在 `PluginResourceImporterImpl.updateFieldForReimport` 更新字段 metadata 后，逐个同步这些 published model 的物理表。
- [x] fail-closed：schema sync 失败时抛出 `PluginException`，不把半同步状态伪装成 import 成功。
- [x] 补测试：`importField_update_syncsPublishedBoundModels` 与 `importField_update_schemaSyncFailureThrows`。
- [x] 验证：`PluginResourceImporterImplApplyTest2` 全量通过。

### C2：Playwright teardown 去掉 `psql` 命令依赖

- [x] 找到根因：`web-admin/tests/global-teardown.ts` 在 frontend 容器内 shell 调 `psql`，但 isolated frontend 镜像不带 `psql`。
- [x] 删除 `child_process.execSync("psql ...")`。
- [x] 改用 web-admin 已有依赖 `pg` 的 Node client。
- [x] 兼容 `PGHOST` / `PG_HOST`、`PGPORT` / `PG_PORT`、`PGDATABASE` / `PG_DB`。
- [x] 验证：`pnpm --dir web-admin exec tsc --noEmit --pretty false` 通过；grep 确认 teardown 不再调用 `psql`。

### C3：replay API 计划

- [x] 定位现有实现：`AgentRunController` 已提供 `/api/admin/agent-runs` 和 `/api/admin/agent-runs/{runId}` read model。
- [x] 数据源已覆盖 run、action、interrupt、child runs、BIF：`ab_agent_run`、`ab_agent_action`、`ab_agent_interrupt_log`、`ab_agent_bif`。
- [x] 权限边界：沿用 `/api/admin/**` admin guard 与 `MetaContext.getCurrentTenantId()` tenant scope。
- [x] Backend contract 已覆盖：分页、status、parentRunId、keyword、intent summary、detail sections、unknown 404、duration fallback、tenant isolation。
- [x] 验证：`AgentRunControllerIntegrationTest` -> `9 passed / BUILD SUCCESSFUL`。

### C4：replay viewer UI 计划

- [x] 定位现有实现：`/admin/agent-runs` 页面、`AgentRunDetailDrawer`、`ChildRunTree`、`LiveStreamSection` 已存在。
- [x] 前端单测覆盖：列表、筛选、分页、详情抽屉、子运行树、live stream tab、dropped badge。
- [x] E2E 覆盖：侧边栏进入、列表/API ground truth 对齐、详情抽屉、status filter、action expand、empty state。
- [x] 修复 E2E 环境缺口：`admin-agent-runs.spec.ts` 从 shell `psql` 改为 Node `pg`；菜单父节点不再依赖硬编码 `AI_CENTER_MENU_ID`。
- [x] 验证：replay viewer vitest `3 files / 11 tests passed`；`admin-agent-runs.spec.ts` -> `5 passed`。

### C5：Memory / Learning Loop / SkillDraft / Shadow Promotion 计划

- [x] 目标文档已存在：`docs/core-concepts/learning-loop.md`、`docs/core-concepts/memory-promotion.md`、`docs/core-concepts/memory-tier-promotion.md`。
- [x] SkillDraft lifecycle 已实现并验证：`DRAFT_PENDING_REVIEW -> REVIEWED_OK -> SHADOW_RUNNING/PROMOTED_PENDING_HUMAN -> ACTIVE`。
- [x] Shadow eval 已实现并验证：read-action 可 shadow，write-action/dsl v0 无 dry-run support 不直接执行真实 write tool。
- [x] Memory promotion 可撤销性已实现并验证：`PROMOTED_SHADOW -> RETRACTED` 会软删除 promoted memory。
- [x] 修复公共 E2E helper：`_real-backend-helpers.ts` 从 shell `psql` 改为同步 Node `pg` runner，并兼容 boolean `t/true`。
- [x] 验证：Memory/Learning backend target `BUILD SUCCESSFUL`；shadow viewer vitest `2 files / 5 tests passed`；`admin-shadow-runs.spec.ts` -> `2 passed`；`ai-learning-drafts-real.spec.ts` + `ai-memory-promotions-real.spec.ts` -> `7 passed`。

### C6：最终 gate

- [x] backend target tests：runtime、replay API、Memory/Learning、schema drift。
- [x] frontend unit：ChatBi result, replay viewer, shadow viewer。
- [x] E2E：skill resume API、PCBA write UI、replay viewer、shadow runs、learning drafts、memory promotions。
- [x] `node scripts/validate-permission-codes.mjs --oss-only` -> `total drift: 0; new: 0`
- [x] enterprise `./scripts/check-docs-drift.sh` -> `0 violations`
- [x] OSS / enterprise `git diff --check` -> pass

## 当前边界

- replay API/UI、conversation turn 全量回放、result-contract 深链与 Memory/Learning 已按当前代码面完成验证；后续增强只剩 time-travel、fork-from-step、historical SSE replay 这类新产品能力。
- 后续不需要再追问“还有哪些任务”；本轮列出的 C1-C6 已完成并验证。
- 默认坚持 One Agent + N Skills；multi-agent 只用于隔离、并行或模型专用场景。

## P1 静态架构测试结果

- [x] 新增 `AgentRuntimeArchitectureTest`。
- [x] `./gradlew :test --tests AgentRuntimeArchitectureTest -x jacocoTestReport` -> `BUILD SUCCESSFUL`
- [x] 覆盖规则：
  - `ToolLoopService` 之外不能直接调用 `skillToolExecutor.dispatch/confirm`。
  - `ToolDiscoveryPort` / `ToolExecutionPort` 不能重新暴露 generic `executeTool` fallback。
  - agent runtime 生产源码不能返回 `Tool executed:` 这类 deterministic fake stub。
  - chat path 不能调用已删除的 `ToolDiscoveryPort.executeTool` / `ToolExecutionPort.executeTool`。
