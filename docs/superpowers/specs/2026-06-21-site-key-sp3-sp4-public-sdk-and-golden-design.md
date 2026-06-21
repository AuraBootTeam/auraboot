---
type: plan-design
status: active
created: 2026-06-21
slug: site-key-sp3-sp4-public-sdk-and-golden
related:
  - docs/backlog/2026-06-21-site-key-anonymous-telemetry-subsystem-decomposition.md
  - docs/superpowers/specs/2026-06-21-site-key-anonymous-ingestion-sp2-design.md
  - docs/handover/HANDOVER-2026-06-21-site-key-sp2-anonymous-ingestion.md
---

# 匿名遥测子系统 SP3 + SP4 — 公开 SDK 模式 + 端到端 golden(设计方案)

> 子系统分解见 `docs/backlog/2026-06-21-site-key-anonymous-telemetry-subsystem-decomposition.md`。
> **SP1(注册表)MERGED #984** → `SiteKeyRegistry.resolveTenant`。
> **SP2(匿名 ingestion)MERGED #995** → `POST /api/collect/keyed`(header `X-Site-Key`,body `{events}`,resp `{accepted}`,403/429/400)+ `SiteKeyIndexInitializer`(双触发建全局 `uk_mt_behavior_site_key_site_key`)。
> 本方案合并交付 **SP3(SDK 公开模式)**+ **SP4(端到端 golden + 管理面收口)**。owner 指令「全部推进」,两 SP 同一会话连做(分解文档原建议各自 fresh session,owner 已覆盖)。

## 0. 用户场景(承接 SP2)

客户用低代码搭了**对外公开、不用登录**的应用并部署到自己域名。匿名访客浏览时,页面里嵌的 `@auraboot/track` 公开 SDK 用客户拿到的公开 `site_key`(GA `measurementId` 同款)把行为事件发回我们的采集端点;服务器据 key 反查租户、落库;该租户的行为看板把匿名访客计入 UV。SP3 = 把客户端这一段(SDK 公开模式)做出来并可嵌入已发布应用;SP4 = 真浏览器端到端证明整条链路 + 多 key/tenant 隔离 + 禁用即停采 + 真 plugin import→索引活路径(SP2 IT 未覆盖)。

## 1. SP3 — SDK 公开模式

### 1.1 现状(grep 实证,2026-06-21)

`@auraboot/track`(`web-admin/packages/track/`)已交付**鉴权态** SDK:
- `createTracker({post, getSessionId, endpoint='/api/collect', batchSize})`(`src/tracker.ts`)——批量 + `visibilitychange`/`pagehide` flush + keepalive;`post` 是注入的 HTTP 函数。
- `buildEvent`(`src/envelope.ts`)产出扁平 `BehaviorEventInput` 信封;**当前信封无 `anonId` 字段**(服务端 `BehaviorEventInput.java` 有)。
- 鉴权态接线在 `web-admin/app/shared/services/trackerInstance.ts`:`post` 来自平台 `~/shared/services/http-client`(带 JWT/cookie),`getClientSessionId` 走 `sessionStorage`。
- 包是 workspace-internal ESM(`main: ./index.ts`),无独立构建产物;测试走 web-admin 的 vitest。

**缺口(SP3 补)**:① 信封缺 `anonId`;② 无公开模式入口(不依赖平台 `http-client`/JWT);③ 无 `anon_id` 客户端生成/持久化;④ 无可嵌入已发布应用的独立构建产物(script 标签)。

### 1.2 锁定的实现决策(owner 已冻结大方向,以下为实现级取舍)

| # | 决策 | 选定 | 理由 |
|---|------|------|------|
| E1 | `anonId` 注入机制 | `createTracker` 增 **可选** `getAnonId?: () => string`;有则每条事件信封带 `anonId`,无则不带 | 鉴权态路径零改动(无 getAnonId → 无 anonId 字段,服务端按 JWT 填 userId);公开态供 getAnonId |
| E2 | 公开模式入口 | 包内新增 `createPublicTracker({ siteKey, collectUrl?, batchSize? })`(`src/public.ts`),返回 `Tracker` | 复用 `createTracker` 内核;**零平台依赖**(裸 `fetch`,非 `http-client`),可独立部署 |
| E3 | 采集端点 | `collectUrl` 默认 `/api/collect/keyed`,可传**绝对 URL**(跨域部署) | 已发布应用在客户域名,跨域调我们的 keyed 端点;SP2 已开 CORS。相对 URL 用于同源/代理场景(golden 走 BFF 代理) |
| E4 | `site_key` 传递 | header `X-Site-Key`(对齐 SP2 契约) | SP2 controller 读 `@RequestHeader("X-Site-Key")` |
| E5 | `anon_id` 持久化 | **cookie 主**(`_aura_anon`,1 年,`SameSite=Lax`,first-party 到已发布应用域名)+ **localStorage 兜底**(cookie 被禁时) | GA 同款;cookie 跨标签页稳定;localStorage 兜底无 cookie 环境。**注入式存储**(`AnonIdStore` 接口)便于单测 |
| E6 | `client_session_id` | `sessionStorage`(`_aura_sid`,session 作用域)+ `crypto.randomUUID()`/`generateEventId()` 兜底 | 镜像现有 `getClientSessionId`,分层于 anonId(SoT §2.0 两层)|
| E7 | `anon_id` 生成 | `crypto.randomUUID()` 优先,fallback `generateEventId()`(已有 ULID) | 无新依赖 |
| E8 | 可嵌入构建 | 新增 global/IIFE 入口 `src/global.ts` 暴露 `window.AuraTrack.init({siteKey, collectUrl})`;esbuild(vite 传递依赖)产 `dist/aura-track.global.js` + sourcemap;`pnpm --filter @auraboot/track build` | script 标签即可嵌入已发布应用;无打包器要求 |
| E9 | 公开态身份语义 | 公开模式**恒匿名**(siteKey + anonId,userId 永远 null) | 已发布应用的访客对我们的遥测是匿名的;"登录发 userId" 指的是**鉴权态管理控制台**那条既有路径(`/api/collect` + JWT),非本模式。文档明确两条路径边界 |

### 1.3 组件与边界(SP3)

```
已发布应用页(客户域名)
  <script src=".../aura-track.global.js"></script>
  <script>AuraTrack.init({ siteKey: 'abk_…' })</script>
        │
        ▼  createPublicTracker({ siteKey, collectUrl }):
        │    - anonId  = AnonIdStore.get()   (cookie _aura_anon || localStorage || 新生成并写回)
        │    - sid     = SessionStore.get()  (sessionStorage _aura_sid || 新生成)
        │    - post    = fetch(collectUrl, { method:POST, headers:{'X-Site-Key':siteKey,
        │                  'Content-Type':'application/json'}, body, keepalive })
        │    - tracker = createTracker({ post, getSessionId:()=>sid, getAnonId:()=>anonId,
        │                  endpoint: collectUrl })
        ▼
  POST /api/collect/keyed   Header X-Site-Key: abk_…   Body { events:[{…, anonId}] }
        ▼  (SP2:resolve tenant → guard → recordAnonymous(tenant, userId=null, anonId))
  ab_behavior_event  ──→  既有 UV 聚合(COALESCE(user_id, anon_id))计匿名访客
```

- **不碰鉴权态**:`createTracker` 仅**新增可选** `getAnonId`;`trackerInstance.ts` 不传 → 行为不变(回归断言)。
- **零平台依赖**:`createPublicTracker` 不 import `~/shared/*` / axios / JWT;只用浏览器原生 `fetch`/`document.cookie`/`localStorage`/`sessionStorage`。

### 1.4 SP3 测试策略(vitest,host-first)

| 层 | 覆盖 |
|----|------|
| 单测(envelope)| `buildEvent` 带 `anonId` 时信封含该字段;不带时字段 undefined |
| 单测(tracker)| `createTracker` 有 `getAnonId` → 每条事件 `anonId` 注入;无 `getAnonId` → 无 `anonId`(鉴权态回归) |
| 单测(public)| ① `createPublicTracker` post 打 `/api/collect/keyed`(默认)/ 传入 collectUrl;② 带 `X-Site-Key: <siteKey>` header + keepalive;③ 事件含稳定 `anonId`(两次 pageview 同 anonId);④ anonId 持久化:首次写 cookie+localStorage,二次读回同值;cookie 被禁 → localStorage 兜底;⑤ sessionId 走 sessionStorage;⑥ 零平台依赖(纯 fetch mock) |
| 构建 smoke | `pnpm --filter @auraboot/track build` 产 `dist/aura-track.global.js`,IIFE 暴露 `AuraTrack.init`(node 加载断言全局存在 / 文件非空 + 含 `X-Site-Key`) |

## 2. SP4 — 端到端 golden + 管理面收口

### 2.1 golden 栈(host-first 零 docker)

复用 `scripts/oss-golden-stack.sh up <name> --slot N`(隔离 runtime,backend `AURA_BUILTIN_PLUGINS_DIR=plugins`,minimal bootstrap)。**关键**:bring-up 后必须真 import `plugins/core-site-key`(config),使 `ab_meta_model` 有 `behavior_site_key` 行 + `mt_behavior_site_key` 表存在,从而触发 `SiteKeyIndexInitializer.onPluginImportCompleted`/`onApplicationReady` → `createFieldIndex` 建真索引。**这是 SP2 IT 未覆盖的活路径**(bare DB 无模型注册,SP2 IT 只验索引 artifact)。

### 2.2 golden 覆盖矩阵(真浏览器 + DB 反查)

| # | 断言 | 证据 |
|---|------|------|
| G1 | **真 import 活路径**:import core-site-key → 索引 `uk_mt_behavior_site_key_site_key` 真存在(UNIQUE/单列/无 tenant 前缀) | `\d mt_behavior_site_key` / `pg_indexes` |
| G2 | **匿名采集落正确租户**:已发布应用 fixture 页加载公开 SDK(siteKey A)→ pageview+click → SDK POST keyed → 事件落租户 A、`user_id IS NULL`、`anon_id` = 客户端值 | 浏览器 + `ab_behavior_event` 反查 |
| G3 | **多 key/tenant 隔离**:siteKey B(租户 B)只入 B,A 的事件不串到 B | 两租户 site_key + DB 分组计数 |
| G4 | **禁用即停采**:disable siteKey A → SDK POST 收 403、0 行新增 | 浏览器 network + DB 计数不变 |
| G5 | **看板计匿名 UV**:登录租户 A 管理台 → `/p/c/behavior_analytics` → UV 卡计入匿名访客(anon_id 作为 distinct 身份);插第二个 distinct anon_id → UV+1 | KPI 卡 DOM 读数(非 grep)|
| G6 | **CORS**:跨域 OPTIONS 预检 `/api/collect/keyed` 返允许头(对齐已发布应用跨域调用) | curl 预检响应头 |
| G7 | **管理面收口 golden**:site-key 列表页创建 key → 列表出现 → 详情显示 key + origin_allowlist → disable → 状态变 disabled(SP1 已有 `site-key-registry.golden.spec.ts`,SP4 复核其仍绿,不重复造) | 复用 SP1 golden 复跑 |

> "已发布应用 fixture 页":golden 在 Vite 源起一个**静态 fixture HTML**(`web-admin/tests/fixtures/published-app.html` 或 `page.setContent` + `addScriptTag` 注入 `dist/aura-track.global.js`),公开 SDK `collectUrl` 走相对 `/api/collect/keyed`(BFF 代理 → 同源,免测内 CORS 摩擦);CORS 跨域单独用 curl 预检证(G6)。

### 2.3 SP4 完成判定

- [ ] G1-G6 真栈全绿(真浏览器 + DB 反查 + curl);截图存档每步。
- [ ] G7 SP1 管理面 golden 复跑绿。
- [ ] 0 product console error。
- [ ] 真 import→initializer→createFieldIndex 活路径首次被端到端覆盖(补 SP2 缺口)。

## 3. 交付与收口

- 一个 feature 分支 `feat/site-key-sp3-sp4-anonymous-sdk`,两个逻辑 commit(SP3 SDK / SP4 golden),一个 OSS PR(SP3+SP4 是一个内聚的"匿名遥测客户端 + 端到端证明"交付)。
- 静态门禁:`check-oss-boundary` / `validate-permission-codes`(本 SP 不新增权限码,验无漂移)/ `check-jsonb-typehandler`(若读 origin_allowlist,SP2 已处理)。
- 收口:memory「统一遥测与分析平台」条目更新 SP3/SP4 MERGED + 下一步;handover + retro;若有新坑升 canonical。

## 4.5 SP4 真栈 golden 抓出的真 bug(SP2 IT 够不到的活路径)

SP4 第一次真 import → `SiteKeyIndexInitializer` → `createFieldIndex` 端到端跑通,当场抓出 **3 个 production bug**(全部已在本 PR 修复 + 回归覆盖):

| # | 现象 | 根因 | 修复 | 回归 |
|---|------|------|------|------|
| B1 | import 后全局唯一索引**没建**(只剩默认 tenant-prefixed 索引);日志 `createFieldIndex` 抛 `Table does not exist` | import 用 `@EventListener`(同步)在**未提交的 import 事务内**发事件,`createFieldIndex` 的表存在性检查走另一连接看不到未提交的 `CREATE TABLE` | 改 `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)`——提交后表可见、请求线程的 tenant context 仍在 | SP4 AK-00(真 import 后断言索引)+ `SiteKeyIndexInitializerTest` |
| B2 | app-ready backstop 每次启动 `ERROR: Tenant context is required but not found`,索引永不收敛 | 启动主线程无 MetaContext,`createFieldIndex→getModelDefinition` 的日志路径读 tenant id 抛异常 | backstop 先查模型 owning tenant 设入 `MetaContext`、`finally` clear | `SiteKeyIndexInitializerTest`(owning-tenant 设置 + 无 tenant 跳过)+ 实测 drop 索引重启自愈 |
| B3 | 跨域 published-app 调 `/api/collect/keyed` preflight **403**(公开 SDK 跨域形同虚设) | 全局 `/api/**` CORS 只放行 admin 自家 origin + 不含 `X-Site-Key` 头 + allowCredentials | 为该公开端点单独注册 GA 式 CORS(任意 origin、POST/OPTIONS、`X-Site-Key`、无凭据),registered before `/api/**` | SP4 AK-01 + `KeyedCollectIT.corsPreflightAllowsPublicCrossOrigin` |

> 印证 AGENTS §2.2「组件间 seam 须 assembled-product 运行时门禁」+ §15「悲观/乐观结论先实证」:`compileJava`+单测+SP2 in-process IT 全绿,但真 import 活路径三处坏。SP2 IT pitfall #5 已诚实标注「live path deferred to SP4」,SP4 兑现并补 3 个 bug。

## 4.6 收尾硬化 / 留作 follow-up

- [x] **(已修)`recordBatch` 单事件健壮性**:原先单条事件违反列约束(如 client `eventId` > `varchar(40)`,misbehaving/hostile 客户端)抛 `DataIntegrityViolationException` → **整批 500**(且已插入的事件部分落库,不一致)。公开未鉴权端点不应因单条脏输入 500。修:`recordBatch` 在 `DuplicateKeyException` 之外**再 catch `DataIntegrityViolationException` 逐事件跳过**(对齐既有「缺 eventId/eventName 跳过」契约),整批不再 fatal、有效事件照落。回归:`KeyedCollectIT.oversizedEvent_skippedNotBatchFatal`(2 事件批:1 有效+1 超长 → 200/accepted:1/有效落库·超长不落)。两条路径共用 `recordBatch` 故鉴权态同样受益。
- controller-authz 门禁报 `BehaviorCollectController`(鉴权态 `/api/collect`,#966)/ `TestImBroadcastController` 未 baseline——**非本 PR 引入**(origin/main 既存),留 owner 决定 baseline 或加注解。

## 4. 非目标

- 不做鉴权态 SDK app 接线改动(已 #966 交付)。
- 不做 Redis 跨节点分布式限流(SP2 hardening follow-up)。
- 不做 Kafka 解耦 / outbox / §5.4 UI 元素身份治理(遥测平台独立线)。
- 公开 SDK 不做客户端采样 / consent UI(后续线;信封已有 consent 字段位)。
