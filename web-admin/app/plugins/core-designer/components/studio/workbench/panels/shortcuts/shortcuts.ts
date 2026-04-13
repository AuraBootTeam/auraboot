/**
 * Shortcuts Definition
 *
 * All available keyboard shortcuts in the page designer.
 *
 * @since 3.2.0
 */

import type { ShortcutDefinition, CategoryInfo, ShortcutCategory } from './types';

/**
 * Category definitions
 */
export const CATEGORIES: CategoryInfo[] = [
  {
    id: 'general',
    name: '通用',
    icon: 'M13 10V3L4 14h7v7l9-11h-7z',
    order: 1,
  },
  {
    id: 'edit',
    name: '编辑',
    icon: 'M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z',
    order: 2,
  },
  {
    id: 'canvas',
    name: '画布',
    icon: 'M4 5a1 1 0 011-1h14a1 1 0 011 1v2a1 1 0 01-1 1H5a1 1 0 01-1-1V5zM4 13a1 1 0 011-1h6a1 1 0 011 1v6a1 1 0 01-1 1H5a1 1 0 01-1-1v-6zM16 13a1 1 0 011-1h2a1 1 0 011 1v6a1 1 0 01-1 1h-2a1 1 0 01-1-1v-6z',
    order: 3,
  },
  {
    id: 'selection',
    name: '选择',
    icon: 'M15 15l-2 5L9 9l11 4-5 2zm0 0l5 5M7.188 2.239l.777 2.897M5.136 7.965l-2.898-.777M13.95 4.05l-2.122 2.122m-5.657 5.656l-2.12 2.122',
    order: 4,
  },
  {
    id: 'layout',
    name: '布局',
    icon: 'M4 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2V6zM14 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2V6zM4 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2v-2zM14 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2v-2z',
    order: 5,
  },
  {
    id: 'navigation',
    name: '导航',
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
    label: '保存',
    description: '保存当前页面',
    keys: [{ key: 'S', ctrl: true }],
    category: 'general',
    tags: ['保存', 'save'],
  },
  {
    id: 'undo',
    label: '撤销',
    description: '撤销上一步操作',
    keys: [{ key: 'Z', ctrl: true }],
    category: 'general',
    tags: ['撤销', 'undo', '返回'],
  },
  {
    id: 'redo',
    label: '重做',
    description: '重做已撤销的操作',
    keys: [{ key: 'Y', ctrl: true }],
    category: 'general',
    tags: ['重做', 'redo', '前进'],
  },
  {
    id: 'redo-alt',
    label: '重做 (备选)',
    description: '重做已撤销的操作',
    keys: [{ key: 'Z', ctrl: true, shift: true }],
    category: 'general',
    tags: ['重做', 'redo'],
  },
  {
    id: 'help',
    label: '快捷键帮助',
    description: '显示快捷键帮助面板',
    keys: [{ key: '?', shift: true }],
    category: 'general',
    tags: ['帮助', 'help', '快捷键'],
  },
  {
    id: 'search',
    label: '搜索',
    description: '打开搜索面板',
    keys: [{ key: 'F', ctrl: true }],
    category: 'general',
    tags: ['搜索', 'search', '查找'],
  },

  // Edit
  {
    id: 'copy',
    label: '复制',
    description: '复制选中的组件',
    keys: [{ key: 'C', ctrl: true }],
    category: 'edit',
    tags: ['复制', 'copy'],
  },
  {
    id: 'paste',
    label: '粘贴',
    description: '粘贴已复制的组件',
    keys: [{ key: 'V', ctrl: true }],
    category: 'edit',
    tags: ['粘贴', 'paste'],
  },
  {
    id: 'cut',
    label: '剪切',
    description: '剪切选中的组件',
    keys: [{ key: 'X', ctrl: true }],
    category: 'edit',
    tags: ['剪切', 'cut'],
  },
  {
    id: 'delete',
    label: '删除',
    description: '删除选中的组件',
    keys: [{ key: 'Delete' }, { key: 'Backspace' }],
    category: 'edit',
    tags: ['删除', 'delete', '移除'],
  },
  {
    id: 'duplicate',
    label: '原地复制',
    description: '在当前位置复制组件',
    keys: [{ key: 'D', ctrl: true }],
    category: 'edit',
    tags: ['复制', 'duplicate', '克隆'],
  },

  // Canvas
  {
    id: 'zoom-in',
    label: '放大',
    description: '放大画布视图',
    keys: [{ key: '=', ctrl: true }],
    category: 'canvas',
    tags: ['放大', 'zoom in'],
  },
  {
    id: 'zoom-out',
    label: '缩小',
    description: '缩小画布视图',
    keys: [{ key: '-', ctrl: true }],
    category: 'canvas',
    tags: ['缩小', 'zoom out'],
  },
  {
    id: 'zoom-reset',
    label: '重置缩放',
    description: '重置画布到 100%',
    keys: [{ key: '0', ctrl: true }],
    category: 'canvas',
    tags: ['重置', 'reset', '100%'],
  },
  {
    id: 'zoom-fit',
    label: '适应画布',
    description: '缩放至适应画布',
    keys: [{ key: '1', ctrl: true }],
    category: 'canvas',
    tags: ['适应', 'fit'],
  },
  {
    id: 'pan',
    label: '平移画布',
    description: '按住空格键拖拽平移',
    keys: [{ key: 'Space' }],
    category: 'canvas',
    tags: ['平移', 'pan', '拖拽'],
  },

  // Selection
  {
    id: 'select-all',
    label: '全选',
    description: '选中所有组件',
    keys: [{ key: 'A', ctrl: true }],
    category: 'selection',
    tags: ['全选', 'select all'],
  },
  {
    id: 'deselect',
    label: '取消选择',
    description: '取消当前选择',
    keys: [{ key: 'Escape' }],
    category: 'selection',
    tags: ['取消', 'deselect', '取消选择'],
  },
  {
    id: 'multi-select',
    label: '多选',
    description: '按住 Shift 点击追加选择',
    keys: [{ key: 'Click', shift: true }],
    category: 'selection',
    tags: ['多选', 'multi-select'],
  },

  // Layout
  {
    id: 'bring-to-front',
    label: '移到顶层',
    description: '将组件移到最顶层',
    keys: [{ key: ']', ctrl: true, shift: true }],
    category: 'layout',
    tags: ['顶层', 'front', '置顶'],
  },
  {
    id: 'send-to-back',
    label: '移到底层',
    description: '将组件移到最底层',
    keys: [{ key: '[', ctrl: true, shift: true }],
    category: 'layout',
    tags: ['底层', 'back', '置底'],
  },
  {
    id: 'bring-forward',
    label: '上移一层',
    description: '将组件上移一层',
    keys: [{ key: ']', ctrl: true }],
    category: 'layout',
    tags: ['上移', 'forward'],
  },
  {
    id: 'send-backward',
    label: '下移一层',
    description: '将组件下移一层',
    keys: [{ key: '[', ctrl: true }],
    category: 'layout',
    tags: ['下移', 'backward'],
  },
  {
    id: 'group',
    label: '组合',
    description: '将选中组件组合',
    keys: [{ key: 'G', ctrl: true }],
    category: 'layout',
    tags: ['组合', 'group'],
  },
  {
    id: 'ungroup',
    label: '取消组合',
    description: '解散组合',
    keys: [{ key: 'G', ctrl: true, shift: true }],
    category: 'layout',
    tags: ['取消组合', 'ungroup'],
  },

  // Navigation
  {
    id: 'preview',
    label: '预览',
    description: '预览当前页面',
    keys: [{ key: 'P', ctrl: true }],
    category: 'navigation',
    tags: ['预览', 'preview'],
  },
  {
    id: 'toggle-left-panel',
    label: '切换左侧面板',
    description: '显示/隐藏左侧面板',
    keys: [{ key: '\\', ctrl: true }],
    category: 'navigation',
    tags: ['面板', 'panel', '左侧'],
  },
  {
    id: 'toggle-right-panel',
    label: '切换右侧面板',
    description: '显示/隐藏右侧面板',
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
export function searchShortcuts(query: string): ShortcutDefinition[] {
  if (!query.trim()) {
    return SHORTCUTS;
  }

  const normalizedQuery = query.toLowerCase();
  return SHORTCUTS.filter(
    (s) =>
      s.label.toLowerCase().includes(normalizedQuery) ||
      s.description?.toLowerCase().includes(normalizedQuery) ||
      s.tags?.some((t) => t.toLowerCase().includes(normalizedQuery)),
  );
}

/**
 * Format key combination for display
 */
export function formatKeyCombo(keys: ShortcutDefinition['keys']): string {
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
      if (keyName === 'Click') keyName = '点击';

      parts.push(keyName);
      return parts.join(isMac ? '' : '+');
    })
    .join(' / ');
}

export default SHORTCUTS;
