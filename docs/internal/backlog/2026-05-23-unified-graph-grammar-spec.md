# Unified Graph Document Grammar — 前后端共同契约 spec

> 日期:2026-05-23 · 状态:**提案,待 owner 评审** · 关联:`2026-05-23-automation-designer-runtime-review.md`(评审 + 目标架构 DDR)
> 定位:automation 设计器与 BPM 设计器**共用的 JSON 图文档文法**,同时是后端 SmartEngine 编译器的输入契约。

---

## 1. Context(为什么要这份契约)

- 后端已锁定统一到 SmartEngine:automation 的 `flowConfig` 与 BPM 的流程定义**最终都编译成 SmartEngine 流程**。
- 现状两边各有一套 JSON(automation `FlowData` / BPM `BPMNProcessDefinition`),骨架几乎相同(都是 `@xyflow` Node/Edge 派生),但有 4 处小分歧。
- 统一文法的收益:**后端一条 ingestion 路径**(`JsonToBpmnConverter` 已是事实标准信封)+ 前端一份共享契约,且**不绑架两个设计器前端是否合并**。

### 非目标(明确不做)
- **不**要求合并两个设计器前端(各自序列化到本文法即可)。
- **不**要求节点词汇/配置统一(节点类型、config 载荷按域不同,本就该)。
- **不**在此定义每个节点类型的完整 config 字段(那是各域 node registry,另行维护)。

---

## 2. 设计原则

1. **BPMN 信封为超集,automation 取子集**(automation 不用的字段留空/省略)。
2. **`@xyflow` Node/Edge 兼容**(两个设计器都已基于它,迁移成本最低)。
3. **`node.type` 是规范的 registry 主键**(域内唯一;automation 与 bpmn 命名不冲突)。
4. **`data.config` 是域载荷**,按 `node.type` 判别其结构(同一份 JSON,TS 侧用判别联合)。
5. **后端 `JsonToBpmnConverter` 是规范消费者**:automation emit 同一信封 + 一张「节点类型→BPMN 元素」映射表,复用同一 converter。
6. dev 阶段:**breaking change 优先,禁 forwarding stub / deprecated alias**(canonical:`AGENTS.md`「开发阶段声明」/ `docs/standards/core/decision-defaults.md`)。

---

## 3. 文法定义(字段级)

### 3.1 文档根 `GraphDocument`

```jsonc
{
  "schemaVersion": "1.0",            // 必填;本契约版本
  "kind": "automation" | "bpmn",     // 必填;域判别(决定 node registry + 编译映射表)
  "meta": GraphMeta,                  // 必填;流程级元数据(见 3.4)
  "nodes": [ Node ],                  // 必填
  "edges": [ Edge ]                   // 必填
}
```

### 3.2 `Node`

```jsonc
{
  "id": "string",                    // 必填;文档内唯一
  "type": "string",                  // 必填;规范 registry 主键(域词汇,见 §4)
  "position": { "x": 0, "y": 0 },    // 必填;画布坐标(@xyflow)
  "parentId": "string?",             // 可选;泳道/池/分组的父节点(automation 不用)
  "data": {
    "label": "LocalizedText | string",  // 必填;显示标签(面向用户文本走 i18n,红线 #3)
    "config": { }                       // 必填;域载荷,结构由 type 判别(§4)
  }
}
```

> 归一说明:bpmn 现在把判别放在 `data.type`(`BPMNNodeType`),automation 放在顶层 `node.type`。**本契约规范主键 = `node.type`**;`data.type` 子类型(automation 现有 'trigger'/'action')废弃(dev 阶段直接删,不留兼容字段)。

### 3.3 `Edge`

```jsonc
{
  "id": "string",                    // 必填;文档内唯一
  "source": "string",                // 必填;源 node.id
  "target": "string",                // 必填;目标 node.id
  "sourceHandle": "string?",         // 可选;@xyflow 出端口(分支/多出边用)
  "targetHandle": "string?",         // 可选
  "data": {
    "label": "LocalizedText | string?",
    "condition": ConditionExpression | null,  // 见 3.5;排他/包容网关出边用
    "isDefault": false                         // 可选;网关默认流向
  }
}
```

### 3.4 `GraphMeta`(流程级元数据,BPMN 超集)

```jsonc
{
  "key": "string",                   // 必填;流程/自动化标识(= 部署 processKey)
  "name": "LocalizedText | string",  // 必填
  "description": "string?",
  "category": "string?",
  "version": 0,                      // 可选;数字版本
  "versionName": "string?",          // 可选;语义版本
  "variables": { },                  // 可选;流程变量初值
  "aura": { },                       // 可选;AuraBoot 域策略(编译进 <smart:properties>)
  // ---- automation 域扩展(kind=automation 时)----
  "automation": {                    // 可选;automation 专属
    "trigger": { "type": "string", "modelCode": "string?", "config": { } }
  }
}
```

> 归一说明:automation 现在**没有顶层 meta**,`name/description/triggerType/modelCode` 散在 `ab_automation` 表列。本契约把它们收进 `meta`(`meta.automation.trigger` 承载触发绑定)。**meta 与 entity 列的权威归属**见 §7 开放点。

### 3.5 `ConditionExpression`

```jsonc
{
  "type": "expression" | "script",   // 必填
  "content": "string",               // 必填;表达式/脚本体
  "language": "mvel" | "juel" | "spel" | null,  // 可选
  "ruleCode": "string?"              // 可选;引用 BPM 规则引擎规则
}
```

> 归一说明:automation 现在 `edge.data.condition` 是裸 `string`。本契约统一为结构化形式;**裸 string 视为糖**,迁移规则:`"x>1"` ⇒ `{ type:"expression", content:"x>1" }`。automation 执行器现在根本没读 edges,改动无运行包袱。

---

## 4. 域扩展点(节点词汇 + 编译映射)

`kind` 决定用哪张 node registry,以及编译到 SmartEngine 的映射表。

### 4.1 BPMN 词汇(kind=bpmn)
`startEvent / endEvent / userTask / serviceTask / receiveTask / exclusiveGateway / parallelGateway / inclusiveGateway / callActivity`(= `BPMNNodeType`)。`config` = 对应的 `*Config`(`UserTaskConfig` / `ServiceTaskConfig` / …)。这是 converter 原生支持的集合。

### 4.2 Automation 词汇(kind=automation)→ BPMN 元素映射

| automation `node.type` | 编译为 | serviceTask delegate(`smart:class`) | 备注 |
|---|---|---|---|
| `trigger-*`(record-create/update/field-change/state-change/scheduled/webhook/bpm-event) | `startEvent` | — | 触发绑定写入 `meta.automation.trigger` |
| `action-send-notification` | `serviceTask` | `notificationServiceTaskDelegate` | 已存在 |
| `action-update-record` | `serviceTask` | `recordUpdateServiceTaskDelegate` | 已存在 |
| `action-call-api` / `action-send-webhook` | `serviceTask` | `httpServiceTaskDelegate` | 已存在 |
| `action-execute-command` | `serviceTask` | command delegate | `CommandServiceTaskDelegate` 已存在 |
| `action-create-record` | `serviceTask` | **新增** create delegate | 待补 |
| `action-llm-call` | `serviceTask` | **新增** llm delegate | 待补 |
| `action-start-process` | `callActivity` | — | 原生 |
| `control-condition` | `exclusiveGateway` | — | 出边 condition 必填(§6) |
| `control-loop` | multi-instance | — | 原生 |
| `control-delay` | timer | — | ⏳ SmartEngine timer 待完善,**挂起** |

> delegate bean 名与节点类型常量集中在后端 `BpmServiceTaskConstants`;映射表是编译器的核心数据,需与之保持单一真源。

---

## 5. 后端契约

- **规范消费者**:`JsonToBpmnConverter` 消费 `GraphDocument`(读 `nodes`/`edges`/`meta`)→ BPMN 2.0 XML → `ProcessDeploymentService.deploy`。
- **automation 编译路径**:`GraphDocument{kind:automation}` → §4.2 映射表 → BPMN 元素 → **同一 converter**(不另写一套)。
- **启动**:触发时 `StorageModeHolder=MEMORY` + `ProcessEngineService.startProcess(defId, businessKey, variables)`。
- converter 现已读 `nodes/edges/aura`;本契约要求其 meta 读取扩展到 `meta.key/name/category/variables`(若尚未)。

---

## 6. 共享校验规则(前后端一致)

1. `node.id` / `edge.id` 文档内唯一。
2. 每条 `edge.source`/`target` 必须指向存在的 `node.id`。
3. 恰好一个 start 类节点(automation: 一个 `trigger-*`;bpmn: 一个 `startEvent`)。
4. **排他/包容网关**:每条出边必须带 `condition` 或 `isDefault:true`;每个网关至多一个默认流向(bpmn 现有 `validateExclusiveGatewayFlows` 逻辑,提升为共享规则)。
5. required `config` 字段非空(对应 automation 评审 P0-4:校验须接线、阻断保存)。
6. 面向用户文本走 i18n,不得裸 code(红线 #3)。

---

## 7. 迁移与开放点

### 迁移(dev 阶段,干净替换无 stub)
- automation `FlowData{nodes,edges}` → `GraphDocument`:加 `schemaVersion`/`kind`/`meta`;`edge.condition` string→结构化;删 `data.type` 子类型。
- bpmn `BPMNProcessDefinition` → `GraphDocument`:字段重命名对齐(`key/name/...` 收进 `meta`);本就最接近超集,改动小。
- 测试 fixture / E2E 中以旧形状构造的文档随之改写(属改测试,非数据迁移——全仓零 shipped 自动化)。

### 开放点(需 owner 拍板)
1. **meta 权威归属**:automation 的 `name/triggerType/modelCode` 以 `meta` 为真源,还是 `ab_automation` 列为真源 + meta 镜像?(影响 entity/API 改动范围)
2. **共享 TS 类型包**:现在就抽 `GraphDocument/Node/Edge/ConditionExpression` 公共类型包,还是各设计器先各自实现、类型后置?
3. **automation 是否需要泳道**:默认否(`parentId` 留空)。
4. **delay/timer**:挂起,待 SmartEngine timer 方案。

---

## 8. 验证(契约本身怎么验)

- **契约一致性测试**:构造一份 `GraphDocument{kind:automation}` 样例 → 经映射表 + `JsonToBpmnConverter` → 产出合法 BPMN 并能 `deploy` + `startProcess`;bpmn 文档 round-trip(JSON↔BPMN)不丢字段。
- **校验规则测试**:§6 每条规则配正反用例(尤其网关出边 condition 必填)。
- 前端:两个设计器各自 export 出的 JSON 通过同一份 schema 校验(JSON Schema / zod)。

---

## 9. 这份契约在整合全景中的位置

| 层 | 是否统一 | 本 spec |
|---|---|---|
| ① JSON 文法/信封 | **统一(本 spec)** | ✅ keystone |
| ② 节点词汇 + config | 按域不同 | §4 各域 registry |
| ③ 设计器前端/SDK | 可分开,整合可选 | 不在本 spec |
| ④ 后端引擎 | 已统一 SmartEngine | §5 消费本 spec |

①(本契约)是 keystone:解耦 ②③,同时作为后端编译器 track 的输入契约。
