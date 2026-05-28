# Plugin Seed/Fixture Mechanism — Design Doc

- **Date**: 2026-05-08
- **Status**: Draft (awaiting owner decision)
- **Owner**: TBD
- **Repo scope**: `auraboot/platform` (OSS) — feature is shared by all plugins

---

## 1. 背景与触发

`acp-showcase` 插件交付后,两条页面 `/p/acs_demo_request` 与 `/p/acs_safety_rule` 浏览器空白。原因:

- 插件 import 时**只导入 metadata**(models / fields / commands / pages / dicts / menus / permissions / namedQueries / processes / dashboards 等),**不导入业务记录**。
- 历史做法是写一个 `scripts/seed-*.sh` 让人工/CI 在 import 后手动跑。已被红线打:违反「CLI 优先」「禁止旁路 Command pipeline」(直接 INSERT)与「测试与 demo 数据来源不一致」(E2E 自建数据 vs demo 靠脚本)。

本 doc 不解决"如何让本 PR 的两条页面有数据",那已经在 commit `33f95295` 用 Aura CLI 改完了。**本 doc 解决:平台层是否要把 seed 升格为 plugin import 的一等公民资源**,以及 — 如果要 — 语义如何定义。

## 2. 现状

`auraboot/platform/src/main/java/com/auraboot/framework/plugin/service/impl/PluginImportServiceImpl.java` 的 `resourceDirs` 已认识 16 个 key:

> models / fields / modelFieldBindings / dicts / commands / menus / permissions / roles / pages / namedQueries / agentDefinitions / savedViews / processes / dashboards / bindingRules(来自 `BindingRulesLoader`)/ rules(.drl,来自 `DroolsRuleLoader`)

**没有** `seed` / `fixtures` / `data` / `records` 任何一个 key。`default-bootstrap.json` 只跑 `rolePermissionBindings`(权限绑定),不是数据 seed。

## 3. 目标 / 非目标

### Scope (in)

- **D1**:plugin 包内携带"业务记录种子",import 时落库。
- **D2**:种子记录与 plugin 强相关(典型用例:demo 插件、template 插件、test fixture 插件)。
- **D3**:支持"幂等 import"——同一 plugin 多次 import 不产生重复或冲突。
- **D4**:支持"环境闸门"——production tenant 默认不导入 demo seed。

### Non-scope (out)

- **N1**:不替代生产数据迁移 / migration。租户业务数据由租户运维管理,不归插件管。
- **N2**:不替代 E2E 自建数据。E2E spec 仍按金标准 `beforeAll` 自建自验,不依赖 plugin seed(避免环境差异爆炸)。
- **N3**:不解决"跨租户共享数据"(那是 platform-admin 范畴)。

## 4. 概念词汇

| 术语 | 定义 | 例子 |
|------|------|------|
| **seed record** | 插件交付时携带、import 期落库的业务记录 | acp-showcase 的 7 条 safety rule、6 条 demo request |
| **fixture record** | 仅在 `AURA_ENV=test` 或 `IMPORT_TEST_FIXTURES=true` 下导入的测试专用记录 | `auraboot-enterprise/plugins/test-fixtures/**` |
| **bootstrap data** | 跨租户、平台启动期写入的系统级数据 | dicts、system roles。**不属于本 doc 范畴** |
| **seed manifest** | seed JSON 文件的格式契约 | 字段集见 §5.4 |

## 5. 三个核心语义问题

### 5.1 执行路径

每条 seed 记录怎么落到数据库?

| 方案 | 优 | 劣 | 适用 |
|------|----|----|------|
| **A. DB direct INSERT** | 快、简单、零耦合 | 绕开 autoSetFields(REQ-yyyyMMdd-seq 不生成)、validation、tenant_id 拦截器、唯一性约束、state machine | ❌ 否决:破坏 Studio Core 不变量 |
| **B. Command replay** | 复用现有 Command pipeline,所有 autoSet/validation/audit 自动生效 | seed 必须能映射到 `create_*` Command;某些 model 没 create command 时退化 | ✅ 推荐 |
| **C. Hybrid** | 默认 B,显式标 `bypassCommand=true` 时走 A(给只能 INSERT 的场景留 escape) | 复杂、用户会滥用 | 仅当 B 证明覆盖率不够再考虑 |

**推荐 B**。理由:平台已有「Studio Core schema-driven」红线,所有写入路径都该走 Command;seed 没理由破例。reads 用 NamedQuery 替代,writes 用 Command 替代,seed 只是"写入"的另一形态。

**反方 steel-man**:某些"系统注释字段"(`acs_req_grounding_result`、`acs_req_token_count` 这类执行回写字段)不在 `create_*` Command 的 inputFields 里,seed 就写不进去。**回应**:这类字段本来就由后端流程写,不该出现在 seed;如果 demo 想伪造"已执行过的请求",应该新增专用 `acs:seed_demo_request_with_history` Command 而不是绕过 pipeline。

### 5.2 幂等性

同一 plugin 第二次 import 时怎么处理已有 seed 记录?

| 方案 | 行为 | 风险 |
|------|------|------|
| **skip-on-key** | 按 seed JSON 里指定的 `idempotencyKey`(通常 = `code` 字段)查;有就跳过 | 用户后期改了 demo 数据,re-import 不会复原 |
| **overwrite** | 找到就 update,没有就 insert | 用户后期改的数据被抹 |
| **error** | 重复 import 直接失败 | 阻塞合理的 plugin 升级 |

**推荐 skip-on-key,以 `idempotencyKey` 字段作为 lookup,缺失时报错**。理由:plugin import 本质是「meta + 一次性 demo data」,update demo 是反模式;如果 plugin 作者真要刷新 demo,应该 bump 版本号 + 提供独立的 reseed Command,而不是靠 import 时静默 overwrite。

### 5.3 FK 解析

seed JSON 里如何引用其他 seed 记录的 PID(它们在 import 时才生成)?

```json
// demo_request 引用 user.pid — 但 user.pid 是 import 时才生成的
{ "acs_req_owner_id": "???" }
```

| 方案 | 例 | 评 |
|------|----|----|
| **lookup by code** | `"acs_req_owner_id": "@user.code:admin"` | 平台需提供 `@<model>.<lookupField>:<value>` 解析器,且 lookupField 必须是 unique key;最干净 |
| **explicit PID** | seed 里写死 PID | PID 跨环境会变;脆弱,否决 |
| **two-pass + symbolic ref** | 第一遍 insert 时记录 `_localId → pid` 映射;第二遍解析 `"acs_req_owner_id": {"$ref":"_localId/owner1"}` | 更通用但复杂 |

**推荐 lookup by code**。绝大多数 model 都有 `code` 或类似 unique business key;真碰到没有的(典型:thr_employee 用 `email`),通过 plugin manifest 配置 `lookupField: "email"` 覆盖。

## 6. 跨切问题

### 6.1 租户作用域

- import 必须在 tenant 上下文里跑(已有约束;`PluginImportService` 由 controller 调用,`@CurrentTenant` 拦截器已注入)。
- seed 落到当前 tenant。**禁止跨 tenant 写**。
- 第一次 plugin import 与租户初始化的顺序:租户先创建,再 import plugin;seed 走当前租户。

### 6.2 test-fixtures 闸门

`auraboot-enterprise/plugins/test-fixtures/**` 已有 `AURA_ENV=test` / `IMPORT_TEST_FIXTURES=true` 闸门。Seed 机制必须**继承同一闸门**:

- plugin manifest 增加 `seedProfile: "demo" | "test" | "always"` 字段。
- `demo`:默认 import,production tenant 关 demo flag 时跳过。
- `test`:仅 `AURA_ENV=test` 或 `IMPORT_TEST_FIXTURES=true` 时 import。
- `always`:无条件 import(留给极少数"meta-essential 业务记录"用例,如默认审批节点角色)。

### 6.3 失败回滚

seed 中途某条 Command 失败:

- **强制全事务**:整个 plugin import(metadata + seed)同一事务,失败整体回滚。
- **风险**:seed 体量大时事务过长;但单 plugin seed 一般 < 100 条,可接受。
- **逃生**:plugin manifest 标 `seedTransactional: false` 时改用 best-effort,部分失败的 seed 记录入 `ab_plugin_import_log` 让运维手动收尾。**默认 true**。

### 6.4 execution-only 字段

`acs_req_status` / `trigger_count` / `last_triggered_at` 这类由后端流程或 state machine 维护的字段:

- **禁止**直接出现在 seed JSON 里(校验器拦)。
- 想 seed 出"已提交"的 demo request → seed 完后串接 `state_transition` Command(走 §5.1 方案 B 的同一路径)。
- Manifest 里允许声明 `postSeedTransitions: [{"target": "@req.code:DEMO-001", "command": "acs:submit_request"}]`。

## 7. 推荐路线总览

| 维度 | 决策 |
|------|------|
| 执行路径 | Command replay(否决 DB direct) |
| 幂等性 | skip-on-`idempotencyKey`,缺失报错 |
| FK 解析 | `@<model>.<lookupField>:<value>`,lookupField 默认 `code`,manifest 可覆盖 |
| 闸门 | manifest `seedProfile: demo/test/always`, 继承既有 `AURA_ENV` / `IMPORT_TEST_FIXTURES` |
| 事务 | 默认全事务,manifest `seedTransactional: false` 退化 best-effort |
| 后处理 | manifest `postSeedTransitions` 声明 state-transition 序列 |

### Manifest 草案

```json
{
  "resourceDirs": {
    "seed": "config/seed"
  },
  "seedProfile": "demo",
  "seedTransactional": true
}
```

```json
// config/seed/acs_safety_rule.json
{
  "modelCode": "acs_safety_rule",
  "createCommand": "acs:create_safety_rule",
  "lookupField": "acs_rule_code",
  "records": [
    {
      "acs_rule_code": "GATE_L3_PLUS",
      "acs_rule_name": "Approval Gate (L3+ Risk)",
      "acs_rule_type": "approval_gate",
      "...": "..."
    }
  ],
  "postTransitions": []
}
```

## 8. 分阶段 milestone(粗粒度,不排时间)

| 阶段 | 内容 | DoD |
|------|------|-----|
| **M1** | DTO + resourceDirs key("seed") + manifest schema 扩展 | plugin.json schema 校验通过;import 期 seed 文件被识别但暂不执行 |
| **M2** | Command-replay 执行器 + skip-on-idempotency-key + lookup 解析器 | 单 model 单条 seed 端到端落库,带集成测试 |
| **M3** | 闸门(seedProfile + AURA_ENV)+ 全事务 + postTransitions | 三种 profile 行为一致;事务回滚验证;test-fixtures 闸门兼容 |
| **M4** | 迁移 acp-showcase 等示例插件,删除 `seed-*.sh` | 端到端 demo 不再依赖 manual 脚本;旧 shell scripts 清退 |

每个阶段单独 PR,按既有「单审阅者直推 main」模式合并。

## 9. Open questions(给 owner 拍板)

1. **seedProfile 默认值**:`demo` 还是 `test`?推荐 `demo`,但 `test` 更安全。
2. **lookupField 缺失时的行为**:报错 vs fallback to (code | id | first unique field)?推荐报错,但用户可能嫌烦。
3. **是否允许 enterprise overlay 插件覆盖 OSS 插件的 seed**?涉及 multi-repo 优先级,建议 §M3 之后单独讨论。
4. **是否要 CLI 命令 `aura plugin reseed <pluginId>`**?用于「插件已 import,只重跑 seed 部分」。低优先级。
5. **跨 tenant 同步**:platform_admin 操作"为所有租户 import 这个 plugin"时 seed 怎么办?推荐:platform_admin 必须显式声明每个 tenant 的 seedProfile,不假设。

---

## 参考

- 红线:`auraboot-enterprise/AGENTS.md` §「配置优先 + Studio Core Schema-driven」
- 现状:`auraboot/platform/src/main/java/com/auraboot/framework/plugin/service/impl/PluginImportServiceImpl.java` L290–450
- 触发故事:本 doc commit 上游 `33f95295`(seed 脚本 CLI 化)+ `378d6e22`(demo seed + E2E)
- AGENTS.md「架构问题先写 doc」:本 doc 即按此红线产出
