# Env / Scripts / Testing 系统性设计（长期视角）

**Status**: draft — awaiting owner decision (选项 A / B / C / D)
**Date**: 2026-05-09
**Branch**: 当前在 `fix/oss-suite-r2`；review 通过后按 phase 拆 feature branch
**Author**: 由今天 23-commit r2 stack 实战收口生成

---

## TL;DR

今天 fix/oss-suite-r2 完成 23 commit，把 OSS full suite 从 170 → 40 fail。但残留 4 个 task（member-login migrate / helpers 推广 / host-env-export / AdminBootstrapRunner 重做）背后是 **5 个系统性根因**。本文给完整方案，3 个 phase（共 ~18h），由 owner 选择执行强度。

**推荐选项 C**：Phase 1 + Phase 2（10h），关 5 个根因中的 4 个；Phase 3 等添加第 6 个 deployment target 时再做。

---

## 1. 历史成因 — 怎么走到今天

### 1.1 OSS 起步只有 host workflow
最早 `oss-reset-and-init.sh` 是「单 dev / 单 host / 单 backend on :6443」假设下写的。所有 helper、spec、CLI 命令都把 `localhost:6443` 当物理常量。

### 1.2 多 worktree 并行后出现共享单点冲突
开发节奏加快 → `git worktree` 普及 → 多个 worktree 抢同一 host postgres :5432。

→ **解药 #1**：docker isolated stack（`docker-compose.isolated.yml` + `start-isolated.sh`），每个 worktree 一个独立 backend/postgres/vite，端口随机分配。

但解药**只解决基础设施隔离，没解决 spec/helper 内部硬编码**。Spec 里 `psql -h localhost -U ghj` 仍然走 host postgres，跟 isolated backend 拉去的 r2 postgres 是两个 DB → cross-DB false-positive。

### 1.3 backend 复杂度上升后 bootstrap 分裂
`platform_admin` / `tenant_admin` / scope-type / multi-tenant — bootstrap 不再是「创 1 个 admin + 1 个 tenant」，而是「创 System Tenant + Business Tenant + grant + 角色映射」。

→ **AdminBootstrapRunner**（启动时 hook）只做最基础的 demo tenant；完整 bootstrap 走 `/api/bootstrap/setup` API；这个 API 必须由「调用方」来调，于是有 2 条 entry point：
- `oss-reset-and-init.sh §4.5`（curl POST）
- Playwright `tests/api/setup/00-bootstrap.spec.ts`

**两个 entry point 各调一遍同一 endpoint，互相不知道对方做没做** —— 维护两份代码同一行为，drift 必然。

### 1.4 4 个 task 的本质归类

```
Task                         | 病灶层级
─────────────────────────────┼──────────────────────────────────
 1. member-login migration   | A. 多环境配置漂移
 6. helpers 推广              | A. 多环境配置漂移
11. host-env-export.sh       | D. host workflow 不是「一等公民」
 4. AdminBootstrapRunner     | B. bootstrap 分层混乱
                             | C. script 既是 infra 又混 application
```

它们看起来是 4 个独立 task，**实际是 4 个 root cause 的现象**。

---

## 2. 当前架构清单（事实）

### 2.1 Env 来源（10+ 独立来源）

| 来源 | 变量数 | 取值时机 | 用户可见 |
|------|--------|----------|----------|
| `.aura-stack/<slug>.env` | 5 | start-isolated.sh 写入 | 是（per-stack） |
| `.env` / `.env.example` | ~5 | dotenv 加载 | 是（host） |
| `docker-compose.yml` | 10+ | docker compose 注入容器 | 否 |
| `docker-compose.isolated.yml` | 10+ | 同上 + override | 否 |
| shell exports（手动） | ? | session 临时 | 是 |
| spec/helper inline `??` | 14+ | TypeScript 默认值 | 否（埋在代码） |
| `playwright.config.ts` | 3 | 项目元数据 | 部分 |

**问题**：同一含义（"backend port"）有 4-5 个独立来源，每个 spec 自选 fallback 链。

### 2.2 Bootstrap 路径（4 条）

| 路径 | 创建什么 | 触发时机 |
|------|----------|----------|
| 1. `AdminBootstrapRunner`（Spring） | 1 admin + demo tenant | backend 启动（1st boot） |
| 2. `/api/bootstrap/setup` endpoint | System+Business+grant | HTTP 调用 |
| 3. `oss-reset-and-init.sh §4.5` | (2) via curl | script 中 |
| 4. Playwright `00-bootstrap.spec.ts` | (2) via page.request | Playwright 启动 |

(3)+(4) 都最终调 (2)；(1) 跟 (2) 不等价（创的东西不一样）。

### 2.3 Script 职责（杂乱层）

`oss-reset-and-init.sh` 537 行（已 trim 一次）做的事：

```
infra 层（应该留）：
  §1-2  kill 进程
  §3    reset DB
  §4    start backend
  §5    start frontend
  §7    verify

application 层（应该走 backend / Playwright）：
  §4.5  bootstrap endpoint    ← entry point #2 of bootstrap
  §7.4  platform_admin grant  ← psql 直接 INSERT
  §7.5  plugin import         ← Aura CLI（需要 admin 已存在）
  §7.6  displayName backfill  ← psql migration-like
  §7.7  marketplace seed      ← psql
  §7.8  cs-agent seed         ← psql
  §7.9  aurabot seed          ← psql
  §8    showcase Playwright   ← Playwright（已经是对的层）
```

**判断标准**：「重启 backend 后失效」的事是 application 层；「重启 backend 后仍在」的事是 infra 层。Bootstrap / plugin import / seeds 都属前者，但今天混在 script 里。

### 2.4 测试 helper 层（drift 重灾区）

```
tests/helpers/playwright-env.ts  ← 中央（今天加）
tests/helpers/pg-env.ts          ← 中央（今天加）
tests/helpers/wd-fixtures.ts     ← 半中央（改了 BASE_URL，psql 没改）
tests/helpers/test-accounts.ts   ← 与 env 无关
tests/e2e/aurabot/_real-backend-helpers.ts ← 内嵌 inline（部分改过）
tests/auth.setup.ts             ← 仍 inline
tests/global-setup.ts           ← 仍 inline
tests/global-teardown.ts        ← 已用 PSQL_BASE
... 各 spec inline (16 已迁；其他还有)
```

**当前状态**：6/N 文件用 import，其余 inline。中央模块存在但**不是唯一路径**。

---

## 3. 系统性根因（5 条）

### A. 多环境 = 多份硬编码副本

**事实**：当前需要支持 5+ 个 deployment target（host / r2-style isolated / ga-e2e / CI / enterprise），每个有不同 port set / postgres user / JWT key。

**问题**：spec/helper 假设单一 target → 任何代码新增就携带这个假设。N 文件 × 5 target = N×5 个错配组合。每次添加 target 都得改 N 文件。

**症状**：任务 1 / 6 / 11

### B. Bootstrap 不在唯一权威

**事实**：4 条 bootstrap 路径，3 个 entry point 调同一 endpoint，1 个 entry point 走 startup hook 但功能减半。

**问题**：
- entry point 增加（CI / k8s deploy / 第三方部署）→ 又得抄一份调用代码
- bootstrap 行为改一处 → 不知道有几处依赖
- AdminBootstrapRunner 的「快 demo」和 setup endpoint 的「完整 setup」语义模糊；任何 entry 都得自己判断要哪个

**症状**：任务 4

### C. Script 边界混乱

**事实**：`oss-reset-and-init.sh` 有 8 step，3 个是 infra，5 个是 application。

**问题**：
- 不能单独「只 reset DB」（要么全跑要么全不跑）
- 不能在「不跑测试」的场景用 script（如纯 demo）
- script 改 application 逻辑（如 grant）需要懂 RBAC / SQL → 维护门槛高
- script 跟 docker-compose / Playwright 形成三角依赖

**症状**：任务 4 同时是 B 和 C 的解（重做 runner + 移逻辑出 script）

### D. host workflow 不是一等公民

**事实**：r2 stack 有 `start-isolated.sh` + `r2-env-export.sh` 一对自洽工具。host workflow 没有等价物，用户期待「在 host 上 default work」，所以缺显式 setup。

**问题**：
- 切换 r2 → host 容易污染（leftover env）
- CI 跑 host run 时跟开发本地行为不一致
- 新 dev onboard 没法「一行 source」get host workflow ready

**症状**：任务 11

### E. drift 检测无自动化

**事实**：drift 只有在 isolated stack 跑挂的时候才被发现。没有 lint / 类型 / CI gate。

**问题**：
- 修了一波，6 个月后下一波再来
- 跨人员协作（每个 dev 写新 spec）扩散概率单调上升
- 今天发现了，但没固化成自动 enforcement

**症状**：任务 1 / 6 是治标。E 是治本。

---

## 4. 长期愿景（3-6 个月后的「好」长什么样）

### 4.1 单一 env 抽象

```typescript
// tests/helpers/environments.ts
export interface Env {
  backend: string;       // 'http://localhost:6443'
  vite: string;          // 'http://localhost:5173'
  bff: string;
  pg: { host: string; port: number; user: string; db: string; password?: string };
  springProfile: string;
}

export function loadEnv(): Env {
  // 优先级：明示 PLAYWRIGHT_BASE_URL > BE_PORT 派生 > 默认 host
  // 同样的逻辑 reset script 也用（通过 JSON output 共享）
}

export const env = loadEnv();
```

所有 spec 和 helper：

```typescript
import { env } from '../helpers/environments';
await fetch(`${env.backend}/api/...`);
```

**ESLint 规则**禁止：
- `process.env.BACKEND_URL` / `process.env.BASE_URL` 在 spec/helper 中（must import env）
- 字面量 `'http://localhost:6443'` / `'localhost:5432'` 在 tests/

### 4.2 Bootstrap 单一权威

```
backend 启动
  → AdminBootstrapRunner（升级版）
    → 检查 ab_tenant 是否已有 System Tenant
      → 没有 → 跑完整 setup（System + Business + grant + role mapping）
      → 有 → 跳过
  → backend ready
  → 任何 entry point（script/Playwright/手工/CI）拿到的都是「完整 bootstrap」状态
```

`/api/bootstrap/setup` endpoint 仍存在但变成「触发再次 bootstrap」的手动路径（极少用）。`oss-reset-and-init.sh §4.5` 删除。Playwright `00-bootstrap.spec.ts` 退化为「assert 已 bootstrap」（断言用，不再驱动）。

### 4.3 Script 纯 infra

```
oss-reset-and-init.sh (~200 行):
  §1-2 kill
  §3 reset DB
  §4 start backend → 启动时自动 bootstrap (via runner)
  §5 start frontend
  §6 (optional) trigger Playwright seed if AURA_ENV=test
```

Plugin import / marketplace / cs / aurabot seeds → 都跟 bootstrap 一样，由 backend 启动 hook 在 dev/test profile 下自动执行（idempotent）。或者一律走 Playwright setup project（统一 entry）。

### 4.4 所有环境对称

```
host workflow:        source scripts/dev/host-env-export.sh        # 显式
r2 isolated:          source scripts/dev/r2-env-export.sh r2       # 显式
ga-e2e:               source scripts/dev/ga-e2e-env-export.sh      # 显式
CI:                   source scripts/dev/ci-env-export.sh          # 显式
enterprise stack:     source scripts/dev/enterprise-env-export.sh  # 显式
```

每个 export 脚本 = 一个 Env profile 的 shell 等价物。背后是同一个 `loadEnv()` 实现。

### 4.5 Drift CI gate

- ESLint custom rule 禁硬编码 port
- pre-commit hook 跑 `playwright test tests/api/setup/00-bootstrap.spec.ts` 在最小 isolated stack 上 → 任何回归立刻可见
- CI 矩阵：每个 PR 跑 host run + 1 个 r2 run（可能开 cache，<5min）

---

## 5. 分阶段实施方案

### Phase 1 — Drift surface 100% 关闭 + 自动化（半天 / 1 session）

**目标**：今天的 3 个症状级任务（1, 6, 11）全部 fix，加 ESLint 规则防回归。

**操作清单**：

| # | 任务 | 估时 |
|---|------|------|
| 1 | `member-login-integration.spec.ts` 迁 import | 15 min |
| 2 | `tests/helpers/wd-fixtures.ts`、`_real-backend-helpers.ts`、`auth.setup.ts`、`global-setup.ts` 等 helper 全部迁 import | 1h |
| 3 | `scripts/dev/host-env-export.sh` 写出来 + 加到 SOP | 30 min |
| 4 | **新增 ESLint custom rule** `no-hardcoded-aura-port` 检测 `'http://localhost:(5173\|6443\|3500\|5432)'` 字面量在 `tests/` 下 | 30 min |
| 5 | `tests/helpers/playwright-env.ts` + `pg-env.ts` 改名 → `environments.ts` 统一对外（前向兼容 re-export 老名） | 15 min |
| | **总计** | **2.5 h** |

**Deliverable**：单 commit + push fix/oss-suite-r2 后续 commit；ESLint 报零硬编码；新 spec 一律 import。

**风险**：低（纯 cleanup + lint）。

### Phase 2 — Bootstrap 单一权威（1.5 session / 4-6h）

**目标**：任务 4 + 拉齐 r2 stack 跟 host 的 bootstrap 行为。

**操作清单**：

| # | 任务 | 估时 |
|---|------|------|
| 1 | 写 design doc `auraboot/docs/plans/.../bootstrap-unified.md` | 30 min |
| 2 | backend Java 改动：`AdminBootstrapRunner` 升级 + idempotency guard + `AURA_FULL_BOOTSTRAP` env hook | 1h |
| 3 | publishToMavenLocal + 重启 r2 backend 验证 | 15 min |
| 4 | trim `oss-reset-and-init.sh §4.5/§7.4` | 30 min |
| 5 | Playwright `00-bootstrap.spec.ts` 退化为 assertion | 15 min |
| 6 | r2 `docker-compose.isolated.yml` 删 `AURABOOT_BOOTSTRAP_ENABLED=false` override | 5 min |
| 7 | 全栈验证：host + r2 + IT | 2h |
| 8 | 文档更新：Memory + SOP | 30 min |
| | **总计** | **5 h** |

**Deliverable**：feature branch（per AGENTS.md「默认走 feature branch」），独立 PR for review。

**风险**：中
- backend Java 改动 → 影响所有 deployment target → 全栈验证必须
- `AURA_FULL_BOOTSTRAP=false` 路径需要保留给「只想要快 demo」的 dev → 不能默认 break
- publishToMavenLocal 在多 worktree 时需走 isolated stack（per AGENTS.md §11）

### Phase 3 — Env 抽象 first-class + script 纯 infra（2 sessions / 6-8h）

**目标**：愿景 4.1+4.3+4.4 落地。

**操作清单**：

| # | 任务 | 估时 |
|---|------|------|
| 1 | 新模块 `tests/helpers/environments.ts` (TS 类型 + loadEnv + 5 profile) | 1h |
| 2 | shell counterpart `scripts/dev/lib/env-loader.sh` + JSON 输出 | 30 min |
| 3 | 统一 export 脚本：host / r2 / ga-e2e / ci / enterprise | 1h |
| 4 | migrate 全部 helper + spec to import `environments.ts` | 2h |
| 5 | ESLint rule 强化：禁直接 `process.env.{BE_PORT\|...}` | 30 min |
| 6 | script 二次 trim：§7.5 plugin import + §7.6-7.9 platform seeds 移到 Playwright OR backend hook | 1h |
| 7 | CI matrix 加 r2 run（PR 检查 = host + r2 双过） | 1h |
| | **总计** | **7 h** |

**Deliverable**：feature branch + 设计 doc + 测试矩阵报告。

**风险**：中-高
- 全 spec migration 到 `env` 模块 → 大改动 → tsc + 全 suite 验证
- CI matrix 加 r2 run 增加 PR check 时间（~5 min/PR）→ 有人会嫌慢
- env 抽象错了（如 profile 设计漏洞）→ 后续要再翻新 → 设计阶段需 review

---

## 6. 风险与权衡

### 6.1 是否一次做完 vs 分 3 phase？

**推荐分 phase**：
- Phase 1 风险低 / 收益立刻见效（drift 关 100%）
- Phase 2 风险中 / 是 architectural 改动，需要 rolling out
- Phase 3 风险中-高 / 投入大但 6 个月不再翻新

如果 Phase 2 失败（backend Java 改坏），Phase 1 的成果不受影响。如果 Phase 3 设计有问题，Phase 2 也仍是好的。

**反模式**：一次性「大重构」3 phase 全打包 → 单 commit 跨 backend Java + script + tests + helpers + CI → 任何环节挂掉全部回滚 → 心理负担大不敢推。

### 6.2 是否要做 Phase 3？

**取决于 6 个月预测**：
- 如果未来要加 3+ 个 deployment target（K8s / SaaS / 客户私有云）→ Phase 3 必做
- 如果只 host + r2 + ga-e2e 三个稳定 → Phase 3 可缓，做 ESLint 即足够
- **当前事实**：今天 4 个 task 暴露的问题已经在 5 个 target 之间扩散；新 dev onboard 困惑明显。建议做。

### 6.3 是否合并 Phase 2 + Phase 3？

**不推荐**：
- Phase 2 改 backend Java（公共消费）
- Phase 3 改 test infrastructure（仅 dev/test 消费）
- 两者影响半径不同；合并会让 PR 难审

### 6.4 与「decision-defaults: 默认走 feature branch」的关系

- Phase 1 改动小但 broad（多文件） → 单 feature branch 一并推
- Phase 2 改 backend Java + script → 需要单独 PR + IT 验证 → feature branch + review
- Phase 3 改动巨大 → 拆成至少 3 个子 PR：`env-module` / `helpers-migrate` / `script-trim`

### 6.5 ROI 总账

| Phase | 工作量 | 收益 | 净值 |
|-------|--------|------|------|
| Phase 1 | 2.5 h | drift 100% 关闭 + 防回归 lint | 高 |
| Phase 2 | 5 h | bootstrap 单一权威 + script 缩 60 行 | 高 |
| Phase 3 | 7 h | env 抽象 first-class + script 纯 infra + CI 矩阵 | 中 |
| **总计** | **14.5 h** | 系统性问题 5 → 0；drift 复发概率 80% → <10% | 高 |

**对比单点 fix**（保留现状）：
- 每 fix session 仍需 30-60 min 排查 env drift 类问题
- 6 个月后再来一波 ~10+ specs hardcode 累积
- 每加一个 deployment target 痛苦递增

---

## 7. 决策点（owner review）

请选择执行强度：

### 选项 A：3 phase 完整推进
- 总 14.5h / 跨 3-4 个 session
- 长期 drift 治本
- 推荐场景：6 个月内会加 deployment target

### 选项 B：只做 Phase 1（推荐保底）
- 2.5h / 1 session
- 关闭 drift surface + lint 防回归
- bootstrap / script 改动留给「事故触发」（被动）
- 推荐场景：精力受限，只想关掉今天暴露的 surface

### 选项 C：Phase 1 + Phase 2，Phase 3 backlog（推荐主选）
- 7.5h / 2 session
- bootstrap 单一权威落地
- env 抽象等下一波 deployment target 实际需求时再做
- 推荐场景：当前 5 个 target 稳定，6 个月内不预期新增

### 选项 D：完全不做
- drift 已知有，每次 r2 跑挂时 30min 处理
- 接受未来仍然会零星扩散
- 只适合「我们不再加 deployment target」的判断

---

## 8. 落地前确认事项

无论选哪个 option，开始前需确认：

1. **是否同意 4 个 task 的根因归类**（A/B/C/D/E）？
2. **AdminBootstrapRunner 升级的兼容性策略**：默认行为变成「全套 bootstrap」是否会破坏现有 Docker 镜像或 K8s 部署的预期？
3. **`AURA_FULL_BOOTSTRAP=false` escape hatch** 是否够（用于纯 demo 启动场景）？
4. **CI matrix 加 r2 run** 是否能接受 PR check +5min 时间？
5. **Phase 3 的 env 抽象**是否预判到第 6 个 deployment target 的需求？

---

## 9. 与今日已完成工作的关系

本设计**不否定**今日 23 commit，而是接着推进：

- 今日 commit 覆盖 5 root cause 中的 **A、E 部分**：
  - 提供了 `playwright-env.ts` / `pg-env.ts` 中央模块（A）
  - 写了 SOP doc（E）
  - 但 lint 自动化没做，spec/helper migration 没全做
- 今日**没动**根因 B / C / D：
  - bootstrap 仍 4 路径
  - script 仍 537 行混业务
  - host workflow 仍无显式 export

Phase 1 = 把 A、E 收尾（migration + lint）
Phase 2 = 解决 B、C
Phase 3 = 把 A 推到极致（env 抽象 first-class）+ CI 矩阵

---

## 10. 不做（明确）

避免范围蔓延，本设计**明确不包含**：

- BPM Drools rule deployment fix（今天 r2 stack 上发现的 product-level seed gap）
- saved-view CF/FV/FF/KG/LF feature audit（产品层未发布功能）
- LLM key 注入（task #10 from yesterday's task list）
- Tier-2 docker（CI 夜构基础镜像）
- Tier-3 docker（stack lease pool）

这些是独立 backlog 项，跟 env/scripts/testing 系统化无直接耦合。
