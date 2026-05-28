# T3 — flow-designer-sdk 能力增强 plan(收敛第一步)

> 日期:2026-05-23 · 状态:**plan,待 owner 批准后执行** · 决策依据:`DDR-2026-05-23-automation-bpm-designer-convergence.md`(Option B)
> 关联:`2026-05-23-unified-graph-grammar-spec.md`(类型契约)、`2026-05-23-automation-designer-runtime-review.md`(能力对比/差距)

## Context

DDR Option B:**先增强 `flow-designer-sdk` 到能托住 BPM 级能力,再迁移 `bpmn-designer`(T4)**。本 plan 只做 T3 = 给 SDK 补扩展点,**不迁移 bpmn、不破坏 automation 薄路径**。

**第一原则(DDR 欠债防线)**:能力一律走**扩展点注入**,不把 BPM 逻辑焊进核心。automation 继续能用最薄的 `configSchema` 路径;BPM 的富交互通过注入的自定义组件接入。守住"零代码加节点"的声明式优势。

现有扩展缝(实测):`FlowNodeDefinition{configSchema, component}`;`FlowPropertyPanel` 仅 `configSchema→PropertyField`;store 仅 `selectedNodeId`;`FlowDesigner` 有 `onValidate` 回调但无引擎,无 edgeTypes/meta 插槽。

## 能力差距 → 扩展点设计

### P1(硬骨头,bpmn 必需,先做)

**G1 自定义边类型 + 边选中/编辑**
- store:加 `selectedEdgeId` + `selectEdge(id)`;`selectNode/selectEdge` 互斥清空。
- canvas:`FlowCanvas` 注册 `edgeTypes`(来自新 `EdgeRegistry`,与 `nodeRegistry` 对称);默认边保持现状。
- 新 `FlowEdgeDefinition { type, component?, configSchema?, editor? }`;`FlowPropertyPanel` 支持"选中边"分支:有 `editor` 用注入编辑器,否则 `configSchema→PropertyField`。
- 边数据对齐文法:`condition: ConditionExpression`(见 grammar spec),`isDefault`。

**G2 per-node 自定义属性编辑器注入点**
- `FlowNodeDefinition` 加 `propertyEditor?: React.ComponentType<NodePropertyEditorProps>`(`{ node, config, onChange, context, readOnly }`)。
- `FlowPropertyPanel`:`definition.propertyEditor` 存在→用它;否则回退现有 `configSchema→PropertyField`。**automation 不传 = 行为不变**;bpmn 的 AssigneePicker/ConditionExpressionEditor/网关编辑器等以注入方式接入。

### P2(收敛完整性)

**G3 流程级元数据面板插槽**
- `FlowDesignerProps` 加 `metaSchema?` 或 `metaPanel?: ComponentType<MetaPanelProps>` + `meta`/`onMetaChange`;store 持 `meta: GraphMeta`(对齐文法)。automation 可不传(沿用实体字段),bpmn 接 `ProcessMetadataPanel`。

**G4 校验引擎接线(同时还 automation P0-4)**
- 把现有 `onValidate`/`ValidationResult`/`NodeValidation` 接成真引擎:节点 required + `NodeValidation(min/max in-out)` + 图级规则(网关出边 condition 必填、单 start、引用完整性,见 grammar spec §6)。
- 保存门:校验失败阻断 + 字段/节点/边级错误态(修 P0-4 静默保存)。

### P3(BPM 运维向,迁移前补齐即可)

**G5 运行态状态叠加**:`FlowCanvas` 加 `nodeStatus?: Record<nodeId, status>` 渲染叠加(bpmn `useNodeMonitorStatus`/`ProcessStatusViewer` 等价能力)。
**G6 版本/保存插槽**:`onSave` 扩展为可携版本上下文,或保留外部(automationService/bpmnService 各自管)——**G6 设计待定**(见开放点)。

## 不做(本 track 边界)
- 不迁移任何 bpmn 节点/编辑器(那是 T4)。
- 不改 automation 现有 NodeRegistry 行为(回退兼容靠"可选扩展点默认关")。
- 不动后端(T2 独立)。

## 验证(关键:迁移前先证明 SDK 托得住 BPM)
- SDK 单测:`EdgeRegistry`、store `selectEdge`、`propertyEditor` 注入回退、校验引擎规则(正反例)。
- **BPM-shaped smoke**:用 SDK 扩展点搭一个最小"startEvent→exclusiveGateway(两条带 condition 出边)→serviceTask"样例渲染 + 选中边配条件 + 校验拦截——**证明 SDK 能托住 BPM 核心交互,再启动 T4**。
- automation 回归:现有 automation E2E/单测全绿(证明薄路径未被破坏)。
- 任何"完成/通过"措辞前跑 `/e2e-truth`。

## 阶段顺序
1. G1 + G2(P1,bpmn 迁移的前提)→ BPM-shaped smoke 通过 = T3 可交付里程碑。
2. G3 + G4(P2)。
3. G5 +(G6 待定)(P3)。

## 开放点(需 owner 拍)
1. G6 版本管理:进 SDK 还是各设计器外部各管?(影响 SDK 边界)
2. meta 权威归属(沿用 grammar spec §7 开放点 1)。
3. 校验引擎:SDK 内置规则集 vs 各域可插拔规则?

## 依赖
- grammar spec 的 `ConditionExpression` / `GraphMeta` 形状(G1/G3)。建议 T1 文法的这两处先定。
