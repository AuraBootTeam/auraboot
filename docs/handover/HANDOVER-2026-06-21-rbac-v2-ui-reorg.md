---
title: HANDOVER — RBAC permission UI v2 reorg (capability-primary / Feishu-style)
date: 2026-06-21
type: handover
status: in_progress
area: permission / rbac / web-admin
---

# HANDOVER — RBAC 权限页 v2 IA 改造(能力为主 / 飞书风)

接手即可继续。基础(后端安全 save + i18n + 设计 mockup)已完成并提交，**余下是大块前端 IA 重排 + 配套后端**。

## 0. 目标 + owner 已拍板的决策

把 `/enterprise/permissions`（`PermissionManagement`）从"三套并排 tab 的矩阵式"改成 **v2 飞书风**：
- **① 业务能力**（checkbox 清单，业务语言，按 group 折叠）= **默认且主体**。
- **② 数据范围**（全公司/本部门及下属/本部门/仅本人）= **顶部独立蓝条 + "修改范围"抽屉**，从矩阵格子里拉出来，**不混进能力清单**。
- **③ 高级·原子动作逃生舱** = 旧 `PermissionMatrix` **降级为默认折叠**的组（码 + 数据范围 + **来源/覆盖**列），给审计/破例 ~5% 用户，默认不展开。**保留，不删**。
- **退役**平铺"权限分配"顶 tab（纯开关、无业务语言、无 scope = 冗余）。

三维正交，别混编：**能力（能做什么）/ 数据范围（对谁的数据）/ 字段脱敏（sensitive 能力 🔒）**。

## 1. 已完成（committed，单元验证）

worktree `/Users/ghj/work/rbac-ux`，分支 `feat/rbac-ux-and-tests`，commit **`611687cd3`**（已 push）。

- **后端安全 save（v2 硬前提）** `CapabilityResolver.capabilityCodeMap()` + `CapabilityViewServiceImpl.applyCapabilitySelection()`：
  - 能力 universe = 声明式 + **约定派生**（`module.resource` → 该资源的 `module.resource.action` 码）→ 能力清单成为唯一授权面后，**它渲染的每个能力都能真授予**。
  - complete-state 替换，但**只在角色"完整持有"的能力内 revoke** → 部分授权（如 matrix 给的 `*.read`）永不被能力 save 误删。
  - 单测 `CapabilityViewServiceImplTest` 5/5：约定派生可存 / 部分不 strip / 非能力码不动。
- **P0 i18n 泄漏全修**：`platform/src/main/resources/seed/i18n-base.json` 注册 21 个 `admin.permission.members.*` + `sidebar.noMenus`（之前全漏 raw key）。⚠️ **要 re-seed/bootstrap 才在 UI 生效**。
- **设计参考**（绝对路径）：
  - v2 高级逃生舱 mockup（重点看这张）：`/Users/ghj/work/rbac-ux/docs/superpowers/specs/2026-06-21-permission-v2-advanced-escape-hatch-mockup.html`
  - v2 飞书原稿：`docs/superpowers/specs/2026-05-28-permission-module-mockup-v2-feishu.html`
  - 设计稿（§4 三维 / §5 三层 IA）：`docs/superpowers/specs/2026-06-21-permission-v2-capability-ux-design.md`
  - UX review（截图验证的发现）：`/Users/ghj/work/rbac-ux/docs/backlog/2026-06-21-rbac-ux-review.md`
  - 上轮 gate-gap 发现：`docs/backlog/2026-06-21-permission-v2-capability-ui-golden-findings.md`

## 2. 余下工作（前端 IA 重排 + 配套后端）

文件都在 `web-admin/app/routes/enterprise/permission/`：

1. **后端：业务标签**——能力清单现在对**平台自带权限**（billing/ai/iot/sys/automation…无 capabilities.json）显示 raw 英文码（`license`/`billing`）。补一份**平台 capabilities.json**（声明式业务标签 + tier 预设），或让约定派生用权限的 `name:zh-CN`。否则飞书风主界面全是 raw 码（UX review P1）。
2. **后端：高级视图"来源/覆盖"数据**——③ 高级表要按原子码标"被哪个能力覆盖（绿）vs 未覆盖·破例（黄）"。`CapabilityResolver` 已有 coveredCodes，需新增一个端点/扩展返回 per-code → capability 映射。
3. **`PermissionManagement.tsx` IA 重排**：顶层 tab 去掉 `assignments`（退役 `AssignmentTab`）；右栏默认 `capabilities`；把 `PermissionMatrixTab`（权限列表）折叠进能力编辑器作 ③ 高级，不再是独立 tab。
4. **`CapabilityRoleEditor.tsx` 加 ③ 高级·原子动作 section**：照 mockup（搜索 + 只看已授予/未覆盖 + 码/名称/数据范围下拉/来源列）。复用 `PermissionMatrix` 的 scope 逻辑（`updateScope`）。
5. **数据范围 → 顶部蓝条 + 抽屉**：把现 matrix 格子里的 `ALL/T/D/S/N` scope 拉成顶部"管理范围: X [修改范围→]" + radio 抽屉（5 档见 `PermissionMatrix.tsx` SCOPE_TYPES）。
6. **成员组件**（`RoleMemberTab` / `AddMemberDialog`）：i18n 已修，但**组件用 `t(k)||fallback` 是 bug**（i18n 找不到返回 key 本身=truthy，fallback 失效）——建议改 `t(k, undefined, fallback)`；加成员对话框 org 树空态 + 接 `org-management` 插件验证。
7. **golden 复验** + RBAC E2E。

## 3. 关键坑 / context（必读）

- **stack 服务 canonical 不服务 worktree**：golden 栈（`scripts/oss-golden-stack.sh`）的 Vite 跑 **canonical** `web-admin`。要验 worktree 前端改动，需把 Vite 的 cwd 指到 worktree（或另起）。上轮在进程重启上踩过坑（孤儿进程占端口），**重启只按 state dir 的 pid + 本 slot 端口，别 pkill 误伤并发会话**。
- **`@auraboot/track` 没 link**（#966 遗留）→ canonical web-admin 全 admin 路由 SSR 500。我留了 symlink `web-admin/node_modules/@auraboot/track -> packages/track`（临时）。**正解 = `pnpm install` 重 link + 提交更新的 `pnpm-lock.yaml`**（lockfile 现缺 @auraboot/track，见 UX review P0）。
- **i18n 改了要 re-seed**：i18n-base.json 是 bootstrap 期种到 DB；fresh stack / 重 bootstrap 才生效。
- **约定派生 save 语义**：complete-state，编辑器必须发**完整选择**（`CapabilityRoleEditor` 已从 granted 种 selected，OK）；raw partial PUT 会 strip（契约如此，别在前端发 partial）。上轮我 raw API 发 partial 误删过 tenant_admin —— 别重蹈。
- **栈清理**：本会话的 `rbac-ux-review`(slot 82) 我已 destroy。下次自己 `oss-golden-stack.sh up <name> --slot N`。

## 4. 验证方法

- 后端：`./gradlew :test --tests CapabilityViewServiceImplTest` 等单测；约定派生 save 端到端可用 `CapabilityLifecycleEnforcementIT` 模式 + 干净 API round-trip（fresh role：GET-before 未授 → PUT 约定派生能力 → **独立** GET-after 已授）。
- 前端：组件 vitest/RTL（jsdom，不需栈）+ 最后 golden 截图复验（点开 ① 能力 / 展开 ③ 高级 / ② 数据范围抽屉 / 成员无 raw key）。

## 5. 起点指针

- worktree `/Users/ghj/work/rbac-ux` 分支 `feat/rbac-ux-and-tests` @ `611687cd3`。
- 先打开 mockup（§1）对照，再按 §2 顺序做（建议 1→2 后端先行，再 3→6 前端，最后 7 验收）。
