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

## 4. 决策(已锁定 · 2026-06-18 · dev 阶段无兼容)

> dev 阶段:破坏性变更优先,**禁 deprecated alias / forwarding stub**;无活消费方的直接删,不做渐进退役。

**锁定的终局选型**:
- **唯一查询契约** = `AggregateQueryService` / `AggregateQueryRequest` / 前端 `ChartDataSource` —— 已是最多消费方的底座(dashboard 图 / kanban / grouped-table / `ChartDataController` / `SemanticAggregateAdapter` 全走它,实测)。
- **唯一治理引擎** = `framework/semantic`(`SemanticQueryService`),经 `SemanticAggregateAdapter` 接到上面的聚合契约。
- **NL→数据引擎** = `chatbi/v2`(NL→token→`TokenCompiler`→`SemanticQueryRequest`),**包装成 AuraBot 的 agent `chat_bi` 工具**(不再前端直连 `/api/chatbi/v2`)。
- **v1 `ai/chatbi`** = **删**(裸 NL→SQL 旁路 · 不接语义层 · 直连 API 绕过 agent 治理 · 前端零活消费方,实测)。

**实测纠正(§15)**:v1 **不能整删** —— `chatbi/v2` 的 lexer 复用了 v1 的 `ChatBiLlmParser`(`chatbi/v2/lexer/DefaultTokenLexer` import 它)。所以 v1 是"**吸收再删**":先把 `ChatBiLlmParser` 迁进 v2 own(或共享 nl-parse 模块),再删 v1 HTTP 面。

**执行清单(无兼容 · 排序为不丢功能,不是为兼容)**:
| 步 | 动作 | 性质 |
|----|------|------|
| 1 | 删纯死代码:`ChatBIPanel.tsx` + `AIChartRenderer.tsx` + barrel 导出 + `chat-bi.spec.ts` | 零活消费方 → 直接删 |
| 2 | `ChatBiLlmParser` 迁入 v2(v2 lexer 依赖);随后删 v1 HTTP 面 `ChatBIController` + `/api/ai/chat-bi` + `ChatBIService` + `ChatBIRequest/Response` DTO | 吸收再删 |
| 3 | `chat_bi` agent 工具:v2 NL→数据包装成工具,走 AuraBot + agent 治理(RuntimeAuth/ACL/approval/trace),退掉前端直连 | 加工具、退直连 |
| 4 | 渲染收一个契约:`ChatBiResultCard`(即席)与 dashboard widget 都收到 `ChartDataSource`/`SharedChartFactory`,即席卡片成薄适配器 | 根治 shape 漂移类 bug |
| 5 | 即席→沉淀桥:即席图卡片加"存为看板" → `dashboard:create` | 加能力 |

**落地前唯一要先验的**:v2 能否对一个**普通 model 零配置**派生 baseline 语义模型(`SemanticAggregateAdapter` 的存在强烈暗示能)。能 → v1 的 zero-setup 优点被彻底抵消,按上表删;还要手工建语义模型 → 先补"从 `ab_meta_model`+fields+reference+dict 自动派生 baseline 语义模型",再删 v1。

## 5. 对测试 / golden 的影响(含一处诚实更正)

- **更正本 campaign 的 S5 误判**:S5「ChatBI 即席图表浏览器 golden」**不是不可做**。
  正确形态是 **"AuraBot 聊天 golden"**:全局拉开面板 → 问一个 NL 查询 → 断言
  `ChatBiResultCard` 真渲 ECharts(SVG/canvas 非空 + records>0 + 0 console error)。
  误判根因:初次 grep 了已死的 `ChatBIPanel` 就下结论,没找替代渲染路径(§15 教训)。
  本文留作该 golden 的 SOT;真做时归到 core-aurabot 的浏览器 golden。
- 收敛后这条 golden 自然并入"AuraBot agent → dsl_query → 图卡片"的链路,不再是孤立的 ChatBI 测试。

## 6. 决策状态 + 剩余执行排序

**引擎选型已锁定(§4,dev 阶段无兼容)**:`AggregateQueryService`/`ChartDataSource` 契约 + `framework/semantic` 引擎 + `chatbi/v2` 作 agent `chat_bi` 工具,**删 v1**(吸收 `ChatBiLlmParser` 后)。剩下的只是执行排序:

1. 先清纯死代码(§4 步 1,零风险、立刻减面),还是先做渲染契约统一(步 4,根治 shape 漂移)?
2. 是否现在补"AuraBot 聊天 ChatBI golden"(§5 误判闭环),还是连同步 4 一起做?
3. **落地第一步**:验 v2 能否从 meta-model 零配置派生 baseline 语义模型(决定 v1 是直接删,还是先补派生)。

## 7. 执行状态 + 落地实测发现(2026-06-18)

> 锁定决策后开始执行,前两片安全清完,但实测**坐实了 §4「落地前必验」那块的答案——v2 还不能零配置跑,所以"删 v1 / chat_bi 工具"被一个真特性 build 卡住,不是 cleanup。诚实记录,不假完成(§1 测试缺失=未完成)。**

**已完成(本次,verified)**:
- **Slice A**:删死代码 `ChatBIPanel.tsx` + `AIChartRenderer.tsx` + barrel 导出(零消费方,`grep` 0 引用)。
- **Slice B(收窄为安全版)**:删 v1 **HTTP 旁路 API** `ChatBIController`(`/api/ai/chat-bi`)+ e2e `chat-bi.spec.ts`。`compileJava exit=0`。
  - **未删** `ChatBIService` + `ChatBiLlmParser` + DTO ——**实测纠正(§15)**:① v2 对它们**零功能依赖**(`chatbi/v2/lexer` 里只有 javadoc/TODO 提及,无 import 无调用);② 但 `ChatBiIntentLiveIT`(真 DeepSeek 5/5 intent)+ `ChatBIServiceLlmTest` **直接 @Autowired 用着 `ChatBiLlmParser`**,且这是**当前唯一可用且被测的"普通 model 零配置 NL→intent"**。现在删=回归 + 丢测试覆盖,无替代。→ **改为:只删旁路 public API,intent 服务降为内部 dormant,留到 v2 零配置就位后再退役。**

**keystone 重新定位(实测纠正,§15)**:先前以为 C 卡在"v2 需语义模型 / 要建 meta-model→semantic 自动派生"。**错了**——`chat_bi` 工具根本不该走 v2 语义层,而是走 **`AggregateQueryService` 的 raw 聚合路径**(`modelCode + dimensions + metrics`,**零配置、无需语义模型**,正是 dashboard 图表底座,S5 golden 已 live 证)。所以:
- v2 语义层(需 `ab_semantic_model`)= **进阶路径**(治理化 metric / 多轮),不是 baseline chat_bi 的前置。
- meta-model→semantic 自动派生 = **只为进阶 v2 路径**,**不阻塞** baseline chat_bi 与渲染收口。

**Slice C — DONE(本次,verified)**:`ChatBiSkill`(`aurabot/skill/builtin/ChatBiSkill.java`)—— agent LLM 用 native tool-use 填 `{modelCode, dimensions, metrics, filters}` → 走 raw `AggregateQueryService` → 返 `{records, columns, chartType}` payload → AuraBot 聊天 `AuraBotChat.tsx:178` 自动渲 `ChatBiResultCard`(ECharts)。**零配置 · 受治理(走 agent tool,非直连 API) · 复用底座**。`ChatBiSkillTest` 5/5(请求映射强制 raw 路径〔偷传 semanticModelCode/queryCode 被剥〕+ 响应映射 + chartType 推断 + 校验)。@Component 自动注册。

**后端收敛 = DONE(本轮全 merged + verified)**:
- `chat-bi` skill 命名 hotfix(#832):`name()="chat_bi"` 撞 `AuraBotSkillRegistry.NAME_PATTERN`(禁下划线)→ 破后端 startup + 所有 IT context load;改 `chat-bi`。**教训(§1)**:单测无 Spring context 抓不到注册期错,薄组件也得真 context 测。
- **coverage 迁移 live IT**(#832):`ChatBiToolIntentLiveIT` —— 真 DeepSeek 用 chat-bi 工具 schema 填 `modelCode/dimensions/metrics`,正确 + grounded(零幻觉),floor 全过。把"NL→intent 正确"覆盖从旧 `ChatBiLlmParser` 迁到 agent-tool 层。
- **v1 完整退役**(#833):删整个 `ai/chatbi` 包(`ChatBIService`/`ChatBiLlmParser`/DTO)+ 3 个 v1 测;compileJava+compileTestJava 绿,无丢覆盖。
- **结果**:单一入口(AuraBot)+ 单一查询底座(`AggregateQueryService`/`ChartDataSource`)+ chat-bi 受治理 agent 工具 + v1 旁路/死代码全清。**终局的后端骨架已落地。**

**剩余 = 前端 + 浏览器(需重拉 OOM 掉的栈,前端切片,单独一轮)**:
1. **Slice D — DONE(2026-06-19)**:渲染收口。第一次尝试被 §2.2 视觉截图抓到 green-but-broken(柱子 tiny,canvas 断言/单测全绿)→ revert → 起独立切片 → 实现修法 B:`useChartData` static **改同步**(`useMemo` 直返,非 effect+setData)→ ECharts 首帧带数据挂载,不缓存空 scale。`ChatBiResultCard` 删自有 echarts + `CHART_COLORS`,薄适配器复用 `getChartComponent`+static dataSource。**验证**:chat-bi golden 截图柱值正确(9/18/11/29/23)+ dashboard/workbench golden **26 passed 无回归** + chart 单测 18 passed。完整记录 → `docs/backlog/2026-06-19-chatbi-dashboard-renderer-convergence-slice.md`。**根因教训**:async static data 让 echarts 在小/动画容器里缓存错 scale;只截图复核抓得出(§2.2)。
2. **chat-bi 浏览器 golden — DONE(2026-06-19,B1,golden 绿)**:重拉栈(java -jar bootJar + host Vite/BFF)写 `chat-bi-render-golden.spec.ts`(+ ChatBiResultCard `data-testid`),**golden 抓到真 Slice C wiring gap → 据此定 DDR → 修 → 跑绿**。
   - **gap(golden 抓,§15 backend-log 实证)**:chat-bi 注册了但**默认 AuraBot 聊天够不着**——默认聊天工具集走 **LLM grounding 发现**(`ChatToolResolver`→`GroundingPort`),只 `fill_form`/`execute_sql` 常驻;chat-bi 不在 → 默认轮次不 offer → 拒「unavailable」。named-agent 带显式工具能跑、绕过 grounding。
   - **决策**:`DDR-2026-06-19-aurabot-chat-tool-exposure-pin-vs-retrieve`(ENT #582)——**选 A 常驻 pin**:chat-bi 是通用数据查询**原语**(已 pin 的 `execute_sql` 的安全结构化版),不是领域工具 → 进常驻集;grounding 检索留给领域长尾。业界 hybrid(pin 核心 + 检索长尾)。
   - **实现(OSS #845)**:`ChatToolResolver.ensurePlatformTools` 无条件含 `PLATFORM_CHAT_BI_TOOL`(`aurabot_chat-bi`,不像 SQL 兜底那样被移除)。**路由修**:只缓存 code+readOnly、不缓存 agent-def(`cacheSyntheticPlatformTool` 会强制 `toolType=platform`→走 `AuraBotSkillToolProvider.execute` 失败桩),让 `resolveToolDefinition` 经 `aurabot:` 前缀判为 `AURABOT_SKILL`→`SkillToolExecutor`→真 `ChatBiSkill`。
   - **验证**:golden 绿(un-fixme);live 证 `tool=aurabot:chat-bi, status=success` + `getModelDefinition crm_lead`(tenant 325894...,90 行);工具解析单测 39 绿。**Slice C 现真接通默认对话**。
3. **Slice E — DONE(2026-06-19,OSS #853)**:即席→沉淀桥。ChatBiSkill payload 带上聚合 spec(dimensions+metrics);ChatBiResultCard 加「存为看板」按钮 → 建 1-widget `DashboardCreateRequest`(chartType→smart-* widget,dataSource aggregate,scope `personal`)→ `dashboardService.create`(`POST /api/dashboards`)→ 内联确认。**e2e + 真 DB 验**:chat-bi `status=success` → `Dashboard created pid=...` → `ab_dashboard` 行「Leads by status \| personal \| 1 widget」。即席(聊天卡)↔沉淀(持久看板)接成一条增长漏斗。
4. (可选)v2 进阶路径:meta-model→semantic 自动派生 + v2 接 chat-bi 的"复杂多轮/治理 metric"档位。
