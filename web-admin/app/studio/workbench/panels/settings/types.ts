/**
 * Settings Panel Types
 *
 * Types for the settings panel.
 *
 * @since 3.2.0
 */

/**
 * Settings category
 */
export type SettingsCategory = 'page' | 'editor' | 'appearance' | 'export';

/**
 * Page settings
 */
export interface PageSettings {
  /** Page title */
  title: string;
  /** Page description */
  description: string;
  /** Page tags */
  tags: string[];
  /** Grid columns (for grid mode) */
  gridColumns: number;
  /** Grid gap */
  gridGap: number;
  /** Page padding */
  padding: number;
  /** Enable multi-view tabs (Table/Kanban/Calendar) for list pages */
  enableMultiView: boolean;
}

/**
 * Editor settings
 */
export interface EditorSettings {
  /** Show grid lines */
  showGrid: boolean;
  /** Snap to grid */
  snapToGrid: boolean;
  /** Grid size for snapping */
  gridSize: number;
  /** Show guides */
  showGuides: boolean;
  /** Show rulers */
  showRulers: boolean;
  /** Auto-save interval (seconds) */
  autoSaveInterval: number;
  /** Enable auto-save */
  enableAutoSave: boolean;
  /** Show component borders */
  showComponentBorders: boolean;
  /** Zoom level */
  zoomLevel: number;
}

/**
 * Appearance settings
 */
export interface AppearanceSettings {
  /** Theme mode */
  theme: 'light' | 'dark' | 'system';
  /** Primary color */
  primaryColor: string;
  /** Background color */
  backgroundColor: string;
  /** Canvas background */
  canvasBackground: 'white' | 'light' | 'dots' | 'grid';
  /** Sidebar position */
  sidebarPosition: 'left' | 'right';
  /** Panel width */
  panelWidth: number;
}

/**
 * Export settings
 */
export interface ExportSettings {
  /** Include metadata in export */
  includeMetadata: boolean;
  /** Pretty print JSON */
  prettyPrint: boolean;
  /** Export format */
  exportFormat: 'json' | 'yaml';
  /** Include version history */
  includeVersionHistory: boolean;
}

/**
 * All settings
 */
export interface AllSettings {
  page: PageSettings;
  editor: EditorSettings;
  appearance: AppearanceSettings;
  export: ExportSettings;
}

/**
 * Default settings
 */
export const DEFAULT_SETTINGS: AllSettings = {
  page: {
    title: '',
    description: '',
    tags: [],
    gridColumns: 12,
    gridGap: 16,
    padding: 24,
    enableMultiView: false,
  },
  editor: {
    showGrid: true,
    snapToGrid: true,
    gridSize: 8,
    showGuides: true,
    showRulers: false,
    autoSaveInterval: 30,
    enableAutoSave: true,
    showComponentBorders: true,
    zoomLevel: 100,
  },
  appearance: {
    theme: 'light',
    primaryColor: '#3B82F6',
    backgroundColor: '#F9FAFB',
    canvasBackground: 'dots',
    sidebarPosition: 'left',
    panelWidth: 280,
  },
  export: {
    includeMetadata: true,
    prettyPrint: true,
    exportFormat: 'json',
    includeVersionHistory: false,
  },
};

/**
 * Settings category info
 */
export const SETTINGS_CATEGORY_INFO: Record<
  SettingsCategory,
  { label: string; icon: string; description: string }
> = {
  page: {
    label: '页面设置',
    icon: 'M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z',
    description: '配置页面的基本信息和布局',
  },
  editor: {
    label: '编辑器设置',
    icon: 'M11 4a2 2 0 114 0v1a1 1 0 001 1h3a1 1 0 011 1v3a1 1 0 01-1 1h-1a2 2 0 100 4h1a1 1 0 011 1v3a1 1 0 01-1 1h-3a1 1 0 01-1-1v-1a2 2 0 10-4 0v1a1 1 0 01-1 1H7a1 1 0 01-1-1v-3a1 1 0 00-1-1H4a2 2 0 110-4h1a1 1 0 001-1V7a1 1 0 011-1h3a1 1 0 001-1V4z',
    description: '自定义编辑器行为和辅助功能',
  },
  appearance: {
    label: '外观设置',
    icon: 'M7 21a4 4 0 01-4-4V5a2 2 0 012-2h4a2 2 0 012 2v12a4 4 0 01-4 4zm0 0h12a2 2 0 002-2v-4a2 2 0 00-2-2h-2.343M11 7.343l1.657-1.657a2 2 0 012.828 0l2.829 2.829a2 2 0 010 2.828l-8.486 8.485M7 17h.01',
    description: '主题、颜色和界面布局',
  },
  export: {
    label: '导出设置',
    icon: 'M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z',
    description: '导出格式和内容选项',
  },
};
