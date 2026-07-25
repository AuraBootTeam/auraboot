# AMOS MES/WMS 功能大图 + 测试报告(诚实版 · 2026-07-25)

> 这份取代之前那份「🟢21 全绿」HTML 报告——那份**高估了**:它把 UI 行的「页面渲染了」当成「功能可用了」。
> 真去驱动行动点后发现:**6 个 UI 行动点里 5 个是坏的**(点了发空 payload → 400)。本报告分三层说清真实状态。

## 一句话大图

- **后端命令层**:11 个 FR,真栈 golden(真命令管道 + psql DB round-trip)验证过,**真工作**。
- **UI 渲染层**:7 张页面渲染 OK——但这只证明「页面加载了」,是最弱的证据。
- **UI 行动点层**:6 个操作按钮里 5 个曾断(缺 inputFields → 点了 400),**现已全部修复 + 行动驱动 golden 验证**(第 6 个「申请绕行」是 navigate-to-form,本就工作)。
- **结论**:后端扎实且测得实;UI 行动点**已从「点了 400」修到「真能用」**——每个按钮弹表单、填、提交、状态真变,都有截图 + golden。
- 原始 spec 60 个 FR(28 MES + 32 WMS),本轮**触及 ~14 个**,**~46 个未开发**(owner 定的储备验收基线)。

---

## 第一层 · 后端命令(开发 ✅ 真栈测 ✅ 工作 ✅)

真命令管道执行 + psql 直查 DB 断言。这些是**扎实的**——golden 见过红(FR-14/FR-10 就是抓 bug 抓出来的)。

| FR | 功能 | golden | 证据 |
|----|------|--------|------|
| FR-04 | HandlingUnit 打包/拆分/合并 | backend 34/34 | 数量守恒 + event 行 + note 用 code |
| FR-05 | 开工互锁(7 检查) | backend | checkedItems=7 含 tooling_life |
| FR-09 | SMT 工装寿命 | backend | used_cycles 累加 |
| FR-13 | 齐套分析 | backend | kitting_result 缺料正确报缺 |
| FR-16 | Hold 放置持久化 | backend | active hold 行 |
| FR-20 | 设备停机防重叠 | backend | 恰好 1 open downtime |
| FR-22 | 班次交接双签认(后端) | backend | pending_ack→acknowledged |
| FR-10 | FEFO/FIFO 拣货 | fr10 10/10 变异验证 | 近效期先分配 + 过期排除 + fifo 翻转 |
| FR-08 | 材料验证与消耗 | fr08-12-14 22/22 | 错料阻断 + 消耗行 |
| FR-12 | 序列化 SN 谱系 | fr08-12-14 | 父子链双写 |
| FR-14 | 测试维修联动 | fr08-12-14 | fail→defect→rework 全链路 |

**沿途真栈 golden 抓到并修的 3 个真产品 bug(单测全看不见)**:
- FR-14 defect 门控 `instanceof Map` 但 JSONB 读回 String → 0 defect(plugins #244)
- FR-14 rework handler 自持久化缺 dslPersistence:false + qc_rw_code 未生成(plugins #244)
- FR-10 入库不初始化 available_qty → 新入库库存不可拣(plugins #244)

---

## 第二层 · UI 页面渲染(开发 ✅ 渲染测 ✅ · 但只证明「加载了」)

真浏览器截图 + DOM 断言(无 404 / 无裸码 / 无 console error)。**弱证据**——只证渲染,不证按钮会动。

| FR | 页面 | 渲染 | 美观(截图复核) |
|----|------|------|------------------|
| FR-01 | 工单接入列表 | ✅ | BOM 蓝链接、计划产量 |
| FR-03 | 派工/工位分配列表 | ✅ | 干净列表 |
| FR-07 | 工位执行列表 | ✅ | 状态 dict、操作员 |
| FR-15 | Andon 工作台 | ✅ | 6 卡 metric-strip + 证据面板 |
| FR-21 | 报工记录列表 | ✅ | 工序蓝链接、班次 dict |
| FR-22 | 班次交接工作台 | ✅ | **美观已修**:datetime 格式化 + 筛选标签(#243) |
| FR-23 | 车间看板 dashboard | ✅ | **布局已修**:表塌陷 h=1→4(#240) |

---

## 第三层 · UI 行动点(开发 ✅ · 曾 5/6 坏 → 现全部修复 ✅)—— 本轮最重要的发现 + 修复

**发现**:每个操作按钮的命令**需要 inputFields,但页配置没声明** → 点了弹不出表单 → 发空 payload → **400 → 按钮静默失效**。后端命令是好的(第一层已证),但 UI 上点不动。`门禁绿 ≠ 功能可用`——列表渲染 golden 恰恰掩盖了这个。

**修复**:根因 = 页 action 配置缺 `inputFields` 声明(select 选项须走 `dataSource` 非内联 `options`——promptInputForm 只读 dataSource)。全部补齐,每个行动驱动 golden 验证。

| 页面 | 行动点 | 命令 | 修复 | 行动驱动 golden |
|------|--------|------|------|------------------|
| 班次交接台 | **签认** | acknowledge_handover | ✅ inputFields[接班人] | fr22-action-golden 8/8(见过红) |
| 班次交接台 | **生成交接单** | create_handover | ✅ inputFields[工位 dataSource select + 班次 select + 交班人] | mes-action-points-golden 9/9 |
| Andon 工作台 | **解决异常** | resolve | ✅ inputFields[处理措施 + 停机分钟] | mes-action-points-golden |
| Hold 工作台 | **下达 Hold** | place_hold | ✅ inputFields[对象类型/ID/范围/原因/责任人] | mes-action-points-golden |
| Hold 工作台 | **解除 Hold** | release_hold | ✅ inputFields[解除说明] | mes-action-points-golden |
| 工序详情 | 申请绕行 | interlock_override(navigate) | — 本就工作(navigate-to-form,sweep 误判) | — |

**每个都验证了完整行动链**(截图 before → 表单 → 后置确认 + DB 状态断言):
- 签认:待签认 → 表单(接班人)→ **DB pending_ack→acknowledged** + UI 翻「已签认」
- 生成交接单:表单(工位 select 11 项 + 班次)→ **新交接单行建成**(count +1)
- 解决异常:seed open 异常 → 表单 → **异常→resolved**
- 下达 Hold:表单(5 字段)→ **新 active hold**(count +1)
- 解除 Hold:表单 → **hold→released**

全部**从 RED(修前空 payload 400)变 GREEN**——见过红,可证伪。截图由 golden 现跑现生成:`ui/fr22-action-golden.mjs`(fr22-act-1-before / -2-action-form / -3-after-confirm)+ `ui/mes-action-points-golden.mjs`(act-{create-handover,resolve,place-hold,release-hold}-{form,after}),对 live 栈跑即产出 before→表单→后置确认三态。

---

## 未开发(~46 / 60 FR · 储备验收基线)

owner 已定位 spec 为储备基线,现阶段不按它开工。未开发含 ⛔ 结构性缺口:MES-06 SOP、MES-10 WIP 路线、MES-11/24/25/26、WMS-17/18/23/25/28 等。完整 60 FR 逐条状态见 `amos/docs/product-docs/mes-wms/11-mes-fr-crosswalk.md`。

---

## 测试类型强度说明(诚实口径)

| 测试类型 | 强度 | 用在哪 | 能证什么 |
|----------|------|--------|----------|
| 后端真栈 golden(真命令管道 + psql DB round-trip) | **强** | 11 个后端 FR | 命令真工作、状态真变、见过红 |
| UI 渲染 golden(浏览器截图 + DOM 断言) | 弱 | 7 页 | 只证页面加载,不证按钮会动 |
| UI 行动驱动 golden(点按钮 + 断言状态变化) | **强** | 只有 FR-22 签认 1 个 | 真行动点 + 后置确认 |

**缺口**:UI 行动驱动 golden 只有 1 个;其余行动点要么坏(需修 inputFields)、要么只有渲染 golden。

---

## 结论

- **真工作 + 测得实**:后端命令层 11 个 FR(真栈 golden)。
- **UI 行动点已修好**:5 个曾断的按钮全部补齐 inputFields,每个**行动驱动 golden 验证**(点按钮→表单→提交→状态真变→截图)。从「点了 400」变「真能用」。
- **诚实过程**:之前「21 全绿」把渲染当可用、高估了 UI 层 → owner 追问「行动点/后置确认」→ 驱动第③层才发现 5 个断点 → 全部修复。这是发现问题→修复→证实的完整闭环。
- **交付真相**:后端 11 FR + UI 7 页 + **5 个行动点端到端可用**(签认/生成交接单/解决异常/下达 Hold/解除 Hold);60 FR spec 里其余 ~46 未开发(储备基线)。这是一个**纵深可用的切片**,不再是「按钮点不动」的半成品。

## 沿途修的真 bug 总账(都真栈/真浏览器验证)

| bug | 层 | 抓法 | 修复 |
|-----|-----|------|------|
| FR-14 defect 门控 instanceof Map(JSONB 读回 String) | 后端 | fr08-12-14 golden | plugins #244 |
| FR-14 rework handler 缺 dslPersistence + code | 后端 | 同上 | plugins #244 |
| FR-10 入库不初始化 available_qty | 后端 | fr10 golden | plugins #244 |
| FR-22 交接时间裸 ISO + 筛选无标签 | UI 美观 | 截图 | plugins #243 |
| FR-23 看板表塌陷 h=1 | UI 美观 | 截图 | plugins #240 |
| **5 个行动点缺 inputFields → 点了 400** | **UI 行动点** | **行动驱动 golden** | **plugins #246(config)+ auraboot #1517(golden)** |
