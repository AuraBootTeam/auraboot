/**
 * Designer Constants
 *
 * 设计器相关常量定义
 */

import type { ThemeConfig } from '~/plugins/core-designer/components/studio/workbench/canvas/types';
import type { ComponentCategory } from '~/plugins/core-designer/components/studio/workbench/palette/ComponentPalette/types';

/**
 * 组件分类
 */
export const COMPONENT_CATEGORIES: ComponentCategory[] = [
  { id: 'layout', name: '布局', icon: 'Layout', description: '布局与栅格组件', order: 1 },
  { id: 'form', name: '表单', icon: 'Form', description: '输入与表单组件', order: 2 },
  { id: 'display', name: '展示', icon: 'Display', description: '文本与媒体展示组件', order: 3 },
  { id: 'navigation', name: '导航', icon: 'Navigation', description: '导航与菜单组件', order: 4 },
  { id: 'feedback', name: '反馈', icon: 'Feedback', description: '反馈与提示组件', order: 5 },
  { id: 'data', name: '数据', icon: 'Data', description: '数据可视化组件', order: 6 },
  { id: 'media', name: '媒体', icon: 'Media', description: '图片与多媒体组件', order: 7 },
  { id: 'other', name: '其他', icon: 'Other', description: '其他通用组件', order: 8 },
];

/**
 * 默认主题配置
 */
export const DEFAULT_THEME: ThemeConfig = {
  primaryColor: '#3B82F6',
  backgroundColor: '#FFFFFF',
  textColor: '#1F2937',
  borderRadius: 8,
  colors: {
    primary: '#3B82F6',
    secondary: '#6B7280',
    success: '#10B981',
    warning: '#F59E0B',
    error: '#EF4444',
    background: '#FFFFFF',
    surface: '#F9FAFB',
    text: '#1F2937',
    textSecondary: '#6B7280',
    border: '#E5E7EB',
  },
  fonts: {
    primary: 'Inter, system-ui, sans-serif',
    mono: 'JetBrains Mono, Consolas, monospace',
  },
  spacing: {
    xs: 4,
    sm: 8,
    md: 16,
    lg: 24,
    xl: 32,
    xxl: 48,
  },
  radiusScale: {
    none: 0,
    sm: 4,
    md: 8,
    lg: 12,
    xl: 16,
    full: 9999,
  },
};
