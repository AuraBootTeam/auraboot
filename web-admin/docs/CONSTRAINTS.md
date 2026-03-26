# Runtime Platform 约束与最佳实践（中文完整版）

本文件总结运行期需要遵守的规则、常见风险以及建议方案，以保障组件体系、数据源和表达式在
设计器与生产环境之间保持一致、稳定。

---

## 1. 组件相关约束

### 1.1 注册与导出（Domain & Services 边界）

1. **唯一数据来源**  
   - 组件描述仅可存在于 `ComponentConfigs.ts`（设计态）和
     `ComponentRuntimeManifest.ts`（运行态）。  
   - 禁止其他文件直接 `import` Smart 组件，否则会破坏懒加载与缓存机制。

2. **导出名称符合 manifest**  
   - Manifest 中的 `exportName` 必须与组件真实导出一致。
   - 如果使用 `default export`，需在 manifest 中显式注明或者留空（默认 `default`）。

3. **命名与别名**  
   - 推荐使用小写类型，例如 `input`、`select`。  
   - 兼容旧代码时，可在 `aliases` 中增加 `SmartInput`、`Input` 等别名。  
   - ComponentLoader 会把名称统一在 manifest 处理，避免硬编码。

### 1.2 组件实现（Hooks = React Glue）

1. **Hook 统一**：Studio 组件必须使用 `useSmartComponent`、`useFieldDataSource` 等
   公共 hook，杜绝自建 `new Function` 或自定义 evaluators。
2. **Props 约定**：所有 Smart 组件应遵循 `SmartComponentProps` 中的属性，如
   `context`、`validationRules` 等，以便运行时传入一致的数据结构。
3. **调试输出**：不建议在组件内打印频繁日志（例如每次渲染日志），避免运行期噪声。

---

## 2. 数据源约束

### 2.1 注册与生命周期

1. **单例管理**：同一页面（作用域）只能由一个 `DataSourceManager` 管理。请通过
   `usePageDataSources` 创建，不要手动 `new DataSourceManager()`。
2. **自动清理**：在 React 组件卸载时必须调用 `manager.clear()`（`usePageDataSources`
   已默认处理），否则会残留订阅。
3. **重复注册保护**：`SchemaRuntime` 注册数据源时，如果发现同名 dataSource 已存在会跳过。

### 2.2 dependOn 的使用（Services 层责任）

1. **仅限 autoFetch 数据源**：`dependOn` 只对 `autoFetch: true` 的数据源生效。
2. **表达式合法性**：表达式必须能在当前 `ExpressionContext` 求值成功，常见写法：
   `state.filters`, `form.store.name`, `global.user.id`。  
3. **避免深层对象 diff**：依赖数组中不要传入大对象，例如 `state` 整体。应拆分到具体字段，
   否则 JSON 比较成本高且容易触发无效刷新。

### 2.3 请求与适配

1. **统一的 fetchResult**：所有 API 数据源调用 `fetchResult`，保持鉴权/错误处理一致。  
2. **Adaptor 定义**：如需新增 `adaptor`，请在 `DataSourceManager.adaptData` 中实现，并写入
   README/约束文档。  
3. **错误处理**：`fetch` 捕获异常后必须调用 `setError`，并在组件中合理提示用户。

---

## 3. 表达式与上下文

1. **禁止 `eval/new Function`**：所有表达式、数据转换、验证逻辑必须使用
   `expressionEvaluator` 及相关 helper。Studio Hooks 已经统一迁移，请勿新增自定义求值方式。
2. **上下文注入**：`SchemaRuntime` 会把 `__dataSourceManager` 写入 ExpressionContext。
   如果在其他场景构造 context，必须手动加入该字段以保证 Smart 组件能访问数据源。
3. **错误容忍度**：表达式解析失败时应捕获错误、记录日志并返回默认值，避免页面崩溃。

---

## 4. 文档与目录（组织层面）

1. Runtime 相关文档（README/Architecture/Constraints/总览）统一放在
   `docs/ `，并保持中英文一致（如有）。
2. 若新增规范或流程，请同时更新 README/ARCHITECTURE/CONSTRAINTS，确保团队成员可以从
   单一目录获取所有信息。

---

## 5. 设计准则（节选自主架构文档）

### 准则 1：统一表达式系统

- **要求**：所有代码只能使用 `~/meta/runtime/expression` 导出的 `evaluate/bind/evaluateTemplate/renderText`。
- **迁移**：禁止引用 `~/core/expression-parser`；旧文件需要逐步替换。
- **示例**：
  ```ts
  // ✅
  import { evaluate } from '~/meta/runtime/expression';
  evaluate('${state.filters.keyword}', ctx);

  // ❌
  import { ExpressionParser } from '~/core/expression-parser';
  ExpressionParser.parse(expr, ctx);
  ```

### 准则 2：SchemaRuntime 仅负责编排

- SchemaRuntime 只处理流程控制（if/loop/sequence），所有动作交由 ActionRegistry。
- ActionRegistry 扩展方式：`actionRegistry.register('myAction', handler)`.

### 准则 3：DataSourceManager 单例

- 页面级通过 `usePageDataSources` 创建单例，并通过 `DataSourceProvider` 下发。  
- 子组件必须使用 `useDataSourceManager`，禁止 `new DataSourceManager()`。

### 准则 4：统一渲染模式

- 所有动态组件必须通过 `ComponentLoader` 渲染。  
- `FieldRenderer` 仅在需要额外逻辑时使用。  
- 禁止手写 `<input>` / `<select>` 等来代替 Smart 组件。

### 准则 5：Hook 职责单一

- 每个 Hook 只做一件事，例如 `useRuntimeHandler` 仅调用 `runtime.executeHandler`。  
- 避免“上帝 Hook”承担初始化、执行、错误处理等多个责任。

### 准则 6：类型安全优先

- 所有公开 API 必须有完整 TypeScript 定义；禁止裸 `any`。  
- 例如 `DataSourceConfig`, `ActionContext`, `SchemaRuntimeConfig` 等必须保持最新。

### 准则 7：配置优于编码

- 优先使用 DSL 配置（handlers/flows/dataSources）实现业务逻辑，尽量减少硬编码操作。  
- 例如删除流程应通过 Flow + `api.request` + `toast.success` + `dataSource.reload` 完成。

---

## 6. 架构检查清单

| 领域 | 检查项 |
| --- | --- |
| 表达式 | 已使用 meta 表达式模块，ExpressionContext 完整。 |
| 状态 | 多表单场景使用 ScopedStateManager，每次更新调用 `updateScope` / `updateField`。 |
| 数据源 | DataSourceManager 单例，`dependOn` 配置合理，无组件级实例化。 |
| 动作 | SchemaRuntime 只做流程控制，动作全部注册在 ActionRegistry。 |
| 渲染 | ComponentLoader 是惟一的动态渲染入口，BlockRenderer/FieldRenderer 只做包装。 |
| Hooks | 每个 Hook 责任明确，依赖数组正确，避免隐藏副作用。 |
| 类型 | 公共 API 均有类型定义，禁止 `any`。 |
| DSL | 尽量通过 schema 配置实现业务，减少硬编码。 |

---

## 7. 常见违例示例

| 场景 | 问题 | 改进 |
| --- | --- | --- |
| 组件直接从 `app/studio/...` import | 绕过 ComponentLoader，无法懒加载和缓存 | 仅在 manifest 中定义，使用 ComponentLoader 渲染 |
| 数据源未声明 `dependOn` | 需要在组件层手动 `fetch`，容易遗漏 | 在 schema 中声明依赖，交给 DataSourceManager |
| Hook 内 `new Function` | 运行期和设计器行为不一致，存在安全隐患 | 改用 `expressionEvaluator.evaluate/bind` |
| 文档散落在不同目录 | 难以维护、团队难以找到规范 | 统一放在 `docs/ ` |

---

---

## 8. 反模式速查

1. **重复创建 Manager**：组件内 `new DataSourceManager()` → 统一改用 Context。  
2. **绕过 ActionRegistry**：在 SchemaRuntime 或 Hook 中直接实现动作 → 使用 `actionRegistry.register()`。  
3. **混用表达式解析器**：仍然引用 `~/core/expression-parser`。  
4. **内联硬编码逻辑**：删除/提交流程直接写在组件中，而不是 DSL Handler。  
5. **跳过 ComponentLoader**：手动渲染 HTML 控件代替 Smart 组件。  
6. **Hook 过载**：`useActionHandler` 同时管理 runtime、actions、loading、toast → 应拆分。

---

## 9. 总结

通过遵循以上约束，团队可以保证：

1. 设计器与运行期始终共享同一套组件与表达式语义；  
2. 数据源自动刷新、缓存与订阅逻辑保持一致；  
3. 文档、元数据和实现能够同步演进，不会出现分叉或未知行为。

在进行任何重大改动（新增组件类型、修改数据源协议、扩展表达式权限）前，请务必先更新本文件
并与团队达成共识。
