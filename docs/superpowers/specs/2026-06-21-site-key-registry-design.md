---
type: plan-design
status: active
created: 2026-06-21
slug: site-key-registry
related:
  - docs/backlog/2026-06-21-site-key-anonymous-telemetry-subsystem-decomposition.md
  - docs/backlog/2026-06-19-unified-telemetry-analytics-platform-architecture.md
---

# Site-key 注册表 — 设计方案(匿名遥测子系统 SP1)

> 子系统分解见 `docs/backlog/2026-06-21-site-key-anonymous-telemetry-subsystem-decomposition.md`。**SP1 = 基础注册表**:其它子项(SP2 匿名 ingestion / SP3 SDK 公开模式 / SP4 golden)全依赖本子项的 `SiteKeyRegistry.resolveTenant`。SP1 **不碰** `/api/collect` 与 SDK。

## 1. 背景与范围

匿名 `/api/collect` 需要"未鉴权请求 → 它属于哪个 tenant"的权威解析。owner 选定 **公开 site-key→tenant 注册表**(GA `measurementId` 风格)。SP1 交付这个注册表:存储 + 生成 + 解析 + 租户范围管理面 + 权限,**到此为止**——让 SP2 能 `resolveTenant(siteKey)`,让租户管理员能建/列/禁用自己的 key。

**已实证就绪(不在本范围)**:UV 聚合已计 anon_id(`BehaviorEventMapper:22`);`BehaviorEventInput.anonId` 已存在;`ab_behavior_event` store 已存在。

### 1.1 冻结取舍(继承自分解文档)
- **site-key 公开非机密**(嵌已发布应用,人人可见)。安全不靠保密。
- **tenant 权威 = 服务端 key→tenant 映射**,绝不信客户端自带 tenant。
- key **服务端生成、不可猜、带前缀**;创建后不可改 tenant 归属(改归属 = 新建 key)。

## 2. 组件与边界

| Unit | 做什么 | 怎么用 | 依赖 |
|------|--------|--------|------|
| `mt_behavior_site_key`(dynamic model)| 持久 key→tenant 映射 + 元数据 + 状态;**免费 DSL CRUD + 原生双 id**(id 雪花 + pid ULID)| DSL model+fields config | 平台 dynamic model |
| hybrid `create` 命令 handler(`behavior_site_key:create`)| 服务端生成 `site_key`(用户不可输)+ 校验后落库 | DSL 表单命令触发 | PF4J handler;**生成逻辑镜像 `connector/airflow/secret/WebhookSecretService`**(已有的生成-密钥服务;差异:site-key 公开,无需 mask)|
| `SiteKeyRegistry`(服务)| `resolveTenant(siteKey)→Long\|empty`(带缓存)· disable(状态命令)| SP2 调 `resolveTenant` | 查 `mt_behavior_site_key` + 缓存 evict |
| DSL 管理页(配置优先,**非 React**)| list(key 列可见)/ form(仅填 name)/ detail / disable,租户范围 | import-directory-sync | dynamic model + 命令 |
| 权限码 `behavior.site_key.{read,create,manage}` | bootstrap 注册 | model 权限 + `@RequirePermission` | permission 命名门禁 |

## 3. 数据模型 — dynamic model `mt_behavior_site_key`(DSL-first,原生双 id)

site-key 是干净 CRUD(列/建/禁用)+ 公开非机密,故走 **dynamic model**(免费 DSL CRUD),不手写平台表。dynamic model **原生双 id**:`id`(雪花 `@TableId(ASSIGN_ID)`)+ `pid`(`VARCHAR(26)` ULID 公开 id,管理 API/行操作用它)——与 `ab_report`/`agent_eval_case`/`PromotionUnit` 同约定。

字段(model + fields config):
| 字段 | 类型 | 说明 |
|------|------|------|
| `id` / `pid` | 平台原生 | 双 id:id 雪花内部主键 + pid ULID 公开记录 id |
| `tenant_id` | 平台原生 | 行级租户归属(dynamic data 自动 scope)|
| `site_key` | string(40),**unique** | 公开遥测 key(`abk_…`),**服务端生成**(用户不可输);SP2 按它 resolve tenant |
| `name` | string(120) | 人读:哪个已发布应用 |
| `status` | dict(active/disabled) | 状态,disable 用状态命令 |
| `origin_allowlist` | json/text[] | SP2 用:允许 origin;**本子项只存不强制** |

- `site_key` **唯一**索引支撑 O(1) resolve。**双 id 与 site_key 是三个不同标识**:id(雪花内部)/ pid(ULID 记录公开 id,平台 CRUD 用)/ site_key(`abk_` 遥测公开 key,嵌应用)。
- **server-gen-on-create**:create 不让用户填 site_key,由 hybrid `create` 命令 handler 生成(§4)。build 确认 dynamic model 能否对 custom field 加 unique 约束 + server-set;不行则退一步平台表 + 注册 model。

## 4. key 生成与解析

- **格式**:前缀 + 不可猜随机,例 `abk_<24 字符 base62>`(`abk_` 便于日志/grep 识别;base62 来自 `SecureRandom`)。公开非机密,但仍要不可猜(防枚举刷别人 tenant 的脏数据——真正拦截在 SP2 origin+限流,但 key 不可猜是第一层)。
- **生成**:hybrid `behavior_site_key:create` handler 在落库前用 `SecureRandom` 生成 `abk_…` 写入 `site_key`(用户表单**不含** site_key 输入),唯一冲突重试。
- **解析**:`resolveTenant(siteKey)` 查 `status='active'` 行 → tenant_id;**缓存活跃 key→tenant**(SP2 的未鉴权热路径,要快);disable 时 evict。未命中 / disabled → `Optional.empty()`(SP2 据此拒绝,**不自愈、不 fallback**)。
- **禁用 vs 吊销**:disable=可恢复(status→disabled);revoke=终态(status→disabled + revoked_at,不可恢复)。rotate = 新建一个 + 旧的 disable(客户端换 key 后再 revoke 旧的)。

## 5. 管理面(DSL 配置优先 — §7)

site-key **公开非机密** → key 就是个**普通可见列**(不像 webhook secret 需"只回显一次"的特殊处理),管理就是干净 CRUD,**走 DSL,不写 React**(§7 配置优先):
- **list 页**:dynamic model 列表,列含 `site_key`(公开,可直接复制)/ name / status(dict 渲染 active·disabled)/ pid。
- **form 页**:create 只填 `name`(+ 可选 origin)——**不含 site_key 输入**(服务端生成,§4);提交走 `behavior_site_key:create` 命令。
- **detail 页**:看单 key + 复制 site_key + disable 行动点(状态命令)。
- 全部 DSL(schemaVersion=4,kind list/form/detail),import-directory-sync `success:true`;`url`+`endpoint` seam、`/p/c/{pageKey}` 路由等照行为看板 slice 教训。
- 唯一"特殊"= server-generate-on-create,**用平台能力(hybrid 命令 handler,§4)解决,不用 React 绕过**;若 DSL 确缺某项渲染(如生成后高亮新 key),先补 custom block/平台能力再消费,仍走配置优先。
- 不泄漏 raw code;状态语义色。

## 6. 权限与安全

- 权限码 `behavior.site_key.read` / `.create` / `.manage`(`module.resource.action`,门禁 `validate-permission-codes.mjs`),bootstrap 注册;Controller `@RequirePermission`;deny=403 真栈 IT 三件套(CustomUserDetails / evictUserPermissions / MockMvc deny)。
- 管理面**严格租户范围**:管理员只能看/管自己 tenant 的 key(`resolveTenant` 跨租户解析是 SP2 内部受信调用,不经管理面)。
- 创建结果回显明文 key 是**有意**(key 公开);但 list 也可回显(非机密)——与 API secret 不同,无需 mask。

## 7. 错误处理

- 生成唯一冲突 → 事务内重试(有界);超界抛错不自愈。
- `resolveTenant` 未命中/disabled → `Optional.empty()`(调用方决策),**不**抛、不建默认 key、不 ensure。
- 管理 API 非法输入(空 name)→ 字段级 400,不泛化 toast。

## 8. 测试策略(host-first 零 docker)

| 层 | 覆盖 |
|----|------|
| 单测 | key 生成格式/不可猜/唯一重试;`resolveTenant` 命中/未命中/disabled/缓存 evict |
| 真栈 IT | create→DB 行→resolve 返对应 tenant;disable 后 resolve empty;**跨租户隔离**(A 租户管理员看不到 B 的 key);`@RequirePermission` deny=403 三件套 |
| DSL validator | 管理页(list/form/detail)import-directory-sync `success:true` |
| 真浏览器 golden | 管理员建 key(仅填 name)→列表出现含生成的 site_key 列→复制→禁用→状态变 disabled;真 DOM 断言、无 raw code 泄漏、0 console error |

## 9. 留给 SP1 build 会话确认的开放点

- **🔴 dynamic model 能否满足约束(build 第一步验证)**:① custom field `site_key` 加 **unique 约束** + 快索引;② create 时 **server-set**(hybrid handler 生成,用户不可输)。两者若 dynamic model 支持 → 走 dynamic model + DSL(本 spec 主路径,§7 配置优先)。若不支持 → 退一步**平台表 `ab_behavior_site_key`(仍带双 id:id 雪花 + pid ULID)+ 注册为 model 让 DSL CRUD 消费**,仍不写 React。
- **key 长度/字母表**:`abk_` + 24 base62(`SecureRandom`),build 时定。
- **resolve 热路径**:SP2 未鉴权高频调 `resolveTenant`,SP1 即建缓存 + site_key 唯一索引。
- **JSONB 雷**:`origin_allowlist` 若 jsonb + MyBatis 读写,注意 `JsonbStringTypeHandler`(本仓高频坑,跑 `scripts/check-jsonb-typehandler.sh`)——SP1 只存不读,可先 text[]/jsonb,SP2 用时再定。

## 9.1 Build 实测结论与决策(2026-06-21,取证后定稿)

> §15 纪律:以下推翻了 build 前 spike 的 code-reading 推断,以**真实导入 + 真栈 golden 实测**为准。

- **✅ server-set on create — 走平台 `@Component` handler,非 PF4J 插件 jar**:`SiteKeyCommandHandler`(platform `behavior/sitekey/`)`implements CommandHandlerExtension`,`getCommandType()=behavior_site_key:create`/`disable`,`requiresDslPersistence()=false`(镜像已发布的 `AgentApprovalCommandHandler`)→ 跳过默认 field-map insert,handler 用 `ctx.dataAccessor().create` 注入服务端生成的 `abk_` key。**决策理由**:行为遥测本就 platform-native(`BehaviorCollectService`/`ab_behavior_event`/UV 聚合全在 platform),且 `SiteKeyRegistry.resolveTenant` 必须 platform-side 供 SP2 的 `/api/collect` 调用;config-only 插件(`plugins/core-site-key`)只承载 model/fields/dict/commands/pages/permissions/menu,无 backend jar。命令**不声明 `handler` 字段**,故 import 不触发 `S-EXT-HANDLER`(`ExtensionValidator` 仅在显式 `handler` 时校验),运行时由 `ExtensionRegistry.getCommandHandler` 按 `supports()` 命中。实测:真栈命令管道 create→DB(`active`+server-gen key)、disable→DB(`disabled`)、deny=403(只读用户无写)。
- **🔴 site_key 的 DB 级唯一索引 — dynamic model 配置层做不到(实测推翻 spike)**:config 层 `constraints.unique:true` / `feature.searchable` **在本平台版本对 `mt_` 动态模型表系统性失效**——字段 `feature` 列在导入路径不被持久化(到表索引生成时 `field.isUnique()/isSearchable()` 读到空),实测本栈 **9 个 `mt_` 表 0 个**有 `_tenant_unique` 或 `_trgm` 索引(唯一的 1 个 trgm 在内置 `ab_` 表)。故 `mt_behavior_site_key.site_key` **无 DB 唯一索引、无 site_key 专用索引**。
  - **决策:保留 dynamic model**(功能完整:CRUD/命令/双 id/jsonb/DSL 页/权限全部真栈验证通过),**不**退平台表 `ab_behavior_site_key`——§9 的平台表 fallback 假设“注册成 model 即免费 DSL CRUD”,实测该路径需 `skipTableCreation` 模型 + `kind:detail`(非 `kind:list`)+ 自定义读端点(AGENTS gotcha),复杂度/风险更高且削弱 DSL-first,得不偿失。
  - **唯一性**:由 `SiteKeyCommandHandler` 在 create 前 `registry.existsAnyTenant(key)` 跨租户预检 + 重试保证(190-bit 随机 key 实际碰撞概率 ~0),DB 约束本为 defense-in-depth。
  - **🟡 SP2 前置(必做,backlog)**:**全局 `UNIQUE(site_key)` 单列索引**留 **SP2**(其建匿名 ingestion 热路径时加;`resolveTenant` 目前对小表 seq-scan,SP1 无线上负载可接受,SP2 上线前必须加索引)。属平台 dynamic-model 索引能力缺口,**不在本 feature PR 修平台**(避免大面 import-path 改动)。
    > **🔧 SP2 纠错(2026-06-21,已实现)**:此处原写 `(tenant_id, site_key)` 唯一 —— 错(resolve 跨租户,复合唯一喂不动查询且允许跨租户同 key 串台)。SP2 已用平台 `createFieldIndex` 落地全局 `UNIQUE(site_key)`(`uk_mt_behavior_site_key_site_key`),见 `docs/backlog/2026-06-21-mt-dynamic-table-index-creation-analysis.md`。
- **key 字母表定稿**:`abk_` + **32 base62**(`SecureRandom.nextInt` 无偏),~190 bit;`site_key` 字段 `maxLength:64`。
- **created_at 不可作页面字段**:系统字段(`created_at`/`id`/`pid`)自动建表但**不进 model 绑定**,DSL 页引用为显示列/字段会被 `S-PAGE-FIELD-REF` 拒(validator 实测抓到);仅可用于 `defaultSort`。

## 10. 非目标(SP2/3/4,不在 SP1)

- `/api/collect` keyed-anonymous 分支 + security 开放 + 滥用防护(限流/origin/payload)= **SP2**。
- SDK 公开模式(siteKey init + anon_id cookie)= **SP3**。
- 端到端匿名采集 golden = **SP4**。
- `origin_allowlist` 本子项只存不强制(SP2 强制)。
