# Agent Runtime 交付前收口任务列表

日期：2026-05-10

## 目标

把本轮 AuraBot / ACP agent runtime 事故修复从“功能已修”推进到“可交付、可 review、可合并”的状态。此列表不重新定义 runtime 目标；runtime 目标、根因和主任务状态以 `2026-05-10-agent-runtime-master-task-list.md` 为准。

## 非目标

- 不重新引入 legacy runtime、generic fallback 或第二套工具执行语义。
- 不把长期产品增强混入本轮事故修复。
- 不用 host 共享数据库或手工 SQL 证明 isolated E2E。

## D 系列任务

| ID | 状态 | 任务 | 验收 |
|---|---|---|---|
| D1 | DONE | 统一任务状态源 | `agent-runtime-*.md` 无未勾选步骤；completion/followups/master/replay 文档口径一致 |
| D2 | DONE | 静态防回退审计 | runtime 关键文件无 generic fallback / fake `Tool executed:` 回归；`SkillToolExecutor.dispatch/confirm` 只由 `ToolLoopService` 调用 |
| D3 | DONE | 后端目标验证 | runtime architecture、tool loop、chat adapter、Replay/Trace integration 目标测试通过 |
| D4 | DONE | 前端目标验证 | Replay/Trace 单测与 `pnpm typecheck` 通过 |
| D5 | DONE | E2E 真实性审计 | 本轮 E2E 覆盖声明已复核；API setup / timeout 边界已单独标注，不包装成纯 UI 覆盖 |
| D6 | DONE | 工作树卫生 | generated storage state 不进入变更；`git diff --check` 通过；canonical 仓库仍在 `main` |
| D7 | DONE | 交付摘要 | 本文记录已完成、验证证据、剩余边界和下一步合并选项 |

## 执行命令

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-runtime-unification-oss

rg -n "^- \[ \]" docs/superpowers/plans/2026-05-10-agent-runtime-*.md
rg -n "legacy|兼容|Tool executed|generic tool|executeTool fallback" \
  platform/src/main/java/com/auraboot/framework/agent \
  platform/src/main/java/com/auraboot/framework/aurabot/service
git diff --check

cd platform
./gradlew :test \
  --tests com.auraboot.framework.architecture.AgentRuntimeArchitectureTest \
  --tests com.auraboot.framework.agent.service.ToolLoopServiceSafetyTest \
  --tests com.auraboot.framework.agent.service.AgentChatPortImplToolLoopTest \
  --tests com.auraboot.framework.aurabot.service.ChatToolExecutorCanonicalRuntimeTest \
  --tests com.auraboot.framework.integration.agent.AgentRunControllerIntegrationTest \
  --tests com.auraboot.framework.integration.agent.AiTraceControllerIntegrationTest \
  -x jacocoTestReport

cd ..
pnpm --dir web-admin exec vitest run \
  app/plugins/core-aurabot/__tests__/AgentRunDetailDrawerLiveStream.test.tsx \
  app/plugins/core-aurabot/__tests__/TraceDetailPage.test.tsx
pnpm --dir web-admin typecheck
```

## 剩余边界

- `conversation turn 全量回放 + result-contract 深链` 已在本轮 post-replay closeout 中完成并验证；后续不再把它列为残留。
- 全量 vitest 中既有 `BlockRegistry.bootstrap.test.ts` 计数差异需要单独排期，不影响本轮目标验证。

## 执行结果

| 项 | 结果 |
|---|---|
| 任务勾选审计 | `rg -n "^- \[ \]" docs/superpowers/plans/2026-05-10-agent-runtime-*.md` 无命中 |
| 静态防回退 | 关键 runtime 文件无生产 `Tool executed:` fake stub；无 `executeTool` fallback；`SkillToolExecutor.dispatch/confirm` 仅在 `ToolLoopService` 调用；`ToolExecutionPort` 已删除并由架构测试防回退 |
| 空白检查 | `git diff --check` 通过 |
| 后端目标验证 | `./gradlew :test --tests ... -x jacocoTestReport` -> `BUILD SUCCESSFUL in 1m 6s` |
| 前端单测 | `pnpm --dir web-admin exec vitest run AgentRunDetailDrawerLiveStream.test.tsx AgentRunsPage.test.tsx TraceDetailPage.test.tsx ChatBiResultCard.test.tsx` -> `4 files / 14 tests passed` |
| 前端类型检查 | `pnpm --dir web-admin typecheck` -> exit 0 |
| E2E truth | `admin-agent-runs.spec.ts` 无写 API 兜底；`pcba-procurement-agent-write.spec.ts` 有 isolated setup/import API 与较长 timeout，已按企业插件 E2E 准备边界记录，并在 spec header 显式声明 setup API 不计入 UI 覆盖 |
| canonical 分支 | `/Users/ghj/work/auraboot/auraboot` 与 `/Users/ghj/work/auraboot/auraboot-enterprise` 均在 `main` |
| generated storage | `web-admin/tests/storage/operator.json` / `viewer.json` 未进入变更 |

## 2026-05-11 增量收口

- D2 runtime observability / audit 已补入 merge readiness：指标覆盖 discovery、execution、authorization、ResultContract、unsupported tool type。
- `/api/admin/agent-runs/audit` 已增加 action、authorization decision、approval、result-contract projection；approval 查询沿 `authorization_decision.approval_id` 关联，不依赖 approval 文案。
- Fresh verification:
  - backend observability/runtime/replay/trace/architecture target gate -> `BUILD SUCCESSFUL in 27s`。
  - permission drift -> `total drift: 0; new: 0`。
  - enterprise docs drift -> `0 violations`。
  - frontend typecheck -> exit 0。
  - replay/trace/result frontend vitest -> `4 files / 14 tests passed`。
  - `git diff --check` -> pass。
