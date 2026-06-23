---
created: 2026-06-19
type: backlog
status: active
area: platform/permission, crm-starter, web-admin/i18n, web-admin/design-system
related: docs/handover/HANDOVER-2026-06-18-export-perm-and-owner-reference.md
---

# UX golden — derived follow-ups

The UX Design System backlog (`docs/backlog/2026-06-17-ux-design-system-tokens-activation.md`,
T1–T10 + G1/G2 + mockup parity) is **complete + merged** (~25 PRs, real-stack golden).
These items were surfaced incidentally by the golden verification and are **outside**
that backlog's scope — platform / crm-starter / i18n / polish. Captured here for later.

Already fixed this round (for context): OTel boot-jar crash (#835), data-export 403
for all models (#896).

## A. Functional

### A1 — owner field resolves to a name (the real fix; a field-type change is NOT enough)
- **Problem**: `crm_acc_owner` shows a raw id on list/detail. Changing it to a `sys_user`
  reference (attempted + reverted) does NOT resolve — `current_username` auto-set stores an
  id that isn't resolvable in `ab_user`, so the page-data API returns `crm_acc_owner_display: null`.
- **Real fix** (model + command, not config): (1) change the command's owner auto-set strategy
  to store a resolvable user identity (the user's pid or username that exists in the user table),
  AND (2) make the field a reference to the correct user model with a matching `targetField` +
  `displayField`. Verify with a real-stack golden that the page-data API returns a resolved
  `crm_acc_owner_display` (a name) and the form renders a working user picker.
- Full spec: `docs/handover/HANDOVER-2026-06-18-export-perm-and-owner-reference.md` (FIX A).
- Priority: medium (UX enhancement; the mockup treats owner as an avatar+name reference).

### A2 — per-model permission auto-gen omits export/import (now optional)
- **Root cause**: model-permission auto-generation creates `{read,create,update,delete}` (4),
  while the permission matrix (`PermissionMatrixServiceImpl.java:45`), the E2E test seed
  (`TestSeedController.java:757`), and `SchemaAccessProjectorImpl.java:204` all use the 6-action
  set `{…,export,import}`. So `model.<model>.export` is never created for real models.
- **Status**: the export 403 was fixed by gating export on `read` (#896, `DynamicController`),
  so this is now **optional** — only needed if fine-grained export/import permissions
  (separate from read) are ever wanted. If so: add export/import to the auto-gen action set,
  verify role-binding is action-agnostic, and full-reset test (multi-tenant permission bootstrap
  is sensitive).
- Priority: low (export already works).

## B. i18n completion (English fallbacks work today)
- **B1** — `import.*` catalog: `ImportModal.tsx` routes strings through `t('import.*', …, fallback)`
  but only the English fallbacks exist; add zh-CN/en-US entries to
  `platform/src/main/resources/i18n.{zh-CN,en-US}.yaml`.
- **B2** — G2 i18n ratchet drive-down: ~111 hardcoded placeholder/title/aria-label strings remain
  (gated at baseline 111 in `web-admin/scripts/check-design-tokens.mjs`); route through `t()`.

## C. Design-system polish (gate holds the line; optional)
- **C1** — T3 sweep tail: residual decorative/slate/shade-pair palette in `app/ui` +
  `app.css .decisionops-*` (367 raw hex); a self-contained migration. Lower the palette
  baseline (currently 1216) as it lands.
- **C2** — `dark:` cleanup: explicit `dark:bg-gray-*` etc. are now redundant (the `.dark` token
  override auto-switches the semantic tokens); remove so dark fully flows through tokens.
- **C3** — mockup focus-ring: standard says `0 0 0 3px accent-weak`; the mockup HTML uses 2px —
  align one to the other.

## D. Deeper golden coverage (renderers support these; demo seed didn't exercise)
- **D1** — list batch/multi-select bar (§3) + cross-page select on a selection-enabled model.
- **D2** — detail sub-tab inner content (timeline §5, comments, activity) drill golden.
