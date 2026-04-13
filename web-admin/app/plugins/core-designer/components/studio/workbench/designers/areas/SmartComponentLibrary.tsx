/**
 * Smart Component Library
 *
 * Draggable library of Smart Components for adding fields to blocks.
 * Integrates with ComponentRegistry to display available components.
 */

import React, { useState, useMemo } from 'react';
import { useDraggable } from '@dnd-kit/core';
import { componentRegistry, initializeComponentRegistry } from '~/meta/registry/components';
import type { ComponentConfig } from '~/meta/registry/components/ComponentConfig';
import { DRAG_TYPES } from '~/plugins/core-designer/components/studio/workbench/constants';

export interface SmartComponentLibraryProps {
  readonly?: boolean;
  onInitialize?: () => void;
}

/**
 * Category display configuration
 */
const CATEGORY_CONFIG: Record<string, { label: string; icon: string }> = {
  all: { label: '全部', icon: '' },
  form: { label: '表单', icon: '📝' },
  display: { label: '展示', icon: '📊' },
  interaction: { label: '交互', icon: '🎯' },
  layout: { label: '布局', icon: '📐' },
  datetime: { label: '日期', icon: '📅' },
  chart: { label: '图表', icon: '📈' },
};

/**
 * Draggable component item
 */
interface DraggableComponentItemProps {
  config: ComponentConfig;
  disabled?: boolean;
}

const DraggableComponentItem: React.FC<DraggableComponentItemProps> = ({ config, disabled }) => {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id: `smart-component:${config.type}`,
    disabled,
    data: {
      type: DRAG_TYPES.PALETTE_ITEM,
      componentType: config.type,
      category: config.category,
      component: {
        type: config.type,
        props: {
          name: config.type, // Will be replaced with actual field name when dropped
          label: config.name,
          ...config.defaultProps,
        },
      },
      // Behavior when dropped
      dragBehavior:
        config.category === 'form' || config.category === 'datetime'
          ? 'add-to-fields'
          : 'create-block',
    },
  });

  return (
    <div
      ref={setNodeRef}
      {...listeners}
      {...attributes}
      className={`flex items-center gap-2 rounded-md border p-2 transition-all ${
        isDragging
          ? 'border-blue-300 bg-blue-50 opacity-50'
          : disabled
            ? 'cursor-not-allowed border-gray-100 bg-gray-50 opacity-50'
            : 'cursor-grab border-gray-200 bg-white hover:border-blue-300 hover:shadow-sm active:cursor-grabbing'
      }`}
    >
      <span className="flex-shrink-0 text-lg">{config.icon}</span>
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm font-medium text-gray-800">{config.name}</div>
        <div className="truncate text-[10px] text-gray-400">{config.description}</div>
      </div>
      {/* Badge for drag behavior */}
      <span
        className={`flex-shrink-0 rounded px-1 py-0.5 text-[9px] ${
          config.category === 'form' || config.category === 'datetime'
            ? 'bg-green-50 text-green-600'
            : 'bg-blue-50 text-blue-600'
        }`}
      >
        {config.category === 'form' || config.category === 'datetime' ? '字段' : '组件'}
      </span>
    </div>
  );
};

/**
 * Search input component
 */
interface SearchInputProps {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  'data-testid'?: string;
}

const SearchInput: React.FC<SearchInputProps> = ({
  value,
  onChange,
  placeholder,
  'data-testid': testId,
}) => {
  return (
    <div className="relative">
      <svg
        className="absolute top-1/2 left-2.5 h-4 w-4 -translate-y-1/2 text-gray-400"
        fill="none"
        stroke="currentColor"
        viewBox="0 0 24 24"
      >
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth={2}
          d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
        />
      </svg>
      <input
        type="text"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder || '搜索组件...'}
        data-testid={testId}
        className="w-full rounded-lg border border-gray-200 py-2 pr-3 pl-8 text-sm placeholder-gray-400 focus:border-transparent focus:ring-2 focus:ring-blue-500 focus:outline-none"
      />
      {value && (
        <button
          onClick={() => onChange('')}
          className="absolute top-1/2 right-2 -translate-y-1/2 p-1 text-gray-400 hover:text-gray-600"
        >
          <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M6 18L18 6M6 6l12 12"
            />
          </svg>
        </button>
      )}
    </div>
  );
};

/**
 * Category filter tabs
 */
interface CategoryTabsProps {
  categories: string[];
  selected: string;
  onSelect: (category: string) => void;
}

const CategoryTabs: React.FC<CategoryTabsProps> = ({ categories, selected, onSelect }) => {
  return (
    <div className="flex flex-wrap gap-1">
      {categories.map((cat) => (
        <button
          key={cat}
          onClick={() => onSelect(cat)}
          className={`rounded-md px-2 py-1 text-xs transition-colors ${
            selected === cat
              ? 'bg-blue-100 font-medium text-blue-700'
              : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
          }`}
        >
          {CATEGORY_CONFIG[cat]?.icon && <span className="mr-1">{CATEGORY_CONFIG[cat].icon}</span>}
          {CATEGORY_CONFIG[cat]?.label || cat}
        </button>
      ))}
    </div>
  );
};

export const SmartComponentLibrary: React.FC<SmartComponentLibraryProps> = ({ readonly }) => {
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedCategory, setSelectedCategory] = useState<string>('all');
  const [isInitialized, setIsInitialized] = useState(false);

  // Initialize registry on mount
  React.useEffect(() => {
    if (!isInitialized) {
      initializeComponentRegistry();
      setIsInitialized(true);
    }
  }, [isInitialized]);

  // Get all components from registry
  const allComponents = useMemo(() => {
    return componentRegistry.getAllComponents();
  }, [isInitialized]);

  // Get available categories
  const availableCategories = useMemo(() => {
    const categories = new Set<string>(['all']);
    allComponents.forEach((config) => {
      categories.add(config.category);
    });
    return Array.from(categories);
  }, [allComponents]);

  // Filter components based on search and category
  const filteredComponents = useMemo(() => {
    let components = allComponents;

    // Filter by category
    if (selectedCategory !== 'all') {
      components = components.filter((config) => config.category === selectedCategory);
    }

    // Filter by search query
    if (searchQuery.trim()) {
      const query = searchQuery.toLowerCase().trim();
      components = components.filter((config) => {
        const nameMatch = config.name.toLowerCase().includes(query);
        const descMatch = (config.description || '').toLowerCase().includes(query);
        const typeMatch = config.type.toLowerCase().includes(query);
        const tagMatch = config.tags?.some((tag) => tag.toLowerCase().includes(query));
        return nameMatch || descMatch || typeMatch || tagMatch;
      });
    }

    return components;
  }, [allComponents, searchQuery, selectedCategory]);

  // Group components by category for display
  const groupedComponents = useMemo(() => {
    if (selectedCategory !== 'all') {
      return { [selectedCategory]: filteredComponents };
    }

    const groups: Record<string, ComponentConfig[]> = {};
    filteredComponents.forEach((config) => {
      if (!groups[config.category]) {
        groups[config.category] = [];
      }
      groups[config.category].push(config);
    });

    return groups;
  }, [filteredComponents, selectedCategory]);

  return (
    <div className="flex h-full flex-col">
      {/* Header with search */}
      <div className="space-y-3 border-b border-gray-100 p-3">
        <SearchInput
          value={searchQuery}
          onChange={setSearchQuery}
          placeholder="搜索组件..."
          data-testid="library-search"
        />
        <CategoryTabs
          categories={availableCategories}
          selected={selectedCategory}
          onSelect={setSelectedCategory}
        />
      </div>

      {/* Component list */}
      <div className="flex-1 overflow-auto p-3">
        {filteredComponents.length === 0 ? (
          <div className="py-8 text-center text-gray-400">
            <svg
              className="mx-auto mb-2 h-10 w-10 text-gray-300"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={1.5}
                d="M9.172 16.172a4 4 0 015.656 0M9 10h.01M15 10h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
              />
            </svg>
            <p className="text-sm">{searchQuery ? '未找到匹配的组件' : '暂无可用组件'}</p>
          </div>
        ) : selectedCategory === 'all' ? (
          // Show grouped components
          <div className="space-y-4">
            {Object.entries(groupedComponents).map(([category, components]) => (
              <div key={category}>
                <h4 className="mb-2 flex items-center gap-1 text-xs font-medium tracking-wider text-gray-500 uppercase">
                  <span>{CATEGORY_CONFIG[category]?.icon}</span>
                  <span>{CATEGORY_CONFIG[category]?.label || category}</span>
                  <span className="text-gray-400">({components.length})</span>
                </h4>
                <div className="space-y-1.5">
                  {components.map((config) => (
                    <DraggableComponentItem key={config.type} config={config} disabled={readonly} />
                  ))}
                </div>
              </div>
            ))}
          </div>
        ) : (
          // Show flat list for single category
          <div className="space-y-1.5">
            {filteredComponents.map((config) => (
              <DraggableComponentItem key={config.type} config={config} disabled={readonly} />
            ))}
          </div>
        )}
      </div>

      {/* Footer with count and hint */}
      <div
        className="space-y-1 border-t border-gray-100 px-3 py-2 text-xs text-gray-400"
        data-testid="library-count"
      >
        <div>共 {filteredComponents.length} 个组件</div>
        <div className="flex items-center gap-2">
          <span className="inline-flex items-center gap-1">
            <span className="h-2 w-2 rounded-full bg-green-400"></span>
            字段 - 添加到表单
          </span>
          <span className="inline-flex items-center gap-1">
            <span className="h-2 w-2 rounded-full bg-blue-400"></span>
            组件 - 创建 Block
          </span>
        </div>
      </div>
    </div>
  );
};

export default SmartComponentLibrary;
