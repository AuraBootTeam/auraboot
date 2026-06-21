---
type: plan-design
status: active
created: 2026-06-21
slug: site-key-anonymous-ingestion-sp2
related:
  - docs/superpowers/specs/2026-06-21-site-key-registry-design.md
  - docs/backlog/2026-06-21-site-key-anonymous-telemetry-subsystem-decomposition.md
  - docs/backlog/2026-06-21-mt-dynamic-table-index-creation-analysis.md
  - docs/retro/2026-06-21-site-key-registry-sp1-retro.md
---

# 匿名遥测子系统 SP2 — 匿名 ingestion 路径(设计方案)

> 子系统分解见 `docs/backlog/2026-06-21-site-key-anonymous-telemetry-subsystem-decomposition.md`。**SP1(注册表)已 MERGED #984**,交付 `SiteKeyRegistry.resolveTenant`。**SP2** 在其上打通公开未鉴权的匿名采集管道:公开站点的匿名访客发事件 → 按 `site_key` resolve 到对应租户 → 落 `ab_behavior_event` → 该租户看板计入匿名 UV。SP2 **不碰** SDK(SP3)与端到端浏览器 golden(SP4)。

## 0. 用户场景(承接 SP1)

客户(租户)用低代码搭了**对外公开、不用登录**的应用。匿名访客浏览时,浏览器把行为事件发回我们服务器。访客无身份,服务器靠访客带来的公开 `site_key`(GA 测量 ID 同款,SP1 已可生成/管理)反查它属于哪个租户,把事件记到那个租户名下。SP2 = 把这条公开管道真正打通,并为这个**未鉴权、面向开放互联网**的入口配齐滥用防护。详细场景见索引分析文档 §0。

## 1. 锁定的设计决策(brainstorm 已与 owner 对齐)

| # | 决策 | 选定 | 理由 |
|---|------|------|------|
| D1 | 匿名入口端点形态 | **独立白名单端点 `POST /api/collect/keyed`** | `JwtAuthenticationFilter` 是 fail-closed(无 `Authorization` 直接 401),整段白名单化 `/api/collect` 会让登录态采集也跳过 filter、`MetaContext` 不再填充、**破坏现有 M1 采集**。独立端点对齐 AGENTS「匿名入口与登录入口分离」,复用 `/api/ext/*/public/**` 的 public+限流先例。登录态 `/api/collect` 原样不动。 |
| D2 | `site_key` DB 索引语义 | **全局 `UNIQUE(site_key)` 单列**(非 `(tenant_id, site_key)`) | resolve 跨租户、不带 tenant_id;复合唯一喂不动查询且允许两租户同 key 串台。详见索引分析文档 §3。 |
| D3 | 索引创建机制 | **Option A:复用平台 `SchemaManagementService.createFieldIndex(...UNIQUE)`,双触发幂等收敛** | 配置层失效 / Flyway 够不到运行时建的动态表 / 启动期 data-repair 撞红线;`createFieldIndex` 产出列级全局唯一 + 自带幂等。详见索引分析文档 §4-§6。 |
| D4 | 滥用防护深度 | **一轮做满基线** | 公开未鉴权端点没有防护就是 DDoS / 脏数据入口。基线 = 限流 + origin 校验 + payload 上限 + key 状态校验(§5)。 |

## 2. 组件与边界

| Unit | 做什么 | 怎么用 | 依赖 |
|------|--------|--------|------|
| `BehaviorKeyedCollectController`(`POST /api/collect/keyed`) | 接匿名批量事件;从 header 取 `site_key`、resolve tenant、跑防护、委托落库 | 公开端点,白名单 | `SiteKeyRegistry` + `BehaviorCollectService` + `KeyedCollectGuard` |
| `KeyedCollectGuard`(防护编排) | 按序跑:key resolve → key 状态 → origin allowlist → 限流(key+IP)→ payload 上限;任一不过即拒(对应 403/429/400/413),**不自愈不 fallback** | controller 调 | `SiteKeyRegistry` + `SiteKeyOriginPolicy` + `ApiRateLimiter` |
| `SiteKeyOriginPolicy` | 读 key 的 `origin_allowlist`(SP1 已存,SP2 强制),校验请求 `Origin`/`Referer`;allowlist 空=不限制(owner 未配视为放开,记审计) | guard 调 | 查 `mt_behavior_site_key.origin_allowlist`(缓存) |
| `BehaviorCollectService.recordAnonymous(events, tenantId)`(新方法) | 用**传入的 tenantId**(来自 key,非 `MetaContext`)+ `userId=null` + 客户端 `anonId` 落库;复用现有 `toEntity`/幂等 | controller 调 | `BehaviorEventMapper`(现有) |
| `SiteKeyIndexInitializer`(Option A 双触发) | `@EventListener PluginImportCompletedEvent`(pluginCode=`behavior`)+ `ApplicationReadyEvent` 兜底,幂等调 `createFieldIndex("behavior_site_key","site_key",UNIQUE)` | 平台启动/import 自动 | `SchemaManagementService`(现有) |
| `WhiteList` 增 `/api/collect/keyed` + CORS | 开放未鉴权 + 允许跨域 POST | security 配置 | — |

> **边界**:`recordAnonymous` 与现有 `record` 共用 `toEntity` + 幂等(`(tenant_id, event_id)` 唯一),仅 tenant 来源不同(key vs JWT)、user 不同(null vs JWT user)。两条采集路径**同一 `BehaviorEvent` 模型、同一 store**(分解文档冻结取舍)。

## 3. 数据流

```
访客浏览器(公开应用,嵌 site_key)
  └─ POST /api/collect/keyed   Header: X-Site-Key: abk_…   Body: { events:[...] }
       │  (白名单 → 跳过 JwtAuthenticationFilter,无 MetaContext)
       ▼
  BehaviorKeyedCollectController
       │
       ▼  KeyedCollectGuard.check(siteKey, request, body):
       │    1. tenantId = SiteKeyRegistry.resolveTenant(siteKey)   未命中/disabled → 403
       │    2. SiteKeyOriginPolicy.check(siteKey, Origin/Referer)  不在 allowlist → 403
       │    3. ApiRateLimiter.isAllowed("collect:key:"+siteKey, MAX_PER_KEY)  超 → 429
       │       ApiRateLimiter.isAllowed("collect:ip:"+clientIp, MAX_PER_IP)   超 → 429
       │    4. payload 上限:events.size ≤ MAX_BATCH、单 event props 序列化 ≤ MAX_PROPS、body ≤ MAX_BODY  超 → 413/400
       ▼
  BehaviorCollectService.recordAnonymous(events, tenantId)
       │   tenant=key 解析所得;user=null;anonId=客户端带;幂等 (tenant_id,event_id)
       ▼
  ab_behavior_event  ──→  既有 UV 聚合(已计 anon_id,BehaviorEventMapper:22)
```

## 4. 索引(D3 / Option A)— 见索引分析文档,这里只列落地

- `SiteKeyIndexInitializer`(`@Component`):
  - `@EventListener` `PluginImportCompletedEvent`:`"behavior".equals(e.getPluginCode())` → `createFieldIndex("behavior_site_key","site_key",IndexType.UNIQUE)`(镜像 `CapabilitySyncListener`)。
  - `@EventListener` `ApplicationReadyEvent`:`tableExists("mt_behavior_site_key")` 才调同一幂等方法(兜底老部署)。
- 产出:`CREATE UNIQUE INDEX uk_mt_behavior_site_key_site_key ON mt_behavior_site_key (site_key)`(全局、单列、自带 `indexExists` 幂等;索引名由平台 `generateIndexName` = `uk_<table>_<col>` 生成)。
- resolve 是否加 `status` 附加索引按 `EXPLAIN` 实测;**唯一键只在 `site_key` 单列**。
- 同步纠正 SP1 spec §9.1 / 分解文档 / handover 把 `(tenant_id, site_key)` 措辞改为全局 `UNIQUE(site_key)`(标 SP2 纠错)。

## 5. 滥用防护基线(D4)

| 维度 | 做法 | 失败响应 | 默认阈值(build 可调,记 audit) |
|------|------|----------|-------------------------------|
| key 解析 | `resolveTenant` 未命中/disabled | 403 `site_key_invalid` | — |
| key 状态 | resolve 已只取 `status='active'`;disable 即 evict 缓存 | 403 | — |
| origin allowlist | `SiteKeyOriginPolicy` 校验 `Origin`/`Referer` 是否在 key 的 `origin_allowlist`;空 allowlist=放开(记审计) | 403 `origin_not_allowed` | 默认空=放开 |
| 限流(per-key) | `ApiRateLimiter.isAllowed("collect:key:"+siteKey, MAX_PER_KEY)` | 429 `rate_limited` | MAX_PER_KEY=600/min |
| 限流(per-IP) | `ApiRateLimiter.isAllowed("collect:ip:"+clientIp, MAX_PER_IP)`;clientIp 取 `X-Forwarded-For` 首段→fallback remoteAddr | 429 | MAX_PER_IP=300/min |
| 批量上限 | `events.size() ≤ MAX_BATCH` | 400 `batch_too_large` | MAX_BATCH=50 |
| payload 上限 | 单 event `props` 序列化字节 ≤ MAX_PROPS;整 body ≤ MAX_BODY(Spring `maxRequestSize`/手测) | 413 `payload_too_large` | MAX_PROPS=16KB / MAX_BODY=256KB |

- **限流实现说明(诚实标注)**:`ApiRateLimiter` 是**进程内单节点**滑动窗口(平台 login 也用它)。多节点部署下每节点独立计数,等效阈值放大 N 倍。SP2 基线**先镜像它**;**Redis 后端跨节点限流**列为 hardening follow-up(见 §10),不在 SP2 基线强求(避免过度建设;owner「做满基线」= 配齐上述 6 维,非要求分布式限流)。
- **不自愈**:任一维度失败直接拒,不补 key、不放过、不降级。错误体走平台 `ApiResponse` 风格,字段级原因码(非泛化 toast)。

## 6. 安全开放与 CORS

- `WhiteList.whiteList` 增 `"/api/collect/keyed"`(精确路径,非 `/**`);`JwtAuthenticationFilter.shouldNotFilter` 自动据此跳过 → 未鉴权可达。
- **CORS**:该端点被客户公开站点**跨域**调用。`SecurityConfig` OPTIONS `/**` 已 permitAll;build 第一步确认 `corsConfigurationSource` 允许对 `/api/collect/keyed` 的跨域 `POST`(简单/预检请求都要过)。真正的 origin 闸门在**应用层** `SiteKeyOriginPolicy`(按 key allowlist),CORS 层对该公开端点放宽是预期(对齐 GA 式公开采集)。
- **登录态 `/api/collect` 原样不动**:仍 `authenticated()`,JWT→tenant 路径零改动(回归断言)。

## 7. 错误处理(§8 禁自愈)

- 未知/disabled key → 403,不建默认 key、不 ensure。
- origin 不匹配 → 403。
- 超限 → 429。
- 超量 → 400/413。
- 单条 event 字段不全(无 eventId/eventName)→ 跳过该条(沿用现有 `record` 的 skip-malformed),不整批失败;但**批量级**上限(size/body)是硬拒。
- guard 不吞异常;`recordAnonymous` 的 `DuplicateKeyException` 视为幂等接受(沿用现有)。

## 8. 测试策略(host-first 零 docker)

| 层 | 覆盖 |
|----|------|
| 单测 | `KeyedCollectGuard` 各维度(key 未命中/ disabled / origin 不匹配 / 超限 / 超量 → 对应拒);`SiteKeyOriginPolicy`(allowlist 命中/不命中/空=放开);`recordAnonymous`(tenant 来自参数、user=null、anonId 透传、幂等) |
| 真栈 IT(真 PG) | ① keyed POST 有效 key → 事件落 `ab_behavior_event`,tenant=key 对应、user_id=null、anon_id=客户端值;② 未知 key→403、0 行写入;③ disabled key→403;④ **跨租户隔离**:A 的 key 只入 A;⑤ 索引:import behavior 插件→`\d` 见 `idx_..._site_key_unique`(UNIQUE/单列/无 tenant 前缀);插两条同 key 不同 tenant→第二条 `DuplicateKeyException`;`EXPLAIN` resolve 走 Index Scan;⑥ 幂等:连续两次触发 initializer 第二次 no-op;⑦ 限流:同 key 超 MAX_PER_KEY→429;⑧ 回归:登录态 `/api/collect` 仍 200 且 tenant 来自 JWT(白名单未误伤) |
| 契约 | keyed 端点请求/响应 shape(header `X-Site-Key`、body `{events}`、响应 `{accepted}` / 错误码体) |
| 静态门禁 | `check-jsonb-typehandler`(origin_allowlist 若读 jsonb)、`check-oss-boundary`、`validate-permission-codes`(本 SP 不新增权限码,确认无漂移) |

> 浏览器 golden(模拟已发布应用页带 siteKey 真实匿名采集)= **SP4**;SP2 用 API 真栈 IT 证明管道与隔离。SP3 SDK 落地后由 SP4 端到端串。

## 9. 非目标(不在 SP2)

- SDK 公开模式(`@auraboot/track` 接 siteKey + anon_id cookie)= **SP3**。
- 端到端浏览器匿名采集 golden = **SP4**。
- Redis 跨节点分布式限流 = hardening follow-up(§10)。
- Kafka 解耦 ingestion / outbox = 遥测平台独立线(SoT §12)。

## 10. 留给 build 确认/承接的开放点

1. **site_key 传参位置**:header `X-Site-Key`(推荐,beacon/fetch 易带、不污染 body schema)vs body 字段。build 定稿(倾向 header)。
2. **clientIp 取值**:`X-Forwarded-For` 首段 → fallback `request.getRemoteAddr()`;确认线上反代是否可信注入 XFF。
3. **限流阈值**:§5 默认值 build 复核(按预期单页事件频率),记 audit;Redis 后端列 follow-up。
4. **CORS**:确认 `corsConfigurationSource` 对公开端点的跨域 POST 行为(§6),必要时为该路径单独放宽。
5. **payload 上限实现**:`props` 字节在 `recordAnonymous` 内测;body 上限优先用 Spring multipart/`maxRequestSize` 或手测 content-length。
6. **origin_allowlist 读取**:SP1 存为 jsonb/text[];`SiteKeyOriginPolicy` 读取注意 `JsonbColumns.toJsonText` 解包(本仓高频坑),build 跑 `check-jsonb-typehandler.sh`。

## 11. 交付定义(SP2 完成判定)

- [ ] `POST /api/collect/keyed` 白名单可达、跨域可调;匿名事件按 key 入对应租户、user=null、anon_id 透传。
- [ ] 滥用防护 6 维全部生效(单测 + 真栈 IT 各拒一次)。
- [ ] `idx_..._site_key_unique` 全局唯一索引真栈存在 + 全局唯一生效(DuplicateKey)+ resolve 走 Index Scan + 幂等 no-op。
- [ ] 登录态 `/api/collect` 回归 200、tenant 来自 JWT(零破坏)。
- [ ] SP1 文档 `(tenant_id, site_key)` 措辞纠正为全局 `UNIQUE(site_key)`。
- [ ] host-first 零 docker 全绿;静态门禁绿。
