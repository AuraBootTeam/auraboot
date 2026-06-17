# Design-token burn-down baseline (T1.5)

> Baseline captured 2026-06-17 on branch `feat/ux-design-tokens` (P0 T1 of the UX
> Design System rollout). Spec: `auraboot-enterprise/docs/standards/core/ux-design-system.md`.
> Tracker for the T2 (base components) / T3 (smart components) sweeps — drive
> each category to 0 (or justified residual) and update the counts below.

## How to reproduce

Run from `web-admin/`:

```bash
# A. raw hex literals
grep -rnoE '#[0-9a-fA-F]{3,8}\b' app/ui --include='*.tsx' --include='*.ts' --include='*.css' | grep -viE '__tests__|\.test\.'
# B. arbitrary-value tailwind (color + px)
grep -rnoE '\b(text|bg|border|ring|fill|stroke)-\[#[0-9a-fA-F]{3,8}\]' app/ui --include='*.tsx'
grep -rnoE '\b(w|h|min-w|min-h|max-w|max-h|p|px|py|pt|pb|pl|pr|m|gap|text|rounded|size)-\[[0-9.]+px\]' app/ui --include='*.tsx'
# C. palette-color utilities that bypass semantic tokens
grep -rnoE '\b(text|bg|border|ring|divide|from|to|via)-(gray|slate|zinc|neutral|stone|blue|sky|indigo|red|rose|green|emerald|amber|yellow|orange|cyan|teal|violet|purple)-[0-9]{2,3}\b' app/ui --include='*.tsx'
# D. radius utilities
grep -rnoE '\brounded(-[a-z]+)?-(sm|md|lg|xl|2xl|3xl|full)\b' app/ui --include='*.tsx'
```

## Baseline counts — scope `app/ui` (156 component files: 19 base in `ui/ui`, 55 smart in `ui/smart`, rest in `ui/meta` etc.)

| # | Category | Occurrences | Target |
|---|----------|------------:|--------|
| A | Raw hex literals (`#rrggbb`) | 23 (4 files) | semantic token / `var(--color-*)` |
| B | Arbitrary tailwind color `text-[#…]` | 1 | semantic utility |
| B | Arbitrary px sizes `…-[NNpx]` | 38 | spacing / control tokens |
| C | **Palette utilities `*-gray/blue/red…-NNN`** | **2943** | semantic color utilities |
| D | **Radius utilities `rounded-sm/md/lg/…`** | **457** | `rounded-control/card/card-lg/pill` |
| E | Inline-style `NNpx` (approx) | 59 | spacing / control tokens |

Separately, `app/app.css` carries **367 raw hex** (the legacy `.decisionops-*` block) — a self-contained migration, tackle alongside T4 (list renderer) or as its own slice.

### Top offenders (C — palette utilities)

`smart/display/Table.tsx` 48 · `meta/CrudTemplateWizard.tsx` 47 · `StoreFormFields.tsx` 44 · `smart/picker/TreeSelect.tsx` 44 · `meta/VersionDetail.tsx` 44 · `meta/RuntimeVerification.tsx` 44 · `meta/FieldSelectionDialog.tsx` 44 · `meta/DictConfigDialog.tsx` 44 · `meta/FieldListManager.tsx` 42 · `meta/FieldBindingConfigForm.tsx` 41 — i.e. the surface is broad, not concentrated (~40 each across many files).

### Top offenders (D — radius)

`meta/FieldSelectionDialog.tsx` 17 · `meta/DictConfigDialog.tsx` 17 · `StoreFormFields.tsx` 15 · `meta/CrudTemplateWizard.tsx` 15 · `meta/PermissionPermissionMapping.tsx` 14.

### Raw-hex files (A) — ⚠ triage before "fixing"

- `app/ui/CommandPalette.tsx` — styling hex → migrate.
- `app/ui/smart/form/FormRef.tsx` — styling hex → migrate.
- `app/ui/smart/form/ColorPickerField.tsx` — **legitimate** (color-picker swatches/value); NOT a token violation.
- `app/ui/smart/picker/avatar-utils.ts` — **legitimate** (deterministic avatar color generation); NOT a token violation.

## Legacy → semantic-token migration cheat-sheet (for T2/T3)

Utilities/vars now available from `tokens.theme.css` (generated from `dsTokens`):

| Legacy tailwind / hex | Semantic token utility | CSS var |
|---|---|---|
| `text-gray-900` `#111827`/`#1f2937` | `text-text` | `--color-text` `#1A1A1E` |
| `text-gray-500` `#6b7280` | `text-text-2` | `--color-text-2` `#5A5E66` |
| `text-gray-400` `#9ca3af` | `text-text-3` | `--color-text-3` `#9A9DA5` |
| `border-gray-200` `#e5e7eb` | `border-border` | `--color-border` `#ECEDEF` |
| `border-gray-300` `#d1d5db` | `border-border-strong` | `--color-border-strong` `#E2E3E6` |
| `bg-white` | `bg-panel` | `--color-panel` `#FFFFFF` |
| `bg-gray-50` `#f9fafb` | `bg-subtle` | `--color-subtle` `#FAFAFB` |
| `bg-gray-100` `#f3f4f6` | `bg-hover` | `--color-hover` `#F3F4F6` |
| `bg-blue-600`/`text-blue-600` `#2563eb`/`#3b82f6` | `bg-accent`/`text-accent` | `--color-accent` `#2563EB` |
| `bg-blue-700` `#1d4ed8` (hover) | `bg-accent-hover` | `--color-accent-hover` `#1D4ED8` |
| `bg-blue-50` `#eff6ff` | `bg-accent-weak` | `--color-accent-weak` `#EFF4FF` |
| status gray/blue/amber/green/red | `text-status-{name}` + `bg-status-{name}-bg` | `--color-status-{name}` / `-bg` |
| `rounded-sm` / `rounded` / `rounded-md` | `rounded-control` (6px) | `--radius-control` |
| `rounded-lg` | `rounded-card` (8px) | `--radius-card` |
| `rounded-xl` | `rounded-card-lg` (10px) | `--radius-card-lg` |
| `rounded-full` | `rounded-pill` | `--radius-pill` |
| focus ring | `focus-visible:shadow-focus` | `--shadow-focus` (= `0 0 0 3px accent-weak`) |
| disabled | `disabled:opacity-50` | `--ds-disabled-opacity` |
| control heights | `h-[var(--ds-control-md)]` etc. | `--ds-control-sm/md/lg/field` |

> Status colors map by **semantic**, not by raw palette — pick from the 5 in
> standard §1.3 (gray=draft/closed, blue=in-progress, amber=pending/warning,
> green=done/normal, red=error/overdue). Don't 1:1 translate every `green-500`;
> confirm the semantic first.

## Notes / decisions

- **Tailwind v4 tree-shakes theme variables**: a `--color-*` var only lands in the
  built `:root` once a utility referencing it is used — expected, not missing.
- **Dark mode** for the new semantic tokens is **out of scope for T1** (standard
  defers dark to T3). T1 emits light values only.
- **Mockup divergence flagged**: the standard says focus ring `0 0 0 3px accent-weak`
  (encoded here); the mockup HTML currently renders `2px`. Standard is authoritative;
  sync the mockup when convenient.
