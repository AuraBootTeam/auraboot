---
date: 2026-06-21
created: 2026-06-21
type: handover
status: shipped
area: permission / rbac / web-admin
---

# Session Handover — 2026-06-21 · RBAC permission v2

## Session Summary
Picked up the RBAC v2 UI-reorg handover (`HANDOVER-2026-06-21-rbac-v2-ui-reorg.md`) and carried the
whole permission-v2 line to a **fully-merged close**: the Feishu-style IA reorg, a new role-level
default data scope (inherit-on-grant), a real production bug found+fixed while verifying, a
browser-level regression guard, the `@auraboot/track` lockfile/typecheck cleanup, canonical
gotcha+retro precipitation, and a live verification of the org-management member org-tree path.
**Nothing is left open except two marginal-ROI residuals (backlog).**

## Tasks Completed (all MERGED to main)
- [x] **OSS #989** `39c63f5d9` — v2 capability-primary IA: ① capability checklist (default, business
  labels) · ② data-scope bar+drawer · ③ matrix folded into a collapsed advanced escape hatch ·
  retired the flat "assignments" tab · convention-derive business labels + module-group i18n ·
  members i18n (`t(k, undefined, fallback)`) + OSS OrgTreePicker graceful empty state.
- [x] **OSS #993** `cff7206d2` — ② role-level default data scope (inherit-on-grant) **+ fixed the
  capability-save snowflake-id precision bug** (capability endpoint `roleId`→`rolePid`).
- [x] **OSS #994** `a97da06e8` — browser-level golden guarding the precision fix.
- [x] **OSS #1003** `1de23f524` — sync `@auraboot/track` into the lockfile + track tests `import
  { it, expect } from 'vitest'` → `pnpm typecheck` 0 errors (was 33).
- [x] **ENT #639** `85f405d1f` — retro + 2 reusable gotchas precipitated to canonical.
- [x] **Live-verified** the org-management member org-tree path (gap #2 from the progress review).

## Tasks In Progress
None. The line is closed.

## Key Decisions
| Decision | Chosen | Rationale | Alternatives |
|----------|--------|-----------|--------------|
| Role default scope: how to inherit | **Eager materialize at grant chokepoint** (`assignPermissionsToRole`) | `resolveScope` (enforcement) byte-untouched → zero blast radius; null default = old behaviour | Lazy fallback in `resolveScope` — rejected (alters enforcement for every role/query) |
| ③ "source/coverage" data | **Client-side derive** (`coverageHelpers`) from existing capability view | No new endpoint; golden-verified | New backend endpoint (handover suggested) — unnecessary |
| §2.1 business labels | **convention-derive uses permission name** (representative action) | One of the two handover options; module group localized via existing `permission.module.*` i18n | Platform `capabilities.json` — not added (so core perms have no tier presets; minor) |
| Capability/role endpoints key | **rolePid (string), not numeric id** | Snowflake ids exceed JS safe-int → browser precision loss → wrong role | numeric id (what #989 shipped, broken) |
| Convention-label algorithm | **representative action name** | LCS-of-names produced garbage (`Webhook管理`+`System webhook update` → `ebhook`) | longest-common-substring — rejected (fragile) |

## Files Changed (by PR — all merged; pointers only)
- #989: `PermissionManagement.tsx`, `capability/{CapabilityRoleEditor,CapabilityChecklist,DataScopeBar,AdvancedAtomicActions,coverageHelpers}.tsx/ts`, `scopeConfig.ts`/`scopeHelpers.ts`, member files, `OrgTreePicker.tsx` stub, `CapabilityResolver.java`/`CapabilityViewServiceImpl.java`, `i18n-base.json`; deleted `AssignmentTab`/`PermissionMatrix(Tab)`.
- #993: `V20260621020000__add_role_default_data_scope.sql` + `database/schema.sql` + snapshot, `Role.java`/`RoleService(Impl)`, `RolePermissionServiceImpl` (grant hook), `PermissionMatrixService(Impl)`, `CapabilityController` (rolePid), `capabilityService.ts`.
- #1003: `pnpm-lock.yaml` (+5 lines), `packages/track/src/__tests__/*.test.ts` (vitest import).
- #639 (ENT): `engineering-gotchas/frontend-ssr-build.md`, `flyway-schema-change-and-local-bringup.md §2.1`, `retro/2026-06-21-rbac-v2-permission-session-retro.md`.

## Pitfalls & Workarounds
1. **Capability save broken for real (snowflake-id) roles** — shipped in #989, caught only when #993's
   golden actually granted via the browser. **Root**: numeric `roleId` round-trips lossily through JS
   (id > 2^53) → FK violation / wrong role. **Fix**: `rolePid` everywhere (#993). **Prevent**: gotcha
   in `frontend-ssr-build.md` + UI golden #994.
2. **Golden bring-up `column ... does not exist`** — golden-stack applies `database/schema.sql`
   (legacy consolidated DDL), NOT flyway. **Fix**: mirror the column into `schema.sql` + regen
   snapshot (triple-maintenance). **Prevent**: gotcha in `flyway-schema-change-and-local-bringup.md §2.1`.
3. **abac/v2-golden cross-test pollution** — abac spec seeds persistent `function.*` perms → v2-golden
   raw-leak assertion trips in batch runs. **Workaround**: pass run isolated. **Residual** (no delete-perm API).

## Lessons Learned
- A read-only golden hides write-path bugs — #989's golden never granted, so the precision bug slid
  through. Golden must exercise the real mutating action (§2.2).
- Snowflake ids + the browser = always key role/entity endpoints on PID, never numeric id.
- A DDL migration is triple-maintenance until the golden-stack is pointed at the snapshot.

## 反思与经验固化 (Reflection & Codify)
### 本会话弯路 / 返工 / 翻车
1. **capability-save 精度 bug 到 #993 golden 才暴露** — 代价:~3 轮诊断(FK violation → 怀疑 hook → 查到雪花精度)— 本可更早:#989 的 golden 若真授予一次就当场抓 — 根因:`[D 验证]`(只读 golden)+`[A 门禁]`(matrix 端点知道用 PID,无 lint 强制新端点同样用 PID)。
2. **golden 起栈缺列** — 代价:1 轮(改 schema.sql)— 本可更早:flyway doc 没写 golden-stack 用 schema.sql — 根因:`[B 输入]`+`[A 门禁]`(无 schema.sql↔migration drift gate)。
3. **capability-save UI 测试 check()+save 竞态granted 0 / org-tree auth+endpoint friction** — 代价:各 1-2 轮 — 根因:`[D 验证]`(测试交互)+`[B 输入]`(API surface 未文档化,靠 grep)。
4. 核心 feature(v2 IA + 默认范围)**无重大弯路**——TDD + golden 纪律顺畅。

### 为什么会发生(根因小结)
主要是 **D 验证纪律**(只读 golden 漏写路径 bug)+ **A 门禁质量**(无 PID-端点 lint、无 schema.sql drift gate)。验证拦截了 bug(verify-don't-trust 起作用),但拦得晚。

### 应该有哪些改进
- (已做)真授予的 golden 守卫 + 两条 canonical gotcha。
- (留 backlog,负 ROI / 无 API)golden-stack 改 apply snapshot 退役 schema.sql 镜像;schema.sql↔migration drift gate;new-entity-endpoint PID lint。

### 已固化 / 待固化(更新文档)
- [x] `engineering-gotchas/frontend-ssr-build.md`(ENT #639):实体端点用 PID 非数字 id。
- [x] `flyway-schema-change-and-local-bringup.md §2.1`(ENT #639):DDL 三处维护 + golden-stack 读 schema.sql。
- [x] `docs/retro/2026-06-21-rbac-v2-permission-session-retro.md`(ENT #639)。
- [x] memory: RBAC 条目转 ARCHIVE.md 墓碑(本会话)。
- [ ] (owner 决策,负 ROI)golden-stack→snapshot / schema.sql drift gate / PID-endpoint lint。

## 运行态快照 (Operational State)
### 分支 / Worktree / PR
- **当前分支**:本 handover 在临时 worktree `docs/rbac-v2-handover`(off main);RBAC 功能分支**全已删**。
- **Worktree**:RBAC 相关 worktree(rbac-ux / rbac-default-scope / rbac-golden-hardening / rbac-docs / track-fix / org-verify)**全部已 remove**。
- **PR**:OSS #989/#993/#994/#1003 + ENT #639 全 **MERGED**(已核对 origin/main 有各 squash commit:`39c63f5d9` `cff7206d2` `a97da06e8` `1de23f524` / ENT `85f405d1f`)。本 handover 自身待开 PR。
- **未提交改动**:仅本 handover 文件。

### Runtime / 端口
- **全部销毁**:本会话用过 slot-58(rbac-v2-golden / rbac-default-scope-gold / rbac-golden-hardening-gold / org-tree-verify),均 `oss-golden-stack.sh destroy` 收口。当前无本任务 runtime。
- 接手者若要复跑 golden:`cd <fresh worktree> && ./scripts/oss-golden-stack.sh up <name> --slot <free>`,golden 在 `tests/e2e/permission/{permission-v2-golden,role-default-scope-golden,capability-save-ui-golden}.spec.ts`(`--project=chromium --no-deps`)。

### Database / Seed
- 无遗留隔离库。golden-stack 是 minimal-bootstrap(admin+tenant);跑 capability/default-scope golden 不需 showcase seed。org-tree 验证需 `import org-management` + `POST /api/org/departments`(本会话已验过流程)。

## Next Steps
本任务线**已闭环,无 must-do**。仅剩 2 项边际 ROI residual(owner 决定是否投入,均非阻塞):
1. abac `function.*` 共享栈污染 v2-golden raw-leak(正解需「删权限」API,当前不存在)。
2. decisionops-permission-negative 的 `decision-detail` 步在 minimal-stack env 受限(我 remap 的 matrix→③ 段已验过)。
3. (可选,负 ROI)企业 overlay 真 OrgTreePicker 像素渲染——需 `build-web-admin.sh` 重建(staged overlay 4/13 stale);验的是未改动的企业前端,backend+契约已实证,渲染必然成立。

## Context for Next Session
- 全文复盘 + 收口细节:memory `ARCHIVE.md` §RBAC 权限 v2;retro `auraboot-enterprise/docs/retro/2026-06-21-rbac-v2-permission-session-retro.md`。
- 2 条复用陷阱:`auraboot-enterprise/docs/agent-rules/engineering-gotchas/frontend-ssr-build.md`(PID 端点)+ `flyway-schema-change-and-local-bringup.md §2.1`(DDL 三处维护)。
- 接手**新战线**(非本任务)建议 fresh session;active-work 见 `MEMORY.md`。
