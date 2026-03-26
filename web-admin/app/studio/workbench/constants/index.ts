// 页面设计器常量定义

// 布局配置常量
export const LAYOUT_CONFIG = {
  DEFAULT_COLUMNS: 4,
  DEFAULT_GAP: 16,
  DEFAULT_PADDING: 16,
  DEFAULT_MODE: 'auto' as const,
  MAX_NESTING_LEVEL: 3,
  MIN_COMPONENT_HEIGHT: 40,
  DEFAULT_SPAN: 1,
  MAX_SPAN: 4,
};

// 断点配置
export const BREAKPOINTS = {
  MOBILE: {
    name: 'mobile',
    minWidth: 0,
    columns: 1,
    gap: 8,
  },
  TABLET: {
    name: 'tablet',
    minWidth: 640,
    columns: 2,
    gap: 12,
  },
  DESKTOP: {
    name: 'desktop',
    minWidth: 1024,
    columns: 4,
    gap: 16,
  },
};

// 组件类型
export const COMPONENT_TYPES = {
  CONTAINER: 'container',
  INPUT: 'input',
  SELECT: 'select',
  BUTTON: 'button',
  TEXT: 'text',
  IMAGE: 'image',
  DIVIDER: 'divider',
  CARD: 'card',
  TABLE: 'table',
  FORM: 'form',
};

// 组件类别
export const COMPONENT_CATEGORIES = {
  LAYOUT: 'layout',
  FORM: 'form',
  DISPLAY: 'display',
  NAVIGATION: 'navigation',
  DATA: 'data',
};

// 拖拽相关常量
export const DRAG_TYPES = {
  COMPONENT: 'component',
  COMPONENT_ITEM: 'component-item',
  PALETTE_ITEM: 'palette-item',
  GRID_CELL: 'grid-cell',
  HIERARCHY_FIELD: 'hierarchy-field',
  HIERARCHY_BLOCK: 'hierarchy-block',
  HIERARCHY_FLOOR: 'hierarchy-floor',
};

// 命令类型
export const COMMAND_TYPES = {
  ADD_COMPONENT: 'add-component',
  REMOVE_COMPONENT: 'remove-component',
  MOVE_COMPONENT: 'move-component',
  RESIZE_COMPONENT: 'resize-component',
  UPDATE_PROPS: 'update-props',
  REORDER_COMPONENTS: 'reorder-components',
};

// 键盘快捷键
export const KEYBOARD_SHORTCUTS = {
  UNDO: 'ctrl+z',
  REDO: 'ctrl+shift+z',
  DELETE: 'delete',
  COPY: 'ctrl+c',
  PASTE: 'ctrl+v',
  SELECT_ALL: 'ctrl+a',
  SAVE: 'ctrl+s',
  PREVIEW: 'ctrl+p',
};

// 事件类型
export const EVENT_TYPES = {
  COMPONENT_SELECTED: 'component-selected',
  COMPONENT_ADDED: 'component-added',
  COMPONENT_REMOVED: 'component-removed',
  COMPONENT_MOVED: 'component-moved',
  COMPONENT_RESIZED: 'component-resized',
  SCHEMA_UPDATED: 'schema-updated',
  LAYOUT_CHANGED: 'layout-changed',
};

// 默认页面Schema
export const DEFAULT_PAGE_SCHEMA = {
  id: '',
  kind: 'form',
  name: '新页面',
  version: '1.0.0',
  meta: {
    title: '新页面',
    description: '',
    tags: [],
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  },
  layout: {
    type: 'grid',
    columns: LAYOUT_CONFIG.DEFAULT_COLUMNS,
    spacing: LAYOUT_CONFIG.DEFAULT_GAP,
    gap: LAYOUT_CONFIG.DEFAULT_GAP,
    padding: LAYOUT_CONFIG.DEFAULT_PADDING,
    mode: LAYOUT_CONFIG.DEFAULT_MODE,
    breakpoints: {
      xs: { columns: 1, gap: 8 },
      sm: { columns: 2, gap: 12 },
      md: { columns: 4, gap: 16 },
      lg: { columns: 6, gap: 16 },
      xl: { columns: 8, gap: 20 },
    },
  },
  components: [],
  metadata: {
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    createdBy: 'system',
    tags: [],
  },
};
