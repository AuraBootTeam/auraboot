---
type: backlog
status: active
created: 2026-05-29
---

# `<Popover>` Invalid hook call → cascades via RenderErrorBoundary

**Status**: OPEN (P3 — non-blocking) — **v2 diagnostic 2026-05-30 by implementer subagent**
**Discovered**: 2026-05-29 by subagent (a441e204c3316baba) during P2 diagnostic stack 实测
**Filed**: 2026-05-29 / **v2 updated**: 2026-05-30

## 症状

在 `/p/bpm_process_management` 真浏览器渲染时,console 报 `<Popover>` 组件 invalid hook call,被 `RenderErrorBoundary` 兜住。**不导致页面空白**(其它组件正常渲染,有数据有交互),但属于 React tree 内的 silent error。

## Stack trace

```
useScope @radix-ui_react-popover.js
  → useMemo (chunk-EKPZ7L7G)
  → RenderErrorBoundary catch
```

---

## v2 静态诊断结果(2026-05-30)

### Phase 1 实测发现

| 检查 | 命令 | 结果 |
|------|------|------|
| web-admin React 多版本 | `pnpm list react react-dom` | **单版本** react@19.2.6 / react-dom@19.2.6 |
| node_modules 物理副本 | `ls web-admin/node_modules/react` + 无 `.pnpm` | **物理只一份**(npm flat layout) |
| `@radix-ui/react-popover` 版本 | `pnpm list @radix-ui/react-popover` | **单版本** 1.1.15 |
| vite.config.ts `resolve.dedupe` | `grep "dedupe"` vite.config.ts | **未设置** ⚠️ |
| vite.config.ts `optimizeDeps.include` | grep | 只含 `react-grid-layout` / `react-draggable` / `react-resizable` — **不含 react / react-dom / radix-ui** ⚠️ |
| Federation plugin 配置 | grep `federation(` | **关键**: `@originjs/vite-plugin-federation` 1.4.x,`shared: { react, react-dom, react-router, zustand, @reduxjs/toolkit, lucide-react }`,无 remote(`remotes: {}`) |
| ViewSelector 自身 Popover 调用 | 读 `web-admin/app/framework/smart/components/view/ViewSelector.tsx:187-300` | **不用 Radix Popover**,用 native button + 自定义 outside-click。**排除 view-selector 本身** |
| Radix Popover 在 list page 路径 | grep `@radix-ui/react-popover` 入 list 渲染链 | **真用户**: `web-admin/app/framework/meta/rendering/pages/list/SortPopover.tsx`(被 `ListToolbar.tsx` 引用,在 `ListPageContent` → `bpm_process_management` 链路上) |
| SortPopover 自身 hook 合规 | 读 SortPopover.tsx:48-247 | **合规** — `useState` / `useCallback` 均在组件顶层 |
| `~/ui/ui/popover.tsx` (shadcn 封装) | 读 | 标准 `PopoverPrimitive` 转发,无问题 |

### 根因归类(推断 — 未跑 dev 实测)

**最可能 = `@originjs/vite-plugin-federation` × React 实例分裂**

- federation 1.4.x 把 `react` / `react-dom` 列为 shared(L26-27),即使没 remote,也会在 rollup 阶段对 react 做"share scope proxy"改写
- `@radix-ui/react-popover` 引用的 `react` 走 federation share scope(可能产生 wrapper module),而用户代码(`SortPopover` 等)直接 `import react` 走 vite 原生模块图
- 两边拿到的 React 不是同一 dispatcher → `useScope`(Radix 内部用 `useMemo`)触发 "Invalid hook call"
- vite.config.ts **缺 `resolve.dedupe: ['react','react-dom']`** 加剧问题:dev 时 esbuild pre-bundle 没强制 react/radix-popover 走同一解析路径

**次要可能**:`optimizeDeps.include` 没把 `@radix-ui/react-popover` 加进来,导致它走 ESM 直传 + 自己 import react,与 pre-bundled chunk 拿不同 react

### Fix 候选(均需 dev mode 实测验证 — 不能盲改)

**Option A(最小手术)**: vite.config.ts 加
```ts
resolve: { dedupe: ['react', 'react-dom', '@radix-ui/react-popover'] },
optimizeDeps: { include: ['react', 'react-dom', '@radix-ui/react-popover', ...] }
```
- 风险:改 vite 配置可能影响整体 build chunk 拓扑,需重跑全套 E2E 才能放心 merge
- 验证手段:必须起 `pnpm dev:full` + 真浏览器打开 `/p/bpm_process_management` 看 console 是否还报 → 静态 typecheck 无法验

**Option B(更稳)**: 临时 fallback 把 `SortPopover` 改成 non-portal 版本 / 临时禁用 — 但本质回避问题,不推荐

**Option C(根治但代价大)**: 去掉 `@originjs/vite-plugin-federation`,改用 Module Federation 2.0(`@module-federation/vite`)或 native dynamic import — 影响整个 plugin 加载架构,远超 P3 scope

### 为什么本会话不修

按 AGENTS.md §15 verify-before-claim + §19「敢说够了」+ Implementer prompt 「不清楚 / 多原因 → fail-fast」:

1. 改 vite.config.ts 是**架构层动作**,必须有 runtime 验证;本会话约束不起 stack
2. federation × react 分裂是经典坑,但**具体哪条 share 链路出问题**只有 dev mode 看 chunk graph 才能确证
3. 静态 typecheck / lint 无法验"hook 错误消失"
4. 风险:盲加 dedupe 可能让某 plugin federation 加载链断 → 引入新回归 > 修 P3 silent log

## Owner 建议

web-admin / 前端框架 owner 接手,**带 runtime 验证**:

1. 在隔离 worktree 起 `pnpm dev:full`
2. 打开 `/p/bpm_process_management`,Devtools 看 stack 完整路径(确认是 SortPopover 还是 shadcn popover.tsx)
3. 试 Option A 改 vite.config.ts,观察 console 是否还报
4. 若 Option A 不解,需深挖 `@originjs/vite-plugin-federation` 1.4.x 是否需要升级到 `@module-federation/vite` 2.x(架构层决策,起新 backlog)

优先级 P3(不阻塞 user 但应 cleanup)。

## 相关

- 触发它的原 P2 backlog:`2026-05-29-bpm-process-management-empty-stub.md`(CLOSED — NOT REPRODUCIBLE)
- 可能间接关联:transient main 空 → smoke fail 假阳性
- 相关 canonical:AGENTS.md §15 / §19 / `docs/agent-rules/engineering-gotchas.md`(可加 federation × radix 分裂条目)
