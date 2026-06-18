---
type: backlog
status: active
created: 2026-06-18
owner: agent-quality-campaign
related:
  - docs/retro/2026-06-18-s5s6-workbench-dashboard-golden-testing-gate-acceptance-report.md
  - docs/backlog/2026-06-17-platform-capability-map-and-test-scenario-design.md
---

# AuraBot 对话式可视化 — 收敛终局(ChatBI / dashboard:create / agent 查询统一)

> 一句话:**"自然语言看数"功能层面早该是一条(AuraBot 通用画图),但底下趴着三套实现没清。**
> 终局 = AuraBot 单入口 + 一套 NL→查询引擎 + 一套图渲染契约;即席(内联卡片)与沉淀(持久 dashboard)是同一入口下的两种产物。

## 0. 这份文档怎么来的(来龙去脉)

2026-06-18 的 agent-quality campaign 在补 **S5「ChatBI 即席图表浏览器 golden」** 时,
最初把它判成 `did_not_run / no-UI-path`,理由是「`ChatBIPanel` 无任何路由 mount」。
随后 owner 追问「ChatBI 和 AuraBot 是不是重复了 / 能不能融合 / AuraBot 是不是终极入口」,
逼出一次彻底取证 —— 结论是**初判错了**:`ChatBIPanel` 确实死了,但 ChatBI 的画图能力
**早已以另一种、更通用的形态活在 AuraBot 聊天里**(`ChatBiResultCard`)。这次取证同时
暴露出底层有**三套并存的 NL→数据/可视化实现**。本文把现状盘清,定终局,给迁移路径。

判断标准已遵守 §16(盘自家底)/ §15(实测不推断):下文每条结论附 file:line 证据。

## 1. 现状盘点(实测,非推断)

### 1.1 入口:AuraBot 已经是事实上的单一 NL 入口 ✅
- `AuraBotProvider`(`web-admin/app/plugins/core-aurabot/components-shell/AuraBotProvider.tsx`)
  是**全局 Provider**,暴露 `openPanel()`(:611)—— 任意页面都能拉出的"AI 同事"侧栏。
- 后端所有对话回合走 `ConversationTurnService.runTurn`(conversation-turn-chokepoint 红线),
  AuraBot 聊天天然是这个 chokepoint 的 UI。

### 1.2 画图:AuraBot 聊天里是"通用"画图,不是"内置一个 ChatBI" ✅(关键)
- `AuraBotChat.tsx:174-178`:
  ```
  if (message.type === 'tool_result') {
    const resultData = message.toolResult?.data || message.toolResult;
    if (resultData?.records?.length > 0) return <ChatBiResultCard result={resultData} />;
  }
  ```
  **任何 agent 工具返回带 `records` 的结果 → 自动渲染成一张 ECharts 图表卡片。**
- `ChatBiResultCard.tsx`:ECharts(echarts-for-react)bar/pie/line,自动从列类型猜
  label(字符串列)/ value(数值列)。是真渲染器,跟 dashboard 同库。
- 产出 records 的工具是 **agent 的 `dsl_query`**(只读)/ `dsl_command`(写):
  `AuraBotChatToolRuntimeAdapter.java:380,391`。
  → 所以"问 AuraBot 要数 → 出图"的**活路径 = agent 选 `dsl_query` 工具 → records → ChatBiResultCard**,
  **不经过下面任何一套 chatbi 引擎**。

### 1.3 三套并存的 NL→数据/可视化实现(真重复)
| # | 实现 | 入口 | 活/死 | 证据 |
|---|------|------|-------|------|
| ① | **agent `dsl_query` 工具** → records → ChatBiResultCard | AuraBot 聊天(全局) | **活**(唯一真活的即席画图路径) | AuraBotChat.tsx:178 / AuraBotChatToolRuntimeAdapter.java:380 |
| ② | **ChatBI v1**(model-direct) `POST /api/ai/chat-bi/query` | 旧独立面板 | **死**:唯一前端调用方是已死的 `ChatBIPanel.tsx:80` | ai/chatbi/controller :46 |
| ③ | **ChatBI v2**(语义层,有状态对话 + disambiguation) `POST /api/chatbi/v2/conversations/{pid}/ask` | —— | **孤儿**:前端零调用方(`grep /api/chatbi web-admin` = 0) | chatbi/v2/controller :89 |
| ④ | **`dashboard:create` skill**(NL→持久多 widget 看板) | AuraBot agent 工具 | **活**(本 campaign 建,#810 修过 dataSource shape) | aurabot/skill/builtin/DashboardGeneratorSkill.java |

### 1.4 三套图渲染器(真重复)
| 渲染器 | 用在哪 | 契约 | 活/死 |
|--------|--------|------|-------|
| `AIChartRenderer`(framework/smart/components/ai) | 旧 ChatBIPanel | 自有 | **死**(只被 ChatBIPanel + barrel index 引用) |
| `ChatBiResultCard`(core-aurabot) | AuraBot 聊天即席卡片 | 自动猜 label/value | **活** |
| `SharedChartFactory`(framework/smart/charts) | dashboard widget(24 chartType) | `ChartDataSource` / `useChartData`(`type:aggregate|namedQuery`) | **活** |

### 1.5 死代码确认 ✅
`ChatBIPanel.tsx` + `AIChartRenderer.tsx`:**仅被 `components/ai/index.ts` barrel 导出引用**,
无任何 route/组件 import(`grep` 实测)。即 OSS 历史上的独立 `/chat-bi` 页被删后,
功能以 `ChatBiResultCard` 在 AuraBot 内重写,这两个旧文件被遗留成死代码。

## 2. 诊断:重复的本质

不是**功能重复**(用户要的就是"问一句、出张图 / 出个看板",一个心智)。
是**实现重复**:三套 NL→查询引擎(agent dsl_query / chatbi v1 / chatbi v2)+
三套图渲染器(AIChartRenderer / ChatBiResultCard / SharedChartFactory),各自演化、互不复用。

**症状证据**:本 campaign 的 S5 修的那个 green-but-broken —— `DashboardGeneratorSkill`
吐 `config.dataSource:{type,code}`、widget 渲染器(`useChartData`)读不到 → 生成的图空 ——
**本质就是 skill 输出契约和 widget 渲染契约漂移**。两条渲染路径不共享一个 `ChartDataSource`
契约,才会"各写各的、import success 还绿、真渲染才空"。这类 bug 是实现重复的必然产物。

## 3. 终局架构

```
                       ┌─ AuraBot 聊天(唯一 NL 入口 · 全局 openPanel)
   用户自然语言 ───────▶│   = ConversationTurnService.runTurn  (chokepoint 红线)
                       └─ agent 选工具 ─┬─ 即席看数 : dsl_query 工具 → records ─┐
                                        │                                      ├─▶ 一套图渲染契约(ChartDataSource)
                                        └─ 沉淀看板 : dashboard:create skill ──┘     · 即席→ 内联卡片
                                                       → /dashboards/view/{code}     · 沉淀→ dashboard widget
```

**三条收敛原则**:
1. **入口收一个**:AuraBot 聊天是唯一对话式看数入口(已是)。不再有独立 ChatBI 页 / 独立 BI 入口。
2. **NL→查询引擎收一套**:agent `dsl_query`(简单即席)与 chatbi v2 语义层(复杂多轮/歧义消解)
   二选一为主、另一为辅或退役 —— **不能三套并存**。
3. **图渲染收一个契约**:即席卡片(ChatBiResultCard)、dashboard widget(SharedChartFactory)、
   skill 产出(DashboardGeneratorSkill)三处**共用同一份 `ChartDataSource` 契约 + 同一渲染内核**。

**即席 ↔ 沉淀的桥**:AuraBot 里看到一张满意的即席图,一键"存成 dashboard widget"
(即席 records/intent → `dashboard:create`),把两条路径在用户体验上接成一条增长漏斗。

## 4. 迁移路径(有序 · 含第一块多米诺)

> 全是 cleanup / 收敛,**不是新功能**;每步可独立 ship + 真栈/真浏览器 golden 验。

| 步 | 动作 | 风险 | 验证 |
|----|------|------|------|
| **0(第一块多米诺 · 需 owner 决策)** | **定 ChatBI 引擎 canonical:v1 `ai/chatbi`(简单 stateless)vs v2 `chatbi/v2`(语义层有状态)。** 推荐:v2 为"复杂 BI"引擎,简单即席继续走 agent `dsl_query`;v1 退役。 | 决策性,无代码风险 | —— |
| 1 | 退役死代码 `ChatBIPanel.tsx` + `AIChartRenderer.tsx` + barrel 导出 | 低(已无引用) | tsc + grep 0 引用 |
| 2 | 退役 / 合并 v1 `ai/chatbi`(若步 0 选 v2);`chat-bi.spec.ts` 改测活路径 | 中(spec 迁移) | 改后 spec 绿 |
| 3 | 图渲染收一个契约:ChatBiResultCard 改走 `ChartDataSource` + SharedChartFactory 内核(或反向),三处共用 | 中(渲染回归) | dashboard + 即席 双浏览器 golden(本 campaign 的 S5 dashboard golden 是基线) |
| 4 | 即席→沉淀桥:AuraBot 即席图卡片加"存为看板"动作 → `dashboard:create` | 低(加能力) | 浏览器 golden:即席图 → 存 → /dashboards/view 渲染 |

## 5. 对测试 / golden 的影响(含一处诚实更正)

- **更正本 campaign 的 S5 误判**:S5「ChatBI 即席图表浏览器 golden」**不是不可做**。
  正确形态是 **"AuraBot 聊天 golden"**:全局拉开面板 → 问一个 NL 查询 → 断言
  `ChatBiResultCard` 真渲 ECharts(SVG/canvas 非空 + records>0 + 0 console error)。
  误判根因:初次 grep 了已死的 `ChatBIPanel` 就下结论,没找替代渲染路径(§15 教训)。
  本文留作该 golden 的 SOT;真做时归到 core-aurabot 的浏览器 golden。
- 收敛后这条 golden 自然并入"AuraBot agent → dsl_query → 图卡片"的链路,不再是孤立的 ChatBI 测试。

## 6. 待 owner 决策

1. **步 0 第一块多米诺**:ChatBI 引擎 v1 退役、v2 为复杂 BI 引擎、简单即席走 agent dsl_query —— 确认这个方向?
2. 收敛优先级:是先清死代码(步 1,几乎零风险、立刻减表面),还是先统一渲染契约(步 3,根治 shape 漂移类 bug)?
3. 是否要现在补"AuraBot 聊天 ChatBI golden"(§5),把误判的洞补上 —— 还是连同步 3 渲染收敛一起做。
