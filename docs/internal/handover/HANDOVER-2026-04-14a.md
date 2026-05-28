# Session Handover — 2026-04-14

## Session Summary

OSS-focused cleanup session: removed 8 menu/page UX issues identified by user (重复菜单、孤儿功能、企业版残留)，unified `/marketplace` + `/system/plugins` into `/plugins` with 4 Tabs, deleted the zero-data ConsistencyRule feature, wired AI Modeling into AuraBot as a header button, and fixed plumbing issues along the way (page-manager i18n format, reset script killing concurrently wrapper). All code committed across two repos; push not yet done.

## Tasks Completed

- ✅ Investigated menu seed mechanism (OSS `default-bootstrap.json` vs plugin `menus.json`), wrote reference doc `docs/system-reference/reference/menu-seed-mechanism.md` (in enterprise repo)
- ✅ Removed 10 orphan menus + 2 permissions from `default-bootstrap.json`
- ✅ Deleted entire ConsistencyRule feature (backend package, frontend, table, tests, docs, `CONSISTENCY_CHECK` command stage)
- ✅ Unified `/marketplace` + `/system/plugins` → `/plugins` with 4 Tabs: 发现 / 行业方案 / 已安装 / 导入历史
- ✅ Hidden `/meta/ai-modeling` standalone menu, added entry button in AuraBot Panel header (Sparkles icon → `/meta/ai-modeling`)
- ✅ Fixed `page-manager/config/i18n.json` nested-object → flat-array format (was blocking plugin import)
- ✅ Fixed reset script to kill `concurrently` + `pnpm dev` wrappers (HMR stale-state issue)
- ✅ Recovered Solutions page (originally deleted by merge agent) as `/plugins?tab=solutions`
- ✅ Added `IconPuzzle` to `icon-resolver.tsx` whitelist
- ✅ Added `CompatibilityBadge` fallback in `InstalledTab.tsx` (`configs[status] ?? configs.compatible`)
- ✅ Unified plugin tabs permission to `plugin_management` (`platform.plugin.manage` wasn't seeded)
- ✅ Added `plugin.tab.solutions` i18n key to seed + DB
- ✅ Updated 10 E2E specs for new `/plugins` routes, smoke test passed (exit 0)
- ✅ Integrated AuraBot tooltip i18n (`aurabot.ai_modeling_entry`) + new E2E `tests/e2e/aurabot/ai-modeling-entry.spec.ts`
- ✅ Committed: 7 OSS commits + 4 enterprise commits

## Tasks In Progress / Pending User Action

- ⏸️ **Push to origin/main** — both repos ahead of origin (OSS +13, enterprise +5). User hasn't explicitly authorized push to main.
- ⏸️ **2 un-staged files in OSS** (NOT this session's work): `web-admin/app/routes/enterprise/PermissionManagement.tsx`, `web-admin/app/ui/smart/picker/useDictTree.ts` — user to decide.
- ⏸️ Full E2E (`pnpm test:full`) not run — only smoke did.
- ⏸️ Backend integration tests (`./gradlew test`) not re-run after consistency removal.

## Key Decisions

| Decision | Chosen Approach | Rationale | Alternatives |
|----------|----------------|-----------|-------------|
| Plugin management merge | Single `/plugins` with 4 Tabs (含 Solutions) | User心智统一；发现→安装→管理闭环 | 分 3 独立路由 |
| Old routes | No redirect | Dev phase, 破坏性变更允许 | 301 兼容 |
| OSS marketplace tab | 有权限显示，无则自动隐藏 | 同一代码两发行版自适应 | OSS 编译时剥离 |
| AI Modeling | Keep standalone page, add AuraBot header button as entry | 轻量，不改 Drawer 组件栈 | 完全内嵌 Drawer (Task 4 deep integration deferred) |
| ConsistencyRule | 整体删除 (后端+前端+表+stage) | 0 data, 集成进 PostExecutionPhase but未使用 | 保留接口 |
| Bootstrap cleanup scope | Only 10 明确孤儿 | 其余 37 需逐条评估 | 全部迁到插件 |
| icon-resolver | 手动加 IconPuzzle | 白名单机制约束，长期应动态 import | 动态 import (deferred) |

## Files Changed (Summary)

### OSS 仓库 auraboot/ (7 commits, ~80 files)

**Backend**
- `platform/src/main/java/.../consistency/` — 整包删 (entity/mapper/service/controller/dto/exception)
- `platform/src/main/java/.../meta/service/impl/pipeline/phases/PostExecutionPhase.java` — 删 consistency phase
- `platform/src/main/java/.../meta/service/impl/CommandExecutorImpl.java`
- `platform/src/main/java/.../application/web/handler/GlobalExceptionHandler.java`
- `platform/src/main/java/.../base/constant/CommandStage.java` + meta/constant 同名
- `platform/src/main/java/.../base/service/impl/CommandPipelineRegistry.java`
- `platform/src/main/resources/database/schema.sql` — 删 `ab_consistency_rule`

**Resources & Bootstrap**
- `platform/src/main/resources/tenant-templates/default-bootstrap.json` — 删 10 菜单 + 2 权限
- `platform/src/main/resources/seed/i18n-base.json` — 加 `plugin.tab.*` / `plugin.permission.denied` / `plugin.title` / `aurabot.ai_modeling_entry` / `plugin.tab.solutions`

**Plugins config**
- `plugins/platform-admin/config/menus.json` — `plugin_marketplace` → `plugin_management` (path=`/plugins`, icon=`IconPuzzle`)
- `plugins/platform-admin/config/permissions.json` — 同步重命名
- `plugins/page-manager/config/i18n.json` — 格式从嵌套对象转为 flat array

**Frontend**
- `web-admin/app/plugins/core-platform/pages/plugins/` — **新建**：`index.tsx` (4 Tab 主容器) + `components/{DiscoveryTab,InstalledTab,HistoryTab,SolutionsTab,PluginCard,InstallDialog,UpgradeDialog,CheckoutDialog}.tsx` + `$pluginId.tsx` + `solutions/$code.tsx`
- `web-admin/app/plugins/core-platform/resources.ts` — 新增 plugin-solution-detail / plugin-detail 路由，权限统一为 `plugin_management`
- `web-admin/app/plugins/core-platform/pages/KernelPluginsPage.tsx` — 文档链接更新
- `web-admin/app/plugins/core-aurabot/components-shell/AuraBotPanel.tsx` — 新增 Sparkles 按钮 + i18n
- `web-admin/app/plugins/core-meta/resources.ts` — `ai-modeling` 设 `menu: false`，删 `consistency-rules`
- `web-admin/app/framework/meta/rendering/pages/FormPageContent.tsx` — 删 ConsistencyViolationAlert 引用
- `web-admin/app/utils/icon-resolver.tsx` — 加 `IconPuzzle: Puzzle`
- `web-admin/app/constants/SpaceConstants.ts` / `routes/Header.tsx` / `public/robots.txt` — `/marketplace` → `/plugins`
- **删除**：`web-admin/app/routes/marketplace/` 整目录、`web-admin/app/plugins/core-platform/pages/system/` 整目录、`web-admin/app/shared/services/consistencyRuleService.ts`、`web-admin/app/ui/consistency/`、`web-admin/app/plugins/core-meta/pages/meta/consistency-rules/`

**Tests**
- `web-admin/tests/e2e/{marketplace,plugin,plugin-lifecycle,community,showcase}/*.spec.ts` — 10 文件，URL/选择器更新
- `web-admin/tests/fixtures/index.ts` — navigation timeout 3s (因 SSE 长连导致 networkidle 永不 fire)
- `web-admin/tests/e2e/aurabot/ai-modeling-entry.spec.ts` — 新建

**Docs & Scripts**
- `docs/core-concepts/commands.md` — 删 CONSISTENCY_CHECK 阶段
- `scripts/reset-and-init.sh` — 加 kill concurrently / pnpm dev

### Enterprise 仓库 auraboot-enterprise/ (4 commits)

- `packages/enterprise/route-manifest.ts` + `platform-route-manifest.ts` — `/marketplace*` → `/plugins*`
- `platform/platform-enterprise-core/src/main/java/.../marketplace/service/MarketplaceCheckoutService.java` — Stripe 回调 `/plugins`
- **删**：`docs/system-reference/subsystems/24-一致性规则DSL.md`
- **新建**：`docs/system-reference/reference/menu-seed-mechanism.md`
- 更新：`docs/system-reference/reference/{01,02}-*.md`、`docs/system-reference/core/{01,06,09}-*.md`、`docs/analysis/platform-swot-analysis.md`、`docs/system-reference/subsystems/38-CLI命令行工具.md`
- `scripts/reset-and-init.sh` — 调整插件导入顺序（platform-admin 从 OSS core 导）

## Pitfalls & Workarounds

1. **`/plugins?tab=installed` 运行时崩溃 "Cannot read properties of undefined (reading 'icon')"**
   - Root cause: `InstalledTab.tsx` 的 `CompatibilityBadge` 中 `configs[status]` 若 status 不在枚举内则为 undefined，然后 `.icon` 崩溃
   - Solution: `configs[status] ?? configs.compatible` 兜底
   - Prevention: 所有 `Record<K, V>` lookup 后立即走 `.foo` 的位置都应显式兜底，或改用 getter 函数

2. **`platform.plugin.manage` 权限在 DB 里不存在**
   - Root cause: 该权限 code 没在任何 `permissions.json` 声明，seed 不会创建
   - Solution: 统一改用已存在的 `plugin_management`（`plugins/platform-admin/config/permissions.json` 中声明）
   - Lesson: 前端写权限 code 前必须在 `permissions.json` 或 bootstrap `permissions` 数组中确认存在

3. **page-manager 插件导入 500 错误**
   - Root cause: `config/i18n.json` 格式错误 — 用的是 `{locale: {key: value}}` 嵌套对象，backend DTO 期望 flat array `[{key, zh-CN, en-US, source, refType}]`
   - Solution: Python 脚本转换格式
   - Prevention: 建立插件 manifest schema 校验工具（列在 next session 任务 #10）

4. **Vite HMR 热更新看似应用但浏览器仍报旧错**
   - Root cause: Reset 脚本只 kill `vite`/`react-router dev`/`bff.server`，但 `concurrently --restart-tries 20` 包装器存活，kill 后立刻重启 vite —— 新 vite 进程但浏览器仍连接旧 HMR channel
   - Solution: 补 `pkill -f "concurrently"` 和 `pkill -f "pnpm dev"` 到 reset 脚本
   - Already applied to OSS `scripts/reset-and-init.sh`

5. **菜单 seed 机制分裂**
   - Root cause: OSS `TenantBootstrapServiceImpl.createMenus()` 从 `default-bootstrap.json` 建菜单时不设 `plugin_pid`（空），而 `PluginImportServiceImpl` 则设；二者共存导致孤儿菜单 + 路由重复
   - Solution: 本次只清 10 条明显孤儿；长期需审计全部 47 条（next session Task 6）
   - 详见：`auraboot-enterprise/docs/system-reference/reference/menu-seed-mechanism.md`

6. **子 agent 误删 Solutions 页面**
   - Root cause: 合并 marketplace 时把 `routes/marketplace/` 整目录删了，连带 `solutions/` 子目录
   - Solution: 通过 `git show HEAD:<path>` 恢复，重建为 `/plugins?tab=solutions` 第 4 Tab
   - Lesson: 委托目录级删除时，prompt 要列明"保留/排除项"

7. **subagent 报告的文件路径可能错误**
   - Root cause: 一个 Explore agent 报告路径在 `.claude/worktrees/agent-xxx/` 下，实际文件在主仓库；误导后续命令
   - Solution: 对 agent 返回的路径做一次主仓库存在性校验
   - Lesson: Agent 报告要验证路径，不能盲信

## Lessons Learned

- **白名单式 icon-resolver 是隐形坑**：任何新图标字符串都要手动注册，插件作者完全不知道。列入 next session Task 7。
- **开发阶段破坏性变更是快刀**：本次多个功能（consistency、marketplace 旧路径）直接删，没有 deprecation / fallback，合起来省了大量适配代码。
- **i18n 格式不统一是插件生态隐患**：page-manager 用的是早期嵌套格式，新 DTO 是 flat array。其他插件可能也有混用。
- **E2E 冒烟 + tsc 足以保护合并**：3 个子 agent 批改 40+ 文件，最终 tsc 0 error + smoke 通过，说明 TypeScript+结构化测试是 AI 协作的刚需。
- **push 前要三仓状态核对**：工作区含 3 个独立 git 仓库（本次只改 2 个），提交/推送必须 cd 进对应目录。

## Current State

### Git Status

**auraboot/** (main, ahead 13 of origin):
```
 M web-admin/app/routes/enterprise/PermissionManagement.tsx     # NOT from this session
 M web-admin/app/ui/smart/picker/useDictTree.ts                 # NOT from this session
```

**auraboot-enterprise/** (main, ahead 5 of origin): clean working tree.

**auraboot-website/**: 未触碰。

### Running Services (session end time)

- Backend `com.auraboot.framework.application.MetaApplication` on `:6443` (started by reset script)
- Frontend `pnpm dev:full` on `:5173` (vite + bff via concurrently)
- PostgreSQL `localhost:5432` db=`aura_boot` user=`ghj`
- Admin: `admin@auraboot.com` / `Test2026x`

### Database State

- Reset 已跑（OSS 脚本 `auraboot/scripts/reset-and-init.sh`）
- `ab_consistency_rule` 表已从 schema.sql 删除
- `plugin.tab.solutions` i18n key 已插入 `ab_i18n_resource`（tenant_id=0, zh-CN+en-US）
- 菜单重复路由 0 条，目标 10 孤儿菜单已清

## Next Steps (Follow-ups for Future Sessions)

### 本地立即待办（不需新 session）

1. **Push 两仓库**到 `origin/main`（需用户明确授权）
2. **处理 2 个未提交文件**：`PermissionManagement.tsx` / `useDictTree.ts` 是否保留
3. **跑 full E2E**（`pnpm test:full`）
4. **跑后端集成测试**（`cd auraboot/platform && ./gradlew test`）

### 新 session 独立任务（用户已明确标为"新会话开始"）

5. **Bootstrap 中期治理**：审计 `default-bootstrap.json` 剩余 37 条菜单，逐条判断"平台级公共" vs "应迁移到插件"。参考 `auraboot-enterprise/docs/system-reference/reference/menu-seed-mechanism.md` §五 治理建议。

6. **icon-resolver 改动态 import**：当前 `auraboot/web-admin/app/utils/icon-resolver.tsx` 是显式白名单，新图标必须手动注册。改造为 `@tabler/icons-react` 或 `lucide-react` 的动态 resolver（e.g. `React.lazy` + 名称转换规则），消除隐性约束。

7. **启动自检：孤儿菜单 WARN**：在 platform 启动时扫 `ab_menu where plugin_pid IS NULL`，不在白名单则 log.warn。白名单可放 `platform/src/main/resources/seed/platform-menu-whitelist.json`。

8. **AuraBot AI Modeling 深度集成**：本次只做 header 按钮跳转（方案 A）；深度方案是改造 `auraboot/web-admin/app/plugins/core-meta/pages/meta/ai-modeling/index.tsx` 为 dual-mode 组件（独立页面 + Drawer 嵌入），AuraBot 内打开 Drawer 完成多步建模。

9. **page-manager i18n 格式校验工具**：写一个 CLI/gradle 任务扫全部 `plugins/*/config/i18n.json`，对照 `I18nDefinitionDTO` schema 校验；不合规直接 fail。

### 预存问题（非本次引入，需另立项）

10. **RAG 测试编译失败**：`QueryRewriteServiceTest` 和 `DocTranslationServiceTest` 缺少构造器参数（`SynonymConfig` / `LlmProviderFactory`）。源自初始 commit `a7a04e3`，阻塞 `./gradlew test` 运行。需补构造器参数或加 @Mock。

### 明确从 backlog 删除

- ~~Vite HMR 卡住问题深入调查~~（用户决定不做，已通过修 reset 脚本绕过）

## Context for Next Session

### 关键文件引用

- **菜单 seed 机制参考**：`auraboot-enterprise/docs/system-reference/reference/menu-seed-mechanism.md` — 必读
- **Bootstrap 源**：`auraboot/platform/src/main/resources/tenant-templates/default-bootstrap.json` (1332 行，删 10 条后)
- **Bootstrap 创建逻辑**：`auraboot/platform/src/main/java/.../tenant/service/impl/TenantBootstrapServiceImpl.java:426-516` (`createMenus()`)
- **插件菜单写入**：`auraboot/platform/src/main/java/.../plugin/service/impl/PluginImportServiceImpl.java:1474` + `MenuMapper.java:207-216` (`updateForPluginImport()`)
- **icon-resolver**：`auraboot/web-admin/app/utils/icon-resolver.tsx`
- **AuraBot Panel**：`auraboot/web-admin/app/plugins/core-aurabot/components-shell/AuraBotPanel.tsx`
- **AI Modeling 页面**：`auraboot/web-admin/app/plugins/core-meta/pages/meta/ai-modeling/index.tsx`
- **I18nDefinitionDTO**：`auraboot/platform/src/main/java/.../plugin/dto/imports/I18nDefinitionDTO.java`（flat array schema 定义）

### 工作区约定

- 主要工作目录：`/Users/ghj/work/auraboot/auraboot/`（OSS）
- 重置脚本：`auraboot/scripts/reset-and-init.sh`（**不是** enterprise 的）
- 数据库：`psql -h localhost -U ghj -d aura_boot -P pager=off -c "SQL"`
- 前端：`cd auraboot/web-admin && pnpm dev:full` (端口 5173)
- 后端：`cd auraboot/platform && ./gradlew bootRun` (端口 6443)
- 登录：`admin@auraboot.com` / `Test2026x`

### 三仓库关系

- `auraboot/` (OSS core, publishToMavenLocal)
- `auraboot-enterprise/` (consumes core，本次有菜单/doc 改动)
- `auraboot-website/` (未涉及)

修改 core 后：`cd auraboot/platform && ./gradlew publishToMavenLocal`
