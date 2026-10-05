/**
 * Shortcuts Definition
 *
 * All available keyboard shortcuts in the page designer.
 *
 * @since 3.2.0
 */

import { getLocalizedText } from '~/utils/i18n';
import type { ShortcutDefinition, CategoryInfo, ShortcutCategory } from './types';

/**
 * Category definitions
 */
export const CATEGORIES: CategoryInfo[] = [
  {
    id: 'general',
    name: { 'zh-CN': '通用', 'en-US': 'General' },
    icon: 'M13 10V3L4 14h7v7l9-11h-7z',
    order: 1,
  },
  {
    id: 'edit',
    name: { 'zh-CN': '编辑', 'en-US': 'Edit' },
    icon: 'M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z',
    order: 2,
  },
  {
    id: 'canvas',
    name: { 'zh-CN': '画布', 'en-US': 'Canvas' },
    icon: 'M4 5a1 1 0 011-1h14a1 1 0 011 1v2a1 1 0 01-1 1H5a1 1 0 01-1-1V5zM4 13a1 1 0 011-1h6a1 1 0 011 1v6a1 1 0 01-1 1H5a1 1 0 01-1-1v-6zM16 13a1 1 0 011-1h2a1 1 0 011 1v6a1 1 0 01-1 1h-2a1 1 0 01-1-1v-6z',
    order: 3,
  },
  {
    id: 'selection',
    name: { 'zh-CN': '选择', 'en-US': 'Selection' },
    icon: 'M15 15l-2 5L9 9l11 4-5 2zm0 0l5 5M7.188 2.239l.777 2.897M5.136 7.965l-2.898-.777M13.95 4.05l-2.122 2.122m-5.657 5.656l-2.12 2.122',
    order: 4,
  },
  {
    id: 'layout',
    name: { 'zh-CN': '布局', 'en-US': 'Layout' },
    icon: 'M4 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2V6zM14 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2V6zM4 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2v-2zM14 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2v-2z',
    order: 5,
  },
  {
    id: 'navigation',
    name: { 'zh-CN': '导航', 'en-US': 'Navigation' },
    icon: 'M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7',
    order: 6,
  },
];

/**
 * All shortcuts
 */
export const SHORTCUTS: ShortcutDefinition[] = [
  // General
  {
    id: 'save',
    label: { 'zh-CN': '保存', 'en-US': 'Save' },
    description: { 'zh-CN': '保存当前页面', 'en-US': 'Save the current page' },
    keys: [{ key: 'S', ctrl: true }],
    category: 'general',
    tags: ['保存', 'save'],
  },
  {
    id: 'undo',
    label: { 'zh-CN': '撤销', 'en-US': 'Undo' },
    description: { 'zh-CN': '撤销上一步操作', 'en-US': 'Undo the previous action' },
    keys: [{ key: 'Z', ctrl: true }],
    category: 'general',
    tags: ['撤销', 'undo', '返回'],
  },
  {
    id: 'redo',
    label: { 'zh-CN': '重做', 'en-US': 'Redo' },
    description: { 'zh-CN': '重做已撤销的操作', 'en-US': 'Redo the undone action' },
    keys: [{ key: 'Y', ctrl: true }],
    category: 'general',
    tags: ['重做', 'redo', '前进'],
  },
  {
    id: 'redo-alt',
    label: { 'zh-CN': '重做 (备选)', 'en-US': 'Redo (alternative)' },
    description: { 'zh-CN': '重做已撤销的操作', 'en-US': 'Redo the undone action' },
    keys: [{ key: 'Z', ctrl: true, shift: true }],
    category: 'general',
    tags: ['重做', 'redo'],
  },
  {
    id: 'help',
    label: { 'zh-CN': '快捷键帮助', 'en-US': 'Shortcut help' },
    description: { 'zh-CN': '显示快捷键帮助面板', 'en-US': 'Show the shortcut help panel' },
    keys: [{ key: '?', shift: true }],
    category: 'general',
    tags: ['帮助', 'help', '快捷键'],
  },
  {
    id: 'search',
    label: { 'zh-CN': '搜索', 'en-US': 'Search' },
    description: { 'zh-CN': '打开搜索面板', 'en-US': 'Open the search panel' },
    keys: [{ key: 'F', ctrl: true }],
    category: 'general',
    tags: ['搜索', 'search', '查找'],
  },

  // Edit
  {
    id: 'copy',
    label: { 'zh-CN': '复制', 'en-US': 'Copy' },
    description: { 'zh-CN': '复制选中的组件', 'en-US': 'Copy selected components' },
    keys: [{ key: 'C', ctrl: true }],
    category: 'edit',
    tags: ['复制', 'copy'],
  },
  {
    id: 'paste',
    label: { 'zh-CN': '粘贴', 'en-US': 'Paste' },
    description: { 'zh-CN': '粘贴已复制的组件', 'en-US': 'Paste copied components' },
    keys: [{ key: 'V', ctrl: true }],
    category: 'edit',
    tags: ['粘贴', 'paste'],
  },
  {
    id: 'cut',
    label: { 'zh-CN': '剪切', 'en-US': 'Cut' },
    description: { 'zh-CN': '剪切选中的组件', 'en-US': 'Cut selected components' },
    keys: [{ key: 'X', ctrl: true }],
    category: 'edit',
    tags: ['剪切', 'cut'],
  },
  {
    id: 'delete',
    label: { 'zh-CN': '删除', 'en-US': 'Delete' },
    description: { 'zh-CN': '删除选中的组件', 'en-US': 'Delete selected components' },
    keys: [{ key: 'Delete' }, { key: 'Backspace' }],
    category: 'edit',
    tags: ['删除', 'delete', '移除'],
  },
  {
    id: 'duplicate',
    label: { 'zh-CN': '原地复制', 'en-US': 'Duplicate in place' },
    description: { 'zh-CN': '在当前位置复制组件', 'en-US': 'Duplicate components at the current position' },
    keys: [{ key: 'D', ctrl: true }],
    category: 'edit',
    tags: ['复制', 'duplicate', '克隆'],
  },

  // Canvas
  {
    id: 'zoom-in',
    label: { 'zh-CN': '放大', 'en-US': 'Zoom in' },
    description: { 'zh-CN': '放大画布视图', 'en-US': 'Zoom in on the canvas' },
    keys: [{ key: '=', ctrl: true }],
    category: 'canvas',
    tags: ['放大', 'zoom in'],
  },
  {
    id: 'zoom-out',
    label: { 'zh-CN': '缩小', 'en-US': 'Zoom out' },
    description: { 'zh-CN': '缩小画布视图', 'en-US': 'Zoom out on the canvas' },
    keys: [{ key: '-', ctrl: true }],
    category: 'canvas',
    tags: ['缩小', 'zoom out'],
  },
  {
    id: 'zoom-reset',
    label: { 'zh-CN': '重置缩放', 'en-US': 'Reset zoom' },
    description: { 'zh-CN': '重置画布到 100%', 'en-US': 'Reset the canvas to 100%' },
    keys: [{ key: '0', ctrl: true }],
    category: 'canvas',
    tags: ['重置', 'reset', '100%'],
  },
  {
    id: 'zoom-fit',
    label: { 'zh-CN': '适应画布', 'en-US': 'Fit canvas' },
    description: { 'zh-CN': '缩放至适应画布', 'en-US': 'Zoom to fit the canvas' },
    keys: [{ key: '1', ctrl: true }],
    category: 'canvas',
    tags: ['适应', 'fit'],
  },
  {
    id: 'pan',
    label: { 'zh-CN': '平移画布', 'en-US': 'Pan canvas' },
    description: { 'zh-CN': '按住空格键拖拽平移', 'en-US': 'Hold Space and drag to pan' },
    keys: [{ key: 'Space' }],
    category: 'canvas',
    tags: ['平移', 'pan', '拖拽'],
  },

  // Selection
  {
    id: 'select-all',
    label: { 'zh-CN': '全选', 'en-US': 'Select all' },
    description: { 'zh-CN': '选中所有组件', 'en-US': 'Select all components' },
    keys: [{ key: 'A', ctrl: true }],
    category: 'selection',
    tags: ['全选', 'select all'],
  },
  {
    id: 'deselect',
    label: { 'zh-CN': '取消选择', 'en-US': 'Deselect' },
    description: { 'zh-CN': '取消当前选择', 'en-US': 'Clear the current selection' },
    keys: [{ key: 'Escape' }],
    category: 'selection',
    tags: ['取消', 'deselect', '取消选择'],
  },
  {
    id: 'multi-select',
    label: { 'zh-CN': '多选', 'en-US': 'Multiple selection' },
    description: { 'zh-CN': '按住 Shift 点击追加选择', 'en-US': 'Hold Shift and click to add to the selection' },
    keys: [{ key: 'Click', shift: true }],
    category: 'selection',
    tags: ['多选', 'multi-select'],
  },

  // Layout
  {
    id: 'bring-to-front',
    label: { 'zh-CN': '移到顶层', 'en-US': 'Bring to front' },
    description: { 'zh-CN': '将组件移到最顶层', 'en-US': 'Move components to the front' },
    keys: [{ key: ']', ctrl: true, shift: true }],
    category: 'layout',
    tags: ['顶层', 'front', '置顶'],
  },
  {
    id: 'send-to-back',
    label: { 'zh-CN': '移到底层', 'en-US': 'Send to back' },
    description: { 'zh-CN': '将组件移到最底层', 'en-US': 'Move components to the back' },
    keys: [{ key: '[', ctrl: true, shift: true }],
    category: 'layout',
    tags: ['底层', 'back', '置底'],
  },
  {
    id: 'bring-forward',
    label: { 'zh-CN': '上移一层', 'en-US': 'Bring forward' },
    description: { 'zh-CN': '将组件上移一层', 'en-US': 'Move components forward one layer' },
    keys: [{ key: ']', ctrl: true }],
    category: 'layout',
    tags: ['上移', 'forward'],
  },
  {
    id: 'send-backward',
    label: { 'zh-CN': '下移一层', 'en-US': 'Send backward' },
    description: { 'zh-CN': '将组件下移一层', 'en-US': 'Move components backward one layer' },
    keys: [{ key: '[', ctrl: true }],
    category: 'layout',
    tags: ['下移', 'backward'],
  },
  {
    id: 'group',
    label: { 'zh-CN': '组合', 'en-US': 'Group' },
    description: { 'zh-CN': '将选中组件组合', 'en-US': 'Group selected components' },
    keys: [{ key: 'G', ctrl: true }],
    category: 'layout',
    tags: ['组合', 'group'],
  },
  {
    id: 'ungroup',
    label: { 'zh-CN': '取消组合', 'en-US': 'Ungroup' },
    description: { 'zh-CN': '解散组合', 'en-US': 'Dissolve the group' },
    keys: [{ key: 'G', ctrl: true, shift: true }],
    category: 'layout',
    tags: ['取消组合', 'ungroup'],
  },

  // Navigation
  {
    id: 'preview',
    label: { 'zh-CN': '预览', 'en-US': 'Preview' },
    description: { 'zh-CN': '预览当前页面', 'en-US': 'Preview the current page' },
    keys: [{ key: 'P', ctrl: true }],
    category: 'navigation',
    tags: ['预览', 'preview'],
  },
  {
    id: 'toggle-left-panel',
    label: { 'zh-CN': '切换左侧面板', 'en-US': 'Toggle left panel' },
    description: { 'zh-CN': '显示/隐藏左侧面板', 'en-US': 'Show or hide the left panel' },
    keys: [{ key: '\\', ctrl: true }],
    category: 'navigation',
    tags: ['面板', 'panel', '左侧'],
  },
  {
    id: 'toggle-right-panel',
    label: { 'zh-CN': '切换右侧面板', 'en-US': 'Toggle right panel' },
    description: { 'zh-CN': '显示/隐藏右侧面板', 'en-US': 'Show or hide the right panel' },
    keys: [{ key: '/', ctrl: true }],
    category: 'navigation',
    tags: ['面板', 'panel', '右侧'],
  },
];

/**
 * Get shortcuts by category
 */
export function getShortcutsByCategory(category: ShortcutCategory | 'all'): ShortcutDefinition[] {
  if (category === 'all') {
    return SHORTCUTS;
  }
  return SHORTCUTS.filter((s) => s.category === category);
}

/**
 * Search shortcuts
 */
export function searchShortcuts(query: string, locale = 'zh-CN'): ShortcutDefinition[] {
  if (!query.trim()) {
    return SHORTCUTS;
  }

  const normalizedQuery = query.toLowerCase();
  return SHORTCUTS.filter(
    (s) =>
      getLocalizedText(s.label, locale).toLowerCase().includes(normalizedQuery) ||
      getLocalizedText(s.description, locale).toLowerCase().includes(normalizedQuery) ||
      s.tags?.some((t) => t.toLowerCase().includes(normalizedQuery)),
  );
}

/**
 * Format key combination for display
 */
export function formatKeyCombo(keys: ShortcutDefinition['keys'], locale = 'zh-CN'): string {
  const isMac = typeof navigator !== 'undefined' && /Mac/.test(navigator.platform);

  return keys
    .map((key) => {
      const parts: string[] = [];
      if (key.ctrl) parts.push(isMac ? '⌘' : 'Ctrl');
      if (key.shift) parts.push(isMac ? '⇧' : 'Shift');
      if (key.alt) parts.push(isMac ? '⌥' : 'Alt');
      if (key.meta) parts.push(isMac ? '⌘' : 'Win');

      // Format key name
      let keyName = key.key;
      if (keyName === 'Delete') keyName = isMac ? '⌫' : 'Del';
      if (keyName === 'Backspace') keyName = isMac ? '⌫' : 'Backspace';
      if (keyName === 'Escape') keyName = 'Esc';
      if (keyName === 'Space') keyName = isMac ? '␣' : 'Space';
      if (keyName === 'Click') keyName = getLocalizedText({ 'zh-CN': '点击', 'en-US': 'Click' }, locale);

      parts.push(keyName);
      return parts.join(isMac ? '' : '+');
    })
    .join(' / ');
}

export default SHORTCUTS;
