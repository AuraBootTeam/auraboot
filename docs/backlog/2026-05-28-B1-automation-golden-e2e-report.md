# B1 方案 A — Automation 黄金 E2E (host stack 降级版)

**Date**: 2026-05-28
**Branch attempted**: `wt/b1-automation-golden-e2e` (NOT CREATED — env preflight failed)
**Worktree**: `/Users/ghj/work/auraboot/wt/sdk-g5-runtime` (HEAD `680ff334a`)
**Status**: BLOCKED — `environment-invalid / host-backend-not-running`
**Classification**: 按 AGENTS.md §2.1 E2E 执行节奏与环境守护红线,fail fast 不带着环境风险盲跑

## Preflight 实测(失败)

| 探测 | 命令 | 结果 |
|------|------|------|
| Backend health | `curl -fsS http://localhost:8080/actuator/health` | 502 (返回 curl exit 22) |
| Vite | `curl -fsS http://localhost:5173/ -o /dev/null -w '%{http_code}'` | 302 (vite 在跑) |
| BFF | `curl -fsS http://localhost:3000/` | 502 |
| 8080 listener | `lsof -iTCP -sTCP:LISTEN -P -n \| grep :8080` | 无 |

### 关键判定
- `node 38947 *:5173 (LISTEN)` 存在 — vite dev server 跑着,但 **没有任何 host backend 进程监听 8080 / 8888 / 6443 (host 模式默认端口)**
- vite 当前 502 因为它代理到不存在的 backend
- 现有 docker isolated stacks(只在容器端口暴露,无"host stack"):
  - `auraboot-bom-mvp-backend` → host:6521
  - `auraboot-mobile-e2e-backend` → host:18191
  - `auraboot-bugfix-daily-*` 等
- 没有任何 stack 把 backend 暴露到 8080(host 默认),即任务前提"host docker stack 不新起 isolated"不成立 —— **host stack 当前不存在**

### Worktree 计数 (红线 #11)
当前 active worktrees = 9:
```
auraboot (main), ida-bi-w1, ida-data-w1, agent-resilience-fixes, aurabot-security-fix,
wt-sdk-bpm-smoke, wt-sdk-g7-g8, wt-sdk-schema-lint, wt/sdk-g5-runtime
```
≥2,因此即便要起 backend 也必须走 docker isolated stack;host bootRun 违反红线 #11。

## 未执行项(任务范围全部 blocked)

| 步骤 | 状态 | 原因 |
|------|------|------|
| 1. 派 branch `wt/b1-automation-golden-e2e` | 未执行 | 无意义 — 后续 E2E 无 backend 可跑 |
| 2. API 创建 automation + UI 编辑器渲染验证 | 未执行 | backend down |
| 3. spec `auraboot/web-admin/tests/e2e/automation/automation-golden.spec.ts` | 未执行 | 无运行时 |
| 4. 消除 `automation-deep.spec.ts:222` no-op | 未执行 | 上下文未读完整,先解 env |
| 5. ≥3 次连续稳定性跑 | 未执行 | preflight 失败 |
| 6. 本报告 | 已执行 | 即此文件 |
| 7. e2e-truth 自审措辞 | 已执行 | 见下"精确措辞"段 |

## 5 维度精确措辞汇报

按 `/e2e-truth`「精确措辞」要求:

> **0 pass / 0 skip / 0 fail / 0 fixme,UI 路径覆盖率 0%(未执行) — environment-invalid**

无任何假通过(没跑就没真通过/假通过之分);无 PUT-API 兜底、threshold 阈值、skip 包装产品缺口、retries:N 兜底等四类反模式;无任何产品判断输出。

## 解锁所需(给主对话 / owner 拍板)

按红线优先级,可选两条路径:

**路径 A — host stack 模式(任务原意)**
1. owner 手动 `cd auraboot/platform && ./gradlew bootRun` 起 host backend(确认 vite 5173 已绑这个 backend)
2. owner 确认 BFF/auth seed 状态
3. 然后我重跑 preflight + 执行 B1 步骤 2-6
4. 风险:违反红线 #11(≥2 worktree 不应共享 host backend)— 但本任务范围只读取 + 创建 automation/record,IO 隔离性弱

**路径 B — 切 docker isolated stack(更稳)**
1. 改 B1 方案为"在 G5 worktree 内 `scripts/dev/start-isolated.sh --slug b1-auto-golden --e2e`"
2. 起完后我执行 B1 步骤 2-6
3. 任务原文明确禁止"起新 docker isolated",需要主对话/owner 明确放行此例外

**路径 C — 直接放弃 host stack,先专注 #4 no-op 消除**
1. `automation-deep.spec.ts:222` 的 `expect(true).toBe(true)` 是静态可读静态可删的
2. 我可以读完上下文判断"真断言或删除",纯代码改动不需要 runtime,可以单独成 PR
3. 但 B1 主目标(端到端跑通)仍需 A 或 B

## UI-drag 后续 backlog 摘要(未启动,占位)

待 B1 主线落地后,补充:
- xyflow 拖拽创建 trigger/condition/action 节点
- 拖拽连线 trigger→condition→action
- 保存后再 reload,断言 flowConfig 序列化往返一致
- 需配合 `@dnd-kit` 单测 + 真实指针 E2E(参考 `docs/standards/e2e-extras/dnd-designer-test-conventions.md`)

## 关联
- 上轮诊断:G5 worktree HEAD `680ff334a`,`AutomationCommandEventBridge.onRecordCreate` → `AutomationTriggerServiceImpl.onRecordCreate:82` 真链路已验
- no-op 位置:`auraboot/web-admin/tests/e2e/automation/automation-deep.spec.ts:222`(待消除)
- 红线引用:`AGENTS.md` §2.1 / §「高频红线」#1 #2 #11
