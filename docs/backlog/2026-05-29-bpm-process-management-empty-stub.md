# BPM 流程定义页 `bpm_process_management` 空壳 (autoCreateDefaultPages stub)

**Status**: **CLOSED — NOT REPRODUCIBLE** (2026-05-29 17:00)
**Resolution**: Subagent (a441e204c3316baba) 起 fresh isolated stack `auraboot-diag` 真浏览器实测 → `/p/bpm_process_management` 正常渲染 23377 bytes 含 toolbar / 4 tabs / 6 列 / 2 行真数据(finance v1 deployed / approval v1 deployed)。`/api/pages/key/bpm_process_management_list` 返回完整 schema,blocks.length=3,与 platform-admin DSL 一致。**No product bug**。

**Smoke fail 的真相**:
- DOM `main [ref=e696]` 完全空 = transient state(login redirect / Suspense lazy / 上轮被破坏的 stack 副作用),不是稳态产品 bug
- 主对话 + 第一个修任务 subagent 都把 transient snapshot 当稳态结论 → 全部假阳性
- **§15 verify-before-claim 双倍反面教材**:(1) backlog 原文 grep 范围漏 platform-admin(第一次违规) (2) 把 transient DOM 当结论(第二次违规)

**Original Status (history)**: OPEN (P2 product gap)
**Discovered**: 2026-05-29 by `bpm-smoke/wf-end-to-end-smoke.spec.ts:37` (new smoke-first canonical)
**Filed**: 2026-05-29

## 症状

`bpm-smoke/wf-end-to-end-smoke.spec.ts:37` `navigateToProcessDefinitionList` 在 `/p/bpm_process_management` 等不到:
- `main table`
- `main [data-testid="dynamic-list"]`
- `toolbar-btn-create`
- `创建/新建/Create` 按钮

15s timeout。菜单导航成功(已展开"流程管理" → "流程定义"),URL 路由有效,但 main 区域无 list / toolbar 渲染。

## 根因(2026-05-29 16:00 修订 — 原诊断错位,§15 verify-before-claim 反面教材)

### ⚠️ 原诊断错位(主对话写 backlog 时违反 §15)

第一版 backlog 写"`grep -rln 'bpm_process_management'` 全空 — page DSL 根本不存在"。**这是主对话 grep 范围错位**:只扫了 `core-bpm/`,**漏了 `platform-admin/`**。

修 P2 任务时 subagent (a7c6e3a72c09a822c) verify-before-claim 揪出真证据:

```
$ grep -rln "bpm_process_management" plugins/
plugins/core-bpm/config/menus.json
plugins/core-bpm/config/permissions.json
plugins/platform-admin/config/bindings.json        ← bindings 已存在
plugins/platform-admin/config/models.json:147       ← model 声明
plugins/platform-admin/config/pages.json:1134-1358  ← 完整 list page DSL
```

`bpm_process_management_list` page DSL **完整存在** 在 `platform-admin/config/pages.json:1134-1358`:
- `kind: "list"` / `modelCode: "bpm_process_management"` / `schemaVersion: 2`
- 完整 toolbar(`create` 跳 `/bpmn-designer`)+ tabs(all/draft/deployed/suspended)+ table(6 columns + 5 rowActions)
- `extension.dataSource = { type:"api", endpoint:"/api/bpm/process-definitions", method:"get" }`
- 被 `web-admin/app/framework/meta/utils/canonicalizePageDsl.ts:402` 正常消费

**第一版 backlog 的"空壳"结论是 grep 范围错位导致的误判,不是真根因。**

### 真候选根因(3 选 1,需进一步实测定位)

smoke 仍 fail 说明运行时确实空白(`<main ref=e696>` 无内容)。在 page DSL 完整存在的前提下,真根因候选:

1. **e2e env 没成功 import platform-admin** — 需进 isolated stack 看 import API 真返回 / DB 看 page 表是否有 `bpm_process_management_list` 行;若是,修 reset/init/seed,**非 core-bpm scope**
2. **platform-admin 导入成功但 schema 没正确落库** — 检查 `MetaPage` / `MetaPageBlock` 等表;若是,修 platform 或 platform-admin import 流程,**非 core-bpm scope**
3. **前端 DynamicPageRenderer 对 `extension.dataSource.type='api'` 渲染 bug** — 检查 `app/routes/p.$pageKey.tsx` 派给 DynamicPageRenderer 后,是否走对 api dataSource 分支(`canonicalizePageDsl.ts:402` 之后路径);若是,修前端 routes/components,**非 core-bpm scope**

## 影响范围

- bpm-smoke `wf-end-to-end-smoke.spec.ts` blocker(2026-05-29 smoke 套唯一 fail)
- 任何依赖"流程定义"页直接 CRUD 的 user 流程
- **不阻塞** PR #347 (`bpm-workflow.spec.ts` 25 pass 3 runs) — 该 spec 用不同导航路径(API drive + 流程实例视角),不进 `/p/bpm_process_management` 列表
- **不阻塞** PR #344 (Spring 6 ctor fix) — backend bean 修复已三重验证,与本页 DSL 无关

## 修复路径(2026-05-29 16:00 重写)

**⚠️ 不能在 core-bpm 加重复 DSL** — 会与 platform-admin 已有的 `bpm_process_management_list` 撞 model + page 命名,import 时触发 conflict:
- `core-bpm` `dependencies:[]` 未声明 platform-admin → cross-plugin 同 modelCode 引用直接 validator 错
- core-bpm `pluginType:config` `resourceDirs` 只 permissions+menus,加 pages 还要带 model+fields(否则 conflict)
- 即使硬加,如果真根因是前端 bug 或 import 失败,仍不解决 smoke

**真正的修复路径**(任一,依赖根因 1/2/3 落地):

1. (根因 1):修 reset/init/seed 让 e2e env 真把 platform-admin import 进去 — owner: platform / e2e infra
2. (根因 2):修 platform 或 platform-admin import 流程让 schema 真落库 — owner: platform
3. (根因 3):修 web-admin DynamicPageRenderer / canonicalizePageDsl 让 api dataSource 真渲染 — owner: web-admin / 前端框架

**诊断步骤**(任一 owner 接手前先做):
1. 起 isolated stack `auraboot-diagnose` (`--offset=70`,避开 user)
2. `curl /api/plugins/import/import-directory-sync` 看 platform-admin 是否真 import success
3. psql 看 `MetaPage` 表是否有 `bpm_process_management_list` 行 + `MetaPageBlock` 是否有 list block
4. 浏览器开 devtools 进 `/p/bpm_process_management`,看:
   - DOM main 区是否真空 vs 有渲染但 list block 内部 empty
   - Network: `/api/bpm/process-definitions` 是否被调
   - Console: 是否有 schema 解析错
5. 根据现象归到 1/2/3,owner 接手

## 主对话静态消除范围(2026-05-29 16:30)

主对话 grep + read 静态分析确认 FE 全链路 **路径上无 bug**,真根因 100% 在 runtime:

| 层 | 静态检查 | 结论 |
|------|---------|------|
| Route | `web-admin/app/routes/p.$pageKey.tsx` 传 `tableName=pageKey, pageType="list"` 给 DynamicPageRenderer | ✅ 正确 |
| DynamicPageRenderer | 6 条状态分支(invalid kind / loading skeleton / error / no-schema alert / render PageContent / fallback alert) | ✅ 都有显式 UI 反馈 |
| useSchemaLoader | `pageKey = ${tableName}_${type}` = `bpm_process_management_list` | ✅ 匹配 platform-admin DSL key |
| API | `GET /api/pages/key/${pageKey}` | ✅ 端点存在 |
| Profile admin | `pageRenderers = Map { 'list' → ListPageContent }` | ✅ kind='list' 有 renderer |
| e2e profile | `scripts/dev/plugin-import-profiles.json:21-40` 含 `platform-admin` | ✅ 应被导入 |

**关键 DOM 证据**(`web-admin/test-results/artifacts/bpm-smoke-wf-end-to-end-sm-7ca10-.../error-context.md`):
```
- main [ref=e696]   ← main 完全空,无任何子元素 / loading / error
```

main 空说明 DynamicPageRenderer 走到 **render PageContent 分支**(line 110-122 的 `<div data-testid="dynamic-page-list">`)但 PageContent 渲染 null,**或** DynamicPageRenderer 根本没挂载(route loader 抛错被吞)。如果是其他分支(loading/error/no-schema),DOM 会有 text。

**剩余候选缩窄**:
- 根因 1(import 失败)+ 根因 2(schema 不全)都会触发 "No schema found" ErrorAlert,DOM 会有 text — **与 main 空矛盾**
- 根因 3(前端 renderer)更可能 — 但 ListPageContent 是 lazy load,可能 Suspense fallback 也没匹配(profile.skeletons.get('list') 为 undefined → LoadingSpinner,LoadingSpinner 一般有 DOM)
- **新候选 4**:`/api/pages/key/bpm_process_management_list` 返回 schema 但 schema.blocks 为空 array → ListPageContent 用空 blocks 渲染出 0 节点(自然空 main)

需要 runtime 验证:核心命令 `curl http://localhost:<BE>/api/pages/key/bpm_process_management_list` 看返回 body 是否 schema.blocks=[]。

## 关键证据(2026-05-29 16:00 修订)

- 错误 artifact: `web-admin/test-results/artifacts/bpm-smoke-wf-end-to-end-sm-7ca10-flow-with-exclusive-gateway-chromium/error-context.md`
- **Page DSL 实际存在的证据**:
  ```
  grep -rln "bpm_process_management" plugins/
  # plugins/platform-admin/config/pages.json:1134-1358  ← 完整 list page DSL
  # plugins/platform-admin/config/models.json:147       ← model
  # plugins/platform-admin/config/bindings.json:87-92   ← 6 field bindings
  # plugins/core-bpm/config/menus.json                  ← menu pointing to /p/bpm_process_management
  ```
- 主对话原 grep 错位(范围只 core-bpm,漏 platform-admin),被修任务 subagent verify-before-claim 揪出
- §15 反面教材升 canonical 待定(是否扩 §15 加"backlog 写作也是 claim,要 verify"条款)

## Owner

**不再是 core-bpm**。根因 1 = e2e infra / platform / 根因 2 = platform / 根因 3 = web-admin 前端框架。需先诊断定位再 routing。

`autoCreateDefaultPages` 行为讨论 **删除** — backlog 原文以为是这条触发,实际跟它无关(platform-admin 已经 user-defined 了 list page,不是 auto stub)。

## 相关

- AGENTS.md §2.2 §「Web/DSL 页面黄金标准验收」— 空壳页面金标闸门
- ENT canonical 2026-05-29 `auraboot-enterprise/docs/agent-rules/oss-e2e-and-playwright.md` §「Smoke-first 纪律」— 本 bug 是 smoke-first canonical 升级当天的首个真发现,验证新规则有效
- PR #347 `bpm-workflow.spec.ts` 3×25 pass 不矛盾 — 不同导航路径
