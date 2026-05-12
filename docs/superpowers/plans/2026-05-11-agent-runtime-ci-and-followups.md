# Agent Runtime CI And Followups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 agent-runtime fresh isolated target gate 产品化到 CI，并把 OSS full gate、conversation replay、result-contract UI 的后续工作整理成可执行任务。

**Architecture:** CI 只阻断 canonical agent-runtime target phases，不默认运行 OSS full。OSS full 作为单独长跑窗口产出失败分类；后续产品和测试扩展继续围绕 `ConversationTurnService`、`ToolLoopService`、result-contract 深链推进，不恢复 legacy runtime/provider-wide fallback/chat-side 私有执行链。

**Tech Stack:** GitHub Actions, Docker Compose isolated stack, Playwright Docker image cache, AuraBoot OSS `scripts/dev/run-agent-runtime-full-gate-docker.sh`.

---

## Task List

### Task 1: 主线同步与旧 worktree 清理

**Files:**
- No source changes.

- [x] 确认 canonical `/Users/ghj/work/auraboot/auraboot` 在 `main`。
- [x] 执行 `git fetch origin main --prune` 与 `git pull --ff-only origin main`。
- [x] 清理 `/Users/ghj/work/auraboot/.worktrees/agent-runtime-post-merge-hardening`。
- [x] 保留历史分支引用，不删除 `codex/agent-runtime-post-merge-hardening`。
- [x] 跑 merged main 轻量 smoke。

**Evidence:**
- `git pull --ff-only origin main`: `Already up to date.`
- canonical 状态仍为 `main...origin/main [ahead 8]`。
- smoke 命令：`scripts/dev/run-agent-runtime-full-gate-docker.sh --slug=agent-runtime-main-smoke`
- smoke 结果：`docker pull mcr.microsoft.com/playwright:v1.59.1-noble` 超过 900 秒，退出 `124`；测试 phase 未启动，归类为环境/镜像拉取失败。

### Task 2: 产品化 agent-runtime target CI gate

**Files:**
- Create: `.github/workflows/agent-runtime-gate.yml`

- [x] 新增独立 GitHub Actions workflow。
- [x] 触发范围覆盖 `platform/**`、`web-admin/**`、`plugins/**`、`packages/**`、`scripts/dev/**`、`docker-compose*.yml`、workspace lock/config 文件和 workflow 自身。
- [x] job 只运行 `scripts/dev/run-agent-runtime-full-gate-docker.sh --slug=ci-agent-runtime --rebuild`。
- [x] 不传 `--include-oss-full`，保持 OSS full 不进入默认阻断 gate。
- [x] 设置 `AGENT_RUNTIME_GATE_LOG_DIR` 并在失败/成功后上传日志 artifact。
- [x] always 调用 `scripts/dev/stop-isolated.sh --slug=ci-agent-runtime --purge` 清理 isolated stack。

### Task 3: 缓解 Playwright base image 拉取慢

**Files:**
- Modify: `.github/workflows/agent-runtime-gate.yml`

- [x] 定义 `PLAYWRIGHT_IMAGE=mcr.microsoft.com/playwright:v1.59.1-noble`。
- [x] 使用 `actions/cache/restore@v4` 恢复 `docker save` 后的压缩镜像 tar。
- [x] cache hit 时通过 `zstd -dc "$PLAYWRIGHT_IMAGE_CACHE_FILE" | docker load` 预热 runner。
- [x] cache miss 时 `docker pull` 后执行 `docker save "$PLAYWRIGHT_IMAGE" | zstd -T0 -10 -o "$PLAYWRIGHT_IMAGE_CACHE_FILE"`。
- [x] 使用 `actions/cache/save@v4` 在 target gate 前保存镜像缓存；`continue-on-error: true` 防止并发 cache key 已存在导致误阻断。
- [ ] 后续如 GitHub cache 仍不稳定，再评估 GHCR mirror 或专用预热 job。

### Task 4: CI workflow 验证

**Files:**
- Verify: `.github/workflows/agent-runtime-gate.yml`

- [x] 运行 YAML 解析。
- [x] 文本检查确认 workflow 未包含 `--include-oss-full`。
- [x] 确认本机 `zstd` 可用。
- [ ] 在 CI 或装有 actionlint 的环境运行 actionlint。

**Evidence:**
- `ruby -e 'require "yaml"; YAML.load_file(".github/workflows/agent-runtime-gate.yml"); puts "yaml ok"'`: `yaml ok`
- `! rg -q -- "--include-oss-full" .github/workflows/agent-runtime-gate.yml`: `oss full not enabled`
- `command -v zstd`: `/usr/local/bin/zstd`
- `npx --yes actionlint --version`: 不可用，`npm error could not determine executable to run`

### Task 5: OSS full gate 基线长跑窗口

**Files:**
- Future update: `OSS_E2E_TASKS.md`
- Future logs: chosen run log directory under `/tmp` or CI artifact.

- [ ] 单独开长跑窗口运行 `scripts/dev/run-agent-runtime-full-gate-docker.sh --slug=<slug> --include-oss-full`。
- [ ] 跑前确认 Docker image cache 或 mirror 已可用，避免再次把镜像拉取慢误判为产品失败。
- [ ] 按 `docs/agent-rules/oss-e2e-and-playwright.md` 更新 `OSS_E2E_TASKS.md`。
- [ ] 对失败分类：环境、fixture、真实产品缺口、flaky。
- [ ] 建立 `blocking`、`non-blocking`、`backlog` 三类清单。

### Task 6: Conversation turn replay 覆盖扩展

**Files:**
- Future tests under `web-admin/tests/api/agent/` or existing agent runtime E2E suites.
- Future backend tests under `platform/src/test/java/com/auraboot/framework/integration/agent/` when behavior belongs to backend contract.

- [ ] 增加同一 conversation 多 turn replay。
- [ ] 覆盖 inbound/outbound anchor 缺失或错位。
- [ ] 覆盖 seq range 超过 50 条的截断行为。
- [ ] 覆盖 cross-tenant / cross-conversation 保护。
- [ ] 覆盖 `tool_call` / `tool_result` / `approval` / result-contract 的完整链路回放。
- [ ] 所有新路径必须经 `ConversationTurnService.runTurn` / `resumeTurn` 与 `ToolLoopService`，禁止新增 legacy runtime 或 chat-side 私有执行链。

### Task 7: Result-contract UI 后续

**Files:**
- Future UI source under `web-admin/` true source files only.
- Future E2E under `web-admin/tests/e2e/aurabot/` or adjacent suite.

- [ ] 增加 `failed` / `partial_success` 排障路径。
- [ ] 支持按 `action` / `status` / `outputType` 过滤。
- [ ] 增加“从 contract 继续操作”的产品入口。
- [ ] 继续操作必须走 canonical runtime，不加兼容旧路径。
- [ ] E2E 覆盖 loading、data、empty、error、operation feedback。

## Non-Goals

- [ ] 不做 legacy runtime。
- [ ] 不恢复 provider-wide fallback。
- [ ] 不加 chat-side 私有执行链。
- [ ] 不把 OSS full gate 放进默认阻断 CI job。
