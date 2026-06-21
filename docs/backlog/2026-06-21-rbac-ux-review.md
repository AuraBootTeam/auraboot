---
title: RBAC / permission-management — UX review (screenshot-verified)
date: 2026-06-21
type: backlog
status: open
area: permission / rbac / org
---

# RBAC permission-management — UX review

Screenshot-verified review of every permission-management surface on a host-first stack
(`scripts/oss-golden-stack.sh`, slot 82). Screenshots in `web-admin/test-results/rbac-ux/`.

## Information architecture (as built)

`/enterprise/permissions` — page "权限管理", two top tabs:

- **角色管理 (Roles)** — left: role list (search + create); right: 3 sub-tabs per selected role
  - **能力 (Capabilities)** — v2 capability checklist (declared + convention-derived) + tier presets
  - **权限列表 (Permissions)** — raw resource × action matrix, grouped by module
  - **成员管理 (Members)** — role's members + add-member dialog (org-tree / list tabs)
- **权限分配 (Assignments)** — left: role list; right: a **flat checklist of all ~326 permissions**

Org structure (部门/岗位/员工) lives in a **separate `org-management` plugin** (DSL pages, e.g.
`/p/org_department`), not in the core RBAC page. The RBAC page only *consumes* it (member rows show
department/position; AddMember has an org-tree tab). Backend: `/api/org/departments` (tree+CRUD),
`/api/org/employees`, `/api/org/teams`.

## Findings (severity-ranked)

### P0 — i18n key leaks on the entire Members surface (blocker)
Raw i18n keys render to users (screenshots 04, 05):
- Members tab: `admin.permission.members.add`, `admin.permission.members.empty`
- Add-member dialog: `admin.permission.members.addTitle`, `.tabOrg`, `.tabList`, `.addConfirm`

The rest of the page IS localized (角色管理 / 权限分配 / 新增角色 …), so these keys are simply
**unregistered**, not a stack-wide i18n failure. Per the design standard this is a blocker.

### P1 — three overlapping "grant permissions to a role" UIs (IA confusion)
The same task is offered three different ways: **能力 (capability)**, **权限列表 (matrix)**, and the
separate **权限分配 (flat 326-item list)**. v2 capability was meant to *replace* the raw matrix, but
all three now coexist with no stated hierarchy — users can't tell which is canonical, and the three
can diverge. (screenshots 01, 02, 07)

### P1 — capability view shows raw codes, not business language
Convention-derived capabilities render the **raw English resource segment** (license, invoice,
subscription) under **raw module group names** (billing, ai, iot) — no localization (screenshot 02).
Yet the Assignments flat list shows the **localized** name for the same codes (许可证查看,
billing.license.read) (screenshot 07). So the v2 surface is *worse* than the legacy one it replaces.
Convention-derive should reuse each permission's `name:zh-CN`. Also: tenant_admin shows `billing 0/7`
(it holds no billing.* codes here) → reads as "admin has no capabilities", which is confusing.

### P1 — convention-derived capabilities are toggleable but Save is a no-op
(Documented separately in `2026-06-21-permission-v2-capability-ui-golden-findings.md`.) The editor
lets you toggle convention-derived capabilities and the Save button responds, but the grant never
persists (`applyCapabilitySelection` only resolves declared capabilities). Either make them savable
safely or render them read-only — silent no-op is the worst option.

### P1 — Add-member org picker is empty / non-functional in core RBAC
The org-tree tab renders empty (no departments without the `org-management` plugin); the dialog has
no graceful empty state and (combined with the i18n leak) is unusable as shipped (screenshot 05).

### P2 — permission matrix density & "ALL" affordance
Per-module tables with **inconsistent column sets** (Analytics: 查看/新增/编辑/删除/生成;
Automation: +管理/执行) and an unexplained **"ALL"** chip next to every checkbox (data scope?).
Dense and hard to scan — the original "矩阵式难用" complaint (screenshot 01).

### P2 — `sidebar.noMenus` raw key
The empty-sidebar state renders the raw key `sidebar.noMenus` (env: no menus seeded), but a raw key
should never reach the UI — needs a localized fallback.

## Proposed UX improvements (for review before implementation)

1. **[P0] Register all `admin.permission.members.*` i18n keys** (zh-CN + en) across Members tab +
   add-member dialog; add the `sidebar.noMenus` fallback. Kills every raw-key leak.
2. **[P1] Resolve the 3-UI redundancy — pick a canonical hierarchy.** Recommended:
   - **能力 (capability)** = the primary, business-language surface (default sub-tab).
   - **权限列表 (matrix)** = advanced / escape-hatch (kept, de-emphasized).
   - **Retire or fold the separate 权限分配 flat-list tab** into the role editor (it duplicates the
     matrix). One place to assign, not two top-level tabs.
3. **[P1] Capability labels = business language.** Convention-derive should reuse the permission's
   localized name + a localized group label; fix the convention-derive Save safely (or read-only).
4. **[P1] Add-member dialog**: localize + graceful empty state + verify the org-tree path with the
   `org-management` plugin imported.
5. **[P2] Matrix polish**: explain/!rename the "ALL" affordance, normalize columns, align to
   `docs/standards/core/ux-design-system.md` tokens.

## Org / department / position scope note (affects the test plan)
"部门/岗位" are **not** part of core RBAC UI — they're the `org-management` plugin (DSL) + `/api/org`.
The eventual golden test plan must therefore span: core RBAC (role/permission/capability/member) +
`org-management` (department tree, position, employee) + the RBAC↔org seam (assign role to a
department-scoped member; grant/revoke and re-verify). This is why the plugin must be imported into
the golden stack, not just the minimal bootstrap.
