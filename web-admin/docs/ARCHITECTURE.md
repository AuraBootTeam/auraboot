# Runtime Platform 架构详解（中文完整版）

本文档深入描述 AuraBoot Runtime Platform 的整体架构、模块职责、数据流及交互序列，
并对扩展点与未来演进方向提供建议。配合 `README.md` 和 `CONSTRAINTS.md` 阅读，可快速掌握
设计器与运行期之间的连接方式。

---

## 1. 顶层结构与分层

```
┌────────────────────────────────────┐
│ Studio Designer (schema editing)  │
│  - Smart Components（React）      │
│  - useSmartComponent Hook         │
└───────────────▲─────────────────┬─┘
                │                 │ convertSchemaToUnified()
                │                 ▼
        ┌───────┴────────────────────────────────────┐
        │           Runtime Platform 核心             │
        │  SchemaRuntime  ── ScopedStateManager       │
        │        │                 │                  │
        │        ▼                 │ context          │
        │  DataSourceManager ◄─────┘ inject __DSM     │
        │        │                                     │
        │        └── ComponentLoader → Smart Components│
        └──────────────────────────────────────────────┘
```

### 核心模块

| 分层 | Studio 模块 | Meta 模块 | 说明 |
| --- | --- | --- | --- |
| **Domain** | `app/studio/domain/schema/**` | `app/meta/schemas/**` | 纯类型/转换器，不依赖 React。|
| **Services** | `app/studio/services/**`（SchemaRuntimeAdapter 等） | `app/meta/runtime/**`（SchemaRuntime、ActionRegistry、DataSourceManager 等） | 核心业务引擎，封装状态/数据流。 |
| **Hooks** | `app/studio/hooks/**` | `app/meta/hooks/**` | “React glue”，只包装 services。 |
| **UI** | `app/studio/workbench/**` (Smart Components, Canvas, Panels) | `app/meta/rendering/**` (BlockRenderer, ComponentLoader) | UI 仅消费 hooks。|

### 1.1 分层架构与插件化（来自主架构文档）

```
┌─────────────────────────────────────────────────────────┐
│                    应用层 (Views)                        │
│  动态路由页面, 设计器界面, 业务组件                      │
└─────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────┐
│                   渲染层 (Rendering)                     │
│  SchemaRenderer, BlockRenderers, CellRenderers           │
└─────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────┐
│                  编排层 (Orchestration)                  │
│  SchemaRuntime (流程控制, 生命周期管理)                  │
└─────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────┐
│                   执行层 (Execution)                     │
│  ActionRegistry (原子操作), Handlers (业务逻辑)          │
└─────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────┐
│                  基础设施层 (Infrastructure)             │
│  Expression, State, DataSource, Theme, i18n              │
└─────────────────────────────────────────────────────────┘
```

#### 架构原则

1. **单向依赖**：上层依赖下层，下层不依赖上层。  
2. **职责单一**：每个模块只做一件事，例如 Orchestration 只负责流程控制。  
3. **依赖倒置**：对外暴露抽象接口（ActionRegistry、SchemaRuntimeConfig），插件或 UI 依赖抽象。  
4. **开闭原则**：新增组件、动作或数据源时仅“注册”扩展点，无需修改核心代码。  
5. **最小知识**：模块只了解自身需要的上下文，例如 Rendering 层只获取 Block/Field 信息而不关心 Action 实现。

---

## 2. 模块展平：Studio 与 Meta

### 2.1 Studio 侧

1. **Domain**：`FormSchema`、Block/Component 类型、`convertSchemaToUnified`。  
2. **Services**：  
   - `services/runtime/SchemaRuntimeAdapter.ts`：复用 meta runtime。  
   - `services/layout/**`：拖拽、对齐、Slotting 引擎。  
   - `services/actions/**`：命令系统、历史记录。  
3. **Hooks**：`useDesignerStore`、`useSmartComponent`、`usePageDataSources`（本质上包装 meta services）。  
4. **UI**：Canvas、Panels、Smart Components、DesignerPreview。

### 2.2 Meta 侧

1. **Domain**：`app/meta/schemas/types.ts`，统一 DSL。  
2. **Services**：`runtime/**`（SchemaRuntime、FlowRunner、ActionRegistry、DataSourceManager、ScopedStateManager）。  
3. **Hooks**：`useSchemaLoader`、`useSchemaRuntime`、`useFieldDataSource`、`usePageDataSources`。  
4. **UI**：`rendering/**`（SchemaRenderer、BlockRenderer、ComponentLoader）。

### 2.3 近期重构里程碑

- **表达式系统迁移**：所有求值逻辑集中到 `app/meta/runtime/expression/*`，彻底摆脱旧 `app/core` 依赖。  
- **SchemaRuntime 纯编排**：`executeAction` 只处理流程，对具体动作零感知。  
- **DataSourceManager 强制单例**：Runtime 配置项必须传入实例，`useFieldDataSource` 对缺失 Manager 的场景直接抛错。  
- **动态路由共享 Hook**：`useDynamicPageSetup` 将 schema 加载、数据源初始化、Runtime 创建整合为单一入口，List/New/Edit/View 统一使用。

---

## 3. 组件渲染序列（文本版时序）

1. Schema 中的 block/field 指定 `component`.
2. `RuntimeFieldRenderer` 等渲染器调用 `ComponentLoader`。
3. `ComponentLoader`：
   - 初始化 registry（如有必要）。
   - 根据 `componentName` 查询 manifest 中的 loader。
   - 通过 `import()` 获取真实的 Studio Smart 组件。
   - 缓存后渲染，并将 `context`、`dataSource`、`validation` 等 props 传入组件。
4. Smart 组件内部通过 `useSmartComponent` Hook 获取状态、表达式值、数据源结果等，运行期和设计态逻辑一致。

> **优势**：组件只需在 manifest 中登记一次，即可在 Studio 设计器、运行时预览、生产环境中共享同一套实现。

---

## 4. 数据源生命周期

### 4.1 注册阶段

1. `SchemaRuntime` 或 `usePageDataSources` 调用 `manager.register(id, config)`。
2. `DataSourceManager`：
   - 记录配置，补齐默认值（method、endpoint、adaptor 等）。
   - 如 `autoFetch === true`，立即调用 `fetch(id)`。
   - 若配置中包含 `dependOn`，则调用 `registerDependencies` 注册依赖。

### 4.2 依赖追踪

1. 当 `dependOn` 存在且 `ScopedStateManager` 可用时，`registerDependencies` 会：
   - 通过 store.subscribe() 监听依赖表达式的值数组；
   - 值变化时自动触发 `fetch(id)`。
2. 若无法订阅（如 scope 不存在），退化为定时器轮询（每 500ms 比较依赖值）。
3. `manager.unregister(id)` 或 `manager.clear()` 时会自动释放订阅，避免内存泄漏。

### 4.3 获取数据

1. `fetch(id, extraParams?)` 根据 config.type 执行 API 或处理静态数据。  
2. `fetchApiDataSource` 会对 `params`/`body` 进行表达式求值（支持 `{{ }}` 绑定）。  
3. 响应数据通过 `adaptData`（optionList/table 等 adaptor）转换。  
4. 最终调用 `setData` 更新状态并通知所有订阅者。

---

## 5. Designer Preview 与 Runtime 对齐

1. 设计器保存的 schema 调用 `convertSchemaToUnified()` 转换为 meta DSL。
2. `DesignerPreview` 创建真实的 ExpressionContext（包含 locale、user、permissions）。
3. 使用和生产环境完全一致的 `usePageDataSources`、`useSchemaRuntime`，并渲染
   `SchemaRendererWithContainer`。
4. 因 `ComponentLoader` 的 loader 来源于 manifest，预览中使用的组件与运行期一致。

> **结论**：设计器预览即运行时，无需额外适配层；任何在 Studio 中的改动都会直接反映在运行期。

---

## 6. 扩展点

| 扩展点 | 方法 |
| --- | --- |
| 新增 Smart 组件 | 在 `ComponentConfigs.ts` + `ComponentRuntimeManifest.ts` 中登记 → 实现组件。 |
| 新的数据源类型/适配器 | 扩展 `DataSourceManager.adaptData()` 或新增 `type`. |
| 自定义动作/事件 | 在 `runtime/actions/ActionRegistry.ts` 注册 handler。 |
| 自定义 Flow Step | 扩展 `FlowRunner` 解析逻辑或添加新的 step 类型。 |
| 表达式/上下文 | 在 `expression/context.ts` 中扩展全局函数或上下文字段。 |

---

## 7. 未来方向（建议）

---

## 8. 调用链路与场景

### 场景 1：页面初始化

1. 动态路由加载 schema（`useSchemaLoader`）并创建 `SchemaRuntime`。  
2. SchemaRuntime：创建 `ScopedStateManager`、绑定单例 `DataSourceManager`、注册数据源与 handlers、执行 `onEnter`。  
3. 渲染 `SchemaRenderer` → Area/Block → `ComponentLoader`。

### 场景 2：用户点击按钮

1. UI 调用 `runtime.executeHandler('submitHandler', args)`。  
2. SchemaRuntime 查找 handler/默认行为，然后执行 flow。  
3. Flow step 逐个交给 ActionRegistry（`form.validate`、`api.request`、`toast.success`、`dataSource.reload` 等）。  
4. DataSourceManager 根据需要刷新数据源。

### 场景 3：表单字段变化

1. Smart 组件通过 `useSmartComponent` 更新值。  
2. Hook 调用 `runtime.getStateManager().updateField(scopeId, field, value)`。  
3. ScopedStateManager 通知订阅者；`dependOn` 监听到变更会触发数据源刷新。

### 场景 4：数据源自动刷新

1. schema 的 dataSource 声明 `dependOn`。  
2. 注册时 DataSourceManager 订阅对应 state。  
3. state 变化 → 自动 `fetch` → `setData` → 通知组件。  
4. 无法订阅时回退到轮询模式。

---

## 9. 核心文件职责

| 层级 | 路径 | 关键职责 |
| --- | --- | --- |
| Infrastructure | `runtime/expression/*` | evaluate/bind/template/i18n 求值。 |
| Infrastructure | `runtime/state/scoped-state.ts` | 多作用域表单与 state 管理、订阅。 |
| Infrastructure | `runtime/data-pipeline/DataSourceManager.ts` | 注册/拉取/缓存/依赖追踪。 |
| Infrastructure | `runtime/theme/tokens.ts` | Design Token 定义与解析。 |
| Execution | `runtime/actions/ActionRegistry.ts` | 注册/执行原子动作。 |
| Execution | `runtime/events/builtin-handlers.ts` | 内置 Handler、默认按钮行为。 |
| Orchestration | `runtime/schema-runtime.ts` | Schema 生命周期 + Flow 编排。 |
| Rendering | `meta/rendering/SchemaRenderer.tsx` | 解析 schema.area/block 并渲染。 |
| Rendering | `meta/rendering/components/ComponentLoader.tsx` | 动态加载 Smart Components。 |
| Views | `app/routes/dynamic.*.tsx` | 动态路由页面，消费 Runtime + Renderer。 |

1. **Manifest 自动化**：通过构建脚本扫描 smart-components 目录，自动生成
   `ComponentRuntimeManifest.ts`，避免手动同步。
2. **依赖诊断面板**：提供 UI 或日志组件展示 `dependOn` 每次求值及触发情况，加速调试。
3. **组件懒加载优化**：未来可基于组件分类/页面切片做并行加载或预取策略。
4. **多 runtime 适配**：若需要支持 Web + Native 或 Web + 小程序，可在 manifest 中增加
   平台字段，生成不同的 loader。

---

通过以上架构设计，AuraBoot Runtime Platform 能够在保持高扩展性的同时，确保设计器与运行期的一致性与可维护性。任何对组件、数据源或动作系统的扩展都可以在清晰的边界内完成。
