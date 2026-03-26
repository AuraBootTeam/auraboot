/**
 * Floor Component Library
 *
 * Left panel component library with templates for detail and home pages.
 * Detail page templates: detail-section, sub-table, stat-card, timeline, action-buttons
 * Home page templates: welcome-banner, quick-links, stat-cards, recent-list, chart-card
 */

import React, { useState, useMemo } from 'react';
import { useDraggable } from '@dnd-kit/core';
import type { PageKind, DslComponent } from '~/studio/domain/dsl/types';

export interface FloorComponentLibraryProps {
  pageKind: PageKind;
  onAddComponent: (component: Omit<DslComponent, 'id'>) => void;
  readOnly?: boolean;
}

/**
 * Component template categories
 */
type ComponentCategory = 'all' | 'content' | 'data' | 'navigation' | 'chart';

const CATEGORY_CONFIG: Record<ComponentCategory, { label: string; icon: string }> = {
  all: { label: 'All', icon: '' },
  content: { label: 'Content', icon: '📝' },
  data: { label: 'Data', icon: '📊' },
  navigation: { label: 'Navigation', icon: '🔗' },
  chart: { label: 'Chart', icon: '📈' },
};

/**
 * Component template definition
 */
interface ComponentTemplate {
  type: string;
  name: string;
  icon: string;
  description: string;
  category: ComponentCategory;
  availableIn: PageKind[];
  defaultConfig: Record<string, unknown>;
  keywords?: string[];
}

const COMPONENT_TEMPLATES: ComponentTemplate[] = [
  // Detail page components
  {
    type: 'detail-section',
    name: 'Detail Section',
    icon: '📄',
    description: 'Read-only field group display',
    category: 'content',
    availableIn: ['detail'],
    defaultConfig: { columns: 2 },
    keywords: ['detail', 'section', '详情', '区段'],
  },
  {
    type: 'sub-table',
    name: 'Sub Table',
    icon: '📊',
    description: 'Related data sub-table',
    category: 'data',
    availableIn: ['detail'],
    defaultConfig: { pagination: true, pageSize: 10 },
    keywords: ['table', 'sub', '子表', '表格'],
  },
  {
    type: 'stat-card',
    name: 'Stat Card',
    icon: '📈',
    description: 'Key metric statistics card',
    category: 'chart',
    availableIn: ['detail', 'home'],
    defaultConfig: {},
    keywords: ['stat', 'metric', '统计', '指标'],
  },
  {
    type: 'timeline',
    name: 'Timeline',
    icon: '🕐',
    description: 'Timeline view for records or processes',
    category: 'content',
    availableIn: ['detail'],
    defaultConfig: { mode: 'left' },
    keywords: ['timeline', 'history', '时间线', '历史'],
  },
  {
    type: 'action-buttons',
    name: 'Action Buttons',
    icon: '🔘',
    description: 'Page action button group',
    category: 'navigation',
    availableIn: ['detail'],
    defaultConfig: {},
    keywords: ['action', 'button', '操作', '按钮'],
  },

  // Home page components
  {
    type: 'welcome-banner',
    name: 'Welcome Banner',
    icon: '🏠',
    description: 'Welcome area with user info',
    category: 'content',
    availableIn: ['home'],
    defaultConfig: { showAvatar: true, showDate: true },
    keywords: ['welcome', 'banner', '欢迎', '横幅'],
  },
  {
    type: 'quick-links',
    name: 'Quick Links',
    icon: '🔗',
    description: 'Quick navigation to common features',
    category: 'navigation',
    availableIn: ['home'],
    defaultConfig: { columns: 4 },
    keywords: ['quick', 'link', '快捷', '入口'],
  },
  {
    type: 'stat-cards',
    name: 'Stat Cards',
    icon: '📊',
    description: 'Multiple statistics cards',
    category: 'chart',
    availableIn: ['home'],
    defaultConfig: { columns: 4 },
    keywords: ['stat', 'cards', '统计', '卡片组'],
  },
  {
    type: 'recent-list',
    name: 'Recent List',
    icon: '📋',
    description: 'Recent operations or record list',
    category: 'data',
    availableIn: ['home'],
    defaultConfig: { limit: 10 },
    keywords: ['recent', 'list', '最近', '列表'],
  },
  {
    type: 'chart-card',
    name: 'Chart Card',
    icon: '📉',
    description: 'Data visualization chart',
    category: 'chart',
    availableIn: ['home', 'detail'],
    defaultConfig: { chartType: 'line' },
    keywords: ['chart', 'graph', '图表', '可视化'],
  },
];

export const FloorComponentLibrary: React.FC<FloorComponentLibraryProps> = ({
  pageKind: rawPageKind,
  onAddComponent,
  readOnly,
}) => {
  // Normalize to lowercase — DB stores PascalCase (e.g. "Detail") but
  // COMPONENT_TEMPLATES.availableIn uses lowercase ("detail").
  const pageKind = (rawPageKind?.toLowerCase() ?? 'detail') as PageKind;
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedCategory, setSelectedCategory] = useState<ComponentCategory>('all');

  // Get available categories based on page kind
  const availableCategories = useMemo(() => {
    const categories = new Set<ComponentCategory>(['all']);
    COMPONENT_TEMPLATES.forEach((tmpl) => {
      if (tmpl.availableIn.includes(pageKind)) {
        categories.add(tmpl.category);
      }
    });
    return Array.from(categories);
  }, [pageKind]);

  // Filter templates
  const filteredTemplates = useMemo(() => {
    let templates = COMPONENT_TEMPLATES.filter((t) => t.availableIn.includes(pageKind));

    if (selectedCategory !== 'all') {
      templates = templates.filter((t) => t.category === selectedCategory);
    }

    if (searchQuery.trim()) {
      const query = searchQuery.toLowerCase().trim();
      templates = templates.filter((t) => {
        const nameMatch = t.name.toLowerCase().includes(query);
        const descMatch = t.description.toLowerCase().includes(query);
        const keywordMatch = t.keywords?.some((kw) => kw.toLowerCase().includes(query));
        return nameMatch || descMatch || keywordMatch;
      });
    }

    return templates;
  }, [pageKind, searchQuery, selectedCategory]);

  const handleAdd = (template: ComponentTemplate) => {
    if (readOnly) return;
    onAddComponent({
      type: template.type,
      config: template.defaultConfig,
    });
  };

  return (
    <div className="flex h-full flex-col">
      {/* Search */}
      <div className="space-y-3 border-b border-gray-100 p-3">
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
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search components..."
            data-testid="floor-library-search"
            className="w-full rounded-lg border border-gray-200 py-2 pr-3 pl-8 text-sm placeholder-gray-400 focus:border-transparent focus:ring-2 focus:ring-blue-500 focus:outline-none"
          />
          {searchQuery && (
            <button
              onClick={() => setSearchQuery('')}
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

        {/* Category tabs */}
        <div className="flex flex-wrap gap-1">
          {availableCategories.map((cat) => (
            <button
              key={cat}
              onClick={() => setSelectedCategory(cat)}
              className={`rounded-md px-2 py-1 text-xs transition-colors ${
                selectedCategory === cat
                  ? 'bg-blue-100 font-medium text-blue-700'
                  : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
              }`}
            >
              {CATEGORY_CONFIG[cat].icon && (
                <span className="mr-1">{CATEGORY_CONFIG[cat].icon}</span>
              )}
              {CATEGORY_CONFIG[cat].label}
            </button>
          ))}
        </div>
      </div>

      {/* Template list */}
      <div className="flex-1 overflow-auto p-3">
        {filteredTemplates.length === 0 ? (
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
            <p className="text-sm">
              {searchQuery ? 'No matching components found' : 'No components available'}
            </p>
          </div>
        ) : (
          <div className="space-y-2">
            {filteredTemplates.map((template) => (
              <DraggableLibraryItem
                key={template.type}
                template={template}
                onAdd={() => handleAdd(template)}
                readOnly={readOnly}
              />
            ))}
          </div>
        )}
      </div>

      {/* Footer count */}
      <div className="border-t border-gray-100 px-3 py-2 text-xs text-gray-400">
        {filteredTemplates.length} components
      </div>
    </div>
  );
};

/**
 * Draggable library item — can be clicked to add or dragged to a floor
 */
const DraggableLibraryItem: React.FC<{
  template: ComponentTemplate;
  onAdd: () => void;
  readOnly?: boolean;
}> = ({ template, onAdd, readOnly }) => {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id: `library:${template.type}`,
    disabled: readOnly,
    data: {
      type: 'library-component',
      componentConfig: { type: template.type, config: template.defaultConfig },
    },
  });

  return (
    <button
      ref={setNodeRef}
      {...attributes}
      {...listeners}
      onClick={onAdd}
      disabled={readOnly}
      className={`flex w-full items-center gap-3 rounded-lg border p-3 text-left transition-all ${
        isDragging
          ? 'border-blue-300 bg-blue-50 opacity-50 shadow-md'
          : readOnly
            ? 'cursor-not-allowed border-gray-100 bg-gray-50 opacity-50'
            : 'cursor-grab border-gray-200 bg-white hover:border-blue-300 hover:shadow-sm active:cursor-grabbing'
      }`}
    >
      <span className="text-xl">{template.icon}</span>
      <div className="min-w-0 flex-1">
        <div className="text-sm font-medium text-gray-900">{template.name}</div>
        <div className="truncate text-xs text-gray-500">{template.description}</div>
      </div>
      {!readOnly && (
        <svg
          className="h-4 w-4 text-gray-300"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M12 6v6m0 0v6m0-6h6m-6 0H6"
          />
        </svg>
      )}
    </button>
  );
};

export default FloorComponentLibrary;
