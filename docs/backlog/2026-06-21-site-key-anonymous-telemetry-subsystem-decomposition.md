---
type: backlog
status: active
created: 2026-06-21
slug: site-key-anonymous-telemetry-subsystem-decomposition
related:
  - docs/backlog/2026-06-19-unified-telemetry-analytics-platform-architecture.md
  - docs/superpowers/specs/2026-06-21-behavior-sdk-uvpv-dashboard-design.md
  - docs/superpowers/specs/2026-06-21-site-key-registry-design.md
---

# Site-key 匿名遥测子系统 — 分解(sub-project 拆分 + 顺序)

> 父上下文:统一遥测 SoT §5.3 入口 (a) Web 行为采集 + §1.3。前序已交付:行为 SDK + UV/PV 看板(authenticated,PR #966 `9af7a2ee8`),其中**匿名 collect 被显式 defer**(spec §1.3)。本文把"匿名 collect"展开为它真实的体量:一个**多组件子系统**,而非单个 slice。

## 为什么是子系统,不是 slice

行为 SDK 当前埋的是 AuraBoot 管理控制台(登录门控,无匿名访客)。匿名 UV 只在**公开 surface**(已发布低代码应用 / 公开站点)被访问时才有消费方。难点不是 SDK,而是:**一个未鉴权请求如何解析它的事件属于哪个 tenant**。平台**无现成 site-key / public-app→tenant 机制**(已实证 grep)。owner 选定走 **公开 site-key→tenant 注册表**(GA `measurementId` 风格)。

**已就绪(零改动)**:`BehaviorEventMapper.overview` 的 UV 已是 `count(DISTINCT COALESCE(CAST(user_id AS text), anon_id))` —— 后端聚合**已**计 anon_id。`BehaviorEventInput` 已有 `anonId` 字段。缺的全在采集入口侧。

## 冻结的设计取舍(贯穿所有子项)

- **site-key 是公开非机密**(嵌在已发布应用 HTML / JS,任何人可见,等同 GA measurement id)。安全**不靠 key 保密**,靠:origin/referer 白名单 + 限流 + key 状态(可禁用)+ payload 上限 + 不回显敏感数据。
- **tenant 权威来自 key→tenant 映射**(服务端注册表),**绝不**信客户端自带的 tenant。
- **匿名入口与登录入口分离**:keyed-anonymous 请求不复用 JWT 鉴权;登录态 collect 保持现状(JWT→tenant/user)。两条路径同一 `BehaviorEvent` 模型、同一 store。
- **anon_id 客户端生成 + 持久**(cookie/localStorage),与登录 user_id 分层(SoT §2.0 session 两层同理);登录后切 user_id,不回填历史匿名行。

## Sub-project 拆分(按依赖序)

| # | 子项 | 交付 | 依赖 | 体量 |
|---|------|------|------|------|
| **SP1** | **site-key 注册表(基础)** | dynamic model `mt_behavior_site_key`(原生双 id:id 雪花 + pid ULID)+ hybrid `create` handler 服务端生成 key(逻辑镜像 `WebhookSecretService`)+ `SiteKeyRegistry.resolveTenant`(缓存)+ 权限码 `behavior.site_key.*` + **DSL 管理页(配置优先,非 React)**+ 真栈 golden | 无(基础)| 中 |
| **SP2** | **匿名 ingestion 路径** | `/api/collect` keyed-anonymous 分支(从 site-key 解析 tenant、不走 JWT、null-tenant 不再直接 401 而是走 key 解析)+ security 开放 keyed 路径 + 滥用防护基线(限流 / origin 校验 / payload 上限 / key 状态校验)+ 契约/真栈测试 | SP1 (`SiteKeyRegistry.resolveTenant`) | 中-大(滥用防护是重点)|
| **SP3** | **SDK 公开模式** | `@auraboot/track` init 接 `siteKey` + 生成持久 `anon_id`(cookie)+ 未登录时发 `siteKey`+`anonId`、登录时发 user_id;公开模式不依赖平台 ApiService(可独立部署到已发布应用)| SP2(keyed 端点契约)| 中 |
| **SP4** | **端到端 golden + 管理面收口** | 真浏览器 golden:模拟已发布应用页(带 siteKey)→ 匿名采集 → 按 key 入对应 tenant → 该 tenant 看板 UV 计匿名访客;多 key/多 tenant 隔离断言;key 禁用即停采证据;管理页 golden | SP1-3 | 中 |

**滥用防护**(限流 / origin / 配额)是 SP2 的核心而非附属——公开未鉴权端点没有它就是 DDoS / 脏数据入口。若 SP2 过大可再拆 SP2a(keyed 解析 + 基础校验)/ SP2b(限流 + origin allowlist + 配额),但默认 SP2 含基线防护。

> **SP1 build 实测补充(2026-06-21,SP1 已交付):** SP1 走 dynamic model + DSL + platform `@Component` handler 落地(详见 SP1 spec §9.1)。实测发现**配置层无法给 `mt_` 动态模型表加 `site_key` 唯一/检索索引**(本平台版本对 `mt_` 表 0/9 有 feature 驱动索引)。SP1 已用 handler 跨租户预检保证唯一性,但 **`site_key` DB 索引是 SP2 的硬前置**:SP2 建匿名 ingestion 热路径(高频 `resolveTenant`)前必须加索引,否则 seq-scan;并复核全局唯一性兜底。
>
> **🔧 SP2 纠错(2026-06-21,已实现)**:本段原写 `(tenant_id, site_key)` 唯一索引 —— **错**。`resolveTenant` 跨租户(`WHERE site_key=?`,不带 tenant_id),复合唯一既喂不动查询又允许两租户同 key 串台。正确是**全局 `UNIQUE(site_key)` 单列**(SP2 用平台 `createFieldIndex` 落地,索引名 `uk_mt_behavior_site_key_site_key`)。详见 `docs/backlog/2026-06-21-mt-dynamic-table-index-creation-analysis.md` §3。

## 执行顺序与会话边界

SP1 → SP2 → SP3 → SP4,严格依赖序(每个产出 production-ready 纵深切片)。**每个 SP 适合独立 fresh 会话**(各自 brainstorm→spec→plan→subagent build→golden→merge)。本轮只产出本分解 + **SP1 的 design spec**(`docs/superpowers/specs/2026-06-21-site-key-registry-design.md`),build 留 fresh 会话。

## 非目标(本子系统不做)

- 不做 §5.4 完整 UI 元素身份治理 / Kafka 解耦 / outbox(那是遥测平台其它独立线,见 SoT §12)。
- 不做跨 tenant 的全局匿名聚合(每个 key 严格归属单一 tenant)。
- SP1 不碰 `/api/collect` 与 SDK(那是 SP2/SP3);SP1 只交付注册表 + 管理面 + resolve 服务。
