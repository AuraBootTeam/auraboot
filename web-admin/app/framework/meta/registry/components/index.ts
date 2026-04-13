/**
 * 组件注册系统统一导出
 */

export * from '~/framework/meta/registry/components/ComponentConfig';
export * from '~/framework/meta/registry/components/ComponentRegistry';
export * from '~/framework/meta/registry/components/ComponentConfigs';

import { componentRegistry } from '~/framework/meta/registry/components/ComponentRegistry';
import { ALL_COMPONENT_CONFIGS } from '~/framework/meta/registry/components/ComponentConfigs';

/**
 * 初始化组件注册系统
 * 注册所有Smart组件配置
 */
export const initializeComponentRegistry = () => {
  // 清空现有注册
  componentRegistry.clear();

  // 批量注册所有组件
  componentRegistry.registerBatch(ALL_COMPONENT_CONFIGS);

  // 暴露到window对象供调试使用
  if (typeof window !== 'undefined') {
    (window as any).__COMPONENT_REGISTRY__ = componentRegistry;
  }

  return componentRegistry;
};

// 导出单例实例
export { componentRegistry };
