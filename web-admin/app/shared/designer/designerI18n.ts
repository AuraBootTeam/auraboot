/**
 * Shared i18n strings for all designer components.
 * Uses LocalizedText format (inline zh_CN / en_US) so no backend changes are needed.
 */

type L = Record<string, string>;

export const DESIGNER_I18N = {
  // ── Empty state ───────────────────────────────────────────────
  emptyState: {
    dragToCanvas: {
      'zh-CN': '从左侧拖拽组件到这里',
      'en-US': 'Drag components from the left panel here',
    } as L,
    orClickToAdd: {
      'zh-CN': '或点击组件添加到画布',
      'en-US': 'Or click a component to add it to the canvas',
    } as L,
    clickToAdd: {
      'zh-CN': '点击左侧面板中的块添加到此处',
      'en-US': 'Click a block from the palette to add it here',
    } as L,
    startDesign: {
      'zh-CN': '从左侧拖拽组件到这里开始设计',
      'en-US': 'Drag components from the left panel to start designing',
    } as L,
    canvasEmpty: {
      'zh-CN': '画布为空',
      'en-US': 'Canvas is empty',
    } as L,
    dragFieldsOrComponents: {
      'zh-CN': '从左侧拖拽字段或组件到此处',
      'en-US': 'Drag fields or components from the left panel here',
    } as L,
  },

  // ── Drop zone ─────────────────────────────────────────────────
  dropZone: {
    dropHere: {
      'zh-CN': '拖拽组件到此处',
      'en-US': 'Drop component here',
    } as L,
    placeHere: {
      'zh-CN': '放置在这里',
      'en-US': 'Place here',
    } as L,
  },

  // ── Widget palette (dashboard) ────────────────────────────────
  palette: {
    title: {
      'zh-CN': '组件库',
      'en-US': 'Components',
    } as L,
    dragHint: {
      'zh-CN': '拖拽组件到画布',
      'en-US': 'Drag components to canvas',
    } as L,
  },

  // ── Studio / Page Designer ────────────────────────────────────
  studio: {
    dragComponent: {
      'zh-CN': '拖拽组件',
      'en-US': 'Drag Components',
    } as L,
    dragComponentDesc: {
      'zh-CN': '从左侧面板拖拽组件到画布',
      'en-US': 'Drag components from the left panel to the canvas',
    } as L,
    useTemplate: {
      'zh-CN': '使用模板',
      'en-US': 'Use Template',
    } as L,
    useTemplateDesc: {
      'zh-CN': '从预设模板快速开始',
      'en-US': 'Quick start from preset templates',
    } as L,
    importPage: {
      'zh-CN': '导入页面',
      'en-US': 'Import Page',
    } as L,
    importPageDesc: {
      'zh-CN': '导入已有的页面配置',
      'en-US': 'Import existing page configuration',
    } as L,
    startDesignTitle: {
      'zh-CN': '开始设计你的{mode}页面',
      'en-US': 'Start designing your {mode} page',
    } as L,
    startDesignDesc: {
      'zh-CN': '{description}。选择下面的方式开始你的设计之旅。',
      'en-US': '{description}. Choose a method below to start your design journey.',
    } as L,
    quickTips: {
      'zh-CN': '快捷提示',
      'en-US': 'Quick Tips',
    } as L,
    tipSave: {
      'zh-CN': '按 Ctrl+S 保存，Ctrl+Z 撤销',
      'en-US': 'Press Ctrl+S to save, Ctrl+Z to undo',
    } as L,
    tipPreview: {
      'zh-CN': '点击预览按钮查看实际效果',
      'en-US': 'Click the preview button to see the actual result',
    } as L,
    tipMultiselect: {
      'zh-CN': '按住 Shift 可多选组件',
      'en-US': 'Hold Shift to multi-select components',
    } as L,
    viewShortcuts: {
      'zh-CN': '查看所有快捷键',
      'en-US': 'View all shortcuts',
    } as L,
    fullscreenMode: {
      'zh-CN': '全屏模式',
      'en-US': 'Fullscreen mode',
    } as L,
    save: {
      'zh-CN': '保存',
      'en-US': 'Save',
    } as L,
    undo: {
      'zh-CN': '撤销',
      'en-US': 'Undo',
    } as L,
  },

  // ── DragCanvas ────────────────────────────────────────────────
  dragCanvas: {
    canvasTitle: {
      'zh-CN': '画布 ({columns} 列)',
      'en-US': 'Canvas ({columns} columns)',
    } as L,
    cols2: {
      'zh-CN': '2列',
      'en-US': '2 cols',
    } as L,
    cols4: {
      'zh-CN': '4列',
      'en-US': '4 cols',
    } as L,
    cols6: {
      'zh-CN': '6列',
      'en-US': '6 cols',
    } as L,
    components: {
      'zh-CN': '组件',
      'en-US': 'Components',
    } as L,
    rows: {
      'zh-CN': '行',
      'en-US': 'Rows',
    } as L,
    conflicts: {
      'zh-CN': '冲突',
      'en-US': 'Conflicts',
    } as L,
    grid: {
      'zh-CN': '网格',
      'en-US': 'Grid',
    } as L,
    gap: {
      'zh-CN': '间距',
      'en-US': 'Gap',
    } as L,
    padding: {
      'zh-CN': '内边距',
      'en-US': 'Padding',
    } as L,
    selected: {
      'zh-CN': '已选中 {count} 个组件',
      'en-US': '{count} component(s) selected',
    } as L,
    selectedIndicator: {
      'zh-CN': '已选中',
      'en-US': 'Selected',
    } as L,
    componentContent: {
      'zh-CN': '组件内容',
      'en-US': 'Component content',
    } as L,
  },

  // ── GridContainer ─────────────────────────────────────────────
  gridContainer: {
    fields: {
      'zh-CN': '字段',
      'en-US': 'fields',
    } as L,
    componentsLabel: {
      'zh-CN': '组件',
      'en-US': 'components',
    } as L,
  },
  // ── AutoSave ──────────────────────────────────────────────────
  autoSave: {
    saving: {
      'zh-CN': '保存中...',
      'en-US': 'Saving...',
    } as L,
    saved: {
      'zh-CN': '已保存',
      'en-US': 'Saved',
    } as L,
    error: {
      'zh-CN': '保存失败',
      'en-US': 'Save failed',
    } as L,
    offline: {
      'zh-CN': '离线模式',
      'en-US': 'Offline',
    } as L,
    unsaved: {
      'zh-CN': '未保存',
      'en-US': 'Unsaved',
    } as L,
    justNow: {
      'zh-CN': '刚刚',
      'en-US': 'just now',
    } as L,
    minutesAgo: {
      'zh-CN': '{n}分钟前',
      'en-US': '{n}m ago',
    } as L,
    hoursAgo: {
      'zh-CN': '{n}小时前',
      'en-US': '{n}h ago',
    } as L,
    saveNow: {
      'zh-CN': '立即保存',
      'en-US': 'Save now',
    } as L,
    noUnsavedChanges: {
      'zh-CN': '没有未保存的更改',
      'en-US': 'No unsaved changes',
    } as L,
    retry: {
      'zh-CN': '重试',
      'en-US': 'Retry',
    } as L,
    retryTooltip: {
      'zh-CN': '重试保存',
      'en-US': 'Retry save',
    } as L,
    offlineHint: {
      'zh-CN': '离线模式，将在网络恢复后自动保存',
      'en-US': 'Offline — will auto-save when connection is restored',
    } as L,
    unsavedChangesWarning: {
      'zh-CN': '您有未保存的更改，确定要离开吗？',
      'en-US': 'You have unsaved changes. Are you sure you want to leave?',
    } as L,
    versionDescription: {
      'zh-CN': '自动保存',
      'en-US': 'Auto save',
    } as L,
  },

  // ── Dashboard viewer ───────────────────────────────────────────
  viewer: {
    noData: {
      'zh-CN': '暂无仪表盘数据',
      'en-US': 'No dashboard data',
    } as L,
    configureHint: {
      'zh-CN': '请在仪表盘设计器中配置概览图表',
      'en-US': 'Configure overview charts in the dashboard designer',
    } as L,
  },
} as const;

/**
 * Helper to resolve a LocalizedText value from DESIGNER_I18N.
 * Supports simple {param} interpolation.
 */
export function resolveDesignerText(
  text: Record<string, string>,
  locale: string,
  params?: Record<string, string | number>,
): string {
  let result = text[locale] || text['en-US'] || text['zh-CN'] || Object.values(text)[0] || '';
  if (params) {
    Object.entries(params).forEach(([key, value]) => {
      result = result.split(`{${key}}`).join(String(value));
    });
  }
  return result;
}
