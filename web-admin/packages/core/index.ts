/**
 * @auraboot/core — Public API
 *
 * Re-exports shared modules that enterprise/website packages may depend on.
 * During monorepo phase: lightweight re-exports.
 * After repo split: these become the real npm package exports.
 */

// Route manifest
export { coreRoutes } from './route-manifest';

// Shared types
export type { RouteManifest, RouteEntry, Edition } from './types';
