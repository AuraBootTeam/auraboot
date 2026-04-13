/**
 * Schema Utilities
 *
 * Schema 管理相关的工具函数
 */

import type { FormSchema, Block, LayoutConfig, ThemeConfig } from '~/plugins/core-designer/components/studio/domain/schema/types';

/**
 * 创建默认的 Schema
 */
export function createDefaultSchema(): FormSchema {
  return {
    id: `schema_${Date.now()}`,
    kind: 'form',
    name: '新页面',
    title: '新页面',
    description: '使用AuraBoot设计器创建的页面',
    version: '1.0.0',
    components: [],
    layout: createDefaultLayout(),
    theme: createDefaultTheme(),
    metadata: {
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      createdBy: 'system',
      tags: [],
    },
  };
}

/**
 * 创建默认的布局配置
 */
export function createDefaultLayout(): LayoutConfig {
  return {
    type: 'grid',
    columns: 12,
    spacing: 16,
    gap: 16,
    padding: 16,
    responsive: true,
    breakpoints: {
      xs: { minWidth: 0, columns: 1, gap: 8 },
      sm: { minWidth: 576, columns: 2, gap: 12 },
      md: { minWidth: 768, columns: 4, gap: 16 },
      lg: { minWidth: 992, columns: 6, gap: 16 },
      xl: { minWidth: 1200, columns: 8, gap: 20 },
      xxl: { minWidth: 1600, columns: 12, gap: 24 },
    },
  };
}

/**
 * 创建默认的主题配置
 */
export function createDefaultTheme(): ThemeConfig {
  return {
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
}

/**
 * 克隆 Schema
 */
export function cloneSchema(schema: FormSchema): FormSchema {
  return JSON.parse(JSON.stringify(schema));
}

/**
 * 合并 Schema
 */
export function mergeSchema(target: FormSchema, source: Partial<FormSchema>): FormSchema {
  return {
    ...target,
    ...source,
    components: source.components || target.components,
    layout: source.layout ? { ...target.layout, ...source.layout } : target.layout,
    theme: source.theme ? { ...target.theme, ...source.theme } : target.theme,
    metadata: source.metadata ? { ...target.metadata, ...source.metadata } : target.metadata,
  };
}

/**
 * 查找组件
 */
export function findComponent(schema: FormSchema, componentId: string): Block | null {
  const findInComponents = (components: Block[]): Block | null => {
    for (const component of components) {
      if (component.id === componentId) {
        return component;
      }
      if (component.children) {
        const found = findInComponents(component.children);
        if (found) return found;
      }
    }
    return null;
  };

  return findInComponents(schema.components || []);
}

/**
 * 获取组件路径
 */
export function getComponentPath(schema: FormSchema, componentId: string): string[] {
  const path: string[] = [];

  const findPath = (components: Block[], currentPath: string[]): boolean => {
    for (let i = 0; i < components.length; i++) {
      const component = components[i];
      const newPath = [...currentPath, i.toString()];

      if (component.id === componentId) {
        path.push(...newPath);
        return true;
      }

      if (component.children && findPath(component.children, [...newPath, 'children'])) {
        return true;
      }
    }
    return false;
  };

  findPath(schema.components || [], ['components']);
  return path;
}

/**
 * 移除组件
 */
export function removeComponent(schema: FormSchema, componentId: string): FormSchema {
  const newSchema = cloneSchema(schema);

  const removeFromComponents = (components: Block[]): boolean => {
    for (let i = 0; i < components.length; i++) {
      if (components[i].id === componentId) {
        components.splice(i, 1);
        return true;
      }
      if (components[i].children && removeFromComponents(components[i].children!)) {
        return true;
      }
    }
    return false;
  };

  removeFromComponents(newSchema.components || []);
  return newSchema;
}

/**
 * 更新组件
 */
export function updateComponent(
  schema: FormSchema,
  componentId: string,
  updates: Partial<Block>,
): FormSchema {
  const newSchema = cloneSchema(schema);
  const component = findComponent(newSchema, componentId);

  if (component) {
    Object.assign(component, updates);
  }

  return newSchema;
}
