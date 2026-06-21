---
title: Permission v2 capability — UI golden findings (env + convention-derive save gap)
date: 2026-06-21
type: backlog
status: resolved
area: permission / rbac
---

> **Resolved 2026-06-21** (commit `ef9ef115a`, branch `feat/rbac-ux-and-tests`): finding #2
> (convention-derive Save no-op) was fixed in the foundation commit `611687cd3`
> (`applyCapabilitySelection` resolves against the full convention-derived universe with a
> revoke universe bounded to fully-held capabilities). Finding #1 (`@auraboot/track` SSR) is an
> env/lockfile concern tracked in the handover; the host-first golden stack
> (`scripts/oss-golden-stack.sh`) handles the symlink at bring-up. The v2 IA reorg ships
> business-language labels + the ③ advanced escape hatch, screenshot-verified.

# Permission v2 capability management — UI golden findings

Host-first UI golden run of the v2 permission-management page (capability editor) against a fresh
isolated stack surfaced two real issues and confirmed the RBAC management UI otherwise works.

## 1. ENV — `@auraboot/track` not linked breaks all admin-route SSR (P1, local)

The telemetry SDK merge (#966) added `@auraboot/track` (`workspace:*`, source pkg at
`web-admin/packages/track`) and `AdminLayout.tsx` imports it. If `web-admin/node_modules` was not
re-linked after that merge, Vite SSR of **every** admin route fails:

```
[vite] Internal server error: Cannot find module '@auraboot/track' imported from
       '.../web-admin/app/routes/AdminLayout.tsx'
```

The page renders the Vite error overlay → no app → all admin-route goldens fail at navigation.

- **Fix**: `pnpm install` (or `pnpm -C web-admin install`) re-links the workspace package. A bare
  symlink `web-admin/node_modules/@auraboot/track -> ../packages/track` is the minimal equivalent;
  Vite must be restarted to drop the cached resolution failure.
- **Guard idea**: the golden-stack bring-up (`scripts/oss-golden-stack.sh`, step 7) should verify
  `@auraboot/track` resolves before declaring the frontend UP, so this fails fast instead of as a
  mid-golden navigation timeout.

## 2. PRODUCT — convention-derived capabilities are toggleable but Save is a no-op (gate-gap)

`CapabilityResolver.resolve()` presents BOTH declared capabilities (from `capabilities.json`) and
**convention-derived** ones (`module.resource` → that resource's `module.resource.*` codes) for any
code not covered by a declaration. The v2 editor renders both as toggleable checkboxes with a Save.

But `CapabilityViewServiceImpl.applyCapabilitySelection()` resolves the selection **only against
declarations** (`expandToPermissionCodes(selected, declarations)` + `universe = declarations'
includes`). So toggling a *convention-derived* capability and clicking Save:

- `desired` = ∅ (the convention-derived code isn't in `declarations`)
- `universe` = ∅ (no declarations) → nothing to grant or revoke

→ **silent no-op**. The button disables (looks saved), but the grant never persists. In production,
products ship `capabilities.json` so their *declared* capabilities save correctly; the gap only bites
**convention-derived** (fallback) capabilities — visible in any stack/tenant without a declaration
covering a resource.

This is "门禁绿 ≠ 功能可用": render + toggle + dirty-state all pass; only a real-browser
grant → Save → reload → assert-checked catches it. (The minimal-bootstrap golden stack is 100%
convention-derived, which is how this surfaced.)

### Why the naive fix is unsafe

Making `universe` = *all* capability codes (declared + convention-derived) turns the write into a
complete-state PUT over **every** permission code. Any selection that isn't the role's *complete*
desired set then revokes everything unselected — a partial PUT silently strips the role (verified:
it stripped `tenant_admin` and locked the admin out of `org.role.read`). Reverted.

### Safe directions (pick one, design first)

1. **Read-only convention-derived** — disable/annotate convention-derived checkboxes in the editor;
   only declared capabilities are savable. Smallest change, honest UX (no silent no-op).
2. **Convention-aware writes with scoped universe** — expand convention-derived selections to their
   codes, but bound the revoke `universe` to the codes the editor actually presented for the
   *toggled* capabilities (not all codes), and require the client to send complete state. Needs a
   tighter API contract + tests for the partial-PUT footgun.
3. **Additive/PATCH semantics for convention-derived** — grant the toggled capability's codes,
   revoke only when its own checkbox is unchecked; never touch unrelated codes.

Recommend (1) short-term (kills the silent no-op safely) + (2) or (3) as the real fix.

## 3. RBAC management UI — works (confirmed this run)

`tests/e2e/permission/permission-management.spec.ts` PM-UI-02..10 pass on the host stack: create /
edit / delete custom role (ConfirmDialog), toggle status, assignments tab, permission tree + search,
cancel-delete. `PM-UI-01` (navigate via sidebar menu) fails only because the minimal-bootstrap stack
seeds no sidebar menus (`sidebar.noMenus`) — env, not a product defect; the direct-nav variants pass.

Capability grant → enforce → revoke **persistence** (the declared path) is already covered end-to-end
by the backend IT `CapabilityLifecycleEnforcementIT` (auraboot#975) and the controller enforcement IT
(#971); this UI golden is about the editor surface, not re-proving persistence.
