---
type: system-reference
status: active
created: 2026-06-24
slug: frontend-theme-and-cache-policy
relates_to:
  - docs/plans/2026-06/2026-06-23-night-mode-css-final-plan.md
---

# Frontend Theme and Static Asset Cache Policy

本文固化本轮夜晚模式 CSS 与浏览器静态资源缓存验证经验，作为 `web-admin` 后续主题与 CSS 变更的系统参考。

## 主题状态源

前端主题只有一个状态源：`ThemeContext` 读取 `localStorage.theme`，支持 `light`、`dark`、`auto` 三态。

- `light`：移除 `html.dark`，强制浅色。
- `dark`：添加 `html.dark`，强制深色。
- `auto`：根据 `prefers-color-scheme` 决定是否添加 `html.dark`。

因此 CSS 的手动主题入口必须跟随 `html.dark`。系统媒体查询只能由 `ThemeContext` 在 `auto` 模式下消费，业务 CSS 不应各自读取 `prefers-color-scheme`。

## Tailwind v4 Dark Variant

Tailwind v4 是 CSS-first 路线，项目里旧的 `tailwind.config.js` 不会自动成为 `dark:` variant 的来源。若不显式覆盖，`dark:*` utility 可能被编译为 `@media (prefers-color-scheme: dark)`，导致用户手动选择 `light / dark` 时样式不跟随应用状态。

入口 CSS 必须保留：

```css
@import 'tailwindcss';
@custom-variant dark (&:where(.dark, .dark *));
```

不要仅为了 dark mode 引入整个旧配置：

```css
@config "../../tailwind.config.js";
```

只有当颜色、插件或其它配置确实需要进入 v4 编译链路时，才单独评估 `@config` 的影响面。

## 手写 CSS 规则

手写 CSS 不要直接写：

```css
@media (prefers-color-scheme: dark) {
  .panel {
    background: #111827;
  }
}
```

普通组件选择器优先写成低特异性 class variant：

```css
:where(.dark) .panel {
  background: #111827;
}
```

根节点变量规则必须直接挂到 `html.dark`，不能写成 `.dark :root`：

```css
html.dark {
  --panel-bg: #111827;
}
```

设计器、拖拽预览、命令面板等独立 CSS 文件同样遵守这一规则。不要假设 `app.css` 中的 Tailwind `@custom-variant` 会影响单独 import 的普通 CSS 文件。

## Light 模式影响判定

这次修复对 light 模式的影响属于纠偏，不是新主题行为。修复后：

- 系统深色 + 用户手动 `light` 时，页面必须保持浅色。
- 系统浅色 + 用户手动 `dark` 时，页面必须保持深色。
- `auto` 仍由 `ThemeContext` 统一读取系统主题。

检查 light 模式时，不要把左侧导航、品牌色按钮、错误提示等设计上允许的深色/高对比元素误判为残留。硬条件应看 `html.dark`、核心 token、内容主面板和异常白底/黑底块。

## Portal 与 iframe

- 当前文档 `body` 下的 portal 仍在 `html.dark` 作用域内，通常无需特殊处理。
- iframe 是独立文档，外层 `html.dark` 不会穿透。iframe 内部主题或 `.preview-frame-container.dark` 这类预览局部 class 必须继续保留。

## 验收矩阵

主题 CSS 变更至少覆盖六种组合：

| 用户设置 | 系统 light | 系统 dark |
| -------- | ---------- | --------- |
| `light`  | 浅色       | 浅色      |
| `dark`   | 深色       | 深色      |
| `auto`   | 浅色       | 深色      |

自动化测试不只检查 `html.classList`。这次问题就是 class 正确但编译 CSS 错误，因此必须断言至少一个实际 CSS token 或背景色。

建议验证项：

- `web-admin/app/app.css` 包含 `@custom-variant dark (&:where(.dark, .dark *));`。
- 设计器目标 CSS 不再包含 `@media (prefers-color-scheme: dark)`。
- `light` 下 `--color-panel=#FFFFFF`，`--color-bg=#F7F7F8`。
- `dark` 下 `--color-panel=#1F2937`，`--color-bg=#111827`，主内容区白底残留为 0。
- 登录页、主列表页、设计器、弹窗或 command 面板、拖拽预览至少各断言一个实际样式。
- 随机页面截图复核时，若抽中页面本身 404 / schema not found，应替换为菜单中真实可访问页面；不要把错误页当主题通过样本。

## 静态资源缓存判读

BFF 对 Vite content-hashed 静态资源已设置长期缓存：

```http
Cache-Control: public, max-age=31536000, immutable
```

HTML / SPA shell 不应长期缓存，仍保持 `no-cache` 或 `no-store` 策略。

DevTools 中看到 CSS / JS 请求 `Status 200` 不一定代表没有缓存。若 Size / Transfer 显示 `memory cache`，说明浏览器已从内存缓存命中，同一 tab / 同一会话刷新通常不会重新传输资源正文。

ETag 的定位是“重新验证”：缓存过期或需要和服务端确认时，用 `If-None-Match` 换取 `304 Not Modified`，减少正文传输。它不能替代 content-hash + immutable，也不能让已经显示 `memory cache` 的同会话刷新再省一层带宽。

只有在以下情况才优先考虑 ETag / Last-Modified：

- 文件名不带 content hash，且又希望浏览器缓存。
- 资源内容可能变但 URL 不变。
- 需要跨会话、跨重启后重新验证而不是直接 immutable。

对 `/assets/*` 这类 content-hashed 文件，优先保持长期 immutable 缓存；问题排查重点应放在构建 hash、发布路径和 HTML shell 不缓存策略是否正确。

## 本轮固定结论

- 夜间 CSS 根因不是简单的“Tailwind 配置失效”，而是 Tailwind v4 默认 media variant，加上旧 JS 配置未自动进入 CSS-first 编译链路。
- `@custom-variant dark (&:where(.dark, .dark *));` 是最小边界修复。
- 手写 dark media query 不能机械替换；根节点规则用 `html.dark`，普通组件规则用 `:where(.dark)`。
- 视觉验收必须分 light / dark 两组看同一批页面，截图与 token 断言都要留证据。
