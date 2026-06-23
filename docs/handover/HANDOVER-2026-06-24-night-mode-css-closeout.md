---
type: handover
status: shipped
created: 2026-06-24
slug: night-mode-css-closeout
distilled_to:
  - docs/system-reference/frontend-theme-and-cache-policy.md
---

# Session Handover - 2026-06-24 01:09

## Session Summary

本轮从浏览器静态资源缓存判断切入，最终收束为 `web-admin` 夜晚模式 CSS 策略修复：Tailwind v4 dark variant 改为由 `html.dark` 控制，设计器手写 CSS 不再绕过应用主题状态，并补齐 OSS 真实环境 light / dark 截图验证。

## Tasks Completed

- [x] 梳理浏览器缓存判断：`memory cache` 命中不等同于未缓存；content-hashed `/assets/*` 优先长期 immutable，ETag 只用于重新验证场景。
- [x] 确认根因：Tailwind v4 默认让 `dark:` 跟随 `prefers-color-scheme`，旧 `tailwind.config.js` 的 `darkMode: 'class'` 未自动进入 CSS-first 编译链路。
- [x] 在 `web-admin/app/app.css` 添加 `@custom-variant dark (&:where(.dark, .dark *));`。
- [x] 将 core-designer 手写 dark media query 收口到 `.dark` / `html.dark`。
- [x] 将动态列表、SavedView 控件、BPM、DecisionOps、Meta badge 等明显白底区域切到主题 token 或补齐 `dark:*`。
- [x] 新增 `manual-dark-css-policy.test.ts` 固化 Tailwind v4 class-based dark policy。
- [x] 启动 OSS host-first 环境并登录验证，随机 10 个页面按 light / dark 两组截图复核。
- [x] 固化经验到 `docs/system-reference/frontend-theme-and-cache-policy.md`。

## Tasks In Progress

- [ ] PR 创建、admin merge、main pull 由本轮后续收口命令完成。

## Key Decisions

| Decision | Chosen Approach | Rationale | Alternatives Considered |
| --- | --- | --- | --- |
| Tailwind dark mode | 在 `app.css` 使用 `@custom-variant dark (&:where(.dark, .dark *));` | 最小边界修复，直接匹配 `ThemeContext` 的 `html.dark` 三态策略 | 为 dark mode 加 `@config tailwind.config.js`，影响面更大 |
| 手写 CSS | 普通选择器用 `:where(.dark)`，根节点变量用 `html.dark` | 避免系统 dark 绕过用户手动 light；避免 `.dark :root` 这类无效选择器 | 机械替换为 `.dark .foo`，会漏根节点规则 |
| iframe / portal | portal 继承当前文档主题，iframe 独立保留自身主题 class | 与浏览器隔离模型一致 | 强行用全局选择器覆盖 iframe，不可行 |
| 视觉验收 | 同一批页面分别截 light / dark contact sheet | 能同时发现 light 误伤和 dark 白底残留 | 只看 `html.classList`，无法覆盖本次编译 CSS 错误 |

## Files Changed

### Frontend

- `web-admin/app/app.css` - Tailwind v4 dark variant 改为 class-based，并保留主题 token。
- `web-admin/app/framework/meta/rendering/pages/list/*` - 列表、toolbar、sort popover、action group 切到主题 token / dark class。
- `web-admin/app/framework/smart/components/view/*` - SavedView / filter / row height 控件补齐 light/dark token。
- `web-admin/app/plugins/core-bpm/**` - BPM task/process/table/status 相关卡片、表格、通知面板补齐 dark 样式。
- `web-admin/app/plugins/core-designer/components/studio/workbench/styles/*.css` - 移除 `prefers-color-scheme: dark`，改为 `.dark` 策略。
- `web-admin/app/plugins/core-meta/pages/meta/models/modelListRenderers.tsx` - Meta 模型列表 badge / 状态样式补齐暗色。
- `web-admin/app/shared/components/SourceTypeBadge.tsx`、`web-admin/app/ui/common/ManagedBadge.tsx` - 共享 badge 样式补齐暗色。
- `web-admin/app/framework/meta/runtime/theme/__tests__/manual-dark-css-policy.test.ts` - 新增策略测试。

### Documentation

- `docs/plans/2026-06/2026-06-23-night-mode-css-final-plan.md` - 夜晚模式方案与验证矩阵。
- `docs/system-reference/frontend-theme-and-cache-policy.md` - 本轮稳定经验沉淀。
- `docs/handover/HANDOVER-2026-06-24-night-mode-css-closeout.md` - 本轮过程、运行态、复盘。
- `docs/README.md` - 增加 system reference 入口。

## Pitfalls & Workarounds

1. **Problem**: 初始容易把 `darkMode: 'class'` 当成仍生效的事实。
   - **Root Cause**: Tailwind v4 CSS-first 路线下旧 JS 配置不会自动加载。
   - **Solution**: 在 CSS 入口使用 `@custom-variant dark`。
   - **Prevention**: 改 Tailwind v4 主题前先检查编译后 `dark:*` 是 media 还是 selector。

2. **Problem**: 手写 `@media (prefers-color-scheme: dark)` 会让用户手动 `light` 失效。
   - **Root Cause**: CSS 自己消费系统主题，绕开 `ThemeContext`。
   - **Solution**: 普通规则改 `:where(.dark)`，根节点规则改 `html.dark`。
   - **Prevention**: 新增策略测试扫描 designer CSS 中的 dark media query。

3. **Problem**: 第一次 10 页面抽样抽到 `/p/domain_config`，实际是 schema not found。
   - **Root Cause**: 凭历史/猜测 URL 抽样，没有从当前菜单取真实路由。
   - **Solution**: 用菜单真实路由 `/p/bpm_domain_config` 替换，重新生成 light / dark contact sheet。
   - **Prevention**: 视觉抽样先从当前登录菜单或路由 registry 取候选，错误页不算有效样本。

4. **Problem**: light 模式自动指标曾把左侧深色导航误判为坏残留。
   - **Root Cause**: light 验证条件过于机械，未区分设计允许的深色区域与主题残留。
   - **Solution**: light 硬条件改为 `html.dark=false`、核心 token、overlay/schema error；dark 才用白底残留计数。
   - **Prevention**: 指标和截图一起看，自动规则不要替代视觉判断。

## Lessons Learned

- 浏览器 DevTools 的 `Status 200` 要结合 Size/Transfer 看；显示 `memory cache` 时同会话刷新已命中缓存，ETag 不是优先优化点。
- 夜晚模式不是只修 `dark:` class，手写 CSS、token、portal/iframe、真实页面视觉都要一起校准。
- `html.classList` 正确不代表 CSS 编译正确，至少要断言实际 `background-color` 或 CSS token。
- light / dark 必须用同一批页面截图对比，否则容易只修 dark 白底，却漏 light 被系统 dark 误伤。

## 反思与经验固化 (Reflection & Codify)

### 本会话弯路 / 返工 / 翻车

1. **根因表述一开始不够精确** - 代价：方案评审多一轮澄清 - 本可如何更早避免：先查 Tailwind v4 编译语义和旧配置加载规则，再写根因 - 根因：`[B 输入信息 / D 验证]`
2. **视觉修复第一轮仍有明显“很丑”的区域** - 代价：需要二次扫 BPM、列表控件、badge 等白底面 - 本可如何更早避免：先做全页面 contact sheet，而不是只看登录页和少量局部 - 根因：`[D 验证]`
3. **随机抽样包含无效路由** - 代价：重新替换页面并重跑截图 - 本可如何更早避免：从登录后菜单 DOM 提取候选页面 - 根因：`[B 输入信息 / D 验证]`
4. **light 自动判定规则误报** - 代价：修正脚本判定与重新生成拼图 - 本可如何更早避免：先定义 light/dark 分别的硬条件，不把深色导航当异常 - 根因：`[A 门禁质量 / D 验证]`

### 为什么会发生(根因归类小结)

本轮主要问题不是实现阻塞，而是验证纪律和输入候选不够严：主题问题必须看编译产物、真实 token、真实菜单路由和截图，而不能只看 class 或凭 URL 经验抽样。

### 应该有哪些改进

- 主题 CSS 变更默认先查 `app.css`、`ThemeContext`、编译后 selector，再写根因。
- 视觉类修复早期就生成 contact sheet，不等用户指出“不好看”后再扩大扫描面。
- 随机页面验证候选从当前菜单/registry 获取；404/schema not found 页面不纳入通过样本。
- light 和 dark 使用不同断言：light 查手动浅色不被系统 dark 污染，dark 查白底残留和可读性。

### 已固化 / 待固化(更新文档)

- [x] 已写入 `docs/system-reference/frontend-theme-and-cache-policy.md`：Tailwind v4 dark variant、手写 CSS、light/dark 验收、静态资源缓存判读。
- [x] 已写入 `docs/plans/2026-06/2026-06-23-night-mode-css-final-plan.md`：方案、根因、实施路径、验证矩阵。
- [x] 已写入 `docs/handover/HANDOVER-2026-06-24-night-mode-css-closeout.md`：本轮过程、弯路、运行态。

## 运行态快照 (Operational State)

### 分支 / Worktree / PR

- **当前分支**: `codex/night-mode-css-strategy`，base `main`，ahead/behind `0/0`（未提交前）。
- **Worktree**: `/Users/ghj/work/auraboot/.worktrees/oss-browser-night-verify`。
- **其它 worktree**: 当前机器有多条并发 worktree，见 `git worktree list`；本轮只操作 `oss-browser-night-verify`。
- **本会话关键 commit**: 待提交后产生。
- **PR**: 待创建。
- **未提交改动**: 24 个前端文件 + 方案文档 + 策略测试 + 本 handover/system reference。

### Runtime / 端口(host-first slot 模型,零 docker)

- **Runtime**: `oss-browser-night-view-88`，slot `88`，env `/Users/ghj/work/auraboot/.workspace/env/oss-browser-night-view-88.env`。
- **端口**: backend `6488`，web `5188`，BFF `6188`。
- **命名空间**: Postgres DB `auraboot_88`，Redis prefix `aura:auraboot:88:`，Kafka prefix `auraboot.88.`。
- **依赖的常驻 broker**: PostgreSQL / Redis / Kafka。
- **当前在跑的服务**: `java` 监听 `6488`，`node` 监听 `5188` 和 `6188`。
- **接手者起栈命令**: `./scripts/oss-golden-stack.sh up oss-browser-night-view-88 --slot 88 --ttl 4h`。
- **停止命令**: `./scripts/oss-golden-stack.sh down oss-browser-night-view-88`。

### Database / Seed 状态

- 运行态完成最小 bootstrap，可用账号 `admin@auraboot.com / Test2026x` 登录。
- 本轮未做 DB migration，未要求 reset-db。

## Verification

- `git diff --check`：通过。
- Targeted Vitest：`11 files passed, 88 tests passed`。
- `pnpm build`：通过。
- OSS 视觉复核：`/tmp/auraboot-light-dark-random-10-v2`，light 10/10 ok，dark 10/10 ok。
- 控制台剩余噪音：登录前 401 与既有 i18n missing key warning；无主题相关错误。

## Next Steps

1. 提交当前分支并创建 PR。
2. Admin merge PR 到 `main`。
3. 更新本地 canonical `main` 与当前 worktree 的 remote refs。
4. 按需停止 `oss-browser-night-view-88`，避免长时间占用端口。
