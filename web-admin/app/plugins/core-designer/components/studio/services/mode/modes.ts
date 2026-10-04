/**
 * Page Mode Configurations
 *
 * Defines the three page modes and their properties.
 *
 * @since 3.2.0
 */

import type { PageMode, PageModeConfig } from './types';

/**
 * All page mode configurations
 */
export const PAGE_MODES: Record<PageMode, PageModeConfig> = {
  floor: {
    mode: 'floor',
    name: 'Floor mode',
    icon: '🏢',
    description: 'For complex business forms, such as order details and customer records',
    structure: {
      levels: ['tab', 'floor', 'block', 'field'],
    },
    capabilities: {
      supportsTabs: true,
      supportsCollapse: true,
      supportsGrid: false,
      supportsFreePosition: false,
      supportsMultiColumn: true,
      maxColumns: 4,
    },
    defaultLayout: {
      type: 'vertical',
      columns: 2,
      gutter: 16,
      padding: 16,
    },
  },
  form: {
    mode: 'form',
    name: 'Form mode',
    icon: '📝',
    description: 'For standard data entry, such as creating customers and editing products',
    structure: {
      levels: ['section', 'field'],
    },
    capabilities: {
      supportsTabs: false,
      supportsCollapse: true,
      supportsGrid: false,
      supportsFreePosition: false,
      supportsMultiColumn: true,
      maxColumns: 4,
    },
    defaultLayout: {
      type: 'vertical',
      columns: 2,
      gutter: 16,
      padding: 24,
    },
  },
  grid: {
    mode: 'grid',
    name: 'Free-flow mode',
    icon: '📊',
    description: 'For dashboards, reports and custom layouts',
    structure: {
      levels: ['cell'],
    },
    capabilities: {
      supportsTabs: false,
      supportsCollapse: false,
      supportsGrid: true,
      supportsFreePosition: true,
      supportsMultiColumn: true,
      maxColumns: 12,
    },
    defaultLayout: {
      type: 'grid',
      columns: 12,
      gutter: 16,
      padding: 16,
    },
  },
};

/**
 * Get mode config by mode
 */
export function getModeConfig(mode: PageMode): PageModeConfig {
  return PAGE_MODES[mode];
}

/**
 * Get all available modes
 */
export function getAllModes(): PageModeConfig[] {
  return Object.values(PAGE_MODES);
}

/**
 * Check if mode supports a capability
 */
export function modeSupports(
  mode: PageMode,
  capability: keyof PageModeConfig['capabilities'],
): boolean {
  const config = PAGE_MODES[mode];
  return !!config.capabilities[capability];
}

/**
 * Get mode by page kind
 */
export function getModeByKind(kind: string): PageMode {
  switch (kind) {
    case 'list':
    case 'detail':
      return 'floor';
    case 'form':
    case 'edit':
    case 'create':
      return 'form';
    default:
      return 'form';
  }
}

/**
 * Form column presets
 */
export const FORM_COLUMN_PRESETS = [
  { columns: 2 as const, label: '2 columns', description: 'Default layout for most forms' },
  { columns: 3 as const, label: '3 columns', description: 'Compact layout for forms with more fields' },
  { columns: 4 as const, label: '4 columns', description: 'Dense layout for dashboard-style forms' },
];

/**
 * Label position options
 */
export const LABEL_POSITIONS = [
  { value: 'top' as const, label: 'Top', description: 'Labels above inputs' },
  { value: 'left' as const, label: 'Left', description: 'Labels to the left of inputs' },
  { value: 'inline' as const, label: 'Inline', description: 'Labels as placeholders' },
];
