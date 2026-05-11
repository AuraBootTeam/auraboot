# Agent Runtime Post-Merge Hardening Plan

日期：2026-05-11
Workspace：`/Users/ghj/work/auraboot/.worktrees/agent-runtime-post-merge-hardening`
Branch：`codex/agent-runtime-post-merge-hardening`

## 背景

`ToolLoopService` 作为唯一工具执行控制面、`ConversationTurnService.runTurn/resumeTurn` 作为对话入口 chokepoint 的主线已经合并到 `main`。本轮不再做 runtime 双实现兼容，也不恢复 provider-wide fallback、chat-side 私有执行或 fake `Tool executed:` 结果。

当前剩余风险集中在四处：

1. 之前的 replay/result-contract 验证偏 targeted，fresh full gate 仍缺少一键、可重复、isolated 的执行入口。
2. conversation turn replay 已能回放 inbound/outbound，但没有覆盖一个 turn 内的中间消息 tape。
3. result-contract UI 仍偏底层渲染器，缺少面向排障/运营的摘要、状态、来源和选中态。
4. isolated stack 在 fresh 机器上会受 Playwright image 拉取、compose build 输出和健康等待缺失影响，CI/本地复跑不够稳定。

## 目标

- 保持 One Runtime：所有入口仍只能进入 `ConversationTurnService -> AgentChatPort / AgentRunService -> ToolLoopService`。
- 扩展 replay 语义：一个 conversation turn 的消息 tape 不能只展示 inbound/outbound，应能展示边界内的中间消息。
- 产品化 result-contract：Run drawer 中的 Results tab 要能快速回答“有几个 contract、状态如何、来自哪个 action、具体内容是什么”。
- 新增 fresh isolated gate：用同一脚本完成 clean stack、image pre-pull、health wait、auth/API/UI agent runtime gate，并预留 OSS full gate 开关。
- 验证闭环：后端集成测试、前端 vitest、E2E 静态真实性检查、脚本语法检查、必要的 targeted E2E 都要跑。

## 非目标

- 不增加 legacy runtime 兼容层。
- 不把真实 LLM 非确定性纳入 deterministic E2E。
- 不把全仓库所有 Playwright 失败都归到本轮 agent runtime 范围。
- 不修改 canonical `main` 工作区中的未提交 marketplace 改动。

## 任务列表

| ID | 优先级 | 状态 | 任务 | 验收 |
|---|---|---|---|---|
| H1 | P0 | DONE | 文档化后合并加固方案 | 本文档记录目标、非目标、任务和验证口径 |
| H2 | P0 | DONE | conversation turn replay 扩展为 seq range tape | 后端 detail 能返回 inbound/outbound 之间的中间消息；IT 覆盖 3 条消息 |
| H3 | P0 | DONE | E2E replay 覆盖扩展 | `admin-agent-runs.spec.ts` seed 中间消息并断言 UI message tape 展示完整 |
| H4 | P1 | DONE | result-contract 产品化 UI | Results tab 有状态摘要、可选 contract 列表、来源/Action/时间元信息；组件测试覆盖 |
| H5 | P1 | DONE | ResultContractView 增强协议元信息 | 展示 outputType/actionability/renderHint/row count，避免只看 JSON/body |
| H6 | P1 | DONE | isolated stack 稳定性脚本增强 | `start-isolated.sh` 支持 image pre-pull、quiet rebuild、health wait |
| H7 | P1 | DONE | fresh agent runtime full gate 脚本 | 新脚本能启动 fresh isolated stack 并串行跑 auth、API resume、admin-agent-runs UI；支持可选 OSS full regular/deep |
| H8 | P1 | DONE | CI/本地 gate 文档与 truth guard | 计划文档记录命令；脚本默认 single-worker、独立 output/log，避免假通过 |
| H9 | P0 | DONE | 验证与复审 | 后端 IT、架构守卫、前端 vitest、typecheck/语法、E2E truth 自审、fresh isolated agent-runtime target gate 已通过；未声称 OSS full 0 failed |

## 执行顺序

1. 先写测试：后端 IT 和 Playwright seed/assertion 先表达“turn tape 包含中间消息”。
2. 实现后端 replay seq range 查询，保持 tenant scope 和最大条数限制。
3. 增强 result-contract UI 与组件测试，不改 API 契约。
4. 增强 isolated stack 脚本并新增 agent runtime full gate wrapper。
5. 跑目标测试和静态真实性检查；若 isolated E2E 因环境不可用失败，按环境/产品分层记录，不包装成通过。

## 验证口径

必须运行：

- `./gradlew :test --tests com.auraboot.framework.integration.agent.AgentRunControllerIntegrationTest -x jacocoTestReport`
- `./gradlew :test --tests com.auraboot.framework.architecture.AgentRuntimeArchitectureTest -x jacocoTestReport`
- `pnpm --dir web-admin exec vitest run app/plugins/core-aurabot/__tests__/AgentRunDetailDrawerLiveStream.test.tsx app/plugins/core-aurabot/components-internal/__tests__/ResultContractView.test.tsx`
- `pnpm --dir web-admin exec tsc --noEmit --pretty false`
- `bash -n scripts/dev/start-isolated.sh scripts/dev/run-agent-runtime-full-gate-docker.sh`
- `node scripts/validate-permission-codes.mjs --oss-only`
- E2E truth 静态 grep：无 `test.only` / `test.skip` / `test.fixme` / `waitForTimeout` / `retries:N` / 写 API 兜底。

尽量运行：

- fresh isolated `scripts/dev/run-agent-runtime-full-gate-docker.sh --slug=agent-hardening-e2e --fresh`

若完整 isolated gate 因 Docker/image/network 超时不可完成，需要保留日志路径和明确归因，不能声称 fresh full 0 failed。

## 本轮验证记录

- `git diff --check` -> exit 0。
- `bash -n scripts/dev/start-isolated.sh scripts/dev/run-agent-runtime-full-gate-docker.sh` -> exit 0（initial + after image-pull timeout patch）。
- Final `bash -n scripts/dev/start-isolated.sh scripts/dev/run-agent-runtime-full-gate-docker.sh` -> exit 0。
- `./gradlew :test --tests com.auraboot.framework.integration.agent.AgentRunControllerIntegrationTest -x jacocoTestReport` -> `BUILD SUCCESSFUL`，17 tests passed。
- `./gradlew :test --tests com.auraboot.framework.architecture.AgentRuntimeArchitectureTest -x jacocoTestReport` -> `BUILD SUCCESSFUL`，6 tests passed；生产代码未恢复 generic fallback / legacy `ToolExecutionPort` / fake execution stub。
- Final combined backend verification: `./gradlew :test --tests com.auraboot.framework.integration.agent.AgentRunControllerIntegrationTest --tests com.auraboot.framework.architecture.AgentRuntimeArchitectureTest -x jacocoTestReport` -> `BUILD SUCCESSFUL`，23 tests passed。
- `pnpm install --no-frozen-lockfile` -> installed workspace dependencies for this new worktree; no lockfile change。
- `pnpm --dir web-admin exec vitest run app/plugins/core-aurabot/__tests__/AgentRunDetailDrawerLiveStream.test.tsx app/plugins/core-aurabot/components-internal/__tests__/ResultContractView.test.tsx` -> 2 files / 20 tests passed。
- `pnpm --dir web-admin exec tsc --noEmit --pretty false` -> exit 0。
- `node scripts/validate-permission-codes.mjs --oss-only` -> `total drift: 0; new: 0`。
- E2E truth static grep on `admin-agent-runs.spec.ts` / `aurabot-skill-resume-runtime.spec.ts` / `pcba-procurement-agent-write.spec.ts` -> no executable `test.only` / `test.skip` / `test.fixme` / `waitForTimeout` / `retries:N` / `page.request.put|patch|delete` hits。
- Final E2E truth self-check on target specs:
  - `admin-agent-runs.spec.ts`: click/fill=12, request=1, `only/skip/fixme/waitForTimeout/retries/thresholds/direct-/p-goto/put-patch-delete` = 0。
  - `aurabot-skill-resume-runtime.spec.ts`: API contract spec, request=3, `only/skip/fixme/waitForTimeout/retries/thresholds/put-patch-delete` = 0。
- `scripts/dev/start-isolated.sh --slug=agent-hardening-dry --dry-run --quiet-build --wait --skip-pull` -> argument parsing and plan generation pass（initial + after image-pull timeout patch）。
- `scripts/dev/run-agent-runtime-full-gate-docker.sh --help` -> usage renders。
- `scripts/dev/run-agent-runtime-full-gate-docker.sh --slug=agent-hardening-e2e --fresh` -> interrupted after several minutes in `docker pull mcr.microsoft.com/playwright:v1.59.1-noble`; no stack was started, no product E2E assertion ran。Cleanup: `scripts/dev/stop-isolated.sh --slug=agent-hardening-e2e --purge` -> no running stack remained。
- Fallback fresh stack: `ISOLATED_FRONTEND_IMAGE=auraboot-perf-frontend:latest AGENT_LLM_STUB_MODE=true scripts/dev/start-isolated.sh --slug=agent-hardening-host --skip-pull --wait --quiet-build --rebuild` -> backend/frontend healthy on isolated ports。
- Manual host-runner target gate against isolated ports:
  - `tests/auth.setup.ts --project=auth` -> 18 passed / 1 skipped。
  - `tests/api/agent/aurabot-skill-resume-runtime.spec.ts --project=api --no-deps` -> 1 passed。
  - `tests/e2e/aurabot/admin-agent-runs.spec.ts --project=chromium --no-deps --workers=1` -> 5 passed。
- Scripted fallback target gate: `scripts/dev/run-agent-runtime-full-gate-docker.sh --slug=agent-hardening-host --reuse-stack --host-runner --skip-pull` -> auth 18 passed / 1 skipped, api-resume 1 passed, admin-agent-runs 5 passed, then stopped stack。
- Final fresh isolated target gate after tightening E2E thresholds:
  `ISOLATED_FRONTEND_IMAGE=auraboot-perf-frontend:latest scripts/dev/run-agent-runtime-full-gate-docker.sh --slug=agent-hardening-verify --fresh --host-runner --skip-pull --rebuild --frontend-image=auraboot-perf-frontend:latest`
  -> backend rebuild + fresh isolated stack healthy; auth 18 passed / 1 skipped, api-resume 1 passed, admin-agent-runs 5 passed; stack stopped。
- Final Docker residue check: `docker ps --format '{{.Names}}' | rg 'agent-hardening' || true` -> no running `agent-hardening` stack。
- OSS full scope sizing only: `playwright.oss.config.ts --project=chromium --list` -> 1470 tests / 216 files; `--project=chromium-deep --list` -> 1662 listed tests / 228 files。未运行该全量 gate，未声明 OSS full 0 failed。

## Fresh Gate 阻塞结论

这次没有 OSS full `0 failed` 证据。阻塞点在 Playwright base image 下载，属于 CI/开发机基础设施层，不是 replay/result-contract 产品断言失败。新脚本已经把该问题显式前置为 `pre_pull_image`，后续 CI 可以通过 runner image cache、GHCR mirror 或预热 job 继续收敛。

补充修复：`start-isolated.sh` 的 pre-pull 现在支持 `AURA_IMAGE_PULL_TIMEOUT_SECONDS`，默认 900 秒，避免 CI runner 无限挂在单个 image pull 上。

本轮已通过 fresh isolated agent-runtime target gate。由于 OSS full scope 当前是千级测试，本轮只建立 `--include-oss-full` 执行入口并量化规模，不把未运行的 OSS full 包装成通过。
