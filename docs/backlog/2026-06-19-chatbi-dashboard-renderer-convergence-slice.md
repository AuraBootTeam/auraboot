---
type: backlog
status: shipped
created: 2026-06-19
owner: agent-quality-campaign
related:
  - docs/backlog/2026-06-18-aurabot-conversational-viz-convergence-endgame.md
---

# Slice D — ChatBi 即席图 ↔ 看板图 渲染收口(独立切片)

> 把 AuraBot 聊天即席图(`ChatBiResultCard` 自有 ECharts)与看板图(`SharedChartFactory` 的 `Smart*Chart`)的渲染收成一套,消除两套 ECharts option 构建的冗余。
>
> **✅ DONE(2026-06-19)**:走**修法 B**——`useChartData` static 分支改**同步**(`useMemo` 直返,不再 effect+setData)→ ECharts 首帧即带数据挂载,不再缓存空 scale → 柱子正确。`ChatBiResultCard` 删自有 `EChartsChart`+`CHART_COLORS`,改薄适配器复用 `getChartComponent`+static dataSource。**验证**:chat-bi golden 截图柱值正确(9/18/11/29/23)+ dashboard/workbench golden 26 passed 无回归 + chart 单测 5 文件 18 passed。`enabled:false` 契约保留(static memo 也 gate enabled)。下方原始分析保留作记录。

## 来龙去脉

收敛终局(`docs/backlog/2026-06-18-aurabot-conversational-viz-convergence-endgame.md`)§1.4 诊断出"三套图渲染器";Slice A 删了死的 `AIChartRenderer`,剩 **`ChatBiResultCard`(聊天内联卡)** 与 **`SharedChartFactory`(看板 24+ 图,fetch/dataSource-driven)** 两套活的。Slice D 要收成一套。

**2026-06-19 第一次尝试**:`ChatBiResultCard` 删自有 `EChartsChart` + 硬编码 `CHART_COLORS`,改 `getChartComponent(chartType)` 复用 `SmartBarChart` + `{type:'static'}` dataSource。**单测绿、golden 的 canvas 断言绿**——但 **§2.2 视觉截图复核抓到 green-but-broken**:柱子全挤在 ~0-1,而 y 轴 0-30、数据正确。已 revert 到干净态。

## 根因(取证,非推断)

逐层 dump 证实数据/组件/键全对,但渲染坏:
- `series0.data = [9,18,11,29,23]`(真值)、`metaMetrics=['cnt']`、`metaDims=['crm_lead_status']`、组件确为 `SmartBarChart`、ECharts option 正确。
- **`SmartBarChart` 是看板 widget 组件**:自带 `rounded-lg border bg-white p-4` 外壳 + `<ReactECharts style={{height:'100%'}}>`;且 **`useChartData` 的 static 分支异步 setData**(effect 里,非同步)→ ECharts 在数据到达前挂载、缓存一个 scale → 数据迟到时 `notMerge`/`lazyUpdate` 没把柱子几何重算到聊天卡的小容器里。
- **对照**:S5 看板 golden 里 `SmartBarChart` 渲染正常 → 这是**把看板组件塞进聊天卡 context 特有的**,不是组件本身坏。
- 证据残留:debug 截图 `web-admin/test-results/chatbi-slice-d-card.png`(若被清);console marker `SBC_DEBUG`/`CHATBI_DEBUG`(已随 revert 移除,记录在此供复现)。

## 两条修法

- **A(推荐)抽纯函数 option builder**:`buildChartOption(chartType, records, dimensions, metrics, opts) → EChartsOption`,`ChatBiResultCard` 与 `Smart*Chart` 都用。最 DRY,不耦合组件外壳/sizing,不引入 static 异步问题。
- **B 修 `useChartData` static 同步**(`useMemo` 而非 effect+setData)+ `SmartBarChart` 容器 `resize` 处理。改共享 hook,影响**所有**看板图,风险面更大。

## 硬要求(完成判定)

任一修法都 touch 共享渲染层 → 完成判定**必含**:
1. **dashboard golden 全量回归**(S5 + 所有 `smart-*` widget 图);
2. `ChatBiResultCard` 的 B1 + Slice-E golden 回归;
3. **真截图复核柱子值正确**(非只 canvas 存在——本片教训:canvas 断言会过、单测会过,只有截图抓得出 green-but-broken);
4. `unified-designer`/dashboard 相关单测全绿。

## 价值 / ROI

**低**(纯 DRY,两渲染器现都各自在自己 context 正常)。建议仅在做更大的"渲染层统一"时一并做,不为单纯 DRY 单独投入。优先级低于任何功能缺口。

## 验收

- `ChatBiResultCard` 与看板图共用一套 option 生成;
- B1/Slice-E golden 截图柱子值正确;
- dashboard golden 全绿无回归。
