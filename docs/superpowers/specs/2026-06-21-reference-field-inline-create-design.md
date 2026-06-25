---
type: plan-design
status: active
created: 2026-06-21
date: 2026-06-21
scope: oss/web-admin (platform field renderer)
owner: diqi
---

# 表单引用字段「inline 新建 + 回填自动选中」设计

## 1. 背景与目标

**场景**:某表单里有个下拉框字段,用户在选项里找不到想要的值时,希望能直接「+ 新建」一条,
新建成功后 callback 回来 **自动选中刚创建的那个值**,而不必离开当前表单、跑去另一个页面建好再回来重选。

典型例子:填订单时下拉选「客户」,客户库里还没有 → 顺手新建一个客户 → 回到订单表单,
该客户已自动选中。同类还有供应商、物料、联系人等「主数据顺手建」场景。

**目标**:在 AuraBoot 低代码平台的表单下拉字段上,提供一个 **声明式、可控** 的 inline 新建能力,
覆盖 80% 的「填单时顺手建一条关联记录」需求,且 **后端零改动**(完全复用现有创建命令管道)。

## 1.1 当前交付状态(2026-06-22)

**Phase 1 已实现并验证:**

- reference 字段声明 `allowCreate: true` 后,下拉可显示「+ 新建」入口。
- 单选下拉内置搜索输入,按 option label/value 过滤;「+ 新建」入口不随搜索结果消失,确保用户找不到选项时仍能继续创建。
- 点击后打开目标模型的 **完整 DSL 表单** quick-create modal。
- 提交走 `createCommand` / `executeCommand`,后端不改。
- 创建成功后回填新记录 `pid` 并自动选中;多选 reference 追加新值。
- 创建失败时弹窗保持打开,外层字段不选中。
- `createPermission` / 当前用户权限 gate 控制入口可见性。
- 数据源在创建成功后刷新,并 pin 住刚创建的 option 防止短暂丢失。
- 显式 `dataSource` 的 reference 字段不再被排除;只要字段类型是 reference,且声明了 `allowCreate` 与目标模型(`refTarget.modelCode` 或 `dataSource.modelCode`),即可显示 inline 新建入口。

**Phase 1 未实现:**

- `createFields` 字段子集过滤。当前只是 schema/type 预留位,运行时不会按它裁剪 quick-create 表单。

## 2. 现状(已核实,带证据)

AuraBoot 表单里的「下拉框」有三种形态,全部由 `SmartSelect` 统一渲染,只是选项数据源不同:

| 下拉类型               | 识别方式                                   | 选项数据来源                                 | 选项结构                                           |
| ---------------------- | ------------------------------------------ | -------------------------------------------- | -------------------------------------------------- |
| **引用字段 reference** | `type/dataType = reference`,带 `refTarget` | `GET /api/dynamic/{targetModel}/list`        | `{pid, displayName, ...}` → 适配为 `{value,label}` |
| **字典 dict**          | `dictCode`                                 | `GET /api/meta/dict/by-code/{dictCode}/data` | `{value, label, description}`                      |
| **静态 options**       | DSL 里写死 `options:[...]`                 | 配置文件本身                                 | `{value, label, disabled?}`                        |

关键源码落点(`web-admin/`):

- 选择器组件:`app/ui/smart/form/Select.tsx`(`SmartSelect`,单选基于 Radix Select,多选基于原生 select;**只负责选值,不负责选项来源的创建**)
- 字段渲染器:`app/framework/meta/rendering/RuntimeFieldRenderer.tsx`
  - reference 数据源自动装配:`:222-277`
  - dict 数据源自动装配:`:211-221`
- 字段配置接口 `FieldConfig`:`app/framework/meta/schemas/types.ts:138`(已有开放式 `props?: Record<string, any>` 扩展口)
- 表单态托管:`SchemaRuntime` 的 stateManager,字段写值走 `updateField(scopeId, field, value)`
- 通用表单弹窗:`app/framework/meta/runtime/actions/FormDialog.tsx`(`dialog:form` CustomEvent 触发;**仅支持 text/select/number/textarea 四种基础字段**)
- DSL 表单渲染入口:`app/framework/meta/rendering/DslFormRenderer.tsx`(承载完整字段类型的模型表单)

创建记录的命令管道(后端,已具备、无需改):

- 入口:`POST /api/meta/commands/execute/{commandCode}`(`CommandController.java:145`)
- 返回 `CommandExecuteResult.data`(Map)**包含新记录的完整字段值,含主键 `pid`(ULID)** —— 回填自动选中所需的数据拿得到
- 前端封装:`useActionHandler.ts` 的 `executeCommand` / `ActionRegistry` 的 `command.execute`

**结论:目前下拉字段没有任何形式的「+ 新建 / 快速新建」入口,需要从零加。**

## 3. 关键约束:能不能 inline 新建 = 数据源是否可写

这是本特性最核心的设计约束。按「可写性」给数据源分类:

| 源类型                             | 背后有没有可写实体                               | inline 新建可行性                                                |
| ---------------------------------- | ------------------------------------------------ | ---------------------------------------------------------------- |
| 静态 options(JSON 写死)            | ❌ 没有,就是配置文件本身                         | **不支持** —— 「新建」要改的是配置文件,不是运行时数据操作        |
| 命名查询 namedQuery / 任意外部 api | ❌ 通常是只读聚合/投影,或平台无从假设的端点      | **不支持** —— 没有单一 insert 目标                               |
| 字典 dict                          | ✅ 有(`dict_item` 表)                            | ⚠️ 可行但有治理成本(字典项写 API + 权限 + 租户隔离 + 防全局污染) |
| **引用 reference**                 | ✅ 有(目标模型 `mt_` 表 + `{model}:create` 命令) | ✅ **最干净** —— 有现成 CRUD 模型、创建命令、表单 schema         |

### 设计原则:显式声明,不靠「猜源」

**反模式**:让平台自动判断某个下拉的源「能不能写、能不能加」。这必然脆弱
(namedQuery 看着像 api、外部 api 看着能 POST 实际不行、静态 options 根本没有源)。

**正确做法**:`+ 新建` 按钮 **只有在字段显式声明了「怎么创建」时才出现**。于是:

- 静态 options / 只读 namedQuery / 外部 api → 没人会去声明 create 能力 → 自然不显示按钮。
  **"源是 JSON / 源是只读接口" 这两类天然被排除,不需要任何特判。**
- reference → `refTarget.targetModel` 已知,`{model}:create` 命令可推导 → 一个开关即可开启。
- dict → 需后端先补「字典项新增」命令 + 权限位才能开。

**实现更新(2026-06-25)**:对于 DSL 已经显式配置 `dataSource` 的 reference 字段,平台仍按 reference 语义处理
inline 新建。目标模型优先取 `refTarget.modelCode`,缺省时回退到 `dataSource.modelCode`;
显示字段优先取 `refTarget.displayField`,缺省时回退到 `dataSource.labelField`。这样可以支持「下拉选项由 DSL
数据源显式控制,同时仍允许顺手创建目标模型记录」的业务表单。

## 4. 范围决策

**第一阶段(本设计)只做 reference 字段。** 理由:80% 命中「填单时顺手建客户/供应商/物料」的真实场景,
背后有现成模型 + 创建命令 + 表单 schema,改动最小、最干净,后端零改动。

**明确不在本期范围(Out of Scope):**

- 字典 dict 的 inline 新增 → 延后(成本在后端治理:字典项写 API、谁能加、租户隔离、防全局字典污染)。见 §10。
- 静态 options / namedQuery / 外部 api 的「新建」→ **永不支持**;要可新增就别用静态 options,改建模成 reference 或 dict。
- 嵌套多层「在新建表单里再 inline 新建」(modal stack)→ 延后,本期只支持一层。

## 5. 设计详解(reference inline 新建)

### 5.1 触发与交互形态

- 在 reference 下拉的选项列表 **底部固定一项** `+ 新建「{目标模型显示名}」`(置底、视觉上与普通选项区分)。
- 点击 → 弹出 **创建表单模态**(见 §5.2),不关闭原下拉的上下文。
- 备选形态(实现时二选一,默认选项列表置底项):下拉控件旁一个独立 `+` 图标按钮。

### 5.2 创建表单从哪来

默认 **复用目标模型自己的表单**,通过现有 `DslFormRenderer` 包在一个模态容器里渲染
(**不是** `FormDialog` —— 后者只支持 4 种基础字段,无法承载 reference/dict 等完整字段类型)。

- 默认:渲染目标模型的完整 create 表单(字段完整、与该模型独立建记录时一致)。
- `createFields: ["name","phone"]`:**Phase 1 仅预留 schema/type 位,运行时不生效**。如需把表单过滤为精简快建字段子集,
  需要单独进入后续阶段设计和实现(必填字段、默认值、校验、字段权限、布局都要一起处理)。

> **实现确认(2026-06-22)**:`useDslForm` 支持 quick-create modal 场景,当前实现已用独立
> `ReferenceCreateDialog` 挂载目标模型 DSL 表单。`FormDialog + createFields` 退路未进入 Phase 1。

### 5.3 创建命令

- 默认 `{targetModel}:create`(`refTarget.targetModel` 已知,可推导)。
- 可选 `createCommand` 覆盖(例如目标模型的 create 命令码与默认推导不一致时)。
- 走标准命令管道 `POST /api/meta/commands/execute/{code}`,payload 即创建表单收集的字段值
  (遵循平台契约:`{ data: {...}, operationType: "create" }`)。

### 5.4 回填自动选中 + 选项刷新

创建成功后:

1. 从 `result.data` 取新记录主键 `pid` 和显示字段(`refTarget.displayField`,默认 `displayName`)。
2. **乐观更新**:把 `{value: pid, label: <displayField 值>}` 插入当前下拉 options 顶部并 **设为选中**
   —— 即调用字段的 onChange,`stateManager.updateField(scope, field, pid)`。
3. **数据源 reload**:触发 reference dataSource 重新拉取,保证后续打开下拉时列表准确(去掉乐观项的临时性)。
4. 关闭创建模态;可选 toast「已创建并选中」。

**多选 reference**:新建结果 **append** 到已选值数组(而非替换),其余逻辑一致。

### 5.5 DSL 配置形态(最小开关)

在 `FieldConfig` 上新增三个 **可选** 位,默认全关、不影响存量字段:

```jsonc
{
  "field": "customer_id",
  "type": "reference",
  "allowCreate": true, // 唯一必需开关,默认 false;为 true 才显示「+ 新建」
  "createCommand": "customer:create", // 可选:不写则推导 {targetModel}:create
  "createPermission": "customer.manage", // 可选:入口可见性权限 gate
  "createPageKey": "customer_form", // 可选:覆盖目标模型默认 quick-create 页面
  "createFields": ["name", "phone"], // Phase 1 仅预留;运行时不按它过滤字段
}
```

配置位置:`FieldConfig` 顶层(与 `dictCode` / `dataSource` 同级),而非塞进 `props`
—— 让它成为平台一等公民、可被 schema validator / 文档治理覆盖,而不是隐式约定。

### 5.6 权限

- 「+ 新建」按钮可见性 **gate 到目标模型的 create 权限**:当前用户没有 `{targetModel}:create` 权限时,
  **不渲染** 按钮(避免给出点了必失败的入口)。
- 命令管道本身在执行时也会再校验一次权限,形成双保险。

### 5.7 错误处理

- 创建失败(字段校验 / 唯一冲突 / 权限 / 后端异常)→ 在创建模态内出 **字段级错误**,
  **保持模态打开、不回填、不自动选中**;遵循平台「不自愈、不吞异常」红线,失败即明确报错。
- 仅当命令成功(`phaseReached` 走完 + 返回 `data.pid`)才执行 §5.4 回填。

## 6. 改动落点

| 层              | 文件                                                                 | 改动                                                                                                                  |
| --------------- | -------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| 字段配置 schema | `app/framework/meta/schemas/types.ts`                                | `FieldConfig` 加 `allowCreate?` / `createCommand?` / `createPermission?` / `createPageKey?`;`createFields?` 仅预留    |
| 字段渲染层      | `app/framework/meta/rendering/RuntimeFieldRenderer.tsx`              | reference 装配时识别 `allowCreate`,推导/读取 `createCommand`,传递 `createPageKey`,装配 `onCreateNew` 回调 + 权限 gate |
| 选择器组件      | `app/ui/smart/form/Select.tsx`(`SmartSelect`)                        | 接收 `canCreateNew` / `createNewLabel` / `onCreateNew`,渲染底部「+ 新建」项,点击触发回调;拿到结果后选中               |
| 创建模态        | 复用 `DslFormRenderer` + 一个轻量 modal 容器(可能新增一个小封装组件) | 在模态里独立实例化目标模型表单 + 提交走 `command.execute` + 成功 resolve `{pid, label}`                               |
| **后端**        | ——                                                                   | **零改动**(复用现有 `{model}:create` 命令与 `POST /api/meta/commands/execute/{code}`)                                 |

## 7. 数据流

```
用户在 reference 下拉点「+ 新建「客户」」
        │
        ▼
RuntimeFieldRenderer 装配的 onCreateNew()
        │  打开 modal,内嵌 DslFormRenderer(目标模型 customer 完整表单)
        ▼
用户填写并提交 → command.execute(POST /api/meta/commands/execute/customer:create)
        │
        ▼
result.data = { pid: "01J...", displayName: "新客户A", ... }
        │
        ├─ 乐观插入 option {value: pid, label: displayName} 并选中
        ├─ stateManager.updateField(scope, "customer_id", pid)
        ├─ reference dataSource reload(后台校准)
        └─ 关闭 modal + toast
```

## 8. 测试策略

涉及 UI 交互,完成判定必须含 **真浏览器 golden + 后端运行时** 成对证据(对齐 AGENTS §1/§2.2/§10):

- **单测(纯逻辑)**:`onCreateNew` 回调装配、命令码推导(`{targetModel}:create` / `createCommand` 覆盖)、
  权限 gate 决定按钮可见性、回填映射(`result.data.pid` → 选中值)、多选 append vs 单选 replace、
  单选下拉搜索过滤与「+ 新建」入口保留、显式 `dataSource` reference 的目标模型回退。
- **浏览器 golden(Playwright,host-first 零 docker)**:
  1. 打开含 reference 字段的表单 → 下拉里看到「+ 新建」项(`allowCreate:true` 才有,`false` 没有)。
  2. 点击 → 模态打开 → 填字段 → 提交 → **断言新记录经真命令管道落库**(DB / API 反查)。
  3. **断言回填**:模态关闭后该字段 **已选中刚建记录**(DOM 实查选中值 = 新 pid 的 label)。
  4. 失败路径:必填留空 / 唯一冲突 → 字段级错误、模态不关、原字段未被改。
  5. 多选 reference:新建后是 append 不是 replace。
  6. 无 create 权限的用户:**看不到** 「+ 新建」入口。
- **覆盖矩阵**:单选成功/失败/无权限走浏览器 golden;多选 append 与去重走单测;完整表单字段集走浏览器断言。
  `createFields` 子集过滤不属于 Phase 1 完成口径。

## 9. 待 plan 阶段确认的开放点

1. **(主要风险)** `DslFormRenderer` 能否在独立 modal 内自带 `SchemaRuntime` 运行而不耦合外层表单 —— 先 spike 验证(§5.2)。
2. 创建模态的视觉与交互形态(选项列表置底项 vs 控件旁 `+` 图标)—— 默认前者,实现时对齐设计系统 `ux-design-system.md`。
3. `createFields` 子集渲染:Phase 1 不实现。若进入后续阶段,需决定是只过滤目标模型 form 字段,
   还是允许字段级覆盖(label/必填/默认值/布局),并补齐校验与权限语义。

## 10. 后续阶段(非本期)

- **Phase 2 — 字典 dict inline 新增**:前端形态与 reference 一致,核心成本在后端先补:
  字典项新增命令 / API、运行时新增权限模型、租户隔离、防全局字典污染。需单独设计。
- **Phase 3 — 多层嵌套新建(modal stack)**:在创建表单里再 inline 新建。需要弹窗栈状态管理。
- **Future — `createFields` 精简快建字段子集**:当前只保留 schema/type 位。真正启用前需处理必填字段、
  默认值、字段级权限、布局重排、隐藏字段校验与 E2E 覆盖。

## 11. 验收口径

- reference 字段加 `allowCreate:true` 后,表单内可顺手新建关联记录并自动选中,后端经真命令管道落库。
- 存量字段(未声明 `allowCreate`)行为零变化。
- 静态 options / namedQuery / 外部 api 字段不出现「+ 新建」(无需特判,声明驱动)。
- 单测 + 浏览器 golden(含失败路径 + 权限 gate)成对绿。
