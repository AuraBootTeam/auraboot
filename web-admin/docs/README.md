# Runtime Platform 使用指南（中文完整版）

本文档面向需要维护或扩展 AuraBoot Runtime Platform 的研发人员，完整描述组件体系、
数据源生命周期、运行时钩子以及如何与 Studio 设计器协同。阅读本指南可帮助你在无需翻阅
多份资料的情况下，快速理解平台的核心概念和最佳实践。

---

## 1. 分层全景：Domain → Services → Hooks → UI

AuraBoot 的运行期与设计器都遵循同一套分层：

1. **Domain（领域模型）**  
   - 位置：`app/studio/domain/**`、`app/meta/schemas/**`。  
   - 作用：描述 schema、block、component、dataSource、theme 等纯数据结构，不含 React。

2. **Services（无 UI 的业务引擎）**  
   - 位置：`app/studio/services/**`、`app/meta/runtime/**`。  
   - 包含 SchemaRuntime、DataSourceManager、ActionRegistry、LayoutManager 等。  
   - 只依赖 Domain，不依赖 React。

3. **Hooks（React Glue）**  
   - 位置：`app/studio/hooks/**`、`app/meta/hooks/**`。  
   - 仅做“React 封装”，内部调用 services/domain。  
   - 示例：`usePageDataSources`（封装 DataSourceManager）、`useSchemaRuntime`（封装 SchemaRuntime）、`useSmartComponent`（封装表达式/验证服务）。

4. **UI（Smart Components / Workbench）**  
   - 位置：`app/studio/workbench/**` 和运行期渲染器（`app/meta/rendering/**`）。  
   - 完全通过 hooks 访问 services，禁止直接依赖 services 代码，以保证可测试性和复用。

> **重点**：Hooks 绝不包含业务逻辑或副作用调度，全部委托给 services；UI 只消费 hooks，使得同一 services 能在 Studio、Preview、Runtime 三个环境复用。

---

## 2. 组件体系

### 2.1 设计态与运行态的单一事实来源

1. **设计态定义**  
   - 位置：`app/meta/registry/components/ComponentConfigs.ts`  
   - 作用：提供组件类型（如 `input`、`select`）、属性 schema、默认值、分类等信息。  
   - Studio 在属性面板和画布上完全依赖这些元数据进行渲染、校验和导出。

2. **运行态元数据（Manifest）**  
   - 位置：`app/meta/registry/components/ComponentRuntimeManifest.ts`  
   - 作用：描述组件在运行时对应的真实模块路径、导出名称和别名；例如：
     ```ts
     input: {
       modulePath: '../../../studio/workbench/components/smart-components/form/SmartInput.tsx',
       exportName: 'SmartInput',
       aliases: ['SmartInput', 'Input', 'input']
     }
     ```
   - 任何新增组件都必须在 Manifest 中登记一次，使设计器和运行时保持一致。

### 2.2 动态加载机制

1. `runtime-component-loaders.ts`
   - 使用 `import.meta.glob` 扫描 `app/studio/workbench/components/smart-components/**`，
     自动根据 Manifest 生成 loader。
   - 避免了手动维护 `import()` 语句或 switch-case，降低漏配风险。

2. `ComponentLoader.tsx`
   - 先调用 `initializeComponentRegistry()` 确保组件元数据加载完成。
   - 将调用方传入的 `componentName` 规范化（支持 Smart 前缀、小写、别名）。
   - 根据 Manifest 定位 loader + 导出名，懒加载真实的 Studio 组件并缓存。
   - 任意自定义组件只要在 Manifest 中定义即可无缝接入运行时。

### 2.3 运行态渲染流程

```
schema.blocks[].fields[].component
        │
        ▼
ComponentLoader
        │
        ▼
Dynamic import (Studio Smart Component)
        │
        ▼
Smart 组件渲染（共享 useSmartComponent hook）
```

> **提示**：Studio 中的 Smart 组件通过 `useSmartComponent` Hook 复用 meta 的表达式求值器与验证逻辑，因此预览与运行期可达到真正的所见即所得。

---

## 3. 数据源与运行时

### 3.1 DataSourceManager 单例

- 页面级通过 `usePageDataSources` 创建 `DataSourceManager`，并注入 `DataSourceContext`；
  所有子组件（字段、Smart 组件、Block）都从 context 获取同一个实例。
- `SchemaRuntime` 会把 `DataSourceManager` 绑定到 `ScopedStateManager`，并写入每个
  ExpressionContext 的 `__dataSourceManager`，方便表达式或 hook 直接调用。

### 3.2 Declarative dependOn

- `app/meta/schemas/types.ts` 允许在 schema 的 `dataSources[id]` 中声明：
  ```json
  {
    "dependOn": ["state.filters", "form.store.name"]
  }
  ```
- `DataSourceManager` 注册时会订阅 `ScopedStateManager`，只要依赖字段发生变化就自动触发
  `fetch`。当无法绑定（例如 scope 不存在）时，会退化为 500ms 的轻量轮询。
- 关闭 `autoFetch` 时，`dependOn` 不会生效，需要调用 `manager.fetch()` 或 Flow Handler 触发。

### 3.3 运行时核心（SchemaRuntime）

1. 初始化  
   - 创建作用域并写入 schema 初始 state。  
   - 绑定外部传入的 `DataSourceManager`。  
   - 注册 schema 中声明的数据源（尊重 `autoFetch` 与 `dependOn`）。  
   - 注册 handlers（支持 builtin / flow / script；script 默认禁用）。  
   - 执行 `schema.events?.onEnter`。

2. 暴露权限  
   - `getContext()`：获取最新 ExpressionContext（含 `__dataSourceManager`）。  
   - `getStateManager()`：访问作用域状态。  
   - `getDataSourceManager()`：直接操作数据源。  
   - `getEvaluator()`：在列表等场景可单独调用表达式求值器。

---

## 4. Studio 与 Runtime 的协作（跨模块展开）

### 4.1 Studio 模块（app/studio）

- **Domain**：`domain/schema/types.ts`、`domain/schema/converters/**`。所有转换/export 操作在此完成。  
- **Services**：  
  - `services/runtime/SchemaRuntimeAdapter.ts`：将 meta runtime 封装为 Studio 可用 API。  
  - `services/layout/**`：处理拖拽、对齐、Slotting、跨列布局。  
  - `services/actions/**`：命令系统（Undo/Redo、命名动作）。  
- **Hooks**：`useDesignerStore`、`useSmartComponent`、`usePageDataSources` 等，全部只做 React 封装。  
- **UI**：`workbench/**`（Canvas、Panels、Smart Components），以及 `DesignerPreview`.

### 4.2 Meta 模块（app/meta）

- **Domain**：`schemas/types.ts` 定义最终 DSL。  
- **Services**：`runtime/**`（SchemaRuntime、ActionRegistry、FlowRunner、DataSourceManager、ScopedStateManager）。  
- **Hooks**：`useSchemaLoader`、`useSchemaRuntime`、`useFieldDataSource`、`usePageDataSources`（运行期封装）。  
- **UI**：`rendering/**`（SchemaRenderer、BlockRenderer、ComponentLoader）。

### 4.3 协同流程

1. **Schema 转换**：Studio 使用 `convertSchemaToUnified` 将设计态 DSL 转成运行态 DSL。  
2. **Runtime Adapter**：Studio 通过 `SchemaRuntimeAdapter` 直接复用 meta 的 ActionRegistry、DataSourceManager、Runtime Hooks。  
3. **预览一致性**：`DesignerPreview` 使用与生产环境完全相同的 hooks/services，确保行为一致。  
4. **组件对齐**：由于 ComponentLoader 读取共享 manifest，设计器和运行期加载的 Smart 组件完全一致。  
5. **Hook 规范**：所有 React Hook 仅封装 services（例如 `useSmartComponent` 调用表达式服务、验证服务、数据源服务），不会出现逻辑分叉。

---

## 5. 常见操作流程示例

### 4.1 新增 Smart 组件

1. 在 `app/studio/workbench/components/smart-components/...` 中实现组件。  
2. 在 `ComponentConfigs.ts` 中添加类型描述（名称、分类、属性 schema）。  
3. 在 `ComponentRuntimeManifest.ts` 中登记模块路径、导出名和别名。  
4. 运行 `npm run dev` 或相关构建任务，即可通过 `ComponentLoader` 在运行期使用。

### 4.2 新增数据源

```json
{
  "dataSources": {
    "ds_productList": {
      "type": "api",
      "endpoint": "/api/product",
      "params": "{{state.filters}}",
      "autoFetch": true,
      "dependOn": ["state.filters"]
    }
  }
}
```
- 运行时会自动注册 `ds_productList`，在 `state.filters` 变化时重新请求。
- 如果组件需要手动刷新，可调用 `manager.reload('ds_productList')` 或在 Flow 中使用 `dataSource.reload` 动作。

### 4.3 在 Block 中使用组件

```json
"fields": [
  {
    "field": "name",
    "label": "商品名称",
    "component": "input",
    "validation": [{ "type": "required", "message": "必填" }]
  },
  {
    "field": "category",
    "label": "商品分类",
    "component": "select",
    "dataSource": "ds_categories",
    "dependOn": ["form.storeId"]
  }
]
```
- `component` 即 `componentName`，`ComponentLoader` 按 Manifest 解析。
- 如果字段自身也声明 `dependOn`，会在 `useSmartComponent` 内部触发手动 `manager.fetch` 或其他 Hook 逻辑。

---

## 6. 约束与最佳实践速览

| 方面 | 要求 |
| --- | --- |
| 组件注册 | 仅可在 `ComponentConfigs.ts` 和 `ComponentRuntimeManifest.ts` 描述，禁止其他文件直接 import Smart 组件。 |
| 导出契约 | Manifest 中的 `exportName` 必须与组件实际导出匹配。 |
| 别名规范 | 建议使用小写类型名；若有旧代码依赖 `SmartXxx`，务必在 `aliases` 中补齐。 |
| 数据源依赖 | 使用 `dependOn` 声明触发条件，避免在组件层写 `useEffect` + `fetch`。 |
| 表达式安全 | 所有表达式/校验/数据转换统一走 `expressionEvaluator`，禁止 `new Function` / `eval`。 |
| 文档归档 | Runtime 相关文档统一放在 `docs/` 目录。 |

详情可参考 `CONSTRAINTS.md` 中文版取得更深入的约束说明。

---

## 7. 下一步行动建议

1. **Manifest 自动生成**：后续可通过 AST/文件扫描自动生成
   `ComponentRuntimeManifest.ts`，进一步降低人工维护的成本。  
2. **依赖诊断工具**：构建 `dependOn` 调试面板，帮助开发者查看表达式是否正确解析、何时触发刷新。  
3. **扩展动作系统**：在 `runtime/actions/ActionRegistry.ts` 中注册更多内置动作（外部 API、消息服务等），并与 Studio UI 对接。

### 7.1 近期重构亮点（来自主架构文档，仅保留仍适用内容）

| 编号 | 内容 | 效果 |
| --- | --- | --- |
| P0-1 | 表达式系统迁移到 `app/meta/runtime/expression`，移除 `app/core` 依赖 | Meta Framework 完全自包含，可独立发布 |
| P0-2 | SchemaRuntime 改为“纯编排”模式，所有原子操作委托给 ActionRegistry | 新增动作只需注册，无需修改 SchemaRuntime |
| P0-3 | DataSourceManager 强制单例，`SchemaRuntimeConfig` 必须传入实例 | 彻底解决多实例数据不同步问题 |
| P1-1 | 动态路由共享 `useDynamicPageSetup` Hook | List/New/Edit/View 页面共享初始化流程，减少重复代码 |

> 这些变更已经并入 runtime-platform 文档，后续新增重构也请同步更新此表。

---

## 8. 总结

AuraBoot Runtime Platform 通过「组件元数据 + Manifest + 动态 loader + 单例 DataSourceManager」
构建了一套高度解耦的运行环境，既能保持设计器与运行期的完全一致，也方便在未来快速扩展。
请在新增组件、数据源或表达式功能时严格遵循本文档中的流程和约束，以确保系统稳定演进。
