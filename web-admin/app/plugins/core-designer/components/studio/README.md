# Studio (New Designer) Architecture

`app/studio/` 承载了新一代页面设计器的重构工作。整个目录遵循 “Domain →
Services → Hooks → Workbench” 的分层思想，确保 React UI 与运行时/引擎解耦。

```
app/studio
├── domain/        // Schema + metadata 纯类型、DSL 转换与版本管理
├── services/      // 布局、拖拽、运行时桥接、命令系统等无 UI 引擎
├── hooks/         // 纯 React Hook，按 drag/layout/runtime 等模块封装 services
├── workbench/     // Canvas、Panels、Providers、Palette、Smart Components 等 UI
├── components/    // （仅保留 workbench 内的 smart-components）
├── sdk/           // 设计器与 meta runtime 的集成层
└── test/          // Vitest / Playwright 等测试
```

## 目录说明

- **domain/** – 完全无 React 依赖，只包含类型定义与转换器。
  - `domain/schema/types.ts` 提供 FormSchema/Block/Component 等结构，以及支持
    kind/breakpoints/theme 等新字段。
  - `domain/schema/converters/toUnified.ts|toExport.ts` 负责运行时/导出 DSL 互转。
  - `domain/metadata/*` 封装版本管理协议，可供 services 或 hooks 复用。
- **services/** – 桌面和运行时通用的业务引擎。
  - `services/layout/*` 重构为 alignment/snap/resize/slotting/drag-preview 五大子模块，
    并通过工厂函数暴露无状态引擎（如 `createCrossColumnDragEngine`）。
  - `services/runtime/SchemaRuntimeAdapter.ts` 将 meta 的 ActionRegistry /
    useSchemaRuntime / 数据源钩子封装成 studio adapter，确保 DesignerPreview 与
    生产运行期共享一套逻辑。
  - `services/actions/*` 收纳命令、历史、Undo/Redo、事件域等模块；`services/state`
    与 `services/managers` 提供 store/pageState/版本管理等单例适配。
- **hooks/** – 纯 React hook，仅向 UI 层暴露 `useDragPreview`、`useSnapAndAlign`,
  `useCrossColumnDrag`, `useDesignerPreview`, `useFormRefManager` 等接口，内部依赖
  services，实现 “services → hooks → UI” 的单向引用关系。
- **workbench/** – 设计器所有可视化体验所在。
  - `canvas/` 包含 DesignCanvas、GridCell、DraggableWrapper、SmartDropZone 等画布组件。
  - `panels/` 包括属性面板、版本面板、AutoSave、CrossColumnDragPanel 等模块。
  - `components/` 目录下整合 smart-components、system 工具、workflow 等 UI；同时
    暴露 `ConflictResolver`、`MultiSelectManager`、`SelectionOverlay` 等单元。
  - `providers/`、`palette/`、`toolbar/` 重构为 studio 自有实现，避免引用 legacy 代码。
  - `workbench/runtime/DesignerPreview.tsx` 复用了 services/runtime adapter，可在 IDE、
    独立预览或运行时共用。
- **sdk/** – 对外暴露 `getDesignerSDK()` 等 API，聚合 schema/layout/state/command
  管理器，便于第三方或 app/meta 直接嵌入 Studio。

## 关键设计原则

1. **Domain + Services 无 React 依赖**  
   所有类型/转换/引擎均位于 `domain/` 与 `services/`，可被运行期、IDE、测试共用。
   Hooks 只是简单的 React 适配层，UI 只与 hooks 交互。

2. **运行时桥接统一**  
   `SchemaRuntimeAdapter` 暴露 actionRegistry、数据源、表达式执行器等统一接口，
   DesignerPreview 与生产运行时只需切换 adapter 即可共享逻辑，避免暴露内部 DSL。

3. **状态隔离与工厂模式**  
   Layout/drag 等 engine 均通过工厂函数创建实例，并使用冻结的 preset，初始化时
   `cloneDeep` 以杜绝跨画布的引用污染。

4. **命名一致 & 路径规范**  
   全部 import 统一使用 `~/studio/...`，并将 legacy `core/` 相关引用重命名为
   `domain` 或 `services`，避免含糊不清的路径。

5. **Testing 就地化**  
   每个子模块可在自身目录下建立 `__tests__`（已在 `runtime/__tests__`,
   `services/layout/**/__tests__` 等目录启用 Vitest），确保重构期间行为一致。

## 当前进展

| 区域 | 说明 |
| --- | --- |
| Domain schema & converters | ✅ 已完全迁入 `domain/`，支持 kind/layout/theme 扩展 |
| Services (layout/runtime/actions/state) | ✅ Alignment、Snap、Resize、DragPreview、Slotting、Runtime Adapter、命令系统等均为 Studio 版本 |
| Hooks (drag/layout/runtime/forms) | ✅ 统一从 services 获取引擎，UI 不再直接依赖 legacy hook |
| Workbench – Canvas/Palette/Toolbar/System | ⚙️ 主要组件已迁移，但部分 Workflow 功能仍需精炼 |
| Workbench – Smart Components & Property Panel | ⚙️ Smart 组件/属性编辑器已合并到 `workbench/components/smart-components`，仍在补齐类型与交互 |
| State/Event managers | ⚙️ 通过 services/managers 适配 legacy 单例，后续可替换为全新实现 |
| SDK / Integration | ⚙️ `sdk/index.ts` 暴露 store/schema/layout/command 等管理器，未来可扩展 useSchemaRuntime 等 API |
| Testing | 🚧 Vitest 覆盖 canvas/runtime/services，仍需扩充 workbench panels/工具栏等测试 |

> 提示：`pnpm typecheck` 仍会因 app 中其它旧模块（auth、BPMN 等）报错，Studio
> 子目录的类型/路径问题需优先清理；完成后再整体修复仓库级别的错误。

欢迎在此目录继续迁移剩余模块，遵循上述分层即可快速定位职责并保证运行时可复用。***
