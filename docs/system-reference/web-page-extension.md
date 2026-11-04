---
type: system-reference
status: active
---

# Web 页面扩展机制(webShell / 应用 web 包 / 贡献组件)

> 状态:机制参考(2026-11-04,依 aura-bpm 生产实例与 web-admin 源码逐层核实)。
> 面向 Agent 的判定卡在 auraboot-enterprise `docs/agent-rules/engineering-gotchas/web-page-extension.md`。

## 1. 三层结构

| 层 | 载体 | 内容 | 例 |
|---|---|---|---|
| **Core web-admin bundle** | 平台仓 `web-admin` 构建 | 动态路由 DSL 页(`/p/<model>` 全部 84 页模式)、BlockRegistry、ComponentLoader 解析链 | `/p/orb_deployment_run/view/...` |
| **应用 web 包** | 应用仓 `app.yaml` 声明 `webShell: 1.0.0` + `webPackages`(共享库清单) | 应用自有 bundle = web shell + 应用页面 + 贡献组件,独立构建独立服务 | aura-bpm 的 `/bpm/designer`(BPMNDesigner) |
| **PF4J 插件 jar** | `plugin/config/*` + 后端类 | 只管后端:models/commands/pages DSL 声明、引擎 handler | orbit-core |

**边界铁律**:PF4J jar 管不了前端;前端 TSX 由**应用 web 包**携带;Core 渲染的 DSL 页只能使用 Core bundle 内的组件——跨 bundle 不互渲染。

## 2. 贡献通路(应用 web 包 → web shell)

插件前端入口 = `definePlugin` 的 `setup(ctx)`,ctx 提供(reality:aura-bpm `packages/aura-bpm-web/src/contribution-module.ts`):

```ts
ctx.registerComponentLoader({ id, componentName: 'SlaPolicyListBlock',
  load: () => import('./components/SlaPolicyListBlock') })   // 动态 import 的 TSX 组件
ctx.registerRenderer(...)      // 渲染器贡献 → kernel contributionRegistry
ctx.registerSlot / registerDataSourceProvider / registerAsset / registerI18n / registerPageRuntimeHook ...
```

DSL 页面引用贡献组件(先例:aura-bpm `plugin/config/pages.json` 的 `sla_config_list`):

```json
{ "id": "sla_policy_workspace", "blockType": "custom", "component": "SlaPolicyListBlock" }
```

## 3. ComponentLoader 解析链(`web-admin/app/framework/meta/rendering/components/ComponentLoader.tsx`)

1. `EAGER_CORE_COMPONENTS`(核心急加载表)命中 → 直接用;
2. 组件缓存命中 → 用;
3. `getRuntimeComponentEntry`(`runtime-component-loaders.ts`):
   - `COMPONENT_RUNTIME_MANIFEST`(静态映射 type → modulePath/componentName) +
   - `import.meta.glob(['ui/smart/**/*.tsx', '/app/ui/smart/**/*.tsx'])`(Vite glob,**放在 `app/ui/smart/**` 下的任何组件自动可被按路径加载**)→
   - 两处都没有 → `Unknown component` 报错。
4. kernel `contributionRegistry` 的 component-loader 贡献由插件 loader(`framework/plugins/loader.ts` 的 `registerComponentLoader` → `contributionRegistry.register('component-loader', pluginCode, reg)`)在插件激活时注册——**仅对已激活插件的前端代码生效,且该前端代码必须在 bundle 构建清单里**。

## 4. 判定流(页面要自定义渲染/画布/拖拽时)

1. **页面宿主 bundle 是谁?** core DSL 页(`/p/...`)→ 组件必须进 core bundle;应用自有页面(`/bpm/designer` 式)→ 应用 web 包随意。
2. **core DSL 页要新交互组件**:新块(backend `DslRegistry.BlockType` + frontend `BlockRegistry.register` + 渲染器)或 `app/ui/smart/**` 组件 + `ComponentRuntimeManifest` 条目。
3. **应用自有页面**:contribution 通路(§2),依赖库优先复用已有(如 `@xyflow/react` 已在 core 与 aura-bpm-web 双侧就位)。
4. **禁止**再输出"插件带不了前端 / 插件没有 TSX"这类结论——错误史实见 enterprise 判定卡。
