/**
 * @auraboot/runtime-kernel — Public API
 *
 * Product-agnostic multi-runtime frontend kernel, extracted from the
 * `auraboot-app` (web-admin) package so that future independent apps
 * (e.g. a standalone commerce-web storefront) can reuse the SAME kernel
 * rather than drifting into a second copy.
 *
 * Scope (zero admin/product dependencies — see packages/runtime-kernel/README.md):
 *   - Runtime profile model (admin / merchant / storefront / checkout / theme-preview)
 *   - The DSL render-profile SEAM: ProfileRegistry + RenderProfile contract + ProfileContext
 *   - Generic rendering primitive: BlockErrorBoundary
 *
 * Admin-specific implementations (concrete block renderers, the admin/report
 * RenderProfile registrations, the global BlockRegistry, ComponentLoader) stay
 * in `auraboot-app` and register themselves against this kernel's contracts.
 *
 * During the monorepo phase this is a lightweight source-direct re-export
 * (`main: ./index.ts`), consumed via `workspace:*`. After a repo split these
 * become the real npm package exports.
 */

// ── Runtime profiles ───────────────────────────────────────────────────────
export {
  RUNTIME_PROFILES,
  DEFAULT_RUNTIME_PROFILE,
  getDefaultPluginRuntimeProfiles,
  getRuntimeProfileFromPathname,
  isAnonymousRuntimeProfile,
  isPublicRuntimePathname,
  normalizeRuntimePathname,
  shouldBootCorePlugins,
  type RuntimeProfile,
} from './runtime';
export { RuntimeProfileProvider, useRuntimeProfile } from './runtime';

// ── Render-profile seam (registry + contract + context) ──────────────────────
export { profileRegistry } from './profiles/ProfileRegistry';
export { ProfileProvider, useProfile, useProfileSafe } from './profiles/ProfileContext';
export type {
  RenderProfile,
  BlockRendererProps,
  PageContentProps,
  ComponentEntry,
  LayoutConfig,
} from './profiles/types';

// ── Generic rendering primitives ─────────────────────────────────────────────
export { BlockErrorBoundary } from './rendering/BlockErrorBoundary';
