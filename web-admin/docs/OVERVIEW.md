# Runtime Platform 总览（中文）

本文档汇集 `README.md`、`ARCHITECTURE.md`、`CONSTRAINTS.md` 的核心内容，完整描述
组件系统、数据源体系、架构流程以及运行期约束，帮助中文读者快速掌握 AuraBoot
Runtime Platform 的最佳实践。

---

## 1. 平台概览

1. **组件注册体系**
   - 所有 Smart 组件在 `app/meta/registry/components/ComponentConfigs.ts` 中定义，包含
     类型、属性面板、默认值等设计时信息。
   - `ComponentRuntimeManifest.ts` 为每个类型提供运行时元数据：模块路径、
     导出名称、别名。新增组件只需维护这两个文件即可。

2. **动态加载**
   - `runtime-component-loaders.ts` 通过 `import.meta.glob` 扫描整个
     `app/studio/workbench/components/smart-components` 目录，基于 manifest 自动生成
     loader。
   - `ComponentLoader.tsx` 使用上述 loader，根据组件名（或别名）懒加载真实的 Studio
     组件并缓存，确保设计器/运行期完全一致。

3. **数据源管理**
   - `usePageDataSources` 创建页面级 `DataSourceManager` 实例，并注入到
     `DataSourceContext`，所有字段/组件通过 context 复用同一个管理器。
   - Schema 级 `dependOn` 声明让数据源可绑定到 `ScopedStateManager`，在相关状态/表单
     变化时自动刷新；若无法绑定则回落到轻量轮询。

4. **运行时核心**
   - `SchemaRuntime` 负责作用域状态、数据源注册、事件/动作编排，将
     `expressionEvaluator` 暴露给渲染层。
   - Studio 的 `useSmartComponent`、`DesignerPreview` 均直接复用 meta 的 evaluator、
     runtime hook，从而实现真正的 WYSIWYG。

---

## 2. 架构流程

```
Designer Schema
  │ convertSchemaToUnified()
  ▼
Unified Schema ──► SchemaRuntime ──► ScopedStateManager
  │                     │
  │                     └─ Context（注入 __dataSourceManager）
  │
  ├─ register dataSources via DataSourceManager
  └─ render blocks via ComponentLoader → Studio Smart Components
```

### 2.1 组件解析流程

1. Block/FieldRenderer 提交 `componentName`。
2. `ComponentLoader` 规范化名称（支持别名/小写/Smart 前缀）。
3. 根据 manifest 找到 loader + 导出名称，执行 `import()`。
4. 将导出的 React 组件缓存并渲染。

> **最佳实践**：新增组件时只需更新 manifest；不要在其他地方硬编码 import。

### 2.2 数据源刷新流程

1. 在 schema 中声明：
   ```json
   "dataSources": {
     "ds_store": {
       "endpoint": "/api/store",
       "autoFetch": true,
       "dependOn": ["state.filters", "form.store.name"]
     }
   }
   ```
2. 注册时，`DataSourceManager` 绑定 `ScopedStateManager`，监听上述依赖。
3. 依赖变化时触发 `manager.fetch(id)`；若无法绑定（如表达式无法求值）则启动定时轮询。

> **提示**：`autoFetch: false` 时不会自动监听，需要显式调用 `fetch()`。

### 2.3 设计器预览与运行期对齐

- `convertSchemaToUnified` 保证 DSL 结构一致。
- `DesignerPreview` 创建真实的 expression context（含 locale/user/permissions），复用
  `usePageDataSources` + `useSchemaRuntime`，确保运行期行为与预览一致。

---

## 3. 运行期约束与最佳实践

### 3.1 组件系统

- **单一事实来源**：组件定义只能出现在 `ComponentConfigs.ts`（设计时）和
  `ComponentRuntimeManifest.ts`（运行时）。禁止在任何其他文件中 import Smart
  组件。
- **导出契约**：组件必须导出 manifest 指定的名称（或 `default`），否则
  `ComponentLoader` 会报错。
- **命名/别名**：推荐使用小写类型名；如需兼容旧代码，可在 manifest 填写
  `aliases`（例如 `SmartInput`、`Input`、`input`）。

### 3.2 数据源 / Schema

- **Declarative dependOn**：优先使用 `dependOn` 声明依赖关系，避免在组件内部订阅状态。
- **作用域清洁**：`dependOn` 中只引用已存在的 `state.*` / `form.*` 字段；缺失字段会被
  解析为 `undefined`，导致 diff 始终为相同值。
- **autoFetch 语义**：当 `autoFetch` 关闭时，`dependOn` 不会触发自动刷新，需要在流程中
  手动调用 `manager.fetch(id)` 或 `manager.reload(ids)`。

### 3.3 表达式安全

- 所有表达式/验证逻辑必须通过共享的 `expressionEvaluator` 求值（Studio hooks 已接入）。
  禁止使用 `new Function`/`eval`。
- 组件/数据源在捕获 evaluator 错误后应优雅降级（如记录日志、返回默认值），防止整个
  页面崩溃。

### 3.4 文档与目录

- 运行期相关文档统一放在 `docs/`，保持 README/Architecture/Constraints
  同步更新。
- 若需要新的构建清单，应该基于 `ComponentRuntimeManifest.ts` 二次生成，避免出现双份
  松散映射。

---

## 4. 可扩展点与未来方向

1. **Manifest 自动生成**：未来可考虑以 AST/文件扫描方式自动生成
   `ComponentRuntimeManifest.ts`，进一步减少人工同步成本。
2. **Datasource Diagnostics**：为 `dependOn` 表达式提供调试日志或面板，方便定位解析
   失败/依赖缺失。
3. **动作/插件生态**：通过 `runtime/actions/ActionRegistry.ts` 注册更多动作（如第三方
   集成、跨系统流程），并提供 Studio 端的配置 UI。

---

## 5. 结语

通过 Manifest + Loader + 单例 DataSourceManager 的组合，AuraBoot Runtime Platform
能够在保持设计时灵活性的同时确保运行期高一致性。请在新增组件、扩展数据源或调整表达式
行为时遵循本文件中的最佳实践，确保系统演进过程中保持可维护、可调试和可验证。
