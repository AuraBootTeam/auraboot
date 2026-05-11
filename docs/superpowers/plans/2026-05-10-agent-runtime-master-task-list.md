# Agent Runtime 主任务列表

日期：2026-05-10
OSS worktree：`/Users/ghj/work/auraboot/.worktrees/agent-runtime-unification-oss`
Enterprise docs/plugins worktree：`/Users/ghj/work/auraboot/.worktrees/agent-runtime-unification-enterprise`

## 背景

AuraBot 在本地 host 环境执行“统计客户信息”时暴露出核心链路缺口：工具发现层能看到 `platform.*` / AuraBot skill，但 ACP `ToolLoopService` 旧执行内核不认识 provider-backed 和 `AURABOT_SKILL` 工具类型。旧测试覆盖了 triage、provider、controller 等零件，没有覆盖真实组合链路：

```text
用户输入 -> ConversationTurnService -> AgentRunService / AgentChatPort
  -> StepLoopService -> ToolLoopService -> Provider / Skill / Approval / Action / ResultContract
```

本轮目标是统一成一个企业事务 runtime：多个入口可以存在，但不能再有第二套工具执行语义、legacy fallback 或 fake stub。

## 总体验收目标

- `ToolLoopService` 是唯一工具执行控制面。
- Provider-backed tools、DSL tools、AuraBot skills、named-agent tools 都经同一 runtime。
- 执行前有 RuntimeAuthorization / EffectClass 判断；执行后有 ResultContract / Action / effects 记录。
- 测试必须覆盖真实 pending/resume 和至少一个 isolated E2E，不再用“手工写 Redis pending”冒充产品链路。
- enterprise PCBA agent E2E 必须能在 per-worktree isolated stack 中运行，不能依赖 host `reset-and-init.sh` 或 canonical 共享数据库。

## 任务总表

| ID | 优先级 | 状态 | 任务 | 验收 |
|---|---|---|---|---|
| A1 | P0 | DONE | 文档化事故、根因、目标校准、legacy runtime 取舍 | `docs/agent/specs/2026-05-10-agent-runtime-target-and-remediation.md` 已落地 |
| A2 | P0 | DONE | 删除 provider-wide `executeTool` fallback 和 chat-side 独立执行 | `ToolDiscoveryPort` / `ToolExecutionPort` 不再暴露 generic fallback；`ChatToolExecutor` 只委托 canonical runtime |
| A3 | P0 | DONE | named-agent fake `Tool executed:` stub 清除 | `AgentRuntimeArchitectureTest` 阻止重新引入 deterministic fake result |
| A4 | P0 | DONE | AuraBot skill preview/confirm 统一进入 `ToolLoopService` | `confirmAuraBotSkill` 和相关集成测试通过 |
| A5 | P1 | DONE | RuntimeAuthorization / EffectClass 接入 `ToolLoopService` | read/write/skill/provider 的 reject、approval、effects 测试通过 |
| A6 | P1 | DONE | Provider-backed / skill 执行写 ResultContract + Action | `ToolLoopServiceSafetyTest` 和 C-2 skill gates 通过 |
| A7 | P1 | DONE | isolated API E2E 改为真实 chat stream pending -> `/execute` resume | `aurabot-skill-resume-runtime.spec.ts` 已跑通 `1 passed` |
| A8 | P1 | DONE | permission drift / docs drift / architecture guard | permission drift 0；enterprise docs drift 0；架构测试通过 |
| A9 | P1 | DONE | enterprise PCBA agent write E2E isolated 可运行 | isolated stack 中 `pcba-procurement-agent-write.spec.ts` 已跑通 `3 passed` |
| A10 | P1 | DONE | 复跑最终 gate 并更新本文档状态 | backend target、architecture guard、frontend unit、isolated API、PCBA UI E2E、permission/docs/diff gate 已记录 |
| A11 | P2 | DONE | Replay API / viewer + conversation/result 深链 | `/api/admin/agent-runs` + `/admin/agent-runs` 已验证 run/action/interrupt/child-run/BIF；conversation turn 全量回放与 result-contract 深链已完成 |
| A12 | P2 | DONE | Memory / Learning Loop / SkillDraft / Shadow Promotion | 现有实现、文档、backend target、frontend unit、real E2E 已验证 |

## B 系列：PCBA isolated E2E 阻塞修复

这些任务不再归类为 agent runtime P0/P1 收敛问题。它们属于 enterprise 插件导入质量、isolated stack 可测性与演示业务依赖治理。最终处理原则仍然是修源头，不能通过关闭校验、宽松 fallback 或隐藏测试缺口绕过。

| ID | 优先级 | 状态 | 任务 | 验收 |
|---|---|---|---|---|
| B1 | P1 | DONE | 修复 `inventory` 插件命令与 `pe:*` handler 的归属关系 | `pcba-agent` profile 导入路径已被 PCBA target E2E 前置流程验证 |
| B2 | P1 | DONE | 修复 `finance` 插件命令与 `fin:*` handler 的归属关系 | `pcba-agent` profile 导入路径已被 PCBA target E2E 前置流程验证 |
| B3 | P1 | DONE | 修复 `sales` / `procurement` / `quality` 导入依赖顺序与 manifest 依赖 | 三个插件在 isolated stack 中随 PCBA target E2E 导入通过 |
| B4 | P1 | DONE | 修复 `pcba-base` 对 `inv_lot_policy` 字典的隐藏依赖 | PCBA target E2E 前置导入未再出现 missing dictionary |
| B5 | P1 | DONE | 修复 `pcba-procurement` 对 `pe_quotation_status` 字典和采购/库存/财务依赖的隐藏依赖 | PCBA target E2E 前置导入未再出现 missing dictionary / missing dependency |
| B6 | P1 | DONE | 修复 `pcba-solution` 角色、菜单权限依赖 | PCBA target E2E 前置导入未再出现 missing permission / missing dependency |
| B7 | P1 | DONE | 将 `scripts/dev/import-isolated-plugins.sh --profile=pcba-agent` 纳入目标验证路径 | isolated target E2E 通过该路径完成依赖导入 |
| B8 | P1 | DONE | 复跑 `pcba-procurement-agent-write.spec.ts` | `--project=critical` 下 `3 passed` |
| B9 | P2 | DONE | 消除 stale isolated volume 的 schema drift 手工修复需求 | `ab_agent_approval.approver_id` 这类字段类型漂移已由插件 re-import schema sync 自动收敛，无需手工 `ALTER TABLE` |

## C 系列：剩余任务总清单与执行顺序

这一节用于把“后续建议”转成完整任务列表。执行原则：先解决会反复影响验证闭环的工程问题，再拆产品化能力；不把长期 Agent 能力混进本轮 runtime 修复。

| ID | 优先级 | 状态 | 任务 | 验收 |
|---|---|---|---|---|
| C1 | P2 | DONE | stale schema drift 自动收敛 | 插件 re-import 更新字段 dataType 后，会同步已绑定的 published model 物理表；`approver_id integer -> long` 不再需要手工 `ALTER TABLE` |
| C2 | P2 | DONE | Playwright global teardown 移除 frontend 容器 `psql` 依赖 | isolated frontend 容器内 teardown 使用 Node `pg` client 清理测试数据，不再输出 `/bin/sh: 1: psql: not found` |
| C3 | P2 | DONE | 为 C1/C2 补目标测试 | importer update path 单测覆盖 schema sync；global teardown 通过 TypeScript 编译；grep 确认不再 shell 调 `psql` |
| C4 | P2 | DONE | 复跑轻量 gate | `PluginResourceImporterImplApplyTest2`、frontend typecheck、permission drift、docs drift、diff check 通过 |
| C5 | P2 | DONE | `BIF -> Skill -> Action -> Tool` replay API 验证 | `AgentRunControllerIntegrationTest` 覆盖 list/detail/404/tenant/duration/filter；当前 MVP 覆盖 run/action/interrupt/child/BIF |
| C6 | P2 | DONE | replay viewer UI 验证 | vitest 覆盖 replay components；`admin-agent-runs.spec.ts` 真实后端 E2E `5 passed` |
| C7 | P3 | DONE | Memory / Learning Loop / SkillDraft / Shadow Promotion 验证 | backend target `BUILD SUCCESSFUL`；shadow viewer unit `5 passed`；shadow/learning/memory real E2E `9 passed` |
| C8 | P2 | DONE | Replay 与 AI Trace 调查链路互跳 | run detail 返回 tenant-scoped `traceId`；Replay drawer 可打开 Trace；Trace detail 可回到相关 Run；backend/frontend/E2E 已验证 |
| C9 | P2 | DONE | Conversation turn 全量回放 + result-contract 深链 | run detail 聚合 `ab_agent_run -> ab_agent_task.input_data -> ab_im_message`，action row 可打开对应 `ResultContractView`；backend/frontend/E2E 已验证 |
| C10 | P1 | DONE | main-sync fresh isolated bootstrap/admin gate 修复 | `BootstrapStartupRunner` 发布 wizard 等价 status contract；PG env alias、setup wait、默认 admin 账号契约已修复；`admin-agent-runs.spec.ts` fresh isolated target `23 passed / 1 skipped` |

### C 系列执行记录

- C1 修复点：`PluginResourceImporterImpl.updateFieldForReimport` 在字段 metadata 更新后，调用 `MetaModelFieldBindingMapper.findPublishedModelCodesByFieldId` 找到已绑定的 published model，并执行 `SchemaManagementService.updateTableByModel`。`SchemaManagementServiceImpl.buildSyncDdls` 已具备物理列类型 diff 能力，因此 `integer -> bigint` 会生成 `ALTER COLUMN TYPE`。
- C1 回归测试：`PluginResourceImporterImplApplyTest2.importField_update_syncsPublishedBoundModels` 覆盖成功同步；`importField_update_schemaSyncFailureThrows` 覆盖 fail-closed。
- C2 修复点：`web-admin/tests/global-teardown.ts` 删除 `child_process.execSync("psql ...")`，改用依赖内已有的 Node `pg` client，并同时识别 `PGHOST` / `PG_HOST`、`PGPORT` / `PG_PORT`、`PGDATABASE` / `PG_DB`。
- C5/C6 复查结论：replay API/UI MVP 不是空白计划，现有 `AgentRunController`、`agentRunsApi.ts`、`agent-runs.tsx`、`AgentRunDetailDrawer` 已覆盖 run/action/interrupt/child-run/BIF。补修 `admin-agent-runs.spec.ts`，去掉 frontend 容器 `psql` 依赖，并动态解析/创建 `AI 中心` 父菜单。
- C7 复查结论：Memory/Learning/SkillDraft/Shadow Promotion 已有产品实现与文档。补修 `_real-backend-helpers.ts`，公共 real-backend E2E helper 不再依赖 shell `psql`，并兼容 Node `pg` boolean 输出。
- C8 修复点：`AgentRunController.detail` 通过 run metadata `traceId` 或 `ab_ai_trace.session_id = runId` 解析 trace，所有查询均限定当前 tenant；`AiTraceController.getTrace` 改为 tenant-scoped detail + spans，跨租户 trace 返回 404；前端 Replay drawer 增加 `Open Trace`，Trace detail 只在 metadata/session 表明来自 agent run 时展示 `Open Run`。
- C9 修复点：`AgentRunController.detail` 增加 `conversationTurn` 与 `resultContracts` 只读 projection；只有存在 `turnId`、`conversationId` 或 `inboundMessageId` 时才返回 turn 对象，避免普通 run 出现空回放对象；前端 Replay drawer 增加 Conversation / Results tab 与 action-to-contract 深链。
- C10 修复点：main-sync fresh isolated E2E 暴露出 bootstrap runner 修完不变量后没有写 `system.initialized` / `system.setup_at`，setup spec 因 status false 进入 wizard fallback；同时 Playwright DB helper 不识别 libpq `PGHOST` / `PGPORT` / `PGUSER` / `PGDATABASE`，默认 admin 账号仍有旧 `example.com` 账号残留。详见 `docs/superpowers/plans/2026-05-11-agent-runtime-main-sync-closeout.md`。
- C4 验证命令：
  - `./gradlew :test --tests com.auraboot.framework.plugin.service.impl.PluginResourceImporterImplApplyTest2 -x jacocoTestReport` -> `BUILD SUCCESSFUL`
  - `pnpm --dir web-admin exec tsc --noEmit --pretty false` -> exit 0
  - isolated `tests/auth.setup.ts --project=auth --no-deps` -> `4 passed`，global teardown 输出 `✅ Global teardown complete`，不再出现 `psql not found`
  - `node scripts/validate-permission-codes.mjs --oss-only` -> `total drift: 0`
  - enterprise `./scripts/check-docs-drift.sh` -> `0 violations`
  - OSS / enterprise `git diff --check` -> exit 0
  - `./gradlew :test --tests com.auraboot.framework.integration.agent.AgentRunControllerIntegrationTest -x jacocoTestReport` -> `BUILD SUCCESSFUL`
  - `pnpm --dir web-admin exec vitest run app/plugins/core-aurabot/__tests__/AgentRunsPage.test.tsx app/plugins/core-aurabot/__tests__/AgentRunDetailDrawerLiveStream.test.tsx app/plugins/core-aurabot/__tests__/ChildRunTree.test.tsx` -> `3 files / 11 tests passed`
  - isolated `admin-agent-runs.spec.ts --project=chromium --no-deps` -> `5 passed`
  - Memory/Learning backend target tests -> `BUILD SUCCESSFUL`
  - shadow viewer vitest -> `2 files / 5 tests passed`
  - isolated `admin-shadow-runs.spec.ts --project=chromium --no-deps` -> `2 passed`
  - isolated `ai-learning-drafts-real.spec.ts` + `ai-memory-promotions-real.spec.ts` -> `7 passed`

## PCBA 修复与根因记录

早期阻塞命令：

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-runtime-unification-oss
scripts/dev/import-isolated-plugins.sh --slug=agent-runtime-e2e --profile=pcba-agent
```

当时 isolated 栈已健康启动，OSS 基础插件与 `product-catalog` / `crm` 可以导入，但 enterprise PCBA 依赖链在插件校验阶段失败。主要根因包括：

- 插件配置与 handler 归属倒挂：`inventory` / `finance` 的 command 配置引用了 `pcba-warehouse` / `pcba-finance` backend 提供的 handler。
- manifest 依赖未表达完整：`sales` / `procurement` / `quality` / `pcba-solution` 对库存、财务、采购、质量插件的依赖没有在 isolated profile 中形成可导入闭环。
- 字典依赖缺口：`pcba-base` 和 `pcba-procurement` 引用了 `inv_lot_policy`、`pe_quotation_status` 等未先导入字典。

本轮处理结果：

- isolated stack 已支持显式挂载 enterprise 插件目录到 `/app/plugins-enterprise`。
- `scripts/dev/import-isolated-plugins.sh --profile=pcba-agent` 已成为 PCBA target E2E 的前置导入路径。
- PCBA target E2E 现在能从 sidebar 进入产品页面，完成 AuraBot 创建比价草稿、批准、拒绝三条 UI 流程。
- `plugins/agent-control-plane/config/fields.json` 中 `approver_id` 已从 `integer` 修正为 `long`，避免 Snowflake 用户 ID 写入 `ab_agent_approval` 时 `integer out of range`。

遗留说明：

- 当前 isolated DB 复用过旧 volume，早期验证时曾对已有 `ab_agent_approval.approver_id` 执行过一次手工 `ALTER TABLE ... TYPE BIGINT`，用于把历史表结构校正到新配置。后续已补 C1/B9：插件 re-import 更新字段 metadata 后会同步已绑定的 published model 物理表，避免再次依赖手工 SQL。
- Playwright teardown 与 aurabot real-backend E2E helper 都出现过 `/bin/sh: 1: psql: not found`，原因是 frontend 容器缺少 `psql` 客户端。现已分别改用 Node `pg`，目标 E2E 已复跑通过。

## 当前执行顺序

1. **已完成 P0/P1 runtime 收敛**
   不再保留原则意义上的 legacy runtime；旧入口只允许作为 adapter。

2. **已收口 enterprise PCBA isolated E2E 可测性**
   已修改 isolated stack：`ENTERPRISE_PLUGINS_DIR` 可显式挂载到 `/app/plugins-enterprise`。
   已新增导入脚本：`scripts/dev/import-isolated-plugins.sh --profile=pcba-agent`。
   已修改目标 spec：`pcba-procurement-agent-write.spec.ts` 在 isolated 路径下导入依赖插件，并用产品 API 动态 seed 产品、供应商、供应商报价，不再依赖固定 demo PID。
   当前 target E2E 已通过；B9 stale schema drift 自动收敛也已补齐。

3. **验证顺序**
   先跑轻量静态/目标测试，再启动 isolated stack 跑目标 E2E；若目标 E2E 仍失败，按层归因到插件导入、权限、seed、UI、LLM stub 或 runtime。

## 验证命令

后端目标 gate：

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-runtime-unification-oss/platform
./gradlew :test \
  --tests StubLlmProviderTest \
  --tests AgentRuntimeArchitectureTest \
  --tests ToolLoopServiceSafetyTest \
  --tests AgentChatPortImplToolLoopTest \
  --tests ChatToolExecutorCanonicalRuntimeTest \
  --tests AuraBotSkillPermissionContractTest \
  -x jacocoTestReport
```

isolated PCBA target：

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-runtime-unification-oss
AGENT_LLM_STUB_MODE=true scripts/dev/start-isolated.sh --slug=agent-runtime-e2e --rebuild
scripts/dev/import-isolated-plugins.sh --slug=agent-runtime-e2e --profile=pcba-agent
docker exec auraboot-agent-runtime-e2e-frontend bash -lc '
  cd /repo/web-admin &&
  BACKEND_URL=http://backend:6443 BE_PORT=6443 \
  PLAYWRIGHT_BASE_URL=http://isolated-frontend:5173 \
  BFF_URL=http://127.0.0.1:3500 \
  PW_SKIP_WEBSERVER=1 PW_PROFILE=critical PW_WORKERS=1 \
  OSS_PLUGIN_ROOT=/app/plugins ENTERPRISE_PLUGIN_ROOT=/app/plugins-enterprise \
  pnpm exec playwright test -c playwright.noweb.config.ts \
    tests/e2e/aurabot/pcba-procurement-agent-write.spec.ts \
    --project=critical --reporter=line --no-deps
'
scripts/dev/stop-isolated.sh --slug=agent-runtime-e2e
```

最终通用 gate：

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-runtime-unification-oss
node scripts/validate-permission-codes.mjs --oss-only
git diff --check

cd /Users/ghj/work/auraboot/.worktrees/agent-runtime-unification-enterprise
./scripts/check-docs-drift.sh
git diff --check
```

## 最新验证结果

本节记录 2026-05-10 本轮收口时已经 fresh rerun 的结果。

| 层级 | 命令 | 结果 |
|---|---|---|
| 后端单元/集成目标 gate | `./gradlew :test --tests com.auraboot.framework.aurabot.service.ChatToolExecutorCanonicalRuntimeTest --tests com.auraboot.framework.agent.provider.StubLlmProviderTest --tests com.auraboot.framework.agent.service.AgentChatPortImplToolLoopTest -x jacocoTestReport` | `BUILD SUCCESSFUL` |
| 架构防回退 gate | `./gradlew :test --tests com.auraboot.framework.architecture.AgentRuntimeArchitectureTest -x jacocoTestReport` | `BUILD SUCCESSFUL` |
| 前端组件单测 | `pnpm --dir web-admin exec vitest run app/plugins/core-aurabot/components-internal/__tests__/ChatBiResultCard.test.tsx app/plugins/core-aurabot/components-internal/__tests__/ResultContractView.test.tsx` | `2 files / 12 tests passed` |
| isolated auth setup | `docker exec ... playwright test ... tests/auth.setup.ts --project=auth --no-deps` | `4 passed` |
| isolated API E2E | `PW_PROFILE=full ... playwright test ... tests/api/agent/aurabot-skill-resume-runtime.spec.ts --project=api --no-deps` | `1 passed` |
| isolated PCBA UI E2E | `PW_PROFILE=critical ... playwright test ... tests/e2e/aurabot/pcba-procurement-agent-write.spec.ts --project=critical --no-deps` | `3 passed` |
| DB 字段校验 | `SELECT data_type FROM information_schema.columns WHERE table_name='ab_agent_approval' AND column_name='approver_id'` | `bigint` |
| Replay/Trace backend integration | `./gradlew :test --tests AgentRunControllerIntegrationTest --tests AiTraceControllerIntegrationTest -x jacocoTestReport` | `BUILD SUCCESSFUL`，14 tests passed |
| Replay/Trace frontend unit | `pnpm --dir web-admin exec vitest run app/plugins/core-aurabot/__tests__/AgentRunDetailDrawerLiveStream.test.tsx app/plugins/core-aurabot/__tests__/TraceDetailPage.test.tsx` | `2 files / 7 tests passed` |
| Replay/Trace E2E | `PLAYWRIGHT_BASE_URL=http://localhost:15174 ... admin-agent-runs.spec.ts --project=chromium --no-deps` | `5 passed` |
| 前端类型检查 | `pnpm --dir web-admin typecheck` | exit 0 |
| 权限漂移 | `node scripts/validate-permission-codes.mjs --oss-only` | `total drift: 0` |
| enterprise 文档漂移 | `./scripts/check-docs-drift.sh` | `drift-audit passed (0 violations)` |
| diff whitespace gate | `git diff --check` in OSS and enterprise worktrees | exit 0 |

E2E 真实性结论：

- `aurabot-skill-resume-runtime.spec.ts` 是 API E2E，验证真实 `chat/stream -> pending -> /execute -> persisted model` 链路，不代表 UI E2E。
- `pcba-procurement-agent-write.spec.ts` 是 UI 目标测试，本轮在 isolated stack 下 `3 passed`。
- 对 `pcba-procurement-agent-write.spec.ts` 的真实性自审结果：无 `test.skip` / `test.fixme`；无 threshold / baseline / retry 放宽；无直接 `page.goto('/p/...')` 绕侧边栏；无 `waitForTimeout`。存在 setup API、fixture API 与较长 timeout，属于 isolated 企业插件测试的准备边界和执行耗时债务，已记录，不把它包装成“全 UI 零 API”的覆盖。

## 阻塞处理规则

- 插件导入失败：修插件资源顺序或配置，不手工 SQL patch。
- seed 失败：修测试 seed 走产品 Command API，不写 host DB。
- PCBA E2E 失败：先判断是否 isolated 环境缺依赖，再判断产品行为。
- 真实 LLM 不稳定：使用 stub marker 只做 deterministic E2E，不进入生产 fallback。
- 任何“为了兼容”重新增加独立工具执行路径的方案都拒绝。
