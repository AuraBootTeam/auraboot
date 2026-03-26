/**
 * 组件注册系统
 * 管理所有Smart组件的注册、查询和配置
 */

import type { ComponentConfig } from '~/meta/registry/components/ComponentConfig';
import { COMPONENT_CATEGORIES } from '~/meta/registry/components/ComponentConfig';

export class ComponentRegistry {
  private static instance: ComponentRegistry;
  private components: Map<string, ComponentConfig> = new Map();

  static getInstance(): ComponentRegistry {
    if (!ComponentRegistry.instance) {
      ComponentRegistry.instance = new ComponentRegistry();
    }
    return ComponentRegistry.instance;
  }

  /**
   * 注册组件
   */
  register(config: ComponentConfig): void {
    this.components.set(config.type, config);
  }

  /**
   * 批量注册组件
   */
  registerBatch(configs: ComponentConfig[]): void {
    configs.forEach((config) => this.register(config));
  }

  /**
   * 获取单个组件配置
   */
  getComponent(type: string): ComponentConfig | undefined {
    return this.components.get(type);
  }

  /**
   * 获取所有组件
   */
  getAllComponents(): ComponentConfig[] {
    return Array.from(this.components.values());
  }

  /**
   * Get components available for a specific profile.
   * Components with no profiles restriction (undefined or empty array) are included in every profile.
   */
  getComponentsByProfile(profileName: string): ComponentConfig[] {
    return Array.from(this.components.values()).filter(
      (c) => !c.profiles || c.profiles.length === 0 || c.profiles.includes(profileName),
    );
  }

  /**
   * 按分类获取组件
   */
  getComponentsByCategory(category: string): ComponentConfig[] {
    return Array.from(this.components.values()).filter((config) => config.category === category);
  }

  /**
   * 按标签搜索组件
   */
  searchComponents(query: string): ComponentConfig[] {
    const lowerQuery = query.toLowerCase();
    return Array.from(this.components.values()).filter(
      (config) =>
        config.name.toLowerCase().includes(lowerQuery) ||
        (config.description || '').toLowerCase().includes(lowerQuery) ||
        config.tags?.some((tag: string) => tag.toLowerCase().includes(lowerQuery)),
    );
  }

  /**
   * 获取组件分类信息
   */
  getCategories() {
    return COMPONENT_CATEGORIES;
  }

  /**
   * 获取分类下的组件数量
   */
  getCategoryComponentCount(categoryId: string): number {
    return this.getComponentsByCategory(categoryId).length;
  }

  /**
   * 验证组件配置
   */
  validateConfig(config: ComponentConfig): boolean {
    if (!config.type || !config.name || !config.category) {
      return false;
    }

    const validCategories = COMPONENT_CATEGORIES.map((cat) => cat.id);
    if (!validCategories.includes(config.category)) {
      return false;
    }

    return true;
  }

  /**
   * 清空所有注册的组件
   */
  clear(): void {
    this.components.clear();
  }
}

// 导出单例实例
export const componentRegistry = ComponentRegistry.getInstance();
