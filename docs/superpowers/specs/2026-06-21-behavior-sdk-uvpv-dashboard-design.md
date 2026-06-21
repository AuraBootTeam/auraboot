---
type: plan-design
status: active
created: 2026-06-21
slug: behavior-sdk-uvpv-dashboard
related:
  - docs/backlog/2026-06-19-unified-telemetry-analytics-platform-architecture.md
---

# 行为采集 SDK + UV/PV 看板 — 设计方案(窄而深首个纵切)

> 上游 SoT(冻结契约):`docs/backlog/2026-06-19-unified-telemetry-analytics-platform-architecture.md`(行为分析域 B,§5)。本 spec 是 SoT §12 "M1 行为采集底座" 剩余前端切片的实现设计。

## 1. 背景与范围

### 1.1 已 golden 的后端(实证)
| 能力 | 触点 | 状态 |
|---|---|---|
| 采集端点 `POST /api/collect` | `BehaviorCollectController` / `BehaviorCollectService` | golden(obs-golden-64) |
| 事件存储 `ab_behavior_event` | `V20260620000200__behavior_event_store.sql`(event-first 信封:event_name/category、`ui_element_id` 稳定键、interaction_id/caused_by、trace_id、props jsonb)+ 幂等 unique(tenant_id,event_id) | golden |
| 分析 API `GET /api/analytics/behavior/{overview,top-events,daily}` | `BehaviorAnalyticsController` → `BehaviorOverview{totalEvents,pageViews,uniqueVisitors,sessions}` / `List<BehaviorEventCount>` / `List<BehaviorDailyPoint>` | golden(obs-golden-65) |

数据进得来、查得出 —— 只缺"有浏览器在发、有看板在看"。

### 1.2 本切片范围(窄而深,production-ready 非 MVP)
端到端纵切打通,纵深完整、真浏览器 golden 绿:

```
浏览器(AdminLayout)→ @aura/track 自动采 pageview + click
   → 批量 fetch+keepalive(带 Bearer)→ POST /api/collect(服务端补 tenant/user)
   → ab_behavior_event
   → GET /api/analytics/behavior/{overview,top-events}
   → DSL UV/PV 看板(kind:detail workbench 页,KPI 卡 + top-events 表,零 React)
```

**UV 语义(已与 owner 锁定)**:UV = 去重登录用户。本切片**不生成 anon_id、不做匿名 collect**;golden 用 ≥2 个登录用户证 UV>1。匿名访客 UV 留到"给已发布低代码应用埋点"那一轮。

### 1.3 显式 defer 清单(写明,不假装做了)
- §5.4 完整 UI 元素身份治理(registry / `identity_quality` 提升 / `augment`·`replace` 声明式语义事件)
- autocapture 隐私 **治理 UI**(安全基线本轮即落地,见 §4.4;治理面板留后续)
- Kafka 解耦层(`aura.behavior.events.v1`)
- 服务端 outcome publisher(outbox)
- 匿名 / 未登录 `/api/collect`
- 趋势 / 留存 / sankey / realtime widget(`/daily` 数据已有,但 `SmartBarChart`/`SmartLineChart` 缺 `type:'api'` 分支,留趋势图那轮一并补)

## 2. 架构与组件边界

每个 unit 单一职责、接口清晰、可独立理解与测试:

| Unit | 做什么 | 怎么用 | 依赖 |
|---|---|---|---|
| `@aura/track`(新包 `web-admin/packages/track`) | 自动采 pageview/click,构造 §5.5 信封子集,批量缓冲并发送 | `track.init({ post, getSessionId })` + 自动监听 | 平台 ApiService(传输)、`data-aura-element-id`(身份) |
| ApiService keepalive 扩展 | 给现有 ApiService 加 `keepalive` 选项,unload 时可靠发送且带 Bearer | `apiService.post(url, body, { keepalive:true })` | 现有 ApiService(`Authorization: Bearer`)|
| BlockRenderer 身份打标 | 渲染块/字段时打 `data-aura-element-id`(从 `block.id`+`fieldCode` 派生) | DOM 属性,被 SDK click 监听读取 | 现有 BlockRenderer |
| SDK 接线 | 在 AdminLayout 初始化 SDK、路由变化触发 pageview | `web-admin/app/routes/AdminLayout.tsx` | `@aura/track` |
| analytics API reshape | 3 端点返回对齐平台标准 chart-api 契约 `Result<{records:[...]}>` | 后端 controller/service | `BehaviorAnalyticsController` |
| UV/PV 看板 | `kind:detail` workbench 页,chart 块消费 analytics API | DSL JSON(import-directory-sync)| `ChartBlockRenderer`/`SmartNumberCard`/`SmartTableChart` |

## 3. 数据流与契约

### 3.1 客户端事件信封(§5.5 子集,本切片)
```jsonc
{
  "schema_version": 1,
  "event_id": "<client ULID,重试不重生成>",
  "event_name": "page_view | element_click",
  "event_category": "navigation | ui_interaction",
  "occurred_at": "<ISO8601,客户端时钟>",
  "client_session_id": "<sessionStorage 持久,驱动 sessions 指标>",
  "ui_element": {                       // 仅 element_click 必填
    "definition_id": "<ui_element_id,取自 data-aura-element-id>",
    "page_id": "...", "block_id": "...", "element_code": "...",
    "identity_source": "dsl | heuristic",
    "identity_quality": "stable | heuristic"
  },
  "props": { "route_template": "<清洗后路由模板>" }  // 见 §4.4 隐私基线
}
```
`tenant_id` / `user_id` / `received_at` 由服务端从 auth context 补全(本切片不发 anon_id)。

### 3.2 analytics API reshape(seam 决策)
`SmartNumberCard` 的 api 分支(`SmartNumberCard.tsx`)自取 url,`fetchResult<{records?,rows?}>` + `ResultHelper.isSuccess`,再 `getValue()=Number(rows[0][metricField])`;若 `rows[0]` 多字段且未设 `cards`,自动渲染多指标卡。`SmartTableChart` api 分支同读 `records/rows`。

→ 3 个端点须对齐**标准 chart-api 契约**:
```jsonc
// GET /api/analytics/behavior/overview
{ "code": 0, "data": { "records": [ { "pageViews": N, "uniqueVisitors": N, "sessions": N, "totalEvents": N } ] } }
// GET /api/analytics/behavior/top-events
{ "code": 0, "data": { "records": [ { "eventName": "...", "count": N }, ... ] } }
```
现状是裸 `BehaviorOverview` 对象 / 裸 `List`。这 3 端点是 M1 新建、本看板是唯一消费方,直接 reshape 干净。**连带:obs-golden-65 的 overview 断言 shape 同步更新。**

### 3.3 看板 DSL(kind:detail workbench 页)
- 页 `kind:detail`、`schemaVersion=4`;菜单 path `/p/c/{pageKey}`(standalone 自定义页路由)。
- 页级 `dataSources`(plural):`ds_behavior_overview`(`type:'api'`, url `/api/analytics/behavior/overview`)、`ds_top_events`(`type:'api'`, url `/api/analytics/behavior/top-events`)。
- KPI 卡块:**4 张单指标卡**(PV / UV / sessions / total),各 `{ "blockType":"chart", "chartType":"number-card", "dataSource":"ds_behavior_overview", "chartConfig":{ "metricField":"pageViews|uniqueVisitors|sessions|totalEvents" } }` —— golden 逐卡断言更清晰(多指标合并卡为可选形态,本切片不用)。
- top-events 表块:`{ "blockType":"chart", "chartType":"table", "dataSource":"ds_top_events" }`(或 `blockType:"table"` + 表 dataSource)。
- 本切片**不上 bar/line 趋势**(避开 `SmartBarChart/LineChart` 无 `type:'api'` 分支的平台 gap)。

## 4. 关键设计点

### 4.1 SDK 传输 = fetch + keepalive(非 sendBeacon)
鉴权是 `Authorization: Bearer ${token}`(`ApiService.ts` `setAuthToken`)。`navigator.sendBeacon` **不能设自定义 header** → beacon 请求丢鉴权 → 服务端补不到 tenant/user。故传输用 `fetch(url, { method:'POST', headers:{Authorization}, keepalive:true })`:既能带 header,又在 `pagehide`/`visibilitychange→hidden` 时可靠发送。**实现 = 复用平台 ApiService(已自动挂 Bearer + baseURL + proxy),给它加 `keepalive` 透传**,SDK 不自存 token。
- keepalive payload 上限 64KB → 批量大小封顶 + 超限分批。

### 4.2 看板页类型 = kind:detail(非 kind:dashboard)
`PageSchemaValidator` 只接受 list/form/detail 三种 importable kind("dashboard/composite have no plugin-page renderer")。看板做成 `kind:detail` workbench-pattern 页(顶部 metric 卡 + 表),走 `ChartBlockRenderer`(透传解析后 dataSource 给 Smart chart,不限制 `type`)。

### 4.3 元素身份(轻量杠杆,非手搓 selector)
`BlockRenderer` 渲染时打 `data-aura-element-id`(`block.id`+`fieldCode` 派生)。click SDK 就近取最近祖先该属性作 `ui_element_id`,`identity_source=dsl`、`identity_quality=stable`;取不到则 `heuristic`(只采安全属性,见 §4.4)。完整 registry / 提升治理留后续。

### 4.4 🔒 隐私基线(不可 defer)
即使治理 UI 留后续,采集侧安全基线本轮即硬编码:
- **绝不采**:input/textarea value、innerHTML、完整 textContent、完整 href/query、未登记 `data-*`、全量 class、record/content id。
- **只采**:`ui_element_id`、tag、role、allowlist 内 aria、清洗后 route template(剥查询参数与路径 id 段 → 模板化)。

## 5. 错误处理
- SDK 发送失败:有界重试队列(指数退避),`event_id` 客户端固定、重试不重生成(配合服务端 `unique(tenant_id,event_id)` 幂等);队列超界丢最旧并计数(不阻塞页面)。
- analytics API 失败:看板 chart 走组件自身 empty/error 态(`ChartEmptyState`),不白屏。
- SDK 初始化失败 / token 缺失:静默降级为不采集(不抛、不打断 app);不做自愈 ensure。

## 6. 测试策略(host-first 零 docker)
| 层 | 覆盖 | 工具 |
|---|---|---|
| SDK 单测 | 信封构造、批量/flush 时机、keepalive 发送、隐私脱敏(断言绝不含 value/innerHTML)、元素 id 派生(dsl vs heuristic)、ULID 重试不重生成 | vitest |
| 后端单测/IT | analytics reshape 后 `{records:[...]}` shape、collect 幂等 | 真栈 IT |
| DSL validator | 看板页 `import-directory-sync` 返 `success:true`(page-golden-audit 不替代) | 平台 validator |
| 真浏览器 golden | 全链路:≥2 登录用户浏览 → SDK 自动发 → 断言 `ab_behavior_event` 行(DB)→ 打开看板 → 断言 KPI 卡 PV/**UV>1**/sessions 与 DB 逐字段一致 + top-events 表有行;真 DOM 断言、不 skip、每步截图 | `dev.sh runtime` bootRun + host Vite/BFF + Playwright 自带 chromium + auth.setup |

## 7. 验收标准(golden 断言,逐条可证)
1. 真浏览器中两个不同登录用户各浏览 ≥2 个页面并点击 ≥1 个 DSL 元素。
2. `ab_behavior_event` 出现对应 page_view + element_click 行,`ui_element_id` 非空(stable),tenant/user 已补全。
3. 看板 KPI 卡:PV = DB 计数、**UV = 2**、sessions = DB 计数(逐字段相等)。
4. top-events 表渲染真实事件名 + 计数,无 raw code 泄漏。
5. 隐私断言:`ab_behavior_event.props` 中不含任何输入值 / innerHTML / 完整 href。
6. 0 console error / exprError。

## 8. 自我 Review 修正记录(动手前拍死的坑)
| # | 初版 | 实测发现(代码出处) | 修正 |
|---|---|---|---|
| 1 | SDK 用 `sendBeacon` | 鉴权 `Authorization: Bearer`(`ApiService.ts` setAuthToken),sendBeacon 不能设 header → 丢鉴权 | 改 fetch+keepalive+Bearer(§4.1) |
| 2 | "新 DSL Dashboard 页" | `kind:dashboard` 不可导入(`PageSchemaValidator` 只收 list/form/detail) | 改 kind:detail workbench 页(§4.2) |
| 3 | KPI 卡 type:'api' 能用? | `SmartNumberCard.getValue()=Number(rows[0][metricField])` ✅;`ChartBlockRenderer` 透传 dataSource ✅ | seam 成立:reshape API 为 `{records:[...]}`,零平台 gap(§3.2) |

## 9. 仓库与收口
- 全部 OSS(`auraboot`):platform(后端 reshape)+ web-admin(SDK / BlockRenderer / 看板 DSL)。
- feature 分支 `feat/behavior-sdk-dashboard-m1` + worktree `/Users/ghj/work/auraboot/auraboot-behavior-sdk-dashboard`(**不写 canonical main**)。
- 收口:本地门禁绿(check-*.sh + DSL validator + golden)→ PR → squash merge → worktree `MERGED_AND_DELETED`。
