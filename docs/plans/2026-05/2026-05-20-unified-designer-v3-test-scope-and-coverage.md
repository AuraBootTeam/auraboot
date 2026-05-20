# Unified Designer Workbench V3 测试范围与完整度审计

日期：2026-05-20

## 背景

Unified Designer Workbench V3 的目标不是单独优化 List Designer，而是统一承载四类页面：

- Form
- List
- Detail
- Dashboard

这意味着测试不能只看“Workbench 能打开、能保存、能预览”。完整验收必须覆盖：

- 每类页面支持哪些 block/component。
- 每个 component 的 Inspector 属性是否能选择、编辑、写回。
- Palette / model field 是否能拖拽到合法画布位置。
- 画布内组件是否能交换位置或调整布局。
- Runtime 是否能按 V3 schema 渲染。
- 保存后重新打开是否保持语义。

本审计依据当前实现清单与测试清单交叉比对：

- 实现清单：
  - `web-admin/app/plugins/core-designer/components/unified-designer/registry/BlockRegistry.ts`
  - `web-admin/app/plugins/core-designer/components/unified-designer/registry/InspectorSchemaRegistry.ts`
  - `web-admin/app/plugins/core-designer/components/unified-designer/runtime/RecursiveBlockRenderer.tsx`
  - `web-admin/app/plugins/core-designer/components/unified-designer/canvas/CanvasHost.tsx`
- 测试清单：
  - `web-admin/tests/e2e/designer/unified-designer-workbench.spec.ts`
  - `web-admin/app/plugins/core-designer/components/unified-designer/__tests__/*.test.ts*`

## Form 与 model 依赖关系

以 Form 为例，当前设计器不应强制依赖 model 才能设计页面。Form authoring 实际分为两条路径：

| 路径 | 是否依赖 model | 说明 |
|------|----------------|------|
| Blocks palette 拖普通组件 | 否 | `form-section`、`field`、`action-bar`、`sub-table`、`repeater`、`subform` 等 block 可以直接拖到画布。普通 `field` 默认生成 `field: new_field`，后续通过 Inspector 手工配置 label、component、field code。 |
| Fields palette 拖 model field | 是 | 只有拖入真实模型字段时需要当前选区能解析出 `dataSource.model` 或页面 `modelCode`。拖入后会自动带入 `field`、label、component、dataType、dictCode、required 等模型元数据。 |

因此测试范围必须同时覆盖：

- `Model-backed Form`：验证模型字段池、模型字段拖拽、字段元数据自动回填。
- `Free-form Form`：验证没有模型字段也可以通过 Blocks palette 创建表单结构、字段、动作和布局。

验收口径不能把“模型字段拖拽可用”等同于“Form 设计器可用”。一个专业设计器需要允许用户先搭结构，再决定是否绑定模型字段。

## 完整测试范围

### 1. Form 页面

#### 组件范围

| 组件 | 说明 |
|------|------|
| `form` | 页面根容器，绑定 model、承载 sections/actions |
| `form-section` | 字段分组，支持标题、描述、折叠、可见条件、列数、span |
| `field` | 表单字段，支持 model field 绑定与多种 component |
| `action-bar` / `action` | 表单按钮，依附 form/action-bar |
| `sub-table` | 表单内子表，支持 columns/actions/preview rows |
| `repeater` | 简单重复行字段编辑器 |
| `subform` | 子表单行编辑器，行内可承载 field 或 section |
| `tabs` / `tab` | 表单内分组布局容器 |
| `ai-fill-banner` | 表单辅助能力入口，已纳入正式作者态和 runtime renderer 覆盖 |

#### 属性范围

Form 页面至少应覆盖：

- `form.title`
- `form.dataSource.model`
- `form.layout.span`
- `form-section.title`
- `form-section.props.description`
- `form-section.props.collapsible`
- `form-section.props.visibleWhen`
- `form-section.layout.columns`
- `field.field`
- `field.props.label`
- `field.props.component`
- `field.props.required`
- `field.props.readOnly`
- `field.props.placeholder`
- `field.props.helpText`
- `field.props.options`
- `field.props.visibleWhen`
- `field.props.validationRules`
- `field.layout.span`
- picker/rich-text/upload/radio/select 等 component-specific props
- `sub-table.dataSource.*`
- `sub-table.props.rows`
- `repeater.props.rows`
- `subform.props.rows`
- `action.actionType`
- `action.props.*`

#### 测试用例大纲

| 用例 ID | 用例 |
|---------|------|
| UDW-FORM-001 | 打开 Form V3 页面，Outline/Canvas/Inspector 选中联动 |
| UDW-FORM-002 | 从 Fields palette 拖 model field 到 `form-section`，生成 `field` block |
| UDW-FORM-003 | 从 Blocks palette 拖 `field` 到 `form-section`，编辑 label，保存重开 |
| UDW-FORM-004 | 编辑 `form-section` 的 title/description/collapsible/visibleWhen/columns/span，保存重开 |
| UDW-FORM-005 | 字段 component 覆盖：input/textarea/select/date/number/checkbox/switch/radio/upload/picker/rich-text |
| UDW-FORM-006 | 字段 required/readOnly/placeholder/helpText/options/visibleWhen/validationRules 写回并 runtime 生效 |
| UDW-FORM-007 | 布局态拖拽交换两个字段位置，保存重开校验顺序 |
| UDW-FORM-008 | 布局态修改 field span，保存重开校验 layout |
| UDW-FORM-009 | 添加 `action-bar/action`，配置 submit/command/workflow/navigate/modal/drawer/create，runtime 执行 |
| UDW-FORM-010 | 添加 `sub-table`，拖 column/action，配置 rows，preview 渲染并保存 |
| UDW-FORM-011 | 添加 `repeater`，拖 field，runtime 编辑多行并提交到 action payload |
| UDW-FORM-012 | 添加 `subform`，拖 field/section，runtime 编辑多行并提交到 action payload |
| UDW-FORM-013 | 添加 `tabs/tab`，tab 内放 section/field/action，保存重开并 preview 渲染 |
| UDW-FORM-014 | `ai-fill-banner` 应覆盖标题、描述、建议字段、反馈文案、保存重开、runtime 专属 renderer 和 apply 交互 |

### 2. List 页面

#### 组件范围

| 组件 | 说明 |
|------|------|
| `list` | 列表根容器，绑定 model、selectionMode |
| `filter-bar` | 筛选区容器 |
| `filter-field` | 筛选字段，支持 operator 和 field-select |
| `action-bar` / `action` | 工具栏按钮 |
| `table` | 表格容器 |
| `column` | 表格列 |
| `action` in `table` | 行操作按钮 |
| `widget` | 列表页辅助指标/图表 block |
| `tabs` / `tab` | 列表视图/过滤分组 |

#### 属性范围

- `list.title`
- `list.dataSource.model`
- `list.props.selectionMode`
- `filter-field.field`
- `filter-field.props.label`
- `filter-field.props.operator`
- `filter-field.props.component`
- `column.field`
- `column.props.label`
- `column.layout.width`
- `column.props.align`
- `table` 子级 column/action 顺序
- `action.actionType`
- `action.region=row-actions` 行动作默认值
- `widget.*` 若放入 list 页面

#### 测试用例大纲

| 用例 ID | 用例 |
|---------|------|
| UDW-LIST-001 | 打开 List V3 页面，Outline/Canvas/Inspector 选中联动 |
| UDW-LIST-002 | 拖 model field 到 `table`，生成 `column`，编辑 label/width/align，保存重开 |
| UDW-LIST-003 | 拖 model field 到 `filter-bar`，生成 `filter-field`，编辑 operator/component，保存重开 |
| UDW-LIST-004 | 拖 action 到 `action-bar`，配置 toolbar action，preview 执行 |
| UDW-LIST-005 | 拖 action 到 `table`，自动标记 row action，preview 点击行按钮并带 currentRow payload |
| UDW-LIST-006 | 布局态交换两个 column 顺序，保存重开 |
| UDW-LIST-007 | 布局态交换两个 toolbar action 顺序，保存重开 |
| UDW-LIST-008 | selectionMode=multiple，选中表格行，toolbar action payload 包含 selectedRows |
| UDW-LIST-009 | 添加 `tabs/tab`，每个 tab 内配置 filter/table/action，保存重开并 preview 渲染 |
| UDW-LIST-010 | 添加 `widget` 到 List 页面，配置 widget 属性，preview 渲染并保存 |
| UDW-LIST-011 | 若 runtime preview 承诺支持真实过滤，则输入 filter-field 后表格行应变化；否则在文档中明确 preview 只渲染筛选控件 |

### 3. Detail 页面

#### 组件范围

| 组件 | 说明 |
|------|------|
| `detail` | 详情根容器 |
| `detail-section` | 详情字段分组 |
| `field` | 详情字段 |
| `sub-table` | 关联子表 |
| `repeater` | 详情页重复行展示/编辑 |
| `subform` | 详情页子表单行编辑 |
| `action-bar` / `action` | 详情页按钮 |
| `widget` | 详情页指标/辅助信息 |
| `tabs` / `tab` | 详情页 tab 分区 |
| `bpm-panel` / `activity-timeline` / `field-history` | 工作流辅助块 |

#### 属性范围

- `detail.title`
- `detail.dataSource.model`
- `detail-section.title`
- `detail-section.props.description`
- `detail-section.props.visibleWhen`
- `field.*`
- `sub-table.*`
- `repeater.props.rows`
- `subform.props.rows`
- `action.*`
- `widget.*`
- workflow helper blocks 的 title、layout、状态、动作、timeline items、history entries 等结构化属性

#### 测试用例大纲

| 用例 ID | 用例 |
|---------|------|
| UDW-DETAIL-001 | 打开 Detail V3 页面，Outline/Canvas/Inspector 选中联动 |
| UDW-DETAIL-002 | 拖 model field 到 `detail-section`，生成 field，编辑 label，保存重开，preview 渲染 |
| UDW-DETAIL-003 | 编辑 `detail-section` 属性并保存重开 |
| UDW-DETAIL-004 | 布局态交换 detail fields 顺序并保存 |
| UDW-DETAIL-005 | 添加 `sub-table` 到 detail-section，配置 columns/rows/action，preview 渲染并保存 |
| UDW-DETAIL-006 | 添加 `repeater` 到 detail-section，拖 field，preview 渲染并保存 |
| UDW-DETAIL-007 | 添加 `subform` 到 detail-section，拖 field/section，preview 渲染并保存 |
| UDW-DETAIL-008 | 添加 `action-bar/action`，覆盖 command/workflow/navigate/modal/drawer/create 的属性与 runtime |
| UDW-DETAIL-009 | 添加 `tabs/tab`，tab 内放 detail-section/sub-table/workflow helper，保存重开并 preview |
| UDW-DETAIL-010 | `bpm-panel/activity-timeline/field-history` 应覆盖结构化 Inspector、保存重开和 runtime 专属 renderer |

### 4. Dashboard 页面

#### 组件范围

| 组件 | 说明 |
|------|------|
| `dashboard` | 仪表盘根容器，控制 grid cols/rowHeight/gap |
| `widget` | 指标卡、图表、表格、Markdown 等 |

#### 属性范围

- `dashboard.title`
- `dashboard.layout.cols`
- `dashboard.layout.rowHeight`
- `dashboard.layout.gap`
- `widget.widgetType`
- `widget.dataSource.type`
- `widget.dataSource.model`
- `widget.dataSource.metric`
- `widget.dataSource.executionMode`
- `widget.dataSource.query`
- `widget.dataSource.queryCode`
- `widget.dataSource.parameters`
- `widget.props.title`
- `widget.props.subtitle`
- `widget.props.value`
- `widget.props.format`
- `widget.props.emptyText`
- `widget.props.errorText`
- `widget.props.drillDownTo`
- `widget.props.thresholds`
- `widget.props.series`
- `widget.props.columns`
- `widget.props.rows`
- `widget.props.markdown`
- `widget.props.refreshInterval`
- `widget.layout.x/y/w/h`

#### 测试用例大纲

| 用例 ID | 用例 |
|---------|------|
| UDW-DASH-001 | 打开 Dashboard V3 页面，Outline/Canvas/Inspector 选中联动 |
| UDW-DASH-002 | 编辑 dashboard grid 属性 cols/rowHeight/gap，保存重开 |
| UDW-DASH-003 | 从 Blocks palette 拖 `widget` 到 dashboard，生成新 widget，保存重开 |
| UDW-DASH-004 | 布局态拖动 widget 改变 x/y，保存重开 |
| UDW-DASH-005 | 布局态 resize widget 改变 w/h，保存重开 |
| UDW-DASH-006 | widget overlap 时拒绝移动并保持原 layout |
| UDW-DASH-007 | number-card 属性写回与 runtime 渲染 |
| UDW-DASH-008 | bar-chart/line-chart series 写回与 runtime 渲染 |
| UDW-DASH-009 | table widget columns/rows 写回与 runtime 渲染 |
| UDW-DASH-010 | markdown widget 写回与 runtime 渲染 |
| UDW-DASH-011 | query-builder live dataSource 执行，API 响应非空，runtime 渲染 |
| UDW-DASH-012 | namedQuery live dataSource 执行，API 响应非空，runtime 渲染 |
| UDW-DASH-013 | widget runtime error/empty state 渲染 |

## 当前测试完整度审计

状态定义：

- `✅ 深度`：UI 操作 + 属性写回 + 保存重开 + runtime/数据断言。
- `✅ 作者态深度`：组件拖拽 + 属性写回 + 保存重开 + runtime 专属渲染已覆盖；真实后端数据源另属产品集成范围。
- `✅ 单测深度`：组件/函数级行为覆盖充分，但没有浏览器级路径。
- `⚠️ 浅覆盖`：有渲染或迁移测试，但缺少浏览器级拖拽/保存/重开/runtime 闭环。
- `❌ 缺失`：没有发现对应测试。

### Form 页面覆盖

| 组件/能力 | 当前证据 | 状态 | 缺口 |
|-----------|----------|------|------|
| Form 根容器选择/渲染/保存 | UDW-001, UDW-005；Workbench 单测 | ✅ 深度 | 无核心缺口 |
| model field 拖入 form-section | UDW-001 | ✅ 深度 | 无 |
| palette field 拖入 form-section | UDW-006 | ✅ 深度 | 无 |
| form-section 属性 | UDW-009；v3-utils inspector schema | ✅ 深度 | 无 |
| field 基础属性 | UDW-001, UDW-006, UDW-009 | ✅ 深度 | 无 |
| field component：checkbox/textarea | UDW-021 | ✅ 深度 | 无 |
| field component：picker/rich-text | UDW-027, UDW-030, UDW-031, UDW-032, UDW-033 | ✅ 深度 | 无 |
| field component：upload | UDW-028 | ✅ 深度 | 无 |
| field component：select/radio | UDW-024, UDW-037 | ✅ 深度 | 无 |
| field component：date/number/switch | UDW-045；Runtime 单测覆盖 component 分支 | ✅ 深度 | 无 |
| required/validationRules | UDW-022；runtime 单测 | ✅ 深度 | 无 |
| visibleWhen | UDW-029；runtime 单测 | ✅ 深度 | 无 |
| 字段交换/排序 | UDW-007, UDW-025；Workbench 单测 | ✅ 深度 | 无 |
| span/layout quick controls | Workbench 单测 | ✅ 单测深度 | 建议补浏览器级保存重开 |
| action-bar/action | UDW-012, UDW-017, UDW-034, UDW-035；runtime 单测 | ✅ 深度 | Form 上 action runtime 有覆盖，部分 action 类型主要在 List 页面覆盖 |
| sub-table | UDW-023；runtime/Workbench 单测 | ✅ 深度 | 无 |
| repeater | UDW-038；runtime/Workbench 单测 | ✅ 深度 | 无 |
| subform | UDW-039；runtime/Workbench 单测 | ✅ 深度 | 无 |
| tabs/tab | v3-utils migration/containment 覆盖 | ⚠️ 浅覆盖 | 缺 Form 浏览器级 tabs author/save/render |
| ai-fill-banner | UDW-047 覆盖 palette 拖拽、标题/描述/建议字段/反馈属性、dataSource 字段、保存重开、runtime 专属 AI suggestion panel；UDW-049 覆盖 live namedQuery 成功态；UDW-051 覆盖 live empty/error 状态；UDW-052 覆盖 `permissionCode` Inspector 与 runtime gating；runtimeExecution 覆盖 namedQuery 数据映射 | ✅ 作者态 + dataSource contract + empty/error + permission | AI 真实生成和字段回填仍需单独产品集成设计 |

### List 页面覆盖

| 组件/能力 | 当前证据 | 状态 | 缺口 |
|-----------|----------|------|------|
| List 根容器选择/渲染/保存 | UDW-005；Workbench 单测 | ✅ 深度 | 无 |
| filter-bar/filter-field | UDW-002；runtime 单测 | ✅ 深度 | 只验证控件渲染和属性保存，未验证真实过滤语义 |
| table/column | UDW-002, UDW-005, UDW-007, UDW-026 | ✅ 深度 | 无 |
| toolbar action | UDW-003, UDW-007, UDW-012, UDW-014, UDW-015, UDW-018 | ✅ 深度 | 无 |
| row action | UDW-019, UDW-020；v3-persistence | ✅ 深度 | 无 |
| list selection + selectedRows payload | UDW-018；runtime 单测 | ✅ 深度 | 无 |
| column/action 交换位置 | UDW-007, UDW-026 | ✅ 深度 | 无 |
| action permission | UDW-034, UDW-035, UDW-036 | ✅ 深度 | 无 |
| widget in List | UDW-046；runtime widget 单测覆盖 widget 本身 | ✅ 深度 | 无 |
| tabs/tab | UDW-041；v3-utils migration/containment 覆盖 | ✅ 深度 | 无 |
| filter runtime behavior | UDW-041；RecursiveBlockRenderer 单测 | ✅ 深度 | 已支持 preview 输入筛选后 table 行变化 |

### Detail 页面覆盖

| 组件/能力 | 当前证据 | 状态 | 缺口 |
|-----------|----------|------|------|
| Detail 根容器选择/渲染/保存 | UDW-008 | ✅ 深度 | 基础路径覆盖 |
| detail-section + field | UDW-008；v3-utils migration | ✅ 深度 | 基础路径覆盖 |
| detail field 属性 | UDW-008；UDW-050 | ✅ 深度 | 覆盖 model field 拖入、label/helpText/component 写回、保存重开和 runtime |
| detail field 交换/布局 | UDW-050 | ✅ 深度 | 覆盖 layout mode 画布拖拽换位、保存重开和 runtime 顺序 |
| detail sub-table | UDW-042 | ✅ 深度 | 覆盖 Detail 页面专项 add/config/render/save |
| detail repeater | UDW-042 | ✅ 深度 | 覆盖 Detail 页面专项 add/config/render/save |
| detail subform | UDW-042 | ✅ 深度 | 覆盖 Detail 页面专项 add/config/render/save |
| detail action-bar/action | UDW-042 | ✅ 深度 | 覆盖 Detail 页面专项 action runtime audit context |
| detail widget | UDW-042 | ✅ 深度 | 覆盖 Detail 页面内 widget 添加和 preview |
| tabs/tab | UDW-047；v3-utils migration 覆盖 | ✅ 深度 | 无 |
| bpm-panel/activity-timeline/field-history | UDW-047 覆盖 palette 拖拽、结构化属性、dataSource 字段、保存重开、runtime 专属 BPM/timeline/history renderer；UDW-049 覆盖 live namedQuery 成功态；UDW-051 覆盖 live empty/error 状态；UDW-052 补齐 helper `permissionCode` Inspector 字段与 runtime gating；runtimeExecution 覆盖 namedQuery/query-builder 数据映射；v3-utils 接受 legacy blocks | ✅ 作者态 + dataSource contract + empty/error + permission | Workflow/timeline/history 的真实业务查询需后续产品集成专项 |

### Dashboard 页面覆盖

| 组件/能力 | 当前证据 | 状态 | 缺口 |
|-----------|----------|------|------|
| Dashboard 根容器渲染/保存 | UDW-005；v3-utils dashboard migration | ✅ 深度 | 无 |
| dashboard grid 属性 | UDW-048；Workbench 单测覆盖 `layout.cols/rowHeight/gap` | ✅ 深度 | 无 |
| widget 属性写回 | UDW-004, UDW-010, UDW-011, UDW-013, UDW-016 | ✅ 深度 | 无 |
| widget resize | UDW-004；Workbench 单测 | ✅ 深度 | 无 |
| widget move x/y | UDW-044；Workbench 单测 | ✅ 深度 | 无 |
| widget overlap rejection | UDW-044；Workbench 单测 | ✅ 深度 | 无 |
| number-card | UDW-005, UDW-010 | ✅ 深度 | 无 |
| bar/line chart | UDW-010, UDW-011；runtime 单测 | ✅ 深度 | 无 |
| table widget static/live query | UDW-013, UDW-016；runtime 单测 | ✅ 深度 | 无 |
| markdown widget | UDW-011；runtime 单测 | ✅ 深度 | 无 |
| live query-builder dataSource | UDW-013 | ✅ 深度 | 可增加 `records.length > 0` 的明确断言，当前用 row count 验证 |
| live namedQuery dataSource | UDW-016 | ✅ 深度 | 无 |
| widget empty/error/permission states | runtime 单测 | ✅ 单测深度 | 浏览器级不是必须，但可补 smoke |
| palette 添加新 widget | UDW-043；registry/template 支持；root/page block drop 单测 | ✅ 深度 | 无 |

## 完整度结论

### 已经达到的范围

现有测试已经覆盖 Unified Designer 的主干能力：

- 4 类页面都至少有打开、编辑、保存或 preview 证据。
- Form/List/Dashboard 三类页面的核心组件已有深度 E2E。
- model field 集成、Palette drag、Canvas reorder、Inspector 写回、Runtime preview、Save/reopen 都有真实浏览器证据。
- command/workflow/action runtime、form values、selected rows、current row、action/helper permission、widget/helper live dataSource 都有覆盖。
- sub-table/repeater/subform 三类复杂 form 组件已有浏览器级闭环。

### 不能声称“业务语义彻底完整”的范围

如果验收口径是“4 类页面的所有 registry 组件都必须有 component × 属性 × 拖拽 × runtime × save 的闭环”，当前作者态矩阵已经覆盖到当前 registry 范围。仍不能扩大表述到“业务语义彻底完整”：

1. `ai-fill-banner` 已有作者态、结构化建议字段、dataSource 配置、apply 反馈、runtime 专属 renderer、namedQuery 数据映射契约、live empty/error 状态以及 helper `permissionCode` gating；尚未实现 AI 生成和真实字段回填。
2. `bpm-panel/activity-timeline/field-history` 已有作者态、结构化 preview 数据、dataSource 配置、runtime 专属 renderer、helper `permissionCode` gating 和 namedQuery/query-builder 数据映射契约；尚未落具体 BPM 状态、活动流、字段历史业务查询定义。
3. Dashboard 根 `layout.cols/rowHeight/gap` 已有浏览器级保存重开与 runtime grid style 断言，不再是结构性缺口。

## 建议补测优先级

### P0：补齐 4 页面类型矩阵的结构性缺口

| 新用例 | 目标 |
|--------|------|
| UDW-040 | Form `tabs/tab`：拖 tabs、拖 tab、tab 内拖 section/field/action，保存重开，preview |
| UDW-041 | List `tabs/tab`：拖 tabs、tab 内放 filter/table/action，保存重开，preview |
| UDW-042 | Detail 复杂块：sub-table/repeater/subform/action/widget 至少各一个，保存重开，preview |
| UDW-043 | Dashboard 新增 widget：palette 拖入 dashboard，配置属性，保存重开，preview |
| UDW-044 | Dashboard widget drag move：浏览器级拖动 x/y，保存重开，校验 layout |

### P1：补齐属性分支

| 新用例 | 目标 |
|--------|------|
| UDW-045 | Form field date/number/switch 浏览器级属性选择、保存、preview |
| UDW-046 | List widget：在 List 页面拖 widget，配置 number-card/table，保存重开，preview |
| UDW-047 | Detail tabs + workflow helper：tabs 内放 bpm-panel/activity-timeline/field-history，明确 renderer 预期 |
| UDW-048 | Dashboard root grid：浏览器级配置 cols/rowHeight/gap，保存重开并断言 runtime grid style |
| UDW-049 | Helper live namedQuery：四类 helper block 走真实 namedQuery execute API 并渲染 |
| UDW-050 | Detail field：拖入 model field、属性写回、layout mode 交换、保存重开和 runtime 顺序 |
| UDW-052 | Helper permission：helper block 暴露 `props.permissionCode`，未授权时 runtime 不加载 live dataSource，已授权时正常渲染 |

### P2：产品范围决策

| 决策项 | 建议 |
|--------|------|
| `ai-fill-banner` | 作者态、runtime 专属 renderer、dataSource 数据映射契约、live empty/error 状态和 generic helper permission gating 已补；若要接入真实 AI 生成能力，需要另补生成、回填专项 |
| `filter-field` runtime filtering | 如果 preview 承诺真实过滤，补 UI 输入后 table 变化；否则文档明确 preview 只负责控件渲染 |
| workflow helper blocks | 作者态、runtime 专属 renderer、dataSource 数据映射契约和 generic helper permission gating 已补；若要接入真实 Detail 业务能力，需要定义并注册真实 BPM/timeline/history 查询 |

## 最小下一步

下一步最小可执行任务建议是：

1. 先补 `UDW-040 Form tabs/tab`，因为 tabs 是递归 blocks 模型的重要证明点。
2. 再补 `UDW-043 Dashboard palette add widget`，因为 Dashboard 目前更多是修改已有 widget，不是完整“拖组件到画布”路径。
3. 再补 `UDW-042 Detail complex blocks`，因为 Detail 页面当前覆盖深度明显弱于 Form/List/Dashboard。

完成 P0/P1 后，四类页面的“组件拖拽、属性配置、runtime 渲染、保存重开”矩阵已经覆盖到当前 registry 的作者态范围。

## 2026-05-20 执行记录

本轮已按上面的 P0 缺口补充测试和实现：

| 用例 | 覆盖目标 | 结果 |
|------|----------|------|
| `UDW-040` | Form 中从 Blocks palette 拖入 `tabs`、`tab`、`form-section`、普通 `field`，验证无 model field 依赖的自由表单结构、保存重开、runtime 渲染 | 已通过 |
| `UDW-042` | Detail 页面拖入并配置 `sub-table`、`repeater`、`subform`、`action-bar/action`、`widget`，保存重开，runtime 渲染并执行 action | 已通过 |
| `UDW-043` | Dashboard 页面从 Blocks palette 拖入新 `widget`，配置 markdown widget 属性、布局，保存重开并 runtime 渲染 | 已通过 |

本轮修复：

- `tabs/tab` 已加入 `createBlockTemplate`，解决 Registry 有定义但 palette 拖拽无法落块的问题。
- Runtime 新增 `RuntimeTabs`，`tabs` 不再只走 generic container，而是有 tab trigger、active panel 和空状态。
- E2E 前置 named-query 创建改为幂等 `ensureNamedQuery` / `ensureNamedQueryField`，避免 targeted 测试被重复测试数据或局部 400 响应挡住。
- Detail 复杂块测试中，repeater/subform 的模型字段显式切换为 `input`，避免 select 型模型字段因为 preview value 不在 options 中而误判容器能力失败。

验证命令：

```bash
PW_SKIP_WEBSERVER=1 PLAYWRIGHT_BASE_URL=http://localhost:5237 VITE_PORT=5237 BFF_PORT=3564 BFF_ALLOWED_PORTS=5237,6443 BFF_INTERNAL_URL=http://127.0.0.1:3564 NO_PROXY=localhost,127.0.0.1 npx playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --project=chromium --grep "UDW-0(40|42|43)" --reporter=line --no-deps --workers=1
```

结果：`3 passed`。

```bash
PW_SKIP_WEBSERVER=1 PLAYWRIGHT_BASE_URL=http://localhost:5237 VITE_PORT=5237 BFF_PORT=3564 BFF_ALLOWED_PORTS=5237,6443 BFF_INTERNAL_URL=http://127.0.0.1:3564 NO_PROXY=localhost,127.0.0.1 npx playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --project=chromium --grep "UDW-0(40|42|43)" --reporter=line --workers=1
```

结果：`21 passed / 1 skipped`，包含 Playwright setup/auth dependencies。

```bash
pnpm exec vitest run app/plugins/core-designer/components/unified-designer/__tests__/RecursiveBlockRenderer.test.tsx app/plugins/core-designer/components/unified-designer/__tests__/UnifiedDesignerWorkbench.test.tsx
```

结果：`2 passed / 73 passed`。

```bash
pnpm typecheck
```

结果：通过。

## 2026-05-20 helper permission 收口

本轮补齐 helper block 的 generic permission 语义，使 `props.permissionCode` 不只是 Action 专属属性：

- 四类 helper block 的 schema-driven Inspector 均暴露 `props.permissionCode`。
- `RecursiveBlockRenderer` 复用同一套 runtime permission evaluator。
- 未授权 helper 不再触发 `loadHelperBlockData`，因此不会执行 live namedQuery/query-builder。
- 已授权 helper 继续加载 live dataSource，并同时显示 `runtime-helper-permission-{id}` 与 `runtime-helper-source-{id}`，方便 E2E 精确断言。
- Action block 原有 `permissionCode` 语义保持不变。

新增验证：

| 用例 | 覆盖目标 | 结果 |
|------|----------|------|
| Runtime 单测 | `ai-fill-banner` 缺权限时不调用 `loadHelperBlockData`；权限允许时正常加载 live data | 已通过 |
| Inspector 单测 | 四类 helper schema 均包含 `props.permissionCode` | 已通过 |
| `UDW-052` | 浏览器级配置 helper `permissionCode`，preview 中缺权限 helper 不发 blocked query，请求允许 helper 正常执行 live namedQuery | 已通过 |

分层验证：

```bash
pnpm typecheck
```

结果：通过。

```bash
pnpm exec vitest run app/plugins/core-designer/components/unified-designer/__tests__
```

结果：`10 passed / 137 passed`。

```bash
NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 VITE_PORT=5237 BFF_PORT=3564 PW_PROFILE=full PW_WORKERS=1 pnpm exec playwright test -c playwright.config.ts tests/e2e/designer/unified-designer-workbench.spec.ts --project=chromium --grep "UDW-052" --reporter=line --no-deps
```

结果：`1 passed`。

```bash
NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 VITE_PORT=5237 BFF_PORT=3564 PW_PROFILE=full PW_WORKERS=1 pnpm exec playwright test -c playwright.config.ts tests/e2e/designer/unified-designer-workbench.spec.ts --project=chromium --grep "UDW-049|UDW-050|UDW-051|UDW-052" --reporter=line --no-deps
```

结果：`4 passed`。

```bash
NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 VITE_PORT=5237 BFF_PORT=3564 PW_PROFILE=full PW_WORKERS=1 pnpm exec playwright test -c playwright.config.ts tests/e2e/designer/unified-designer-workbench.spec.ts --project=chromium --reporter=line --no-deps
```

结果：`52 passed`。

```bash
NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 VITE_PORT=5237 BFF_PORT=3564 PW_PROFILE=full PW_ROLE_PROJECTS=1 PW_WORKERS=1 pnpm exec playwright test -c playwright.config.ts tests/e2e/designer/unified-designer-workbench.spec.ts --project=chromium --reporter=line
```

结果：`70 passed / 1 skipped`，包含 Playwright setup/auth dependencies。

E2E truth 自审：

- `waitForTimeout/test.skip/test.fixme/retries/page.goto('/p/')`：0。
- `toBeLessThanOrEqual/toBeGreaterThanOrEqual`：0。
- `test.only/describe.only/fit`：0。
- UI `click/fill/drag/select`：709。
- `page.request/request.*`：21，集中在 setup/fixture/权限读取/保存结果读取；核心拖拽、Inspector 编辑、preview、runtime 权限 gating 均走 UI 路径。
- `page.request.put`：1 处，为 beforeAll 中幂等维护 namedQuery field 的 setup fallback，不是绕过设计器保存或组件属性写回的产品路径。

更新后的剩余缺口：

- `ai-fill-banner` 的真实 AI 生成与字段回填仍未接入，需要后续 AI 产品专项。
- `bpm-panel/activity-timeline/field-history` 的真实 BPM 状态、活动流、字段历史查询仍未定义，需要后续业务查询/权限资源设计。
- 现有 helper permission 是 block-level gating；若未来要做到字段级、动作级或数据行级权限，需要在业务查询和 command/workflow 层另行建模。

```bash
git diff --check
```

结果：通过。

当前剩余缺口从 P0 降为 P1/P2：

- Detail `tabs/tab` 已在后续 UDW-047 中补齐浏览器级 author/save/render。
- `ai-fill-banner`、`bpm-panel/activity-timeline/field-history` 已在后续 UDW-047 中补齐结构化作者态、dataSource 配置和 runtime 专属 renderer；runtimeExecution 已补 namedQuery/query-builder 数据映射契约。若要纳入真实业务能力，还需要业务查询、权限和失败态专项。

## 2026-05-20 深度补测记录

本轮按“黄金测试”口径继续补齐 P1 缺口：每个新增用例都要求真实 UI 拖拽或选择、Inspector 属性写回、保存重开、runtime 渲染或行为断言。

| 用例 | 覆盖目标 | 结果 |
|------|----------|------|
| `UDW-041` | List 中从 Blocks palette 拖入 `tabs/tab/filter-bar/table/action-bar/action`，配置 table preview rows，runtime 输入 filter 后表格行真实变化，modal action 可执行 | 已通过 |
| `UDW-044` | Dashboard 浏览器级拖动 widget 改变 `layout.x/y`，尝试拖到重叠位置被拒绝，保存重开后 layout 保持 | 已通过 |
| `UDW-045` | Form 中从 Blocks palette 添加 `field`，分别配置 date/number/switch，保存重开，runtime 控件类型和值变化生效 | 已通过 |
| `UDW-046` | List 页面内拖入 `widget`，配置 table widget 的 columns/rows/title/subtitle，保存重开，runtime 渲染 | 已通过 |

本轮实现修复：

- `table` 加入 schema-driven Inspector 的 `props.rows` JSON 字段，使 List tab 内新建 table 可以通过 UI 配置 preview rows。
- List runtime 新增 `filter-field` 控件与表格过滤语义，`contains/equals/gt/lt/between` 基础 operator 可直接作用于静态 preview rows。
- `RecursiveBlockRenderer` 增加 List filter 单测，锁住 filter-field 输入后 table rows 变化。
- 对嵌套容器拖拽用例明确拖到容器头部区域，避免 Playwright `dragTo` 命中已有子 block 中心导致误判。
- Dashboard 测试 fixture 仍按当前 `/api/pages` 接口约束使用 `kind=detail` + `blockType=dashboard`，记录为现阶段接口限制；V3 目标 DSL 仍保留 dashboard kind 决策。

验证命令：

```bash
PW_SKIP_WEBSERVER=1 PLAYWRIGHT_BASE_URL=http://localhost:5237 VITE_PORT=5237 BFF_PORT=3564 BFF_ALLOWED_PORTS=5237,6443 BFF_INTERNAL_URL=http://127.0.0.1:3564 NO_PROXY=localhost,127.0.0.1 npx playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --project=chromium --reporter=line --no-deps --workers=1
```

结果：最新收口后为 `51 passed`。

```bash
PW_SKIP_WEBSERVER=1 PLAYWRIGHT_BASE_URL=http://localhost:5237 VITE_PORT=5237 BFF_PORT=3564 BFF_ALLOWED_PORTS=5237,6443 BFF_INTERNAL_URL=http://127.0.0.1:3564 NO_PROXY=localhost,127.0.0.1 npx playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --project=chromium --reporter=line --workers=1
```

结果：最新收口后为 `69 passed / 1 skipped`，包含 Playwright setup/auth dependencies。

```bash
pnpm exec vitest run app/plugins/core-designer/components/unified-designer/__tests__/RecursiveBlockRenderer.test.tsx app/plugins/core-designer/components/unified-designer/__tests__/UnifiedDesignerWorkbench.test.tsx
```

结果：`2 passed / 74 passed`。

```bash
pnpm typecheck
```

结果：通过。

```bash
git diff --check
```

结果：通过。

E2E truth 自审：

- `waitForTimeout/test.skip/test.fixme/retries/page.goto('/p/')`：0。
- `toBeLessThanOrEqual/toBeGreaterThanOrEqual`：0。
- `click/fill/drag/select/check` 类 UI 操作：713。
- `page.request/request.*`：26，集中在 beforeAll/test setup、fixture 创建、权限/登录/读取保存结果校验；核心拖拽、Inspector 编辑、保存、预览、action 执行均走 UI 路径。

## 2026-05-20 完整补测收口

继续按黄金测试口径补齐剩余结构性缺口：

| 用例 | 覆盖目标 | 结果 |
|------|----------|------|
| `UDW-047` | Detail 页面内拖入 `tabs/tab/detail-section/field/ai-fill-banner/bpm-panel/activity-timeline/field-history`，编辑标题、字段 label、helper dataSource、AI suggestion、BPM 状态/动作、timeline items、field history entries，保存重开，runtime 专属 renderer 渲染并交互 | 已通过 |
| `UDW-048` | Dashboard 根节点浏览器级配置 `layout.cols/rowHeight/gap`，保存重开，runtime 断言 `gap/grid-auto-rows/grid-template-columns` | 已通过 |
| `UDW-049` | Detail 页面内配置 AI/BPM/Timeline/Field History helper 的 live namedQuery dataSource，preview 等待真实 `/api/meta/named-queries/{code}/execute`，断言返回 rows 写入四类专属 renderer 并保存到 V3 blocks | 已通过 |
| `UDW-050` | Detail 页面内拖入 model field，编辑 field label/helpText/component，layout mode 画布拖拽交换两个 detail field，保存重开并断言 V3 child order 与 runtime field 顺序 | 已通过 |
| `UDW-051` | Detail 页面内配置 helper live namedQuery 空结果和缺失 queryCode 错误结果，编辑 AI emptyText 并在 preview 中断言 AI/BPM/Timeline/Field History empty/error 状态 | 已通过 |

本轮实现修复：

- 补齐 `ai-fill-banner`、`bpm-panel`、`activity-timeline`、`field-history` 的 V3 block template，避免 registry 可见但 palette 拖拽无法创建 block。
- `RuntimeDashboard` 不再固定 `grid-cols-12`，改为读取 `layout.cols` 并输出 `gridTemplateColumns`，让 Dashboard 根 grid 配置在 runtime 真实生效。
- 给 helper blocks 增加结构化 Inspector schema 和专属 runtime test id，避免只走 generic fallback。
- 给 runtime dashboard grid 增加 `runtime-dashboard-grid-{id}` test id，用于浏览器级样式断言。

分层验证：

```bash
PW_SKIP_WEBSERVER=1 PLAYWRIGHT_BASE_URL=http://localhost:5237 VITE_PORT=5237 BFF_PORT=3564 BFF_ALLOWED_PORTS=5237,6443 BFF_INTERNAL_URL=http://127.0.0.1:3564 NO_PROXY=localhost,127.0.0.1 npx playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --project=chromium --grep "UDW-0(47|48)" --reporter=line --no-deps --workers=1
```

结果：`2 passed`。

```bash
PW_SKIP_WEBSERVER=1 PLAYWRIGHT_BASE_URL=http://localhost:5237 VITE_PORT=5237 BFF_PORT=3564 BFF_ALLOWED_PORTS=5237,6443 BFF_INTERNAL_URL=http://127.0.0.1:3564 NO_PROXY=localhost,127.0.0.1 npx playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --project=chromium --grep "UDW-0(40|41|42|43|44|45|46|47|48)" --reporter=line --no-deps --workers=1
```

结果：`9 passed`。

```bash
PW_SKIP_WEBSERVER=1 PLAYWRIGHT_BASE_URL=http://localhost:5237 VITE_PORT=5237 BFF_PORT=3564 BFF_ALLOWED_PORTS=5237,6443 BFF_INTERNAL_URL=http://127.0.0.1:3564 NO_PROXY=localhost,127.0.0.1 npx playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --project=chromium --reporter=line --no-deps --workers=1
```

结果：`51 passed`。

```bash
PW_SKIP_WEBSERVER=1 PLAYWRIGHT_BASE_URL=http://localhost:5237 VITE_PORT=5237 BFF_PORT=3564 BFF_ALLOWED_PORTS=5237,6443 BFF_INTERNAL_URL=http://127.0.0.1:3564 NO_PROXY=localhost,127.0.0.1 npx playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --project=chromium --reporter=line --workers=1
```

结果：`69 passed / 1 skipped`，包含 Playwright setup/auth dependencies。

```bash
pnpm exec vitest run app/plugins/core-designer/components/unified-designer/__tests__/RecursiveBlockRenderer.test.tsx app/plugins/core-designer/components/unified-designer/__tests__/UnifiedDesignerWorkbench.test.tsx app/plugins/core-designer/components/unified-designer/__tests__/v3-utils.test.ts
```

当时结果：`3 passed / 100 passed`；后续已补 runtimeExecution dataSource contract，最新结果见下一节。

## 2026-05-20 dataSource contract 续补

本轮继续把 helper block 从静态 preview 推进到统一 runtime dataSource contract：

- `RuntimeExecutionServices` 新增 `loadHelperBlockData`，默认支持 namedQuery 和 query-builder 数据源。
- `ai-fill-banner` 映射 `field/label/value` 建议字段；`bpm-panel` 映射 `status/assignee/dueAt/actions`；`activity-timeline` 映射 `actor/action/time/description`；`field-history` 映射 `field/from/to/changedBy`。
- 四类 helper block 的 Inspector 均新增 `dataSource.type`、`dataSource.executionMode`、`dataSource.query`、`dataSource.queryCode`、`dataSource.parameters`、分页字段。
- UDW-047 已覆盖 helper dataSource 字段选择、写回、保存重开，同时继续覆盖 runtime 专属 renderer。
- UDW-049 已覆盖 live namedQuery 真实执行链路；mapper 同时识别 camelCase、snake_case 和 SQL alias lowercase 形态，避免 `dueAt/actionLabel/actionType/changedBy/suggestedValue` 等字段在真实查询结果中漏映射。
- UDW-050 已补齐 Detail field 专项路径，不再只借用 Form/List 的通用 reorder 证据。
- UDW-051 已覆盖 helper live dataSource 的空数据和错误状态；AI/BPM 补齐 `props.emptyText` Inspector 字段，runtime 空结果不再用静态 fallback 伪装成有效业务状态。

新增验证：

- `runtimeExecution.test.ts` 覆盖 AI namedQuery、BPM query-builder、timeline/history query-builder 映射。
- `RecursiveBlockRenderer.test.tsx` 覆盖注入式 `loadHelperBlockData` runtime 渲染。
- `v3-utils.test.ts` 覆盖 helper dataSource Inspector schema。
- `unified-designer-workbench.spec.ts` 新增 UDW-049，覆盖浏览器级 live namedQuery helper dataSource 执行、渲染、保存和重开。
- `unified-designer-workbench.spec.ts` 新增 UDW-050，覆盖浏览器级 Detail field 拖入、属性配置、交换位置、保存和 runtime 顺序。
- `unified-designer-workbench.spec.ts` 新增 UDW-051，覆盖浏览器级 helper empty/error 状态和 AI emptyText Inspector 写回即时 preview。

最新验证结果：

- `pnpm typecheck`：通过。
- `RecursiveBlockRenderer.test.tsx` + `UnifiedDesignerWorkbench.test.tsx` + `v3-utils.test.ts` + `runtimeExecution.test.ts`：`4 passed / 112 passed`。
- `UDW-047` targeted：`1 passed`。
- `UDW-049` targeted：`1 passed`。
- `UDW-050` targeted：`1 passed`。
- `UDW-051` targeted：`1 passed`。
- `UDW-049~051` slice：`3 passed`。
- `unified-designer-workbench.spec.ts --no-deps`：`51 passed`。
- `unified-designer-workbench.spec.ts`（含 setup/auth dependencies）：`69 passed / 1 skipped`。
- E2E truth grep：`waitForTimeout/test.skip/test.fixme/retries/page.goto('/p/')` 为 0；`toBeLessThanOrEqual/toBeGreaterThanOrEqual` 为 0；UI 操作 713 次，`request.*` 26 次。

```bash
pnpm typecheck
```

结果：通过。
