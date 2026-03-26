import React, { useState, useMemo } from 'react';
import { PaletteItem } from '~/studio/workbench/palette/PaletteItem';
import { componentRegistry } from '~/meta/registry/components';
import type {
  ComponentPaletteProps,
  ComponentType,
  ComponentCategory,
} from '~/studio/workbench/palette/ComponentPalette/types';

export const ComponentPalette: React.FC<ComponentPaletteProps> = ({
  showCategories = true,
  searchable = true,
}) => {
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedCategory, setSelectedCategory] = useState<string>('all');

  // 从注册系统获取组件和分类
  const categories = useMemo(() => componentRegistry.getCategories(), []);
  const allComponents = useMemo(() => {
    return componentRegistry.getAllComponents().map((config) => ({
      type: config.type,
      name: config.name,
      icon: config.icon,
      category: config.category,
      description: config.description,
    }));
  }, []);

  // 过滤组件
  const filteredComponents = useMemo(() => {
    let filtered = allComponents;

    // 按分类过滤
    if (selectedCategory !== 'all') {
      filtered = filtered.filter((comp) => comp.category === selectedCategory);
    }

    // 按搜索关键词过滤
    if (searchQuery.trim()) {
      const query = searchQuery.toLowerCase();
      filtered = filtered.filter(
        (comp) =>
          comp.name.toLowerCase().includes(query) ||
          comp.description?.toLowerCase().includes(query),
      );
    }

    return filtered;
  }, [allComponents, selectedCategory, searchQuery]);

  // 按分类分组组件
  const componentsByCategory = useMemo(() => {
    if (!showCategories || selectedCategory !== 'all') {
      return { [selectedCategory]: filteredComponents };
    }

    const grouped: Record<string, ComponentType[]> = {};
    categories.forEach((category) => {
      grouped[category.id] = filteredComponents.filter((comp) => comp.category === category.id);
    });

    return grouped;
  }, [filteredComponents, categories, showCategories, selectedCategory]);

  return (
    <div className="flex w-64 flex-col border-r border-gray-200 bg-white lg:w-80">
      {/* 头部 */}
      <div className="border-b border-gray-200 p-4">
        <h2 className="text-lg font-semibold text-gray-900">组件库</h2>
        <p className="mt-1 text-sm text-gray-500">拖拽组件到画布</p>
      </div>

      {/* 搜索框 */}
      {searchable && (
        <div className="border-b border-gray-200 p-4">
          <input
            type="text"
            placeholder="搜索组件..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-transparent focus:ring-2 focus:ring-blue-500 focus:outline-none"
          />
        </div>
      )}

      {/* 分类选择 */}
      {showCategories && (
        <div className="border-b border-gray-200 p-4">
          <div className="flex flex-wrap gap-2">
            <button
              onClick={() => setSelectedCategory('all')}
              className={`rounded-full px-3 py-1 text-xs transition-colors ${
                selectedCategory === 'all'
                  ? 'border border-blue-200 bg-blue-100 text-blue-700'
                  : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
              }`}
            >
              全部
            </button>
            {categories.map((category) => (
              <button
                key={category.id}
                onClick={() => setSelectedCategory(category.id)}
                className={`flex items-center gap-1 rounded-full px-3 py-1 text-xs transition-colors ${
                  selectedCategory === category.id
                    ? 'border border-blue-200 bg-blue-100 text-blue-700'
                    : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
                }`}
              >
                <span>{category.icon}</span>
                <span>{category.name}</span>
              </button>
            ))}
          </div>
        </div>
      )}

      {/* 组件列表 */}
      <div className="flex-1 overflow-y-auto p-4">
        {showCategories && selectedCategory === 'all' ? (
          // 按分类显示
          <div className="space-y-6">
            {categories.map((category) => {
              const categoryComponents = componentsByCategory[category.id] || [];
              if (categoryComponents.length === 0) return null;

              return (
                <div key={category.id}>
                  <div className="mb-3 flex items-center gap-2">
                    <span className="text-lg">{category.icon}</span>
                    <h3 className="text-sm font-medium text-gray-900">{category.name}</h3>
                    <span className="text-xs text-gray-500">({categoryComponents.length})</span>
                  </div>
                  <div className="space-y-2">
                    {categoryComponents.map((comp) => (
                      <PaletteItem
                        key={comp.type}
                        type={comp.type}
                        name={comp.name}
                        icon={comp.icon}
                      />
                    ))}
                  </div>
                </div>
              );
            })}
          </div>
        ) : (
          // 平铺显示
          <div className="space-y-2">
            {filteredComponents.map((comp) => (
              <PaletteItem key={comp.type} type={comp.type} name={comp.name} icon={comp.icon} />
            ))}
          </div>
        )}

        {/* 无结果提示 */}
        {filteredComponents.length === 0 && (
          <div className="py-8 text-center">
            <div className="mb-2 text-4xl text-gray-400">🔍</div>
            <p className="text-sm text-gray-500">
              {searchQuery ? '未找到匹配的组件' : '该分类下暂无组件'}
            </p>
          </div>
        )}
      </div>
    </div>
  );
};
