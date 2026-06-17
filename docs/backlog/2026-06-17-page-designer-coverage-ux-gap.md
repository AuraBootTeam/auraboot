---
type: backlog
status: active
created: 2026-06-17
---

# 统一页面设计器 — 测试覆盖度 / UX 交互 / 后端联动 完整方案与 Gap

> 任务:针对**统一页面设计器(Unified Page Designer)与后端联动**,分析测试覆盖度、页面 UX 交互性,关注**每个组件 / 每个属性 / 每个行动点 / 每个视觉反馈**,输出完整方案与 gap,并按 `/aura-endgame` 流水线修复。
>
> 全部结论取证自最新 `origin/main`(HEAD `a46566055`),5 个并行只读 agent 逐条 `grep`/`read` 带 `file:line` 证据。代码改动在隔离 worktree `feat/page-designer-golden-coverage`。

---

## 0. 终局定义(P1,增量场景)

统一页面设计器是 AuraBoot 低代码"配置优先"战略的可视化内核(AGENTS §7)。终局态:

- **每个 palette 组件**都能拖入画布、配置属性、保存、重开回显、runtime 真渲染。
- **每个属性面板字段**都有专属富控件(模型/字典/命名查询/权限码用选择器而非裸文本框),编辑→保存→回显→runtime 生效成对验证。
- **每个行动点**(保存/发布/预览/导出/导入/撤销重做/删除/复制/版本/diff/回滚/kind 切换)在统一 workbench 内可达且有黄金测试。
- **每个视觉反馈**(选中态/拖拽预览/drop 指示/dirty/校验错误/保存反馈/空·加载·错误态/AI lock 徽标)真浏览器驱动断言。
- **后端联动**:保存/发布/版本/diff/回滚全链路有真栈集成测试;在线保存与 import 双路径 blockType/kind 校验一致、无静默落库未知 block。

---

## 1. 现状盘点(取证摘要)

### 1.1 组件 palette(`registry/BlockRegistry.ts`,24 droppable + custom)
内建 24 个可拖入 blockType:`form/form-section/field/list/filter-bar/filter-field/table/column/action-bar/action/detail/detail-section/dashboard/widget/tabs/columns/tab/sub-table/repeater/subform/ai-fill-banner/bpm-panel/activity-timeline/field-history`(field-like 3 个只在 Fields 页;kindPolicy 按 kind 收窄)。

### 1.2 后端 DslRegistry 白名单(`DslRegistry.java`)
BlockType 30(含 custom)/ DataType 13 / PageKind 5(list/form/detail/dashboard/composite)/ ChartType 24 / FieldType 13 / ChartDataSourceType 3 / `PAGE_SCHEMA_CURRENT_VERSION=4`。

### 1.3 属性面板(`registry/InspectorSchemaRegistry.ts` + `inspector/SchemaInspector.tsx`)
schema-driven,20 个 block 有专属 inspector;PropertyType 类型系统声明 24 种,registry 只用 7 种,SchemaInspector 只渲染 5 种 + fallback。

### 1.4 行动点(`workbench/UnifiedDesignerWorkbench.tsx` + `WorkbenchToolbar.tsx`)
工具栏:返回/离开警告/AI 副驾/预览·编辑·布局模式/撤销/重做/保存/模板应用/预览设备。画布:@dnd-kit 拖入·移动·嵌套·删除·选中·span·排序·widget 拖拽缩放。Save→真 `POST/PUT /api/pages`。

### 1.5 后端联动(`PageSchemaController.java` 等)
24 个 REST 端点(list/load/create/update/publish/unpublish/delete/versions/rollback/compare/by-key/sync/batch)。校验器两套(i18n + import-gate)。版本 + 两套 diff。表 `ab_page_schema` + `ab_page_schema_history`。

### 1.6 现有测试覆盖(**强基线,需如实承认**)
- `designer/unified-designer-workbench.spec.ts`(UDW-001~061,61 例,1113 expect,真 PUT/GET readback + 真 live command/workflow)。
- `page-designer/unified-designer-kind-and-binding.spec.ts`(47 例,743 expect,真 mouse.move 多步指针 + 43 处 schema readback)。
- 28 个 page-designer authoring/runtime spec 多数走"PUT 保存 + GET readback 回显"或"真 `/p/` runtime + 真命令管道 + DB"。
- 后端:PageSchemaValidatorTest(38)、VersionServiceImplTest(34)、多个真栈 mapper IT。

> **结论:核心 CRUD/拖拽/保存/嵌套/权限/kind 链路覆盖深。Gap 集中在"叶子能力 / 视觉反馈深度 / 后端联动 seam / 缺失行动点"。**

---

## 2. GAP 矩阵(按维度 × 严重度,取证)

### A. 测试覆盖 Gap(用户核心 ask:每个组件/属性/行动点/视觉反馈)

| # | Gap | 维度 | 严重度 | 证据 | 修复路线 |
|---|-----|------|--------|------|----------|
| A1 | **伪断言 tautology**:`field-properties.spec.ts:92` `expect(hasPropertiesPanel \|\| true).toBe(true)` 恒真,选中态/属性面板出现未真断言 | 视觉反馈-选中 | **P0** | `field-properties.spec.ts:92` | 改成真断言:选中 block → 属性面板出现 + selection ring(`data-selected`)|
| A2 | helper blocks inspector 属性覆盖。⚠️**初判"零 E2E"高估**(§15 实测纠正):UDW `unified-designer-workbench.spec.ts:5570+` **已编辑** bpm-panel/activity-timeline/field-history inspector;真缺口是**隔离确定性 readback 覆盖** + helper block 在 detail 下须嵌 columns/tab(canContain 限制)的 gate-gap | 属性 | P1→P2 | UDW:5570+ 已覆盖编辑 | 新增隔离确定性 readback spec(已交付)|
| A3 | **widget 高级图表属性零断言**:thresholds/series/drillDownTo/refreshInterval/markdown | 属性 | **P1** | UDW-010/043 不覆盖这些字段 | 新增 widget-advanced-props authoring spec |
| A4 | **form-section 属性零断言**:collapsible/visibleWhen/columns | 属性 | **P1** | E2E 0 命中 | 并入 inspector authoring spec |
| A5 | **AI lock inspector 全链路无 E2E**:勾选 aiLocked→保存→徽标→AI 重生成跳过 | 属性+视觉 | **P1** | E2E 0;仅 unit | 新增 ai-lock authoring spec |
| A6 | **Advanced JSON tab 零 E2E**:4 个原始 JSON 编辑器 + 无效 JSON 错误态(`inspector-json-error-*`) | 属性+视觉 | **P1** | `inspector-tab-advanced`/`inspector-json-apply` E2E 0 | 新增 advanced-json-tab spec |
| A7 | **拖拽过程视觉反馈无断言**:drop-indicator(before/inside)、drag-overlay ghost、root drop 高亮只在拖后查结果 | 视觉反馈 | **P1** | `drop-indicator-*`/`drag-overlay-ghost` 视觉断言 0 | 新增 drag-visual-feedback spec(多步 pointer 中途断言 indicator)|
| A8 | **设计器自身 dirty/save-error/空/加载态视觉零散浅**:`designer-save-error`、空画布、离开警告 `designer-leave-warning` | 视觉反馈 | **P1** | 散落,无专测 | 新增 designer-feedback-states spec |
| A9 | **版本 version-history E2E 仅 report,page designer 无** | 行动点 | **P1** | DUA-06/07/09 仅 report | 见 C 组(需先有 UI)|
| A10 | **diff / rollback 完全无测**(E2E + 后端 REST IT 皆 0) | 行动点+后端 | **P0** | grep 0 | 见 B/C 组 |
| A11 | **chart 类型广度仅 bar**:line/pie/area/scatter/radar/gauge... authoring + runtime 未测 | 组件广度 | P2 | 仅 chart-stat | 参数化 chart-type spec |
| A12 | **input 类型 checkbox/datetime 弱**;**layout columns 仅 1 处** | 组件广度 | P2 | 计数 | 补 input/layout 广度 |

### B. 后端联动 Gap(后端联动)

| # | Gap | 严重度 | 证据 | 修复路线 |
|---|-----|--------|------|----------|
| B1 | **在线保存路径 `POST/PUT /api/pages` 无 blockType 校验**:未知 blockType 静默落库,只在渲染期暴露;与 import 双标准 | **P0** | `PageSchemaServiceImpl.validateCreate/Update`(L651-702)只调 i18n+size;无 DslRegistry import | 加**union 白名单**轻量守卫(前端 v4 registry codes ∪ DslRegistry.BlockType codes),只拒真正未知 blockType + 真栈 IT |
| B2 | **publish/unpublish/rollback/compare REST 端点无真栈 MockMvc IT** | **P1** | 仅 VersionServiceImplTest 纯单元 mock mapper | 新增 PageSchemaPublishVersionIntegrationTest(真 DB round-trip)|
| B3 | **REST diff 粗粒度且无端点测试**:`/compare` 走顶层 key diff,blocks 当整串比不下钻 | P1 | `VersionServiceImpl.calculateDifferences` L647-669 | IT 断言现行粒度(特征化)+ backlog 记下钻增强 |
| B4 | **command handler 路径(pgm:publish/archive/duplicate)仅纯单元 mock,无真命令管道→DB golden** | P1 | PageSchemaCommandHandlerTest 纯单元 | 新增真命令管道 IT |
| B5 | **i18n controller test 假联动**:`PageSchemaI18nValidationControllerTest` 把 service mock 掉 thenThrow,未跑真 validator | P1 | 该测试 | 新增真链路 controller→service→validator IT |
| B6 | **`canRollbackToVersion` catch(Exception) 吞异常**(疑触红线 §8) | P1 | VersionServiceImpl L246-249 | 收窄异常或移除吞 |
| B7 | **`page.page.manage` 写权限码硬编码字符串非常量** | P2 | Controller L132 等 inline | 提为 `MetaPermission` 常量 |

### C. 缺失行动点 = 真产品 Gap(统一 workbench 行动点不存在,非测试缺口)

| # | Gap | 严重度 | 证据 | 评估 |
|---|-----|--------|------|------|
| C1 | **Publish/Unpublish 按钮**统一 workbench 缺失(API 存在,workbench 不接)| P1 | grep `designer-publish` workbench 0 | 中等工作量,可建 |
| C2 | **Export/Import** 统一 workbench 缺失 | P1 | 同上 | 中等,可建 |
| C3 | **Version/Diff/Rollback UI** 统一 workbench 未接(后端 API 齐)| P1 | 同上 | 较大,需面板 |
| C4 | **Kind 切换 UI** 缺失(只消费后端 kind)| P2 | grep `setKind` 0 | 设计取舍,可 roadmap |
| C5 | **多选/批量操作** 未实现(单 selectedBlockId)| P2 | `selectedBlockId:string\|null` | roadmap |

### D. 属性面板实现 Gap(UX 交互性)

| # | Gap | 严重度 | 证据 | 评估 |
|---|-----|--------|------|------|
| D1 | **`type:'model'` 无专属选择器**→裸文本框手敲 modelCode(form/detail/list/sub-table/widget ~6 处)| P1 | SchemaInspector 无 model 分支 | 接入 model 选择器,显著提升 UX |
| D2 | **17/24 PropertyType 控件统一设计器未用**:model-select/namedQuery/command-select/dict-select/icon/localizedText...→字典码/命名查询/权限码全裸 text/json | P2 | registry 只用 7 种 | 渐进接入富控件 |
| D3 | **4 palette block 无专属 inspector**:tabs/tab/action-bar/filter-bar 回退 defaultFields(只 Title/Span/Region)| P1 | InspectorSchemaRegistry 未注册 | 补专属 inspector schema |
| D4 | **inspector 仅 JSON 解析级校验,无字段级业务校验反馈**(required 空/非法枚举/跨字段)| P2 | SchemaInspector 不读 field.required/dependsOn | 接入字段级校验 |

### E. 组件 palette Gap(组件广度)

| # | Gap | 严重度 | 证据 | 评估 |
|---|-----|--------|------|------|
| E1 | **widget inspector 只暴露 5 种 widgetType**(number-card/bar/line/table/markdown),后端 24 ChartType/renderer 28+ 多数无设计器配置入口 | P1 | InspectorSchemaRegistry L425-435 | 扩 widgetType 选项 + 条件属性 |
| E2 | **19 个后端 block 无 palette 入口**:workbench 家族(metric-strip/record-inspector/candidate-list/workbench-action-bar/evidence-panel/review-drawer/status-banner/artifact-timeline)、chart/stat-card/trace-graph/embedded-list/record-comments/form-buttons/form-wizard/filters/toolbar/description/monthly-grid | P2 | 前后端对照 | **较大**:workbench 范式无可视化路径,roadmap |

---

## 3. 修复方案(P5 slices,按 ROI + 风险排序)

> 原则:**先低风险高价值(测试覆盖 + 后端 seam + 后端 hygiene),后中等工作量(缺失行动点 + 属性富控件),最大特性(workbench palette/全 chart 类型)进 roadmap 如实标 NOT-MET**。每 slice TDD(先红后绿)+ 真栈/真浏览器黄金 + 本地门禁绿即自动 PR/merge。

### Slice 1 — 测试覆盖硬化(用户核心 ask;低风险)
- S1.1 修 A1 tautology → 真选中态断言。
- S1.2 新增 inspector authoring E2E:helper blocks(A2)+ widget advanced(A3)+ form-section(A4)+ AI lock(A5)+ Advanced JSON tab(A6)。
- S1.3 新增视觉反馈 E2E:拖拽 drop-indicator/ghost(A7)+ dirty/save-error/空/离开警告(A8)。
- 全部 PUT 保存 + GET readback + 截图复核。

### Slice 2 — 后端联动 seam + hygiene(后端联动;中低风险)
- S2.1 B1:加 union blockType 守卫(前端 v4 registry codes ∪ DslRegistry.BlockType)到在线保存路径 + 真栈 IT(已知 blockType 通过 / 真未知 blockType 拒)。
- S2.2 B2/B4/B5:publish/unpublish/rollback/compare REST 真栈 IT + command 管道 IT + i18n 真链路 IT。
- S2.3 B3:特征化 REST diff 现行粒度 IT(+ backlog 记下钻增强)。
- S2.4 B6/B7:修 catch(Exception)+ 提权限码常量。

### Slice 3 — 属性面板 UX + 缺失行动点(UX 交互;中等工作量,按预算推进)
- S3.1 D3:为 tabs/tab/action-bar/filter-bar 补专属 inspector schema + authoring E2E。
- S3.2 D1:`type:'model'` 接入 model 选择器(SchemaInspector 加 model 分支)+ E2E。
- S3.3 C1/C2:统一 workbench 加 Publish/Export/Import 行动点 + 黄金(若预算允许;否则 roadmap)。

### Roadmap(NOT-MET,如实标,进 backlog 不假报完成)
- E1 widget 全 chart 类型设计器配置(24 类)
- E2 workbench 家族 19 block 的 palette 可视化 authoring
- C3/C4/C5 Version/Diff/Rollback UI、kind 切换、多选
- D2/D4 富属性控件全接入、字段级校验反馈
- B3 REST diff blocks 下钻

---

## 4. 完成判定(P5 goal 契约)
- Slice 1+2 gap 全 DONE,集成测试真栈,UI 黄金 happy/sad/edge/corner + 截图。
- `/e2e-feature-coverage` 矩阵对补测维度清零 + `/e2e-truth` 过。
- Roadmap 项如实标 NOT-MET + 进 backlog,**不计入"已完成"**。
- 复盘固化教训进 AGENTS/canonical。

---

## 5. 交付状态(P5 复核 + P6,2026-06-17,已独立重跑验证)

> **诚实分层(§2.4):本会话交付 = Slice 1(测试覆盖)+ Slice 2(后端联动),均真栈/真浏览器独立重跑绿。Slice 3 + Roadmap(C/D/E 大特性 + A7)如实标 NOT-MET,不计入"已完成"。**

### ✅ Slice 2 后端联动(commit `17652e7c8`,24 IT 独立重跑 0 fail)
| 项 | 状态 | 证据 |
|----|------|------|
| B1 在线保存结构守卫(硬拒 缺id/blank blockType/重复id;未知类型仅 warn,前向兼容 custom) | ✅ DONE | `PageSchemaBlockStructureValidator` + 12 单测 + IT 三态(合法v4/缺陷拒/未知前向兼容) |
| B2 publish/unpublish/version/rollback/compare 真栈 IT | ✅ DONE | `PageSchemaPublishVersionIntegrationTest` 8 IT 真 DB round-trip |
| B5 i18n 真链路 IT(不 mock service) | ✅ DONE | `PageSchemaI18nValidationFullStackIntegrationTest` 4 IT |
| B6 catch(Exception) 吞异常移除 | ✅ DONE | `canRollbackToVersion` 改 |
| B7 `page.page.manage` → MetaPermission 常量 | ✅ DONE | 7 处 inline 替换,permission 门禁 0 drift |
| **真栈 IT 揪出并修 5 个生产 bug**(版本/回滚链路,mocked 测试长期掩盖) | ✅ FIXED | @CurrentUserId Long/String 错配 500 / PageSchemaHistory.snapshot 错 typeHandler ClassCastException / pid 列名映射错 / op varchar(20) 溢出 / unpublish null-skip(updateById NOT_NULL) |

### ✅ Slice 1 测试覆盖(commit `d5fd98a7e`,17 E2E 独立重跑 0 flake,host-first 隔离 slot 38)
| 项 | 状态 | 证据 |
|----|------|------|
| A1 tautology 修 → 真选中态断言(并暴露+修 fixture 早坏) | ✅ DONE | `field-properties.spec.ts` 11/11 |
| A2 helper blocks inspector 隔离确定性 readback(bpm-panel/activity-timeline/field-history) | ✅ DONE | `inspector-authoring-golden.spec.ts` |
| A4 form-section(collapsible/visibleWhen/columns) | ✅ DONE | 同上 |
| A5 AI lock toggle→徽标→persisted aiLocked | ✅ DONE | 同上 |
| A6 Advanced JSON tab(valid persist + invalid 错误态不写回 sad-path) | ✅ DONE | 同上 |
| A8 dirty pill + leave-warning(**真零先验**) | ✅ DONE | 同上 |

### ✅ Slice 3 属性面板 UX(commit `15ce7d93c`,8 E2E 独立重跑 0 fail,host-first 隔离 slot 44)
| 项 | 状态 | 证据 |
|----|------|------|
| A3 widget 高级图表属性 E2E(thresholds/series/columns/rows/markdown/drillDownTo/refreshInterval/format,均运行时消费) | ✅ DONE | `widget-advanced-props-golden.spec.ts` 5/5(含 sad-path 无效 JSON 不写回)|
| D1 `type:'model'` → model 选择器(SchemaInspector 加 'model' 分支 + `useModelOptions()` 拉 published model 列表 + 手敲兜底前向兼容) | ✅ DONE | `inspector-model-select-golden.spec.ts` 3/3(下拉 68 真实选项 + select·manual 双路 persist)|
| D3 tabs/tab/action-bar/filter-bar 专属 inspector | ⏸ **诚实跳过** | 取证证 tab label 走 title 兜底、容器其它 props 运行时不消费 → 建 inspector 会引入假字段(§2.2),不做 |

### ✅ Slice 4 缺失行动点(commit `53f43a1fb`,16 E2E 合跑独立重跑 0 fail,host-first 隔离 slot 45)
| 项 | 状态 | 证据 |
|----|------|------|
| C1 Publish/Unpublish 行动点(统一 workbench 加按钮 + repository publishPageSchemaV3,只 pageId+非dirty 可点)| ✅ DONE | `publish-export-import-golden.spec.ts`:真按钮→POST→**后端 GET 反查** status=published/draft + publishedAt;sad 无 pid 禁用 |
| C2 Export/Import 行动点(export 序列化 download `<key>.page.json` / import file→parse+shape 校验→载入入 undo 栈) | ✅ DONE | export 真 `waitForEvent('download')` 验**文件内容** schemaVersion 3 + blocks;import `setInputFiles`→canvas+save readback;sad 非法/v2 JSON 内联错不替换 |

### ✅ Slice 5 C3 Version history + Rollback UI(commit `5b836a4a`+`56c1a36b`,3 E2E + 206 designer 单测独立重跑 0 fail,host-first 隔离 slot 51)
| 项 | 状态 | 证据 |
|----|------|------|
| C3 Version history 面板 + Create snapshot + Rollback 行动点(后端端点 #711 已证明;repository getPageVersions/createPageVersion/rollbackPageToVersion + VersionHistoryPanel.tsx drawer + 工具栏 designer-versions 按钮) | ✅ DONE | `version-history-golden.spec.ts` 3/3:snapshot→列表增→编辑+save→2nd snapshot→rollback 最早版本→**画布回显 + `GET /api/pages/{pid}` 后端反查 blocks 恢复 + version 号增**;sad 无 pid 禁用 / 取消确认不回滚 |
| 🐛 #717 model-select 单测回归(本 slice 顺手修)| ✅ FIXED | #717 把 `dataSource.model` 改 `<select>`+manual 后,2 个 UnifiedDesignerWorkbench 单测仍 fireEvent 驱动空 select 致 model 留空(jsdom 无 model 列表)→ 改用 `-manual` fallback input 绑定;SchemaInspector 未改,全量 206 designer 单测绿 |
| C3 diff/compare UI(commit `d766a438`,Slice 6)| ✅ DONE | `VersionHistoryPanel` Compare 模式(选两版本→`version-compare-run`)+ diff 视图(`version-diff-summary` + 差异行 ADDED/REMOVED/MODIFIED badge + 源→目标值 + 空态);repository `comparePageVersions`;`version-diff-golden.spec.ts` 2/2 真栈后端 compare(happy modifiedFields≥1 + sad 相同快照 totalDifferences=0)。**粗粒度如实**:REST compare 顶层 key 级(blocks 整 blob+title+rowVersion),UI 显示真实响应不造前端 drill-down。顺带修 studio VersionHistoryPanel 大小写 latent bug(`'added'` 永不匹配 UPPERCASE enum→case-insensitive)。**C3 三件套 list+rollback+diff 全闭环** |

### ✅ Slice 7 E1 widget 新 chart 类型(commit `9c7dfdbe`,3 chart golden + 209 单测独立重跑 0 fail,host-first 隔离 slot 8)
| 项 | 状态 | 证据 |
|----|------|------|
| E1(部分)widget +3 chart 类型 pie/area/progress 端到端 | ✅ DONE | inspector widgetType +3 选项 + **真 runtime mini-renderer**(`RuntimePieChart` SVG 扇形 / `RuntimeAreaChart` SVG 填充 path / `RuntimeProgressWidget` 阈值带百分比条)+ `WIDGET_BODY_TYPES` guard(progress 读 props.value 跳过 number-card 框);`widget-chart-types-golden.spec.ts` 3/3 真栈(save readback widgetType+props + runtime 真渲染非空 DOM:扇形/填充/进度条 + 截图复核);advanced-props 5/5 无回归 |
| §15 修正 | 📌 记录 | 派发前取证发现 widget runtime 是手写 5 种 mini-renderer **非 SharedChartFactory** → 加新 type 必须配真渲染器(非单纯加选项,否则假选项 §2.2)。本切片加 3 type;**全 24 type parity = widget runtime 统一到 SharedChartFactory,是更大 follow-up** |

### ✅ Slice 8 C5 画布多选 + 批量删除(commit `2b0f91ef`,4 E2E + 216 单测独立重跑 0 fail,host-first 隔离 slot 48)
| 项 | 状态 | 证据 |
|----|------|------|
| C5 画布多选 + 批量删除 | ✅ DONE | 独立 `multiSelectedIds: Set`(**不动 selectedBlockId 拖放上下文**,仅 5 行删除纯增量);shift/cmd-click 多选 + `multi-select-bar`(N selected + 批量删除 + 清除)+ 批量删除(跳过不可删 root,单次 undo)+ `data-multi-selected` 视觉;`canvas-multiselect-golden.spec.ts` 4/4 真栈(多选→批量删→**PUT save→GET readback 持久化删除**→undo 恢复;edge 普通click收起/重复 modifier 取消;sad clear 不删);顺手修 CanvasHost BlockContent pass-through 漏传(破坏嵌套 block)。inspector-authoring 6/6 无回归 |
| C5 box-select 几何框选(commit `3dac2092`,Slice 9)| ✅ DONE | 空白 canvas 拖拽画 marquee(`marquee-rect`)选中相交 block 进 C5 `multiSelectedIds`;**命中逻辑抽纯函数 `marqueeHitTest`(rectFromPoints + blocksWithinMarquee)+ 单测**(相交/包含/部分/不交/顺序);`onHostPointerDown` 只在空白区起(`isOnBlockOrInteractive` 跳过 block/交互元素 + 6px 阈值)→ 不破坏 block 选择/widget move/拖放;`canvas-box-select-golden.spec.ts` 3/3 **连跑 3 次稳定**(marquee 选 2/全 3 + sad 阈值下不选);multiselect 回归 4/4。**C5 多选+批量+框选全闭环** |

### ✅ Slice 10 E2 workbench 块 palette 首切片(commit `51cb40f8`,4 E2E + 242 单测独立重跑 0 fail,host-first 隔离 slot 48)
| 项 | 状态 | 证据 |
|----|------|------|
| E2(部分)metric-strip + status-banner 可视化 authoring | ✅ DONE | BlockRegistry 注册 2 块(+allowedChildren+kindPolicy)+ inspector(**bare-path key** `metrics`/`toneMap`/`statusField` 非 props.*,匹配平台渲染器 `block.metrics` 顶层读,已对真实页 mfg_andon/bom-std 取证)+ `RecursiveBlockRenderer` 代表性预览(`RuntimeMetricStripPreview`/`StatusBannerPreview`,config-driven 占位);`workbench-blocks-authoring-golden.spec.ts` 4/4:A1 metric-strip/A2 status-banner authoring+readback+预览、A3 非法 JSON sad、**L1 live `/p/c/{key}` 真平台渲染器显绑定数据(Pending=7/Ready=3)证明端到端可用**;widget 5/5 无回归 |
| §15 架构边界 | 📌 记录 | 设计器自有 runtime(DslBlockV3)≠ 平台 meta rendering(BlockConfig)两套;**按 §15 取证未做完整两 runtime 桥接** → 设计器内代表性预览,完整数据绑定渲染由 live `/p/` 平台 runtime 负责。bounded 不冒架构险 |

### ✅ Slice 11 E2 workbench 块批次2(commit `6fd2d3a5`,12 E2E + 259 单测独立重跑 0 fail,host-first 隔离 slot 30)
| 项 | 状态 | 证据 |
|----|------|------|
| E2 再加 6 块补全 workbench-family(workbench-action-bar/review-drawer/evidence-panel/record-inspector/candidate-list/artifact-timeline)| ✅ DONE | 照 Slice 10 范式:BlockRegistry+kindPolicy 注册 6 块(record-inspector 含子块 allowedChildren)+ inspector bare-path keys(`actions`/`sections`/`fields`/`item`/`candidates`/`compare`/`summaryBadges` 等,**读各平台渲染器顶层 prop,无假字段**)+ `RecursiveBlockRenderer` 6 个代表性预览;`workbench-blocks-batch2-authoring-golden.spec.ts` 8/8(B1-B6 每块 authoring+readback〔props 落块顶层〕+预览、B7 非法 JSON sad、**L1 live `/p/c/` 真平台渲染器 + 候选→record-inspector/evidence-panel/review-drawer 交互状态绑定**);Slice 10 baseline 4/4 无回归。**workbench-family 现共 8 块在设计器** |

### ✅ Slice 12 E2 非 family 展示/数据块(commit `be9b2f20`,6 E2E + 272 单测独立重跑 0 fail,host-first 隔离 slot 71)
| 项 | 状态 | 证据 |
|----|------|------|
| E2 加 4 个非 family 块(stat-card / description / record-comments / embedded-list)| ✅ DONE | 照范式逐块读平台渲染器取真 prop 路径:stat-card(`dataSource`+`statCard` obj)/ description(`content`,bare+props 混合处理)/ record-comments(title only,无 block 级数据)/ embedded-list(bare `modelCode`/`parentField`/`columns`/`pageSize`/`searchable`/`filterable`);BlockRegistry+kindPolicy+inspector(无假字段)+代表性预览;`display-blocks-authoring-golden.spec.ts` 6/6(B1-B4 authoring+readback、B5 非法 JSON sad、**L1 live `/p/c/` 真平台渲染器 stat-card 真值 128〔ds_orders.open_total〕+ embedded-list RecordListView**)。**设计器内置 blockType 现 36(原 32)** |
| 诚实 caveat | 📌 记录 | record-comments **无 L1 live 渲染**(仅 detail-page 路径派发、modelCode/recordPid 由页面+记录上下文推导、无 block 级数据可绑)—— 平台固有约束非 shortcut,已注释;palette+inspector+预览+authoring 真绿 |

### ⏸ NOT-MET(roadmap,**未完成,不假报**)
- **A7** mid-drag drop-indicator/ghost 视觉断言(@dnd-kit 中途手势最易 flake,ROI 最低)→ defer。
- **A11/A12** chart 类型广度 / input·layout 广度 → defer。
- **E1(剩余)** widget 全 24 chart parity(runtime 统一 SharedChartFactory)/ **E2(剩余)** 余 12 个非 family 块(chart/trace-graph/form-buttons/form-wizard/filters/toolbar/monthly-grid/divider/rich-text/gerber-viewer/selection-info/text)palette + 完整数据绑定设计器渲染 → 大特性,未做(**workbench-family 8 块 + 4 展示/数据块已交付,设计器现 36 blockType**)。
- **C4** kind 切换 → 未做(C5 多选+批量+框选已全交付)。
- **D2/D4** 富属性控件全接入(dict/namedQuery/command/permission 选择器)、字段级校验反馈;**B3** REST diff blocks 下钻 → 未做。
- **🧪 测试鲁棒性 follow-up** — ✅ 已由 Slice 9(`3dac2092`)闭环:`inspector-model-select-golden:152` 改 seed-agnostic 断言(SELECT + option ≥2),不再锁定具体 model;leaner seed 栈也过。本行历史保留。
- **🐛 ViewModelService latent bug** — ✅ 已由 #725 闭环(读 `data.records`),本行历史保留。

### 复核五项结论(P5)
1. **方向** ✅ 对齐:增量硬化"测试覆盖+后端联动",未漂移;大特性如实划入 roadmap。
2. **进度** ✅ Slice 1+2 逐项 DONE(commit/IT/E2E 证据);NOT-MET 清单完整无"标 DONE 实际没做"。
3. **gap** ✅ 复扫发现 A2 高估(已纠)+ 新记 gate-gap(unified designer 须 schemaVersion 3 / detail 不能直含 helper block 须嵌 columns·tab,错 seed 则 save 静默不触发)+ showcase 模型依赖。
4. **UX 截图** ✅ E2E 每步截图存 `web-admin/test-results`;断言为 PUT save + GET readback `toMatchObject` + 视觉态(徽标/错误态/dirty pill)真断言。
5. **测试完备性** ✅ 后端 24 IT + 前端 17 E2E,均**主对话独立重跑**绿(非仅信 subagent 自报);targeted 维度 feature 覆盖清零,NOT-MET 显式列出。
