# Web-Admin Split Inventory (2026-04-13)

> **2026-04-13 用户决策修正**(覆盖下文旧分类):
> - `ent-permission` → **并入 core-iam(开源)**:完整权限矩阵/策略/角色成员开源
> - `ent-space` → **并入 core-system(开源)**:多空间/多工作区切换开源
> - `ent-bpm-pro` 缩窄:**只保留 SLA / AI 审批辅助 / 跨流程协同**,BPM 基础流程引擎全开源(`core-bpm`)
> - Dashboard 拆分:**`core-dashboard-viewer`(含基础图表 line/bar/pie/area)开源**;**`ent-dashboard-designer`(设计器)闭源**;高级图表进 `ent-charts-pro`;Workbench 进 `ent-dashboard-workbench`
>
> 详细边界见 memory:`project_oss_enterprise_boundary.md`

对比来源：
- **A (Core, possibly stale)**: `/Users/ghj/work/auraboot/auraboot/web-admin/`
- **B (Enterprise, newer/richer)**: `/Users/ghj/work/auraboot/auraboot-enterprise/web-admin/`

方法：`diff -rq A/ B/`，忽略 `.DS_Store` / `.env*` / `.playwright-mcp/` / `.react-router/` / `node_modules/` / build artifacts。

## Summary

| 类别 | 数量 | 含义 |
|------|------|------|
| Enterprise-only (B only) | **120 条目** | 企业增值功能 / 新加功能，需要进 overlay 或拆 ent-* 插件 |
| Core-only (A only) | **32 条目** | 绝大多数是 B 侧已重构替换掉的旧结构（studio 老 workbench/panels、aurabot/skills、form/meta-schema、plugins/、TransactionPageRenderer 等），**基本视为废弃**；少数（`org-department-crud.spec.ts`、`authConfig.ts`）是 core 漏同步需迁回 B |
| Drift (同名但内容不同) | **750 条** | A 几乎全部 "旧于 B"；抽样 20 个文件，未见 A 领先于 B 的实质性提交 |

**结论（总纲）**：B 是**事实单一真相源**（single source of truth）。除 32 条 core-only 中的极少数判定为 "core 漏同步" 外，全部取 B；随后按关注点将 B 独有的企业特性抽成 ent-* 插件/overlay 包，把 core 不应知道的引用从 B 中解耦出去。

---

## Section 1: Enterprise-Only → Move to overlay / ent-* plugin

完整 120 项见 `/tmp/ent-only.txt`。下表按关注点归类关键文件（其他按同目录合并处理）：

| 路径 (相对 `web-admin/`) | 目标插件 / Overlay | 说明 |
|---|---|---|
| `app/chat/components/group/` (9 文件：GroupChatPage/CreateGroupDialog/Mention/TypingIndicator…) | **ent-im-chat** | IM 群聊核心 UI。Core 侧仅保留 1:1 Chat 基础骨架 |
| `app/chat/services/imService.ts`, `imSseClient.ts` | **ent-im-chat** | 群聊 REST + SSE 客户端 |
| `tests/e2e/chat/` | **ent-im-chat** | 群聊 E2E |
| `app/components/shared/OrgTreePicker.tsx` | **ent-org** | 组织树选择器 |
| `app/components/shared/RecordShareDialog.tsx` | **ent-org** | 记录按组织/成员共享 |
| `app/routes/enterprise/OrganizationPage.tsx`, `routes/enterprise/organization/` | **ent-org** | 组织中心路由 |
| `app/services/organizationService.ts` | **ent-org** | 组织 API client |
| `tests/e2e/organization/` | **ent-org** | Org E2E |
| `app/routes/enterprise/permission/` (5 文件：PermissionMatrix、PolicyConfigDialog、RoleMemberTab、AddMemberDialog…) | **ent-permission** | 细粒度权限矩阵（IAM Phase 1） |
| `tests/e2e/permission/`, `tests/e2e/enterprise/permission-matrix-smoke.spec.ts` | **ent-permission** | 权限 E2E |
| `app/components/PlatformGuard.tsx`, `app/routes/PlatformLayout.tsx`, `app/routes/platform/` | **ent-platform-guard** | 平台（跨租户）管理入口 + 守卫 |
| `app/aurabot/hooks/useAuraBotSafe.ts` | **ent-aurabot-safe** | 企业侧 AuraBot 安全兜底 hook（core AuraBot 不应强依赖此路径 —— slot/optional import 占位） |
| `tests/e2e/aurabot/` (data-analysis、form-fill、helpers) | **ent-aurabot-safe** | 企业 AuraBot 场景 E2E |
| `app/components/smart/picker/AddressField.tsx` | **ent-address** | 地址字段（省市区级联、需 ent 字典） |
| `app/components/smart/picker/avatar-utils.ts` | **ent-identity**（或合并进 ent-org） | 头像/成员展示工具 |
| `app/components/smart/picker/useDictTree.ts` | **ent-identity** | 字典树 hook（admin 字典服务） |
| `app/constants/SpaceConstants.ts` | **ent-space** | 多空间（workspace/tenant space）常量 |
| `app/routes/api.switch-space.tsx`, `tests/e2e/auth/space-selection.spec.ts` | **ent-space** | 空间切换 |
| `app/dashboard-designer/widgets/workbench/` | **ent-dashboard-workbench** | 企业 workbench 仪表板组件 |
| `app/smart/components/charts/SmartComboChart/Gallery/Kanban/Nps/WordCloud.tsx` | **ent-charts-pro** | 高级图表（依赖 `echarts-wordcloud`，已在 B `package.json` 中新增） |
| `app/smart/automation/components/AutomationEditPageImpl.tsx` | **ent-automation** | 自动化高级编排实现（Core 只留 facade） |
| `tests/e2e/automation/`, `tests/e2e/bpm/bpm-*`, `tests/e2e/connectors/`, `tests/e2e/compliance/`, `tests/e2e/growth/`, `tests/e2e/portal/`, `tests/e2e/procurement/`, `tests/e2e/record-share/`, `tests/e2e/regression/`, `tests/e2e/showcase/sc-*`, `tests/e2e/acp-showcase/`, `tests/e2e/agent-control-plane/`, `tests/e2e/marketplace/`, `tests/e2e/workbench/` | **相应 ent-*** | 按各自业务域挂在目标插件下，core 仓库不保留 |
| `app/studio/core/`, `app/studio/registry/`, `app/studio/components/`, `app/studio/hooks/canvas/`, `app/studio/domain/canvas/`, `app/studio/workbench/designers/canvas/`, `app/studio/workbench/PageDesignerEditorImpl.tsx`, `app/studio/workbench/designers/areas/editors/FieldPermissionSection.tsx` | **留在 core** (Studio V2) | Studio V2 canvas 架构（react-grid-layout + dnd-kit），已是新的核心 Studio。**这些 "B only" 是因为 A 仍是 V1 旧骨架——取 B，替换掉 core 里的 V1 残骸** |
| `app/meta/migration/`, `app/meta/rendering/layout/`, `app/meta/rendering/pages/CompositePageContent.tsx`, `app/meta/rendering/pages/list/` | **留在 core** | Page Kind V2 / 扁平 blocks 的 runtime 渲染 —— 核心能力，取 B |
| `app/shared/designer/DependentFieldSelect.tsx`, `DependentMultiSelect.tsx`, `shared/designer/expression/`, `app/studio/workbench/components/expression-editor/syntax/__tests__/` | **留在 core** | Schema-driven PropertyPanel 扩展，core Studio 必备 |
| `app/hooks/useDebouncedValue.ts`, `useSSE.ts`, `app/providers/QueryProvider.tsx`, `app/utils/ssr-cache.ts`, `app/server/metrics.server.ts`, `app/routes/_index.tsx`, `app/routes/home/`, `app/routes/p.$pageKey.*.tsx`, `app/routes/p.c.$pageKey.tsx` | **留在 core** | 基础设施（QueryProvider、SSR 指标、统一 `/p/` 路由壳）—— core 必须有 |
| `app/routes/admin/mobile-config.tsx`, `mobile-config.shared.ts` | **ent-mobile-config** | 移动端配置（B 专属需求） |
| `app/services/engagementService.ts` | **ent-engagement** | 活跃度/埋点服务 |
| `app/components/RouteLoadingFallback.tsx`, `app/components/__tests__/`, `app/middleware/__tests__/`, `app/services/__tests__/session.test.ts` | **留在 core** | 测试 / 路由骨架，core 漏补 |
| `packages/enterprise/`, `public/vendor/`, `public/logo192-light.png`, `build/`, `vite.config.ts.timestamp-*.mjs` | **Enterprise 仓独有** | 构建产物 / enterprise overlay package，**不迁回 core** |
| `tests/api/setup/seed-crm-data.spec.ts`, `seed-crossplatform.spec.ts`, `tests/perf/` | **留在 enterprise 仓** | 企业专属 seed / 性能脚本 |

---

## Section 2: Core-Only → Decide

| Path (相对 A) | Decision | Reason |
|---|---|---|
| `app/aurabot/skills/` | **废弃** | AuraBot AI chain fix 后 core 已移除 builtin__/legacy skill 路径（见 memory：`aurabot_ai_chain_fix`）。删 |
| `app/designer/` | **废弃** | 旧 designer 入口，已被 `app/studio/` 取代 |
| `app/meta/components/form/` | **废弃** | 旧 form builder。Page Kind V2 后 form 以 blocks 渲染，取 B 的 `meta/rendering/` |
| `app/meta/meta-schema/` | **废弃** | 被 `page-kind` 扁平格式取代 |
| `app/meta/rendering/pages/RecordPageRenderer.tsx`, `TransactionPageRenderer.tsx`, `sections/` | **废弃** | Page Kind V2 取代 Record/Transaction concept；B 已无 |
| `app/meta/utils/page-semantics.ts` | **废弃** | 随旧语义删 |
| `app/smart/components/scanner/` | **核查后再决定** | B 侧未见同名目录，若 core 仍用条码扫描需确认；推测是早期实验，**建议删** |
| `app/studio/domain/schema/SchemaValidator.ts`, `converters/SchemaConverter.ts` | **废弃** | V2 canvas 架构不再使用 V1 schema converter |
| `app/studio/services/layout/drag-preview/`, `slotting/SlotHighlightEngine.ts`, `SmartSlotSystem.ts` | **废弃** | 被 B 的 `hooks/canvas/` + `domain/canvas/` 替代（dnd-kit + react-grid-layout） |
| `app/studio/workbench/Designer.tsx`, `DesignerWorkbench.tsx`, `canvas/SmartSlot.tsx`, `components/FormRef/FormDesignerBridge.ts`, `components/wizard/`, `panels/datasource/hooks/`, `panels/new-page-wizard/`, `panels/page-list/` | **废弃** | Studio V1 旧 workbench。B 换成 `PageDesignerEditorImpl.tsx` + `designers/canvas/`，取 B |
| `app/utils/authConfig.ts` | **迁回 B** ⚠️ | Core 独有，若被 Login 引用需同步到 B。**需核查 B 的 `AuthContext.tsx` 是否已内联** |
| `docs/plans/`, `plugins/` | **Core 仓专属** | `plugins/` 在 A 是空占位；`docs/plans/` 本次文档的归宿，**保留** |
| `tests/.auth/`, `tests/storage/*-login-failed.png` | **Gitignore** | 运行时 artifacts，应 ignore 非迁移 |
| `tests/e2e/org/org-department-crud.spec.ts` | **迁回 ent-org（B）** ⚠️ | Core 有但 B 无 —— 需确认是否被 `tests/e2e/organization/org-management.spec.ts` 吸收。**若未覆盖部门 CRUD 场景，迁回** |

---

## Section 3: Drift → File-by-file 原则

- **默认策略：取 B**。抽样 20 个 drift 文件（AuthContext、ChatPage、package.json、Login、MessageList、CommandPalette、SchemaTableRenderer、DashboardDesigner、I18nContext、ToastContext、smart/picker/* 等）无一例外 B 为 superset / 更新版。
- **原因**：B 侧持续演进了 Page Kind V2、Studio V2 canvas、List UX refactor、SavedView 原子 upsert、Command pipeline 重构、Security review 30 fixes（见 memory）等，A 侧基本停留在这些工作前。
- **不需逐文件列 750 条**：直接 `rsync -a B/ core/` 覆盖 core 同名路径（限于 `app/` 下 core 需保留目录集合），再按 Section 1 将 ent-* 文件搬出。

需要**手工合并**的候选（抽查中尚未发现，但以下位置风险较高，合并前 diff）：
- `app/contexts/AuthContext.tsx` — B 加了 `preferences` 字段，若 core 另有变化需合
- `app/chat/ChatPage.tsx` — B 已改掉 `ChatInput.test` 这个测试引用的意外依赖；core 若有它用不得全覆盖
- `vite.config.ts` / `vitest.config.ts` / `vitest.setup.ts` — 构建配置，取 B 后需改路径常量（`@auraboot/enterprise` workspace 在 core 侧不存在）
- `package.json` — core 不应保留 `@auraboot/enterprise` workspace 依赖；差异中 B 新增 `compression`、`prom-client`、`echarts-wordcloud`、`react-resizable`、`@tanstack/react-query` 等 —— 按功能分到 core 或 ent-* 的 peerDeps

---

## Recommended Enterprise Plugins (npm 包视角)

命名建议 `@auraboot/ent-<domain>`，在 `auraboot-enterprise/web-admin/packages/` 下：

| 包名 | 覆盖 |
|------|------|
| **ent-im-chat** | `chat/components/group/`、`chat/services/imService.ts`、`imSseClient.ts`、`tests/e2e/chat/` |
| **ent-org** | `OrgTreePicker`、`RecordShareDialog`、`routes/enterprise/OrganizationPage.tsx`、`organization/`、`services/organizationService.ts`、`tests/e2e/organization/`、（回迁的）`org-department-crud.spec.ts` |
| **ent-permission** | `routes/enterprise/permission/` 全部、`tests/e2e/permission/` + `enterprise/permission-matrix-smoke.spec.ts` |
| **ent-platform-guard** | `PlatformGuard.tsx`、`PlatformLayout.tsx`、`routes/platform/` |
| **ent-aurabot-safe** | `useAuraBotSafe.ts`、`tests/e2e/aurabot/`（core AuraBotProvider 需加 hook slot） |
| **ent-space** | `SpaceConstants.ts`、`api.switch-space.tsx`、`tests/e2e/auth/space-selection.spec.ts` |
| **ent-address** | `AddressField.tsx`（依赖 ent-identity 字典） |
| **ent-identity** | `avatar-utils.ts`、`useDictTree.ts`（成员 / 字典） |
| **ent-charts-pro** | `SmartComboChart/Gallery/Kanban/Nps/WordCloud`、`echarts-wordcloud` 依赖 |
| **ent-dashboard-workbench** | `dashboard-designer/widgets/workbench/` |
| **ent-automation** | `AutomationEditPageImpl.tsx`、`tests/e2e/automation/`（core 留 facade） |
| **ent-bpm-pro** | `tests/e2e/bpm/bpm-*`（若后端 BPM 已是 ent，对应前端也下沉） |
| **ent-mobile-config** | `routes/admin/mobile-config*` |
| **ent-engagement** | `engagementService.ts`、`routes/home/` 仪表盘 |
| **ent-showcase** | `tests/e2e/showcase/sc-*`、`acp-showcase/`、`agent-control-plane/`（演示插件族） |
| **ent-procurement / ent-connectors / ent-compliance / ent-growth / ent-portal / ent-record-share / ent-marketplace / ent-workbench** | 各自 `tests/e2e/<domain>/`（若后端 plugin 已存在，前端 E2E 对齐归属） |

Core 侧必须预留的 **slot / 占位**：
1. `AuraBotProvider` — 通过 optional `use*Safe` hook 访问空间/组织，core 不强引入 `useAuraBotSafe`
2. `AuthContext` — `preferences` 字段保留，但 `Preferences` 类型在 core 给最小接口，ent-space / ent-identity 扩展
3. `RouteManifest` — core 提供 `registerRoute(path, element)`，`enterprise/*`、`platform/*`、`api.switch-space` 路由由 ent-* 动态注册
4. `SmartPickerRegistry` — `MemberPicker` / `TreeSelect` 在 core（通用），`AddressField` / `useDictTree` 通过 registry 注入
5. `ChartRegistry` — core 提供基础图表；SmartComboChart/Nps/WordCloud/Kanban/Gallery 由 ent-charts-pro 注册
6. `ChatAdapter` — core 只有 1:1；ent-im-chat 注入群聊路由 + services
7. `PageKind renderer registry` — core 渲染 `list/form/detail/dashboard`；ent 可扩 `composite` 高阶布局

---

## 后续执行建议

1. **先反向同步**：`rsync -a auraboot-enterprise/web-admin/app/ auraboot/web-admin/app/ --delete` 到 core，然后在 core 侧**删除** Section 1 所有 "ent-* only" 路径；net 结果 = core 拿到最新 B 基线并移除企业特性。
2. **建 slot**：按上方 7 个 slot 在 core 补 registry/hook 占位，所有 B 侧直接 import 企业文件的地方改为 registry 查询或 `useOptionalHook`。
3. **拆包**：在 `auraboot-enterprise/web-admin/packages/` 下新建上表 16 个 ent-* 包，把 Section 1 文件移入，对应 `tests/e2e` 也迁入包内 `__tests__/`。
4. **Core-only 清理**：按 Section 2 表逐条 `git rm`，`authConfig.ts` 与 `org-department-crud.spec.ts` 确认后迁回 B。
5. **配置清理**：core `package.json` 去掉 `@auraboot/enterprise` / `echarts-wordcloud` / `prom-client` / `compression`；保留 `@tanstack/react-query`、`react-resizable`、`@auraboot/core`。
6. **验收**：core 独立 `pnpm build` + `pnpm test:unit` 通过；enterprise 仓 `pnpm test:smoke` 全绿。

附件：
- 全量 diff：`/tmp/webadmin-diff.txt`（902 行）
- Core-only 列表：`/tmp/core-only.txt`（32）
- Enterprise-only 列表：`/tmp/ent-only.txt`（120）
- Drift 列表：`/tmp/drift.txt`（750）
