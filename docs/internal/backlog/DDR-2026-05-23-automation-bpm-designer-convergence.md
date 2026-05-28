# DDR-2026-05-23-automation-bpm-designer-convergence

> 类型:架构决策记录(前端设计器收敛) · 决策人:owner(yaoyi) · 日期:2026-05-23
> 关联:`2026-05-23-automation-designer-runtime-review.md`(评审+目标架构 DDR)、`2026-05-23-unified-graph-grammar-spec.md`(统一文法契约)

---

## Context(背景 / 分析过程前因后果)

本 DDR 记录从"评审 automation"一路推导到"前端设计器收敛"的完整链条,供未来回看为什么这么选。

### 触发链(前因后果)

1. **起点**:评审 automation 可视化设计器 + 后端触发→条件→动作链路执行。
2. **撞到 2 条架构级 P0**(verify-before-flag,三方交叉印证):
   - P0-1:设计器存的 `flowConfig` **永不被执行**(无 flowConfig→actions 编译器,运行时只读空 `actions[]`)。
   - P0-2:执行器**线性按 sequence 跑,无视图拓扑**(condition 不 gate、loop no-op、delay 玩具级)。
   - 根因判断:**产品意图(图自动化)与实现(扁平 actions 执行器)从一开始就错位**。
3. **产品定位讨论**(对标 n8n / Zapier / Salesforce Flow / ServiceNow):锁定 **automation = 全自动图编排(n8n/Flow 档)**,人工任务/审批归 BPM,方向 = **统一**(两授权入口 + 同一引擎)。
4. **后端方向**:统一到**已有的 SmartEngine**(memory 模式承接高频触发);`flowConfig` 编译成 SmartEngine 流程(action→Service Task delegate、condition→网关、loop→multi-instance、delay→timer 挂起)。owner 熟 SmartEngine,免可行性 spike。
5. **前端调查**(实测):
   - 两个**图设计器**:automation 用 `flow-designer-sdk`、BPM 用 `bpmn-designer`,**平行的两套 @xyflow 实现,零代码共享**(bpmn 对 sdk import = 0)。
   - `flow-designer-sdk` README 自称"可用于 Automation/BPMN/其它",但**实际只有 automation 在用**(单一消费者),BPM 当初自己又造了一套。
   - `unified-designer`(页面设计器)是**正交范式**(@dnd-kit 块树,非 @xyflow 图),与本讨论无关。
6. **JSON 文法分析**:两个图设计器的信封(nodes/edges/position/data{label,config}/handles)**几乎完全相同**(都是 @xyflow 派生),仅 4 处小分歧 → 结论:**JSON 文法可统一**(已产出 `unified-graph-grammar-spec`)。
7. **能力对比**(本 DDR 的直接输入,详见评审文档对比表):
   - `flow-designer-sdk` = **声明式/配置驱动**(注册 `FlowNodeDefinition{configSchema}`,自动出调色板+属性面板),薄、域无关、加节点零 React 代码;**缺**:边编辑、per-type bespoke 编辑器、流程元数据、版本、运行态、校验接线(均无或未接线)。
   - `bpmn-designer` = **命令式/bespoke**(10 手写节点 + 14 per-type 编辑器 + 自定义边 + 边选中编辑 + 流程元数据面板 + 版本管理 + 运行态状态叠加 + 人工任务域),厚、可控,**已基本可工作**。
   - 两者**都没有**泳道/池。

### 由此逼出的决策问题

前端两个图设计器要不要、以及如何收敛?(后端已统一到 SmartEngine,文法可统一,但前端是两套并存的 @xyflow 实现。)

### 约束

- dev 阶段:breaking 优先、禁 forwarding stub(canonical:`AGENTS.md`「开发阶段声明」)。
- `bpmn-designer` 已成熟、有测试、基本可工作(回归风险是真实成本)。
- 全仓零 shipped/seed 自动化与流程(无生产数据迁移负担)。

---

## Options(选项)

### Option A — 保守:抽 thin core,两套并存(assistant 原推荐)
- **做法**:只把确实通用的 20%(@xyflow 画布壳 / undo-redo store / NodeRegistry / palette)抽成共享 thin core;`bpmn-designer` 保留自己的 bespoke 编辑器层 + 运行态;automation 继续用 SDK 薄能力。
- **优点**:回归风险最低(不动成熟 bpmn 的富能力);尊重两种合理取向(声明式 vs 命令式各服务各的复杂度档);投入小。
- **缺点**:两套设计器长期并存,UX/维护仍有双份;"统一"只到文法+引擎层,前端没真正收敛。
- **为何未被采纳**:owner 判断长期应收敛到一套底座,DRY + UX 一致的长期价值高于短期回归风险。

### Option B — 增强 flow-designer-sdk,再把 bpmn-designer 迁移过去(owner 选定)
- **做法**:先按差距清单**增强 SDK**(补 edgeTypes + 边选中/编辑、per-node 自定义编辑器注入点、流程元数据面板、校验接线、版本、运行态钩子等),再把 `bpmn-designer` 的 10 节点 + 14 编辑器 + 边/运行态**迁移到 SDK**,前端收敛到**一套 SDK + 两套 NodeRegistry/皮肤**。
- **优点**:前端真正收敛到一套底座;与"统一 JSON 文法 + SmartEngine 后端"形成三层对齐;以后加能力一处改两处受益;长期维护/UX 一致性最优。
- **缺点/风险**:SDK 要从"声明式薄"长成"能托住 BPM 富交互"——有**变成 bpmn 翻版、丢掉声明式优势**的风险;迁移成熟 bpmn 有**回归风险**;一次性投入大。
- **steel-man**:差距是**有限可列举**的(edgeTypes/边编辑/编辑器注入/元数据/校验/版本/运行态),SDK 底座 API 已被 automation 验证过;迁移是"搬已工作的组件 + 接 SDK 扩展点",非重新设计;收敛后两个设计器共享 bug 修复与体验升级。

### Option C — 维持现状,两套各自演进(否决)
- **做法**:不抽公共、不收敛,各演各的。
- **优点**:零额外投入。
- **缺点**:双份维护永久化,文法/后端已统一而前端长期分裂,体验漂移。
- **为何否决**:与已定的"统一"方向矛盾,且持续制造重复。

---

## Decision(决策)

**选 Option B**:**先增强 `flow-designer-sdk` 的能力(按能力差距清单),再将 `bpmn-designer` 的能力迁移过去,前端收敛到一套 SDK。**

- 决策人:owner(yaoyi);决策时间:2026-05-23。
- 备注:assistant 原推荐 Option A(保守 thin-core 并存);owner 权衡后选 B(长期收敛优先)。本 DDR 如实记录该分歧。
- 顺序硬约束:**先增强、后迁移**(SDK 先具备目标能力,bpmn 再搬过去),不允许边迁边补导致 bpmn 中途不可用。

---

## Consequences(后果)

### 立刻 / 短期成本
- SDK 增强工作量:edgeTypes + 边选中/编辑、per-node 自定义编辑器注入点、流程元数据面板、校验接线(automation P0-4 同步还)、版本管理、运行态钩子(`useNodeMonitorStatus` 等价物)、结构化边条件。
- bpmn 迁移工作量:10 节点组件 + 14 编辑器 + 自定义边 + 运行态视图搬到 SDK 扩展点,并逐项回归。
- 回归风险:`bpmn-designer` 已基本可工作,迁移期必须有等价 E2E/截图复核护栏。

### 中长期收益
- 前端收敛到一套底座,与「统一 JSON 文法(grammar spec)+ SmartEngine 后端」三层对齐。
- 加能力/修 bug/升体验一处生效,双设计器共享。

### 已知欠下的债(诚实记录)
- **SDK 膨胀风险**:从"声明式薄 SDK"长成"能托 BPM 富交互"后,必须**守住扩展点设计**(自定义编辑器/边/运行态走注入,而非把 BPM 逻辑焊进核心),否则 SDK 沦为 bpmn 翻版、丢掉"零代码加节点"的声明式优势。
- **automation 简单档不能被拖重**:增强后 automation 侧仍要能用最薄的 configSchema 路径,不被 BPM 的复杂度污染。
- **timer/delay 仍挂起**(待 SmartEngine timer 完善)。
- 迁移期"一套 SDK 托两域"的回归面变大,测试成本上升。

### 反向触发条件(何时该重评本决策)
- SDK 增强后**丧失声明式优势**(automation 加节点也要写大量组件)→ 回退 Option A。
- bpmn 迁移**回归不可控**或工作量爆炸(远超差距清单预估)→ 暂停迁移,退回 thin-core 并存。
- 产品方向若变(automation 不再是图编排 / 两域复杂度差距进一步拉大到无法共底座)→ 重评。

---

## 后续(不在本 DDR 决策范围,另出 plan)
1. SDK 能力增强 plan(按差距清单,定扩展点 API)。
2. bpmn→SDK 迁移 plan(分节点/编辑器批次 + 回归护栏)。
3. 与后端 `flowConfig→SmartEngine` 编译器 track、统一文法落地 track 的先后编排。
