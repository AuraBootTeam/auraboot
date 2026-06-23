# @auraboot/runtime-kernel

Product-agnostic multi-runtime frontend kernel, extracted from the
`auraboot-app` (web-admin) package. The goal: when a second app is built on the
platform (e.g. a standalone storefront web app), it can depend on the **same**
kernel instead of copying these files and letting the two drift apart.

## What lives here (and why it is safe to share)

Every module in this package depends on **only `react` + other files in this
package**. There are zero imports of the `~/*` app alias and zero product/admin
modules. That invariant is the whole point of the package and is enforced in CI
review by grepping the package for non-`react`/non-relative imports.

| Area | Files | Notes |
|------|-------|-------|
| Runtime profiles | `runtime/runtimeProfile.ts`, `runtime/RuntimeProfileContext.tsx`, `runtime/index.ts` | Pure functions + a React context. Defines the five runtime profiles (`admin` / `merchant` / `storefront` / `checkout` / `theme-preview`), anonymous-profile detection, and core-plugin boot gating. |
| Render-profile seam | `profiles/ProfileRegistry.ts`, `profiles/ProfileContext.tsx`, `profiles/types.ts` | The `RenderProfile` contract + the global `profileRegistry` + the React context/hooks. This is the seam that lets a new rendering style (e.g. `storefront`) be **registered as another profile** rather than forking the renderer — exactly how the existing `report` profile works. |
| Rendering primitive | `rendering/BlockErrorBoundary.tsx` | Generic per-block crash isolation. No product knowledge. |

## What deliberately stays in `auraboot-app`

These are admin/product-specific implementations that register **against** the
kernel's contracts. Keeping them out is what keeps the kernel reusable:

- **Concrete block renderers** + the global `BlockRegistry` (`app/ui/schema-renderer/BlockRegistry.ts`) and the `admin`/`report` `RenderProfile` registrations (`app/framework/meta/profiles/{admin,report}`). These are the *content* of the admin profile.
- **`BlockRenderer.tsx`** (the profile-aware dispatcher) and **`ComponentLoader`**. The dispatcher's logic is generic, but it currently hard-imports the admin `BlockRegistry` + `ComponentLoader`. Moving it cleanly requires decoupling it from those concrete singletons (see Follow-ups).
- **`FederationManager.ts`** (the plugin federation store). It already consumes the kernel's runtime-profile gating helpers; only the gating predicate is kernel-relevant, the plugin-loading machinery is admin.
- **Design tokens** (`app/framework/meta/runtime/theme/tokens.ts`).

## Staged follow-ups (not in this extraction)

This first cut intentionally extracts only the provably zero-admin-dep seam.
The remaining pieces from the analysis are staged so each is independently
reviewable and so the highest-risk / highest-contention moves don't block the
foundation:

1. **BlockRenderer dispatcher → kernel.** Requires resolving the
   `profile.blockRenderers` vs global `BlockRegistry` fallback relationship and
   injecting the registry + custom-block loader into the kernel rather than
   importing the admin singletons. Touches the hot render path → needs full
   build + render goldens, best done in isolation.
2. **Design tokens → kernel.** `tokens.ts` is pure data and kernel-clean, but it
   is an active contention point with the UX design-system token sweep; move it
   once that work settles to avoid path-rename conflicts.
3. **FederationManager profile-gating predicate → kernel.** Extract just the
   gating, leave the plugin-loading store in admin.

## Consumption

Source-direct during the monorepo phase (`main: ./index.ts`), consumed via
`workspace:*` and resolved by the pnpm workspace symlink (no bundler alias).
After a repo split these become the real package exports.

```ts
import {
  getRuntimeProfileFromPathname,
  isAnonymousRuntimeProfile,
  profileRegistry,
  useProfileSafe,
  BlockErrorBoundary,
  type RenderProfile,
} from '@auraboot/runtime-kernel';
```
