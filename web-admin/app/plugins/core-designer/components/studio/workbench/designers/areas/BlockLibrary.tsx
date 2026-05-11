/**
 * Block Library
 *
 * Draggable library of available block types with search and category filtering.
 * Now includes a tab to switch between Blocks and Smart Components.
 */

import React, { useState, useMemo } from 'react';
import { useDraggable } from '@dnd-kit/core';
import type { BlockType, PageKind } from '~/plugins/core-designer/components/studio/domain/dsl/types';
import { SmartComponentLibrary } from './SmartComponentLibrary';
import { useI18n } from '~/contexts/I18nContext';

export interface BlockLibraryProps {
  pageKind: PageKind;
  readonly?: boolean;
  onAddBlock?: (type: BlockType) => void;
}

/**
 * Library tab types
 */
type LibraryTab = 'blocks' | 'components';

/**
 * Block category types
 */
type BlockCategory = 'form' | 'display' | 'layout' | 'chart' | 'all';

/**
 * Category display configuration
 */
const CATEGORY_CONFIG: Record<BlockCategory, { label: Record<'zh-CN' | 'en-US', string>; icon: string }> = {
  all: { label: { 'zh-CN': '全部', 'en-US': 'All' }, icon: '' },
  form: { label: { 'zh-CN': '表单', 'en-US': 'Form' }, icon: '📝' },
  display: { label: { 'zh-CN': '展示', 'en-US': 'Display' }, icon: '📊' },
  layout: { label: { 'zh-CN': '布局', 'en-US': 'Layout' }, icon: '📐' },
  chart: { label: { 'zh-CN': '图表', 'en-US': 'Chart' }, icon: '📈' },
};

/**
 * Block type definitions with category
 */
interface BlockTypeInfo {
  type: BlockType;
  name: string;
  icon: string;
  description: string;
  availableIn: PageKind[];
  category: BlockCategory;
  keywords?: string[];
}

const BLOCK_TYPES: BlockTypeInfo[] = [
  // Form category
  {
    type: 'filters',
    name: '筛选表单',
    icon: '🔍',
    description: '带筛选条件的表单',
    availableIn: ['list'],
    category: 'form',
    keywords: ['filter', 'search', '搜索', '过滤'],
  },
  {
    type: 'form-section',
    name: '表单区段',
    icon: '📝',
    description: '表单字段分组',
    availableIn: ['form', 'list'],
    category: 'form',
    keywords: ['section', '区段', '分组'],
  },
  {
    type: 'form-buttons',
    name: '表单按钮',
    icon: '✅',
    description: '提交/取消等按钮',
    availableIn: ['form'],
    category: 'form',
    keywords: ['button', 'submit', '提交', '按钮'],
  },

  // Display category
  {
    type: 'table',
    name: '数据表格',
    icon: '📊',
    description: '展示数据的表格',
    availableIn: ['list'],
    category: 'display',
    keywords: ['table', 'grid', '表格', '列表'],
  },
  {
    type: 'detail-section',
    name: '详情区段',
    icon: '📄',
    description: '只读详情展示',
    availableIn: ['form', 'list'],
    category: 'display',
    keywords: ['detail', 'view', '详情', '查看'],
  },
  {
    type: 'text',
    name: '文本内容',
    icon: '📃',
    description: '静态文本或说明',
    availableIn: ['list', 'form'],
    category: 'display',
    keywords: ['text', 'content', '文本', '说明'],
  },

  // Layout category
  {
    type: 'toolbar',
    name: '工具栏按钮',
    icon: '🔘',
    description: '操作按钮组',
    availableIn: ['list'],
    category: 'layout',
    keywords: ['toolbar', 'actions', '工具栏', '操作'],
  },
  {
    type: 'selection-info',
    name: '选择信息',
    icon: '☑️',
    description: '显示选中项信息',
    availableIn: ['list'],
    category: 'layout',
    keywords: ['selection', 'selected', '选中', '批量'],
  },

  // Chart category
  {
    type: 'stat-card',
    name: '统计卡片',
    icon: '📈',
    description: '数据统计卡片',
    availableIn: ['list', 'form'],
    category: 'chart',
    keywords: ['stat', 'metric', 'kpi', '统计', '指标'],
  },
  {
    type: 'chart-card',
    name: '图表卡片',
    icon: '📉',
    description: '图表展示',
    availableIn: ['list', 'form'],
    category: 'chart',
    keywords: ['chart', 'graph', 'visualization', '图表', '可视化'],
  },
];

const BLOCK_TYPE_EN: Record<BlockType, { name: string; description: string }> = {
  filters: { name: 'Filter Form', description: 'Form with filter conditions' },
  'form-section': { name: 'Form Section', description: 'Form field group' },
  'form-buttons': { name: 'Form Buttons', description: 'Submit and cancel buttons' },
  table: { name: 'Data Table', description: 'Table for displaying data' },
  'detail-section': { name: 'Detail Section', description: 'Read-only detail display' },
  text: { name: 'Text Content', description: 'Static text or description' },
  toolbar: { name: 'Toolbar Buttons', description: 'Action button group' },
  'selection-info': { name: 'Selection Info', description: 'Show selected item information' },
  'stat-card': { name: 'Stat Card', description: 'Metric summary card' },
  'chart-card': { name: 'Chart Card', description: 'Chart visualization' },
};

function blockName(blockInfo: BlockTypeInfo, locale: string): string {
  return locale === 'zh-CN' ? blockInfo.name : BLOCK_TYPE_EN[blockInfo.type]?.name || blockInfo.name;
}

function blockDescription(blockInfo: BlockTypeInfo, locale: string): string {
  return locale === 'zh-CN'
    ? blockInfo.description
    : BLOCK_TYPE_EN[blockInfo.type]?.description || blockInfo.description;
}

/**
 * Single draggable block item
 */
interface DraggableBlockItemProps {
  blockInfo: BlockTypeInfo;
  disabled?: boolean;
  onAdd?: (type: BlockType) => void;
  locale: string;
}

const DraggableBlockItem: React.FC<DraggableBlockItemProps> = ({
  blockInfo,
  disabled,
  onAdd,
  locale,
}) => {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id: `library:${blockInfo.type}`,
    disabled,
  });

  const handleClick = () => {
    if (!disabled && onAdd) onAdd(blockInfo.type);
  };

  return (
    <div
      ref={setNodeRef}
      {...listeners}
      {...attributes}
      onClick={handleClick}
      data-testid={`block-palette-item-${blockInfo.type}`}
      className={`flex items-center gap-3 rounded-lg border p-3 transition-all ${
        isDragging
          ? 'border-blue-300 bg-blue-50 opacity-50'
          : disabled
            ? 'cursor-not-allowed border-gray-100 bg-gray-50 opacity-50'
            : 'cursor-grab border-gray-200 bg-white hover:border-blue-300 hover:shadow-sm active:cursor-grabbing'
      }`}
    >
      <span className="text-xl">{blockInfo.icon}</span>
      <div className="min-w-0 flex-1">
        <div className="text-sm font-medium text-gray-900">{blockName(blockInfo, locale)}</div>
        <div className="truncate text-xs text-gray-500">{blockDescription(blockInfo, locale)}</div>
      </div>
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
  categories: BlockCategory[];
  selected: BlockCategory;
  onSelect: (category: BlockCategory) => void;
  locale: string;
}

const CategoryTabs: React.FC<CategoryTabsProps> = ({ categories, selected, onSelect, locale }) => {
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
          {CATEGORY_CONFIG[cat].icon && <span className="mr-1">{CATEGORY_CONFIG[cat].icon}</span>}
          {CATEGORY_CONFIG[cat].label[locale === 'zh-CN' ? 'zh-CN' : 'en-US']}
        </button>
      ))}
    </div>
  );
};

export const BlockLibrary: React.FC<BlockLibraryProps> = ({ pageKind: rawPageKind, readonly, onAddBlock }) => {
  const { locale } = useI18n();
  const l = (zh: string, en: string) => (locale === 'zh-CN' ? zh : en);
  // Normalize to lowercase — DB stores PascalCase (e.g. "List") but
  // BLOCK_TYPES.availableIn uses lowercase ("list").
  const pageKind = (rawPageKind?.toLowerCase() ?? 'list') as PageKind;
  const [activeTab, setActiveTab] = useState<LibraryTab>('blocks');
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedCategory, setSelectedCategory] = useState<BlockCategory>('all');

  // Get available categories based on page kind
  const availableCategories = useMemo(() => {
    const categories = new Set<BlockCategory>(['all']);
    BLOCK_TYPES.forEach((block) => {
      if (block.availableIn.includes(pageKind)) {
        categories.add(block.category);
      }
    });
    return Array.from(categories);
  }, [pageKind]);

  // Filter blocks based on page kind, search query, and category
  const filteredBlocks = useMemo(() => {
    let blocks = BLOCK_TYPES.filter((block) => block.availableIn.includes(pageKind));

    // Filter by category
    if (selectedCategory !== 'all') {
      blocks = blocks.filter((block) => block.category === selectedCategory);
    }

    // Filter by search query
    if (searchQuery.trim()) {
      const query = searchQuery.toLowerCase().trim();
      blocks = blocks.filter((block) => {
        const nameMatch = blockName(block, locale).toLowerCase().includes(query);
        const descMatch = blockDescription(block, locale).toLowerCase().includes(query);
        const keywordMatch = block.keywords?.some((kw) => kw.toLowerCase().includes(query));
        return nameMatch || descMatch || keywordMatch;
      });
    }

    return blocks;
  }, [pageKind, searchQuery, selectedCategory, locale]);

  // Group blocks by category for display
  const groupedBlocks = useMemo(() => {
    if (selectedCategory !== 'all') {
      return { [selectedCategory]: filteredBlocks };
    }

    const groups: Record<BlockCategory, BlockTypeInfo[]> = {
      all: [],
      form: [],
      display: [],
      layout: [],
      chart: [],
    };

    filteredBlocks.forEach((block) => {
      groups[block.category].push(block);
    });

    // Remove empty groups
    return Object.fromEntries(
      Object.entries(groups).filter(([key, value]) => key !== 'all' && value.length > 0),
    ) as Record<BlockCategory, BlockTypeInfo[]>;
  }, [filteredBlocks, selectedCategory]);

  return (
    <div className="flex h-full flex-col">
      {/* Tab switcher */}
      <div className="flex border-b border-gray-200">
        <button
          onClick={() => setActiveTab('blocks')}
          data-testid="library-tab-blocks"
          className={`flex-1 px-3 py-2 text-xs font-medium transition-colors ${
            activeTab === 'blocks'
              ? 'border-b-2 border-blue-600 bg-blue-50 text-blue-600'
              : 'text-gray-600 hover:bg-gray-50 hover:text-gray-800'
          }`}
        >
          📦 {l('区块', 'Blocks')}
        </button>
        <button
          onClick={() => setActiveTab('components')}
          data-testid="library-tab-components"
          className={`flex-1 px-3 py-2 text-xs font-medium transition-colors ${
            activeTab === 'components'
              ? 'border-b-2 border-blue-600 bg-blue-50 text-blue-600'
              : 'text-gray-600 hover:bg-gray-50 hover:text-gray-800'
          }`}
        >
          🧩 {l('智能组件', 'Smart Components')}
        </button>
      </div>

      {/* Conditional content based on active tab */}
      {activeTab === 'components' ? (
        <SmartComponentLibrary readonly={readonly} />
      ) : (
        <>
          {/* Header with search */}
          <div className="space-y-3 border-b border-gray-100 p-3">
            <SearchInput
              value={searchQuery}
              onChange={setSearchQuery}
              placeholder={l('搜索区块...', 'Search blocks...')}
              data-testid="library-search"
            />
            <CategoryTabs
              categories={availableCategories}
              selected={selectedCategory}
              onSelect={setSelectedCategory}
              locale={locale}
            />
          </div>

          {/* Block list */}
          <div className="flex-1 overflow-auto p-3">
            {filteredBlocks.length === 0 ? (
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
                  {searchQuery ? l('未找到匹配的区块', 'No matching blocks') : l('暂无可用区块', 'No blocks available')}
                </p>
              </div>
            ) : selectedCategory === 'all' ? (
              // Show grouped blocks
              <div className="space-y-4">
                {Object.entries(groupedBlocks).map(([category, blocks]) => (
                  <div key={category}>
                    <h4 className="mb-2 flex items-center gap-1 text-xs font-medium tracking-wider text-gray-500 uppercase">
                      <span>{CATEGORY_CONFIG[category as BlockCategory].icon}</span>
                      <span>
                        {
                          CATEGORY_CONFIG[category as BlockCategory].label[
                            locale === 'zh-CN' ? 'zh-CN' : 'en-US'
                          ]
                        }
                      </span>
                    </h4>
                    <div className="space-y-2">
                      {blocks.map((blockInfo) => (
                        <DraggableBlockItem
                          key={blockInfo.type}
                          blockInfo={blockInfo}
                          disabled={readonly}
                          onAdd={onAddBlock}
                          locale={locale}
                        />
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              // Show flat list for single category
              <div className="space-y-2">
                {filteredBlocks.map((blockInfo) => (
                  <DraggableBlockItem
                    key={blockInfo.type}
                    blockInfo={blockInfo}
                    disabled={readonly}
                    locale={locale}
                  />
                ))}
              </div>
            )}
          </div>

          {/* Footer with count */}
          <div
            className="border-t border-gray-100 px-3 py-2 text-xs text-gray-400"
            data-testid="library-count"
          >
            {l(`共 ${filteredBlocks.length} 个区块`, `${filteredBlocks.length} block(s)`)}
          </div>
        </>
      )}
    </div>
  );
};

export default BlockLibrary;
