# `<Popover>` Invalid hook call → cascades via RenderErrorBoundary

**Status**: OPEN (P3 — non-blocking)
**Discovered**: 2026-05-29 by subagent (a441e204c3316baba) during P2 diagnostic stack实测
**Filed**: 2026-05-29

## 症状

在 `/p/bpm_process_management` 真浏览器渲染时,console 报 `<Popover>` 组件 invalid hook call,被 `RenderErrorBoundary` 兜住。**不导致页面空白**(其它组件正常渲染,有数据有交互),但属于 React tree 内的 silent error。

## Stack trace

```
useScope @radix-ui_react-popover.js
  → useMemo (chunk-EKPZ7L7G)
  → RenderErrorBoundary catch
```

## 可能根因

典型 Vite optimizeDeps 中 React 实例分裂场景之一:
1. **多 React 副本**:react/react-dom 在 node_modules 中存在多副本(可能 plugin / SDK 自己 bundle)
2. **dedupe 缺失**:vite.config.ts 没 dedupe React → optimizeDeps 给不同 chunk 不同实例
3. **view-selector Popover 自身**:可能 view-selector Popover 内部 hook 调用模式不规范(e.g., 在条件分支内调 hook)

## 诊断路径(任一 owner)

1. `pnpm list react react-dom` 看是否多版本
2. `vite.config.ts` 检查 `resolve.dedupe`(应含 `['react', 'react-dom']`)
3. grep `view-selector` 组件,找 Popover 调用点,看是否在条件 / loop / callback 内
4. 启 dev mode 跑 `/p/bpm_process_management`,真 console 看完整 stack + RenderErrorBoundary 兜住的报错

## 影响

- 不阻塞 user-facing 功能(其它渲染正常)
- React tree 内 silent error 长期看是技术债 — 真有 invalid hook 会让无关 hook 也乱序
- 可能在某些极端 timing 下确实让某子树空白(可能解释了 P2 backlog 的"transient main 空"现象)

## Owner 建议

web-admin / 前端框架 owner。优先级 P3(不阻塞 user 但应 cleanup)。

## 相关

- 触发它的原 P2 backlog:`2026-05-29-bpm-process-management-empty-stub.md` (CLOSED — NOT REPRODUCIBLE)
- 可能间接关联:transient main 空 → smoke fail 假阳性
