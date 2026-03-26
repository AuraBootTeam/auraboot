/**
 * AuraBoot Studio Module
 *
 * Page Designer Studio - Main entry point.
 *
 * This module exports all components, hooks, services, and utilities
 * needed for the page designer functionality.
 *
 * @since 3.2.0
 */

// ============================================================
// Services
// ============================================================
export * as StudioServices from './services';

// ============================================================
// Hooks
// ============================================================
export * as StudioHooks from './hooks';

// ============================================================
// Workbench
// ============================================================
export * from './workbench';

// ============================================================
// Version Info
// ============================================================
export const STUDIO_VERSION = '3.2.0';

export const STUDIO_BUILD_INFO = {
  version: STUDIO_VERSION,
  buildDate: '2026-01-26',
  features: [
    'expression-editor',
    'datasource-panel',
    'property-binding',
    'canvas-zoom-pan',
    'clipboard',
    'context-menu',
    'shortcuts',
    'field-binding',
    'three-mode-switching',
    'computed-fields',
    'action-debugger',
    'virtual-list',
    'lazy-loading',
    'memoization',
    'property-editors',
    'search',
    'performance-monitoring',
    'shortcut-help',
    'device-preview',
    'page-preview',
    'page-list-management',
    'new-page-wizard',
    'empty-state-guide',
    'settings-panel',
  ],
};
