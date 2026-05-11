# Agent Runtime Main Sync Closeout

日期：2026-05-11
Workspace：`/Users/ghj/work/auraboot/.worktrees/agent-runtime-main-sync2`
Branch：`codex/agent-runtime-main-sync2`

## 背景

本轮把 `codex/agent-runtime-unification` 合并到最新 `origin/main` 后，按合并前 gate 重新跑 fresh isolated E2E。目标是验证 agent runtime 的 replay / result-contract / conversation turn 深链，但 E2E 在进入 runtime 页面前先失败在 bootstrap/admin 基础链路：

- `00-bootstrap` 断言找不到默认管理员。
- 后续 multi-role/setup 链路出现 `403`，缺少 `org.role.update`。
- `/api/bootstrap/status` 在启动修复已经跑完后仍返回未初始化。

这说明问题不在 AuraBot runtime 本体，而在启动自动 bootstrap、测试环境指向、默认账号契约之间存在交叉缺口。该缺口会让真实 E2E 在“看似已经初始化”的环境里继续走 wizard fallback，从而制造重复或不完整的角色/权限状态。

## 根因

1. `BootstrapStartupRunner` 只调用 `BootstrapRepairService.repairAll()` 修复 9 个基础不变量，但没有写回 wizard 同等的系统状态契约：`system.initialized=true` 与 `system.setup_at`。
2. Playwright 环境 helper 只识别 Aura 私有变量 `PG_HOST` / `PG_PORT` / `PG_USER` / `PG_DB`，不识别 libpq 常用变量 `PGHOST` / `PGPORT` / `PGUSER` / `PGDATABASE`。当运行命令只传 libpq 变量时，测试中的 DB 断言可能落到 host DB，而不是 isolated DB。
3. 默认管理员账号契约已经收敛为 `admin@auraboot.com`，但脚本、文档、测试和 seed 中仍残留旧 `example.com` 默认账号，导致 fresh stack 与测试账号不一致。
4. `00-bootstrap.spec.ts` 在 status 暂时未初始化时立即调用 `/api/bootstrap/setup`，没有等待启动自动 bootstrap 完成，放大了 status-contract 缺口。

## 修复

- `BootstrapStartupRunner` 在非致命修复结束后调用 `finalizeStartupBootstrap()`：
  - 写入 `system.initialized=true`。
  - 仅当 `system.setup_at` 缺失时写入 timestamp，避免每次重启覆盖原始初始化时间。
- `BootstrapStartupRunnerIT` 增加断言：
  - fresh DB 启动后 `systemConfigService.isInitialized()` 为 `true`。
  - `system.setup_at` 存在。
  - 重复执行 runner 不重复创建 admin / role，且不覆盖原始 `setup_at`。
- `web-admin/tests/helpers/environments.ts` 增加 libpq alias 支持，并保持 Aura 私有变量优先。
- `web-admin/app/__tests__/test-environments.test.ts` 覆盖 alias fallback 与 Aura override。
- `web-admin/tests/api/setup/00-bootstrap.spec.ts` 在 wizard fallback 前轮询 `/api/bootstrap/status`，避免与自动 bootstrap 竞争。
- 默认管理员账号统一为 `admin@auraboot.com`，同步脚本、测试和文档残留。

## 覆盖缺口为什么之前没发现

- 之前的 bootstrap 测试重点覆盖 repair invariants，没有把“启动自动修复后 status API 必须与 wizard 完成后的契约一致”作为验收点。
- 之前的 E2E setup 可以在 status false 时直接走 wizard fallback，因此会掩盖自动 runner 没有 finalize status 的缺陷。
- DB helper 的 env alias 不完整，使部分 isolated 验证命令即使看起来传了 `PGHOST` / `PGPORT`，断言仍可能读错数据库。
- 大量测试使用固定已初始化环境或 storage state，没有覆盖 fresh isolated stack 的“启动修复正在收尾时访问 status/setup”的竞争窗口。

## 验证记录

- `./gradlew :integrationTest --tests com.auraboot.framework.saas.bootstrap.BootstrapStartupRunnerIT -x jacocoTestReport` -> `BUILD SUCCESSFUL`。
- `pnpm --dir web-admin exec vitest run app/__tests__/test-environments.test.ts` -> `2 tests passed`。
- `pnpm --dir web-admin typecheck` -> exit 0。
- `node scripts/validate-permission-codes.mjs --oss-only` -> drift 0。
- agent runtime backend target gate -> `BUILD SUCCESSFUL`。
- agent runtime frontend vitest target -> `4 files / 14 tests passed`。
- fresh isolated `admin-agent-runs.spec.ts` target E2E -> `24 tests`，`23 passed`，`1 skipped`。
- E2E truth audit for `admin-agent-runs.spec.ts`：executable request=1，click/fill/select=12，`test.only/skip/fixme/retries=0`，`waitForTimeout=0`，direct `/p/` goto=0，PUT/PATCH/DELETE=0；3 个 `toBeGreaterThanOrEqual(1)` 是详情子列表存在性断言。

## E2E 环境说明

`scripts/dev/start-isolated.sh --slug=agent-sync2-e2e` 在本机遇到两个环境问题：

- fresh isolated frontend image `mcr.microsoft.com/playwright:v1.59.1-noble` 下载很慢，属于镜像缓存/网络问题。
- verbose foreground compose 日志会导致本地 Codex 会话收到 SIGINT，backend/redis 随 compose 退出；改为 `docker compose build --quiet backend` + detached `up -d --no-build postgres redis backend` 后 stack 稳定，目标 E2E 通过。

这些是本地验证基础设施问题，不改变产品代码修复结论；后续应单独把镜像预拉取、detached stack 保活和日志采集做成 CI/开发机 gate。

## 产品级验收清单

- [x] Fresh isolated stack 首次启动后，后端自动 bootstrap 能把系统推进到 initialized 状态。
- [x] `/api/bootstrap/status` 与 wizard bootstrap 完成后的契约一致，前端/测试不会误判未初始化。
- [x] 默认管理员账号、脚本、测试和文档使用同一账号契约：`admin@auraboot.com`。
- [x] E2E DB 断言能正确指向 isolated Postgres，不会因为只传 libpq env alias 而回落到 host DB。
- [x] Agent Runs 页面真实后端 E2E 能经过 setup/auth 后进入 replay/result-contract 路径。
- [x] 本轮修复没有增加 legacy runtime、generic fallback 或第二套 agent 执行语义。

## 清理状态

- [x] `auraboot-agent-sync2-e2e` containers / volumes / network 已在 E2E 后清理。
- [x] 本轮生成的 Playwright `web-admin/test-results` 与 `web-admin/tests/storage/admin.json` 已清理。
- [ ] 旧 worktree `/Users/ghj/work/auraboot/.worktrees/agent-runtime-main-sync` 仍有未提交改动，不能自动删除。
- [ ] 旧 worktree `/Users/ghj/work/auraboot/.worktrees/agent-runtime-unification-oss` 仍有未提交改动，且包含 Discord invite 修改；不能自动删除或合并进本次 main-sync。

## 剩余后续任务

| ID | 优先级 | 状态 | 任务 | 验收 |
|---|---|---|---|---|
| S1 | P1 | DONE | 修复启动 bootstrap status contract | `BootstrapStartupRunnerIT` 覆盖 initialized/setup_at/idempotency |
| S2 | P1 | DONE | 修复 Playwright DB env alias 漏洞 | env helper vitest 覆盖 libpq alias 与 Aura override |
| S3 | P1 | DONE | 修复 setup E2E 与 auto-bootstrap 竞争 | `00-bootstrap.spec.ts` wizard fallback 前等待 status |
| S4 | P1 | DONE | 统一默认管理员账号契约 | seed、脚本、测试、文档统一 `admin@auraboot.com` |
| S5 | P2 | PENDING | isolated frontend image 预拉取/缓存 gate | fresh dev machine 不因 Playwright image 下载阻塞目标 E2E |
| S6 | P2 | PENDING | isolated compose 日志/attach 稳定性治理 | verbose foreground 不再因会话 SIGINT 中断；脚本能自动落到 quiet build + detached health polling |
| S7 | P2 | PENDING | 清理旧 `agent-runtime-main-sync` worktree/branch | 确认未承载新改动后删除 stale worktree 和 stale branch |

## Legacy 结论

本轮没有引入 legacy runtime，也没有保留第二套执行语义。历史入口只能作为 adapter，执行仍必须进入 `ConversationTurnService -> AgentChatPort / AgentRunService -> ToolLoopService`。这次 bootstrap/admin 修复属于 runtime gate 的基础环境契约，不改变 One Runtime 的目标。
