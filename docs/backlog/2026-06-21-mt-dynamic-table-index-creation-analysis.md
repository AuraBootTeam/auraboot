---
type: backlog
status: active
created: 2026-06-21
slug: mt-dynamic-table-index-creation-analysis
related:
  - docs/superpowers/specs/2026-06-21-site-key-registry-design.md
  - docs/backlog/2026-06-21-site-key-anonymous-telemetry-subsystem-decomposition.md
  - docs/retro/2026-06-21-site-key-registry-sp1-retro.md
---

# 动态 `mt_` 模型表加真实 DB 索引 — 机制来龙去脉与选型

> **为什么有这份文档**:SP2(匿名 ingestion)的硬前置是给 `mt_behavior_site_key.site_key` 加一条真实 DB 索引。SP1 实测发现"配置层声明 unique/searchable 在 `mt_` 动态表上不生成索引",把这条留给了 SP2。本文把这件事的**来龙去脉**讲清楚——为什么常规手段(配置层 / Flyway)都不行、平台到底有哪些索引设施、它们各自产出什么形状的索引、以及 SP2 应该走哪条路——作为 SP2 索引机制决策的依据,并沉淀为一条可复用的平台认知(任何"给动态模型字段加 DB 索引"的需求都会撞上同一组约束)。
>
> 本文只分析"索引怎么建",**不**涵盖 SP2 的 ingestion 路径 / 安全开放 / 滥用防护(那些进 SP2 spec)。

---

## 0. 用户场景:这一切在解决谁的什么问题

先抛开数据库,讲一个真实场景,索引问题会自然浮现。

**谁、想干什么**:我们的一个客户(租户,比如"杰佳科技")用我们的低代码平台搭了一个**对外公开、不用登录**的应用/网站(产品展示页、H5 落地页之类)。他想知道:每天多少访客?哪个页面看得多?哪个按钮被点得多?——也就是**网站流量分析**(类似 Google Analytics / 百度统计)。

**怎么统计**:访客在浏览时,他的浏览器要把"看了哪个页面、点了哪个按钮"这些**行为事件**发回我们服务器记下来,我们再聚合成 UV/PV 看板给客户看。

**核心难题**:访客是**匿名的**——没登录,请求里**没有任何"我属于哪个客户"的身份**。但我们是多租户平台,杰佳的访客数据只能进杰佳的账,绝不能和别的客户混。**服务器收到一条匿名事件,凭什么知道它是哪个客户的?**

**解法(owner 已定)= 站点密钥 site-key**,跟 Google Analytics 那个 `G-XXXXXXX` 测量 ID 一个套路:
1. 客户在后台建一个 key,我们生成一串 `abk_…`,他把它嵌进自己发布的网页里。
2. 访客浏览器发事件时带上这个 key。
3. 服务器拿 key 去查一张**注册表**(key → 哪个租户),把事件记到那个租户名下。
4. key 是公开的(嵌在网页里人人可见),安全**不靠它保密**,靠后面的限流 + 来源校验。

SP1 已经把"注册表 + 拿 key 查租户的服务(`resolveTenant`)"建好了。**SP2** 要打通的是真正那条公开管道:匿名访客发事件 → 按 key 入对应租户。

**索引问题从这里冒出来**:那个"拿 key 查租户"的动作,SP2 上线后会**极其高频**——公开网站每个访客每个动作都触发一次,且来自开放互联网。它查的就是 SP1 那张注册表(数据库里一张表)。数据库查一行,如果那一列**没有索引**,就得**一行行翻整张表**;表小没流量(SP1 现状)无所谓,但 SP2 公开后高频查,逐行翻就是又慢又烧 CPU,撑不住。所以**必须给 `site_key` 这列建索引**(像给字典加拼音目录,一翻就到),顺带这索引还得是**唯一**的,保证一个 key 全网只对应一个租户、不串台。

**而"建个索引"在我们这儿偏偏麻烦**,就因为这张注册表是低代码的**"动态表"**——不是手写死的表,是平台按配置在**运行时临时生成**的。下面 §1-§6 讲的就是:为什么常规建索引的几条路对动态表全不通,平台其实自带一个现成工具能正确解决,以及它和红线的相容性。读完技术细节后,§0 这个场景就是它要保住的东西:**公开网站的匿名访客流量,能快、能准、能隔离地入到正确客户的账上。**

---

## 1. 背景:SP2 为什么必须有这条索引

`SiteKeyRegistry.resolveTenant(siteKey)` 是匿名 `/api/collect` 的**未鉴权热路径**——每一条匿名采集事件进来,服务端都要用公开的 `abk_` key 反查它属于哪个 tenant。当前实现(SP1 交付):

```java
// SiteKeyRegistry.java — 跨租户解析,无 tenant_id 过滤
SELECT tenant_id FROM mt_behavior_site_key WHERE site_key = ? AND status = 'active' LIMIT 1
```

这条查询有两个对索引的诉求:

1. **性能(resolve 热路径)**:`WHERE site_key = ?` 在没有 `site_key` 索引时是顺序扫描(seq-scan)。SP1 无线上负载可接受,但 SP2 一旦开放公开端点,这是高频调用,seq-scan 不可接受。
2. **完整性(全局唯一)**:一个 `site_key` 必须**全局唯一**地映射到单一 tenant(见 §3),否则跨租户 resolve 二义。当前由 handler 应用层 `existsAnyTenant` 预检兜底,但**没有 DB 级唯一约束做 defense-in-depth**——并发创建、绕过 handler 的写入、未来的 bug 都可能破坏这个不变量。

所以 SP2 上线前**必须**有:一条 `site_key` 上的 **DB 级全局唯一索引**(一举满足上面两点)。

---

## 2. 核心难点:动态 `mt_` 表的索引为什么不能用常规手段

### 2.1 `mt_` 表是什么、何时建

`mt_behavior_site_key` 不是手写的平台表(`ab_*`),而是**动态模型**(dynamic model)在**运行时**由 `import-directory-sync` 导入插件时,经 `SchemaManagementServiceImpl.createTableByModel` 建出来的(`MetaModelServiceImpl:2173` 调用)。时间线关键点:

```
应用启动 → Flyway 跑 db/migration/V*.sql(此时 mt_behavior_site_key 不存在)
        → 插件 import-directory-sync(此时才 CREATE TABLE mt_behavior_site_key)
```

### 2.2 配置层 `constraints.unique` / `feature.searchable` 系统性失效(SP1 实测)

平台**有**一个多租户索引设施 `MultiTenantIndexManager.generateMultiTenantIndexDDLs(model)`,在建表时按字段的 `field.isUnique()` / `field.isSearchable()` 生成索引 DDL。但 SP1 实测(`\d mt_*` 真表)发现:

- 导入路径上 `mt_` 字段的 `feature` 列**未被持久化**,到建表生成索引时 `field.isUnique()/isSearchable()` 读到空;
- 结果:本栈 **9 个 `mt_` 表里 0 个**带 feature 驱动的 `_tenant_unique` / `_trgm` 索引(唯一一个 trgm 在内置 `ab_` 表上)。

即:**在 model/fields 配置里写 `unique:true` / `searchable:true`,对 `mt_` 表不产生任何 DB 索引**。这是本平台版本的一个能力缺口,非 SP1/SP2 引入。

### 2.3 Flyway migration 为什么不行

直觉做法是写一条 `V<date>__add_site_key_index.sql`。但 §2.1 的时间线决定了它在 **fresh DB 上引用不到表**:Flyway 在启动期跑,那时 `mt_behavior_site_key` 还没被插件 import 建出来,migration 里 `CREATE INDEX ... ON mt_behavior_site_key` 会因表不存在而失败(或用 `DO`/`IF EXISTS` 包成条件式后**静默跳过**——fresh DB 永远跳过,等于没加)。Flyway 管的是**静态平台 schema**(`ab_*`),不是运行时动态表。

### 2.4 启动期 `ensure/repair` 的红线张力

第二直觉是"应用启动后跑一段 ensure 把索引补上"。但这撞 AGENTS 两条红线:

- **§4.1 环境初始化单一写入口**:应用启动阶段**禁止**自动 repair/bootstrap 写库;reset/init 脚本与 `/api/bootstrap/setup` 才是初始化入口。
- **§8 禁自愈**:`ensureXxx()` 自愈模式被禁。

关键区分:这两条红线针对的是**数据层自愈**(补角色、补权限、补 seed、补菜单)。**幂等 schema 收敛(DDL)** 是否落入禁区,取决于"它是不是一段盲目的 startup repair"。本文 §5 的推荐方案把索引创建**挂在模型 import / 表创建的生命周期上**(而非一段独立的 startup repair runner),并复用平台**自带的幂等索引 API**,从而与红线相容(§6 详述)。

---

## 3. 关键纠错:全局 `UNIQUE(site_key)`,不是 `(tenant_id, site_key)`

SP1 的 spec / handover / 分解文档都把硬前置写成 **`(tenant_id, site_key)` 唯一索引**。这是**错的**,SP2 必须纠正:

### 3.1 resolve 是跨租户的,复合唯一既无用也二义

`resolveTenant` 的 `WHERE` 只有 `site_key`,**没有** `tenant_id`(公开请求此刻还没有 tenant 上下文,要靠这次查询才得到 tenant)。所以:

- `(tenant_id, site_key)` 复合索引**喂不动** `WHERE site_key=?` 的查询(前导列是 tenant_id,查询不带它)。
- 更糟:`(tenant_id, site_key)` 唯一**允许两个不同租户注册同一个 `site_key`**。一旦发生,`resolveTenant` 的 `LIMIT 1` 会任意挑一个 tenant → 跨租户串数据。

正确的不变量是 **`site_key` 全局唯一**(与 handler 的 `existsAnyTenant` 全局预检一致),对应 DB 约束 **`CREATE UNIQUE INDEX ... ON mt_behavior_site_key (site_key)`**——单列、全局、不带 tenant_id 前缀。它同时:① 强制全局唯一(完整性);② 直接喂 `WHERE site_key=?`(性能)。一条索引满足两个诉求。

### 3.2 平台有两套索引设施,产出形状不同 —— 这决定了选型

| 设施 | 触发 | 对"唯一"产出的形状 | 适配本需求? |
|------|------|---------------------|--------------|
| `MultiTenantIndexManager.generateMultiTenantIndexDDLs` | 建表时按 `field.isUnique()` | **强制** `CREATE UNIQUE INDEX ... (tenant_id, col)`(`MultiTenantIndexManager:44-53`,租户隔离原则总把 tenant_id 放前导列) | ❌ 产的是 `(tenant_id, site_key)`,正是 §3.1 错误形状 |
| `SchemaManagementService.createFieldIndex(modelCode, fieldCode, IndexType.UNIQUE)` | 显式调用 | `CREATE UNIQUE INDEX ... ON <table> (col)`(`generateCreateIndexDDL`,**列级、无 tenant 前缀**) | ✅ 正是 `UNIQUE(site_key)` 全局唯一 |

**这条对比直接淘汰了"修 import-path 根因"的方案**:即便把 §2.2 的 `feature` 持久化补好、让 `MultiTenantIndexManager` 正常工作,它对 unique 字段产出的也是 `(tenant_id, site_key)`——**错误形状**。要拿到正确的全局唯一,必须走 `createFieldIndex` 这条列级 API,而不是多租户索引设施。

---

## 4. 选项分析

| 选项 | 做法 | 产出索引形状 | 触发可靠性 | 平台改动面 | 红线相容 |
|------|------|--------------|------------|------------|----------|
| **A. 模型 import 生命周期钩子(复用 `createFieldIndex`)** | 一个小平台组件,在 site-key 模型表存在后调 `createFieldIndex("behavior_site_key","site_key",UNIQUE)`(幂等) | ✅ 全局 `UNIQUE(site_key)` | 每次 import / 启动都收敛,幂等 | 极小(无新 DDL 管道,无 import-path 改动) | ✅ 见 §6 |
| **B. 修 import-path 根因(让 `feature` 持久化 + 喂 `MultiTenantIndexManager`)** | 补 mt_ 字段 feature 持久化,使建表自动按 unique 建索引 | ❌ `(tenant_id, site_key)`(§3.2,错误形状) | 建表时 | 大(import-path,需全量回归) | — 形状已错,先天出局 |
| **C. reset/init 脚本拥有** | 把 `CREATE UNIQUE INDEX` DDL 加进 reset-and-init / bootstrap,在 import 后跑 | ✅ 可写对形状 | **弱**:已有/线上部署不重跑 init 就不生效;依赖人记得跑 | 零 Java | ✅(§4.1 init 入口),但生产可靠性弱 |

补充说明:

- **B 先天出局**——不是改动面大的问题,是它经过的设施(`MultiTenantIndexManager`)**只会**产 tenant 前缀的复合唯一,拿不到 §3 要的全局唯一。修根因是另一个有价值的平台 backlog(让配置层索引真正生效),但**不能**作为本需求的索引来源。
- **C 可行但弱**:符合 §4.1"init 单一入口",DDL 形状也能写对;但 production hot-path 的关键索引依赖"每次部署都重跑 init"才存在,对真实可用产品太脆——新环境、已有环境的增量升级都可能漏。
- **A 是 B 与 C 的最优合并**:用 §3.2 已验证能产出**正确形状**的平台 API(`createFieldIndex`),靠它**自带的幂等**(`indexExists` → 已存在即 no-op),挂在模型 import 后触发,从而"每次都收敛 + 形状正确 + 改动极小 + 不碰 import-path"。

---

## 5. 推荐:A — 复用 `createFieldIndex`,挂模型 import 后的幂等钩子

### 5.1 为什么 A 胜出

1. **形状正确**:`createFieldIndex(...UNIQUE)` 产出列级全局 `UNIQUE(site_key)`(§3.2 已读源码确认),正是 resolve 热路径 + 全局唯一完整性所需。
2. **幂等、可靠**:`createFieldIndex` 内置 `tableExists` / `columnExists` / `indexExists` 三重前检(`SchemaManagementServiceImpl.createFieldIndex`),已存在即返回 success(no-op)。每次启动/import 都安全收敛,fresh DB 与已有 DB 行为一致——补上了 C 的可靠性短板。
3. **改动面极小**:不新增 DDL 管道、不改 import-path、不动 `MultiTenantIndexManager`。只加一个监听"site-key 模型表已就绪"的小组件 + 一行 `createFieldIndex` 调用。
4. **不是平台级冒进**:只对 SP2 自己的模型负责,不改全平台动态模型的索引行为(那是 B 的范畴,留独立 backlog)。

### 5.2 落地接缝与**调用时机**(已取证,SP2 spec 承接)

核心问题:`createFieldIndex` 要求表已存在(内置 `tableExists` 守卫),所以索引收敛**必须在 `mt_behavior_site_key` 表被建出来之后**触发。而这张表在**插件 import 时**(`MetaModelServiceImpl:2173` → `createTableByModel`)才建。取证后确定**双触发**(都调同一个幂等 `createFieldIndex`,叠加安全):

**① 主触发 — 监听 `PluginImportCompletedEvent`(最贴生命周期,零 startup-repair 味道)**

- 平台在每次插件 import 成功后发布 `PluginImportCompletedEvent(source, tenantId, pluginCode)`(`PluginImportServiceImpl:1298`,`pluginCode` = 插件 manifest namespace)。site-key 插件 `plugin.json` 的 `namespace = "behavior"`。
- 现成先例:`agent/service/CapabilitySyncListener` 就是 `@Component` + `@EventListener public void onPluginImportCompleted(PluginImportCompletedEvent e)`,读 `e.getPluginCode()` 决定是否同步。SP2 **镜像**它。
- 设计:一个 `@Component` listener,`@EventListener` `PluginImportCompletedEvent`,当 `"behavior".equals(e.getPluginCode())` → 调 `createFieldIndex("behavior_site_key","site_key",IndexType.UNIQUE)`(幂等,已存在即 no-op)。
- **时机 = 每次 `behavior` 插件 import / re-import 完成的那一刻**:reset/init 的 import、bootstrap 首次 import、管理员后台手动 re-import 都覆盖。此刻表刚被确保建好,紧接着补索引——挂在 import 生命周期上,不是一段独立的 startup 数据 repair。

**② 兜底触发 — `ApplicationReadyEvent` 一次性幂等收敛(覆盖"老部署:表早已存在但本次部署未 re-import")**

- 主触发只在"有 import 发生"时点火。对于一个**已经跑过、表早已存在**的环境,部署 SP2 代码后若没有触发任何 re-import,主触发不会点火 → 索引补不上。
- 兜底:一个 `@EventListener(ApplicationReadyEvent.class)`,若 `tableExists("mt_behavior_site_key")` 则调同一个幂等 `createFieldIndex`;表不存在(真正 fresh、插件还没 import)则干净跳过,留给主触发在 import 时补。
- 这条是 production 健壮性兜底;§6 论证了它属"幂等 schema 收敛"而非 §4.1 禁止的"startup 数据 repair"(对象是索引 schema、靠 `indexExists` 幂等、限定单一模型、不写任何业务数据行)。

> 两触发同一幂等动作:fresh DB 走 ① 在 import 时建;已有 DB 走 ② 在 ready 时补;之后任何 re-import 走 ① no-op。无论哪条路径,系统稳态都保证索引存在。

- **目标 DDL**(由 `createFieldIndex` 生成,无需手写):`CREATE UNIQUE INDEX idx_mt_behavior_site_key_site_key_unique ON mt_behavior_site_key (site_key)`。
- **status 维度**:resolve 查 `status='active'`。是否再加 `(site_key, status)` 或 `WHERE status='active'` 的部分索引,SP2 按 explain 实测决定;**全局唯一必须建在 `site_key` 单列上**(否则唯一性允许同 key 不同 status 重复,破坏完整性),status 只作查询优化的附加索引,不并入唯一键。

### 5.3 不变量与 defense-in-depth 的分工

- **应用层**(SP1 已交付,保留):handler `existsAnyTenant` 跨租户预检 + 190-bit 随机 key + 唯一冲突重试 → 正常路径下不产生重复。
- **DB 层**(SP2 新增):`UNIQUE(site_key)` 全局唯一索引 → 兜住并发/绕过/bug,任何重复写入直接被 DB 拒绝(`DuplicateKeyException`),不依赖应用层不犯错。
- 两层都在,符合 SP1 retro"DB 约束本为 defense-in-depth"的定位。

---

## 6. 与 AGENTS 红线的相容性论证(§4.1 / §8)

推荐方案 A 涉及"应用运行期创建索引",必须论证它不违反 §4.1(禁启动期写库 repair)/ §8(禁 ensure 自愈):

1. **对象是 schema 不是数据**:§4.1 / §8 的靶子是**数据层自愈**——补角色、补 `role_permission`、补 seed、补菜单这类"症状层数据补齐"。索引是 schema 对象,创建它不是在补业务数据,不掩盖任何初始化顺序/配置缺失。
2. **形态是幂等收敛不是盲目 repair**:不是"发现缺数据就 backfill",而是"声明式地保证某模型表上存在某索引",靠平台自带 `indexExists` 幂等,语义等同 `CREATE INDEX IF NOT EXISTS`。它不会因为"数据缺失"去写任何行。
3. **挂生命周期、限定单一模型**:不引入通用的 startup `repairAll/ensureAll`,只对 `behavior_site_key` 一个平台原生模型收敛它的关键索引,作用域窄、可审计。
4. **替代方案更差**:Flyway 不可达(§2.3)、配置层失效(§2.2)、reset/init 生产不可靠(§4 C)。在"动态表 + 生产 hot-path 关键索引"这个具体约束下,挂生命周期的幂等索引收敛是唯一既正确又可靠的形态。

> 若 owner 认为即便如此也应避免任何运行期 DDL,退路是 **C(reset/init 拥有)** + 在部署 runbook 显式列出"import 后必跑索引收敛",并接受其生产可靠性弱于 A。本文推荐 A;最终由 owner 在 SP2 决策点拍板。

---

## 7. 待 SP2 spec 承接的实现要点与验证

**实现要点**(进 SP2 spec / plan):
1. 纠正索引语义为**全局 `UNIQUE(site_key)`**(非 `(tenant_id, site_key)`),并同步改 SP1 spec §9.1 / 分解文档 / handover 的措辞(标注"SP2 纠错")。
2. 走 **A**:小组件在 site-key 模型表就绪后幂等调 `createFieldIndex("behavior_site_key","site_key",IndexType.UNIQUE)`;build 第一步取证触发点(优先 model-table-created 事件,无则 `ApplicationReadyEvent` + `tableExists` 守卫)。
3. resolve 是否需 `status` 附加索引按 `EXPLAIN` 实测定,不并入唯一键。

**验证**(host-first 零 docker,SP2 golden 的一部分):
- 隔离栈 import site-key 插件 + 重启 → `\d mt_behavior_site_key` 确认 `idx_..._site_key_unique` 存在且为 UNIQUE、单列、无 tenant 前缀。
- 真栈插入两条同 `site_key` 不同 tenant → 第二条被 DB 拒(`DuplicateKeyException`),证明全局唯一生效。
- `EXPLAIN SELECT tenant_id FROM mt_behavior_site_key WHERE site_key=? AND status='active'` → Index Scan(非 Seq Scan),证明 resolve 走索引。
- 幂等:连续两次触发收敛,第二次 `indexExists` no-op,不报错。

---

## 8. 一句话沉淀(候选升 engineering-gotchas)

> 给**动态 `mt_` 模型表**字段加 DB 索引:配置层 `unique/searchable` 失效(0/9)、Flyway 够不到运行时建的表、启动期 data-repair 撞 §4.1/§8。正确姿势=用平台 `SchemaManagementService.createFieldIndex(model,field,IndexType)`(列级、**全局**、自带 `indexExists` 幂等),挂模型 import/表就绪生命周期收敛;**注意** `MultiTenantIndexManager` 对 unique 强制 `(tenant_id,col)` 前缀,跨租户解析的全局唯一键不能用它。
