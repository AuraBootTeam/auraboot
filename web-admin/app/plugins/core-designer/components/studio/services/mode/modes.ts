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
    name: '楼层模式',
    icon: '🏢',
    description: '适用于复杂业务表单，如订单详情、客户档案',
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
    name: '表单模式',
    icon: '📝',
    description: '适用于标准数据录入，如新建客户、编辑商品',
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
    name: '自由流模式',
    icon: '📊',
    description: '适用于仪表盘、报表和自定义布局',
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
    case 'home':
      return 'grid';
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
  { columns: 2 as const, label: '2列', description: '默认布局，适合大多数表单' },
  { columns: 3 as const, label: '3列', description: '紧凑布局，适合字段较多的表单' },
  { columns: 4 as const, label: '4列', description: '超紧凑布局，适合仪表盘式表单' },
];

/**
 * Label position options
 */
export const LABEL_POSITIONS = [
  { value: 'top' as const, label: '顶部', description: '标签在输入框上方' },
  { value: 'left' as const, label: '左侧', description: '标签在输入框左侧' },
  { value: 'inline' as const, label: '内联', description: '标签作为占位符' },
];
