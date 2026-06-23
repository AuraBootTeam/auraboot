---
type: plan-impl
status: closed
created: 2026-06-23
slug: night-mode-css-final-plan
distilled_to:
  - docs/system-reference/frontend-theme-and-cache-policy.md
---

# 夜晚模式 CSS 优化最终方案

日期: 2026-06-23
范围: `web-admin` 全局 Tailwind dark variant 与 core-designer 手写暗色样式收口

## 背景

当前产品层主题策略是 `light / dark / auto` 三态。`ThemeContext` 会根据用户选择在 `html` 上添加或移除 `.dark`: `light` 移除, `dark` 添加, `auto` 根据 `prefers-color-scheme` 决定是否添加。

OSS 验证中发现: 手动设置 `theme=dark` 后, `html.classList` 已包含 `.dark`, 但登录页主体、左右分栏和标题仍保持浅色。这说明问题不在主题状态同步, 而在 CSS dark variant 的触发条件。

## 根因

Tailwind CSS v4 默认 `dark:` variant 由 `prefers-color-scheme` 触发。项目虽然保留了 `tailwind.config.js` 中的 `darkMode: 'class'`, 但 v4 主路径是 CSS-first; 旧 JS 配置不会自动成为 dark variant 的来源。当前编译产物中可以看到 `dark:bg-*` 被放入 `@media (prefers-color-scheme: dark)`, 因此手动主题 class 无法驱动这些 utility。

Tailwind 官方手动主题方案是在入口 CSS 中覆盖 dark variant:

```css
@import "tailwindcss";
@custom-variant dark (&:where(.dark, .dark *));
```

这会让 `dark:*` utilities 跟随祖先节点上的 `.dark`, 与当前 `ThemeContext` 三态策略匹配。

同时, core-designer 的多个 CSS 文件仍有手写 `@media (prefers-color-scheme: dark)`, 会绕过用户手动 `light / dark` 选择。系统深色 + 用户手动浅色时, 这些区域可能仍被套上暗色样式。

## 目标

1. `dark:*` utilities 必须由 `html.dark` 控制, 不再只跟随系统媒体查询。
2. 用户手动 `light` 时, 即使系统为 dark, 应保持浅色。
3. 用户手动 `dark` 时, 即使系统为 light, 应保持深色。
4. 用户选择 `auto` 时, 由 `ThemeContext` 根据系统主题统一切换 `.dark`。
5. 设计器主容器、command 面板、拖拽预览等手写 CSS 不再绕过应用主题策略。

## 非目标

- 不改静态资源缓存策略。
- 不重做登录页视觉。
- 不全量 token 化所有颜色。
- 不迁移 `tailwind.config.js` 的插件体系; `tailwindcss-animate` / `tailwind-scrollbar` 是否进入 v4 编译链路另行处理。
- 不改变 iframe 内部主题隔离策略。已有 `.preview-frame-container.dark` 等局部预览主题 class 保留。

## 实施方案

1. 在 `web-admin/app/app.css` 的 Tailwind import 后添加:

   ```css
   @custom-variant dark (&:where(.dark, .dark *));
   ```

   不为了 dark mode 添加 `@config`, 避免把旧 JS 配置中其它行为一并引入。

2. 将 core-designer 手写系统暗色 media 收口到应用主题 class:
   - `command.css`
   - `drag.css`
   - `drag-preview.css`
   - `responsive.css`
   - `smart-slots.css`

   使用显式低特异性选择器 `:where(.dark) .selector`。不在这些单独 import 的 CSS 文件中使用 `@variant dark`, 因为它无法继承 `app.css` 中定义的 custom variant, 会继续按 Tailwind 默认 system media 编译。

3. 对根节点变量类规则单独处理为 `html.dark { ... }`。本次扫描的 designer dark media 块中未发现 `:root` 规则。

4. Portal 与 iframe 分开处理:
   - 当前文档 `body` 下的 portal 可以继承 `html.dark`。
   - iframe 是独立文档, 外层 `html.dark` 不穿透。已有 preview 容器/iframe 内主题 class 不强行替换。

## 回归测试

新增策略测试 `manual-dark-css-policy.test.ts`:

1. 断言 `app.css` 显式定义 class-based dark variant。
2. 断言设计器目标 CSS 文件不再包含 `@media (prefers-color-scheme: dark)`。
3. 断言不为 dark mode 添加 legacy `@config tailwind.config.js`。

## 验证矩阵

功能验证覆盖六种组合:

| 用户设置 | 系统 light | 系统 dark |
| -------- | ---------- | --------- |
| `light`  | 浅色       | 浅色      |
| `dark`   | 深色       | 深色      |
| `auto`   | 浅色       | 深色      |

自动/手动验证点:

- `/login` 的 `body`, page root, 左右 section, heading 实际 `background-color` / `color`。
- 设计器主容器、command/shortcut panel、拖拽预览相关规则不再由系统媒体查询单独触发。
- `pnpm vitest run app/framework/meta/runtime/theme/__tests__/manual-dark-css-policy.test.ts`。
- `pnpm build`。
- 构建产物抽样检查: `dark:bg-*` 应由 `.dark` selector 控制; 目标 designer CSS 不应残留 `prefers-color-scheme: dark`。
