# Env / Scripts / Testing 系统性设计（长期视角）

**Status**: v2 — awaiting owner decision (选项 A / B / C- / D)
**Date**: 2026-05-09 (v1) / 2026-05-09 (v2 post-review)
**Branch**: 当前在 `fix/oss-suite-r2`；review 通过后按 phase 拆 feature branch
**Author**: 由今天 23-commit r2 stack 实战收口生成；v2 整合 reviewer 7 个 P1/P2 findings

## Revision history & reviewer findings

**v1 → v2 改动**：reviewer review 发现 7 项实证错误，已在 v2 全部对齐源码事实。

| # | Finding | v1 错误 | v2 对齐 |
|---|---------|---------|---------|
| F1 | 硬编码 port 实际数 | 写「14+」 | grep 验证 ~99 处 / 46 文件（reviewer 报 125/55） |
| F2 | `pnpm lint` 不覆盖 `tests/` | 想当然 ESLint 全仓 | 实际 `app/**/*` only ([package.json:19](#)、CI 同) → 需新增 `test:env-lint` |
| F3 | Bootstrap 路径数 | 列 4 条 | 实际 **5 条**：漏 `BootstrapStartupListener`（mode=seed 启动时执行 BootstrapEngineService） |
| F4 | `AURA_FULL_BOOTSTRAP` 不存在 | v1 凭直觉造名 | 实际是 `AURABOOT_BOOTSTRAP_ENABLED`（runner gate）+ `AURABOOT_BOOTSTRAP_MODE`（listener gate）；不可新增第 3 套 env 语义 |
| F5 | 脚本强制 disable runner | Phase 2 漏 fix | `oss-reset-and-init.sh:152 export AURABOOT_BOOTSTRAP_ENABLED=false` 必须在 Phase 2 显式调整 |
| F6 | Idempotency 太弱 | 设计「检查 System Tenant 存在跳过」 | 当前 `AdminBootstrapRunner` 只看 `humanUserCount>0` skip；中间态（demo tenant 在 + System Tenant 缺）会漏；需要 desired-state invariants matrix |
| F7 | `00-bootstrap.spec.ts` 退化为 assert 太弱 | Phase 2 给 15min | 当前只 check `initialized=true`，需要补 System+Business+grant+API 多项断言 → 升到 30-45 min |
| F8 | Phase 3 plugin 移到 hook 漏 SoT | 一句带过 | `BuiltinPluginImportService` 只内建 2（org-management/platform-admin）；脚本导 11；test-fixtures 受 `AURA_ENV=test` 控；需 3 profile 切分（core / demo / test fixture） |
| F9 | CI +5min 估算无依据 | 直接写 5min | start-isolated 冷启 6:28 / 暖启 22s；Playwright 4-phase serial；需先实测再定 PR gate vs nightly |

**v2 推荐选项变化**：v1 推荐「C」（直接做 Phase 1 + 2）；v2 改为 **「C-」** —— 先补设计缺口（修真实 bootstrap 路径表 / 改掉伪 env 名 / 明确脚本 env 变更 / 给出 contract test 实际命令 / 写 desired-state 不变量），review 通过后再开 phase 1 工作。

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

### 2.2 Bootstrap 路径（5 条 — v2 修正）

| # | 路径 | 创建什么 | 触发时机 | Gate |
|---|------|----------|----------|------|
| 1 | `AdminBootstrapRunner` | 1 admin + demo tenant + 2 builtin plugins (`org-management` / `platform-admin`) | backend 启动（无 human user 时） | `AURABOOT_BOOTSTRAP_ENABLED=true` |
| 2 | `BootstrapStartupListener` | 调 `BootstrapEngineService.execute()` 走 seed-config JSON（System+Business+grant+demo seed） | backend 启动（systemConfig.initialized=false 时） | `AURABOOT_BOOTSTRAP_MODE=seed` |
| 3 | `BootstrapEngineService` / `/api/bootstrap/setup` | 同 (2) 但走 HTTP 入参 | HTTP POST | 永远开启（系统未 initialized 时才生效） |
| 4 | `oss-reset-and-init.sh §4.5` | (3) via curl | script 中 | 永远跑（除 `--no-bootstrap`） |
| 5 | Playwright `00-bootstrap.spec.ts` | (3) via page.request | Playwright 启动 | 永远跑 |

**关键事实**（reviewer F3/F4 验证）：
- (1) 与 (2) 是 **2 条独立 startup runner**，分别由 `AURABOOT_BOOTSTRAP_ENABLED` 和 `AURABOOT_BOOTSTRAP_MODE` 控制 — 不是同一个开关的两挡
- (1) 创建的内容跟 (2)/(3)/(4)/(5) 不等价 — (1) 只 demo tenant + 2 builtin plugins；(2)+ 走 BootstrapEngineService 才有 System+Business+grant
- (4) 强制 `export AURABOOT_BOOTSTRAP_ENABLED=false`（[oss-reset-and-init.sh:152](#)）— 显式关掉 (1) 防 race，但 (2) 没关
- v1 文档少列了 (2)，且发明了不存在的 `AURA_FULL_BOOTSTRAP` env

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

### 4.2 Bootstrap 单一权威（v2 修正 — 复用现有 listener，不发明新 env）

**两条 startup runner 二选一收口**：

- **方案 A — 升级 `AdminBootstrapRunner`**：默认行为从「demo tenant only」扩展为「调 BootstrapEngineService 完整 setup」。保留 `AURABOOT_BOOTSTRAP_ENABLED=true` env 不变，新增「desired-state 检查 + 缺失项修复」逻辑。
- **方案 B — 收口到 `BootstrapStartupListener`**：把 `AURABOOT_BOOTSTRAP_MODE=seed` 设为 OSS dev/test default（`application-dev.yml` 改默认）。`AdminBootstrapRunner` 退役。
- **方案 C — 二者合并**：删 `AdminBootstrapRunner`，扩展 `BootstrapStartupListener` 同时处理 dev demo 和 seed 模式；只留一条 startup runner。

**强烈建议方案 C**（reviewer F4 隐含）：当前两条 runner 语义重叠（都是 startup hook + idempotent + 创 admin/tenant），是历史拼凑遗留。统一可减少未来 entry point 增加时的混乱。

**目标**：

```
backend 启动
  → unified startup runner
    → 读 desired-state invariants matrix（§4.6）
    → 检查每项 invariant；缺失 → 修复；存在 → skip
  → backend ready, system.initialized=true
  → /api/bootstrap/setup endpoint 仍存在但只供「外部触发再次 bootstrap」（罕用）
  → script/Playwright entry 都退化为 assertion（不再驱动）
```

`oss-reset-and-init.sh §4.5/§7.4` 删除；script line 152 的 `export AURABOOT_BOOTSTRAP_ENABLED=false` 删除（不再需要 race 防护）。Playwright `00-bootstrap.spec.ts` 升级为 contract test（详见 §4.7）。

### 4.6 Desired-state invariants matrix（v2 新增 — 回应 reviewer F6）

bootstrap idempotency 不能只看「是否 initialized」（reviewer F6 痛点）。每项 invariant 需独立判断、各自的 repair / fail-fast / skip 策略：

| # | Invariant | 检查方式 | 缺失时 |
|---|-----------|----------|--------|
| 1 | `system_config.initialized=true` | `SystemConfigService.isInitialized()` | repair（创 1+2+3+4） |
| 2 | System Tenant（id=1）存在 | `ab_tenant where id=1 and name='System'` | repair（BootstrapEngineService.bootstrapSystemTenant） |
| 3 | `platform_admin` role 存在于 System Tenant | `ab_role where code='platform_admin' and tenant_id=1 and scope_type='global'` | repair |
| 4 | Default admin user (`admin@example.com`) 存在 | `ab_user where email='admin@example.com' and deleted_flag=false` | repair（user.signUp） |
| 5 | Admin 在 System Tenant 是 member | `ab_tenant_member where user_id=admin.id and tenant_id=1` | repair |
| 6 | Admin grant `platform_admin` | `ab_user_role where member_id=... and role_id=platform_admin.id` | repair |
| 7 | 至少 1 个 Business Tenant 存在 | `ab_tenant where id != 1` | repair（创 demo tenant） |
| 8 | Admin 在 Business Tenant 也是 member（带 tenant_admin） | join | repair |
| 9 | 2 个 builtin plugin（org-management / platform-admin）已 import | `BuiltinPluginImportService.isImported()` | repair（importForTenant） |

**Skip 条件**：所有 9 项都满足 → skip；任何缺失 → repair（按需创建，幂等 INSERT/UPDATE）。

**Fail-fast 条件**：若发现 inconsistent state（如 platform_admin role 存在但 grant 缺失），先 log warn 然后 repair；不让中间态长期存在。

### 4.7 `00-bootstrap.spec.ts` 升级为 contract test（v2 新增 — 回应 reviewer F7）

当前 spec 仅 check `initialized=true`。升级后需断言全 9 项 invariant：

```typescript
test('bootstrap contract: System Tenant + platform_admin grant + admin can access platform API', async ({ request }) => {
  // 1. Initialized flag
  expect((await get('/api/bootstrap/status')).data.initialized).toBe(true);

  // 2-3. System Tenant + platform_admin role (via DB query helper or admin API)
  const tenants = await dbQuery(`SELECT id, name FROM ab_tenant WHERE id=1`);
  expect(tenants).toHaveLength(1);

  // 4-6. Admin user + membership + grant
  const adminLogin = await loginAdmin(request);
  expect(adminLogin.userId).toBeTruthy();

  // 7. Admin can access platform_admin-only endpoint
  const infraResp = await request.get(`${BACKEND_URL}/api/admin/infrastructure/status`,
    { headers: { Authorization: `Bearer ${adminLogin.jwt}` } });
  expect(infraResp.status()).toBe(200);

  // 8. Business Tenant exists with tenant_admin grant
  const spaces = await request.get('/api/tenant-selection/my-spaces', { headers: ... });
  expect(spaces.data.some(s => s.spaceType === 'business')).toBe(true);

  // 9. Builtin plugins imported (verify via /api/plugins/list filter)
  const plugins = await request.get('/api/plugins/list', { headers: ... });
  expect(plugins.data.records.map(p => p.code)).toEqual(
    expect.arrayContaining(['core-org-management', 'core-platform-admin']));
});
```

**估时**：30-45 min（不是 v1 的 15 min — reviewer F7 痛点）。

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

### Phase 1 — Drift surface 关闭（v2 修正 — 回应 reviewer F1/F2）

**目标重新定义**：「100% 关闭 + ESLint 报零」当前**不可执行**（reviewer F1/F2）—

实测 grep（v2 验证）：
- `tests/` + `playwright*.ts` 硬编码 port 字面量：**~99 处 / 46 文件**（reviewer 报 125/55 应包含更宽匹配模式）
- `tests/` 下直接 `process.env.{BACKEND_URL|...}` 读取：**49 处**
- `pnpm lint` script：`eslint "app/**/*.{ts,tsx,js,jsx}"` ([package.json:19](#)) — **完全不覆盖 tests/**
- CI ([frontend.yml:65](#)) 只跑 `pnpm lint` — 任何 ESLint rule 加在 tests/ 都无 CI gate 触发

**v2 重新定义 Phase 1 = 「drift gate 建立 + baseline allowlist + 增量收紧」**：

| # | 任务 | 估时 |
|---|------|------|
| 1 | grep 实际 scope，生成 `tests/.env-drift-baseline.json`（49 process.env + 99 字面量按文件 hash 列入 allowlist） | 30 min |
| 2 | 新增 `pnpm test:env-lint` script — 自定 grep 检查 + diff 对比 baseline；新增违例报错，已 baselined 不报 | 45 min |
| 3 | CI workflow 加 `test:env-lint` 步骤（fail PR if new drift） | 15 min |
| 4 | `member-login-integration.spec.ts` 迁 import（v1 任务 1） | 15 min |
| 5 | `wd-fixtures.ts` / `_real-backend-helpers.ts` / `auth.setup.ts` / `global-setup.ts` helper 迁 import（v1 任务 6 — 4 文件） | 1h |
| 6 | `scripts/dev/host-env-export.sh` 写出来 + 加到 SOP（v1 任务 11） | 30 min |
| 7 | `tests/helpers/playwright-env.ts` + `pg-env.ts` 改名 → `environments.ts` 统一（保留 re-export 老名） | 15 min |
| 8 | 全 46 文件迁 import — 替代 baseline 中的 99 字面量条目（**不在本 phase**，留 Phase 1.5） | — |
| | **本 phase 总计** | **3.5 h** |

**Phase 1.5 — 全量 migration**（独立 session）：
| # | 任务 | 估时 |
|---|------|------|
| 1 | 46 个文件批量迁 import（python script + tsc 验证） | 1.5h |
| 2 | 删 baseline allowlist 中 migration 已覆盖的条目 | 15 min |
| 3 | r2 + host smoke 验证 | 30 min |
| | **总计** | **2.5 h** |

**Deliverable**：
- Phase 1 PR 1：drift gate（baseline + lint script + CI gate）+ 4 helpers + host-env-export
- Phase 1.5 PR 2：46 specs 全量 migration

**风险**：低-中（baseline allowlist 模式可滚动收紧；不阻塞既有 PR）。

**v1 错误纠正**：v1 估时 2.5h 把全量 migration 写得过轻。实际 8 个 spec 改 import 就需要 15 min × 8 ≈ 2h，46 个不可能 1h 搞定。修正后总投入 6h（Phase 1 + Phase 1.5）。

### Phase 2 — Bootstrap 单一权威（v2 修正 — 回应 reviewer F3/F4/F5/F6/F7）

**目标**：任务 4 + 拉齐 host / r2 / docker-isolated 三种启动路径行为；修复 `oss-reset-and-init.sh:152` 显式禁 runner 的 race-prevention hack。

**前置决策**（owner 拍板）：选哪种统一路径？
- 方案 A：升级 `AdminBootstrapRunner`（gate=`AURABOOT_BOOTSTRAP_ENABLED`）
- 方案 B：扩展 `BootstrapStartupListener`（gate=`AURABOOT_BOOTSTRAP_MODE=seed`），废弃 runner
- 方案 C（推荐）：两者合并为单条 startup runner，复用现有 `AURABOOT_BOOTSTRAP_ENABLED` env，删 listener 的 `mode` gate（保留 seed-config JSON 加载逻辑）

下表按方案 C 估时：

**操作清单**：

| # | 任务 | 估时 |
|---|------|------|
| 1 | 写 sub-design doc `auraboot/docs/plans/.../bootstrap-unified.md`（含 §4.6 invariants matrix 详细化） | 45 min |
| 2 | backend Java：合并 `AdminBootstrapRunner` + `BootstrapStartupListener` → 单一 `BootstrapStartupRunner`，按 invariants matrix 各项 repair；保留 `AURABOOT_BOOTSTRAP_ENABLED` env（默认 dev profile = true，prod = false） | 1.5h |
| 3 | backend IT：`BootstrapStartupRunnerIT` 覆盖 9 项 invariants 的 missing/present 组合 | 1h |
| 4 | publishToMavenLocal + 重启 r2 backend 验证 | 15 min |
| 5 | trim `oss-reset-and-init.sh §4.5/§7.4`，**删除 line 152 `export AURABOOT_BOOTSTRAP_ENABLED=false`**（reviewer F5），加 `--no-bootstrap` 才禁用 | 30 min |
| 6 | r2 `docker-compose.isolated.yml` 删 `AURABOOT_BOOTSTRAP_ENABLED=false` override；改成默认 enabled | 5 min |
| 7 | Playwright `00-bootstrap.spec.ts` 升级为 contract test（§4.7，不是 assert one flag）| 30 min |
| 8 | 全栈验证：host smoke + r2 smoke + IT；外加新加的 `00-bootstrap` contract test 必须 pass | 2h |
| 9 | 文档更新：Memory + SOP；删 `feedback_no_more_prs` 旧 entry 中关于 bootstrap-race 的描述 | 30 min |
| | **总计** | **6.5 h** |

**Deliverable**：feature branch（per AGENTS.md「默认走 feature branch」），独立 PR for review。

**风险**：中
- backend Java 改动 → 影响所有 deployment target → 全栈验证必须
- 移除 line 152 `export AURABOOT_BOOTSTRAP_ENABLED=false` 后必须验证 host workflow 不出现 race（runner 启动 + script `/api/bootstrap/setup` 调用）
- 保留 `AURABOOT_BOOTSTRAP_ENABLED=false` escape hatch 给 prod 部署 / 纯 demo 启动场景
- publishToMavenLocal 在多 worktree 时需走 isolated stack（per AGENTS.md §11）

**v1 错误纠正**：
- v1 估时 5h 漏估 IT (item 3，1h) 和 contract test 升级（item 7 从 15 min → 30 min）
- v1 没列 line 152 修复（reviewer F5）
- v1 用了不存在的 `AURA_FULL_BOOTSTRAP` env 名（reviewer F4）

### Phase 3 — Env 抽象 first-class + script 纯 infra（v2 修正 — 回应 reviewer F8/F9）

**目标**：愿景 4.1+4.3+4.4 落地，但**plugin/seed 移位需先拆 profile**。

**前置：3 profile 边界**（reviewer F8）：

当前 `BuiltinPluginImportService` 只内建 2 个 plugin（org-management + platform-admin）。`oss-reset-and-init.sh §7.5` 导入 11 个。`test-fixtures` 由 `AURA_ENV=test` 控制。**移到 backend hook 必须先分清三种性质**：

| Profile | 内容 | 运行时机 | Gate |
|---------|------|----------|------|
| **core** | org-management / platform-admin（已在 BuiltinPluginImportService） | backend startup（dev + prod） | 永远 |
| **demo** | core-meta / core-bpm / core-aurabot / page-manager / crm-starter / showcase / agent-control-plane / acp-showcase / workflow-demo / org-management（重复要去）| dev/test profile | `AURABOOT_DEMO_SEED=true` |
| **test fixture** | test-fixtures plugin（`AURA_ENV=test` 时） | Playwright setup project 内 | `AURA_ENV=test` |

**禁止**：backend startup 默认导入 demo / test fixture → 会让 prod 部署带 demo 数据。

**操作清单**：

| # | 任务 | 估时 |
|---|------|------|
| 1 | 新模块 `tests/helpers/environments.ts` (TS 类型 + loadEnv + 5 profile) | 1h |
| 2 | shell counterpart `scripts/dev/lib/env-loader.sh` + JSON 输出 | 30 min |
| 3 | 统一 export 脚本：host / r2 / ga-e2e / ci / enterprise | 1h |
| 4 | migrate 全部 46 helper + spec 完成（如 Phase 1.5 已做，跳过） | 0-2h |
| 5 | ESLint rule 强化：禁直接 `process.env.{BE_PORT\|...}` | 30 min |
| 6 | backend：扩展 `BuiltinPluginImportService` 支持 `core` / `demo` 2 profile，按 `AURABOOT_DEMO_SEED` env 决定是否加载 demo 集 | 1.5h |
| 7 | Playwright `01-import-test-fixtures.spec.ts`（new）— 仅当 `AURA_ENV=test` 跑 | 30 min |
| 8 | trim `oss-reset-and-init.sh §7.5/§7.6/§7.7/§7.8/§7.9` — 全部由 backend startup hook 接管 | 30 min |
| 9 | r2 + host 全栈实测（**先实测**冷启 / 暖启时长，再决定 CI gate） | 1.5h |
| 10 | CI matrix 决策：host smoke + targeted r2 smoke 作 PR gate（~3min？）；full r2 run 走 nightly（确切实测后定） | 30 min |
| | **总计** | **7-9 h**（取决于 Phase 1.5 是否合并） |

**Deliverable**：feature branch + 3 个 profile 设计 doc（core / demo / test）+ 实测时长报告 + CI 矩阵。

**风险**：中-高
- 全 spec migration → 大改动 → tsc + 全 suite 验证
- 3 profile 边界设计错（如把 demo data 错误标记为 core）→ prod 部署带 demo 数据
- CI gate 选错（r2 full 跑太慢卡 PR）→ 开发体验恶化；**先实测再决策，不写死估算**（reviewer F9）

**v1 错误纠正**：
- v1 「移 plugin 和 seed 到 backend hook」一句带过 → 实际需要 3 profile 切分
- v1 「PR check +5min」无依据；v2 改为「先实测再决策」

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

### 6.5 ROI 总账（v2 修正）

| Phase | 工作量 (v1) | 工作量 (v2) | 收益 | 净值 |
|-------|------|------|------|------|
| Phase 1 (gate + 部分 migrate) | 2.5h | **3.5h** | drift gate + 防回归 + 部分 helper 收口 | 高 |
| Phase 1.5（全量 migrate） | — | **2.5h** | 46 specs 完整迁完 import | 中 |
| Phase 2 (bootstrap 统一) | 5h | **6.5h** | bootstrap 单一权威 + 9-invariant contract test | 高 |
| Phase 3 (env 抽象 + 3 profile) | 7h | **7-9h** | env 抽象 first-class + plugin profile 拆分 + CI 实测 | 中 |
| **总计** | **14.5 h** | **19.5-21.5 h** | 系统性问题 5 → 0；drift 复发概率 80% → <10% | 高 |

v1 → v2 估时增加原因：reviewer 找到的实证 gap 都需要补工时（baseline allowlist / contract test / IT / 3 profile 设计 / 实测）—— 这些不是膨胀，是 v1 漏估。

**对比单点 fix**（保留现状）：
- 每 fix session 仍需 30-60 min 排查 env drift 类问题
- 6 个月后再来一波 ~10+ specs hardcode 累积
- 每加一个 deployment target 痛苦递增

---

## 7. 决策点（owner review，v2 修正）

请选择执行强度：

### 选项 A：3 phase 完整推进
- 总 19.5-21.5h / 跨 4-5 个 session
- 长期 drift 治本
- 推荐场景：6 个月内会加 deployment target

### 选项 B：只做 Phase 1 + 1.5（保底）
- 6h / 1-2 session
- drift gate + 完整 migration + lint 防回归
- bootstrap / script 改动留给「事故触发」（被动）
- 推荐场景：精力受限，只想关掉 drift surface

### 选项 C-：先补设计缺口，再选 phase（v2 推荐）
- 当前 v2 修订完成（doc）+ 0.5h owner 决策
- 然后视决策结果走 Phase 1+1.5 / Phase 1+1.5+2 / 全 phase
- **v1 推荐 C 直接开工」改为 v2 推荐「C-」**：reviewer 7 项 P1/P2 finding 表明 v1 设计未到执行级；先补完 design doc 的真实路径列表 / 真实 env 名 / contract test 命令 / 3 profile 边界 / 实测时长，再决定执行 phase
- 推荐场景：希望系统性走，但承认 v1 设计有 gap

### 选项 C：Phase 1+1.5 + Phase 2，Phase 3 backlog
- 12.5h / 3 session
- bootstrap 单一权威落地 + 完整 drift gate
- env 抽象等下一波 deployment target 实际需求时再做
- 推荐场景：当前 5 个 target 稳定，6 个月内不预期新增

### 选项 D：完全不做
- drift 已知有，每次 r2 跑挂时 30min 处理
- 接受未来仍然会零星扩散
- 只适合「我们不再加 deployment target」的判断

**v2 默认推荐**：**选项 C-**（先 review 这份 v2 doc，确认设计 gap 已补；然后选 C 或 A）。直接执行 v1 的 C 风险高 — reviewer 找到的 7 项实证错误说明 v1 不到 ready-for-execution 状态。

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
