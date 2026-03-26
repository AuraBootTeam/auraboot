import React, { useCallback } from 'react';
import { useFieldLibrary } from '~/studio/hooks/fields/useFieldLibrary';
import { useDesignerStore } from '~/studio/hooks/store/useDesignerStore';
import { FieldItem } from './FieldItem';
import { FieldCategoryFilter } from './FieldCategoryFilter';
import { SEMANTIC_TYPE_INFO, DATA_TYPE_COMPONENT_MAP, type MetaFieldDTO } from './types';
import type { Component } from '~/studio/domain/schema/types';

interface FieldLibraryPanelProps {
  modelPid?: string;
  modelCode?: string;
  viewModelCode?: string;
}

/**
 * Find the next empty position in the grid.
 * Scans row by row, column by column to find an unoccupied cell.
 */
function findNextEmptyPosition(
  components: Component[],
  columns: number = 12,
  maxRows: number = 100,
): { row: number; column: number } {
  // Build a set of occupied positions
  const occupied = new Set<string>();
  components.forEach((comp) => {
    if (comp.position) {
      const span = comp.span || comp.size?.width || 1;
      for (let c = comp.position.column; c < comp.position.column + span && c < columns; c++) {
        occupied.add(`${comp.position.row}-${c}`);
      }
    }
  });

  // Scan for first empty position
  for (let row = 0; row < maxRows; row++) {
    for (let col = 0; col < columns; col++) {
      if (!occupied.has(`${row}-${col}`)) {
        return { row, column: col };
      }
    }
  }

  // Default to next row if all positions are occupied
  const maxRow = Math.max(0, ...components.map((c) => c.position?.row ?? 0));
  return { row: maxRow + 1, column: 0 };
}

/**
 * Resolve component configuration from a field.
 */
function resolveComponentConfig(field: MetaFieldDTO) {
  const mapping = DATA_TYPE_COMPONENT_MAP[field.dataType] || DATA_TYPE_COMPONENT_MAP.STRING;
  const isReadonly = field.virtualType === 'computed_readonly';

  return {
    type: mapping.type,
    name: field.displayName || field.code,
    props: {
      label: field.displayName || field.code,
      name: field.code,
      placeholder: `${field.displayName || field.code}`,
      required: field.required || false,
      disabled: isReadonly,
      readonly: isReadonly,
      ...mapping.defaultProps,
      _fieldMeta: {
        pid: field.pid,
        code: field.code,
        dataType: field.dataType,
        virtualType: field.virtualType,
        computeExpression: field.computeExpression,
      },
    },
    span: 1,
  };
}

/**
 * Field Library Panel.
 * Displays model fields from the backend, grouped by semantic type.
 * Fields are draggable and auto-map to Smart Components on drop.
 *
 * @since 3.1.0
 */
export const FieldLibraryPanel: React.FC<FieldLibraryPanelProps> = ({
  modelPid,
  modelCode,
  viewModelCode,
}) => {
  const {
    filteredFields,
    fieldsByCategory,
    categories,
    loading,
    error,
    searchQuery,
    setSearchQuery,
    selectedCategory,
    setSelectedCategory,
    refresh,
  } = useFieldLibrary({ modelPid, modelCode, viewModelCode });

  const { components, addComponent, selectComponent, pageSchema } = useDesignerStore();

  /**
   * Handle double-click on a field to quickly add it to the canvas.
   */
  const handleFieldDoubleClick = useCallback(
    (field: MetaFieldDTO) => {
      const componentConfig = resolveComponentConfig(field);
      const allComponents = Object.values(components);
      const columns = pageSchema?.layout?.columns || 12;
      const position = findNextEmptyPosition(allComponents, columns);

      const newComponent: Component = {
        id: `comp_${Date.now()}_${field.code}`,
        type: componentConfig.type,
        name: componentConfig.name,
        props: componentConfig.props,
        position,
        size: {
          width: componentConfig.span,
          height: 1,
          span: componentConfig.span,
        },
        span: componentConfig.span,
        children: [],
      };

      addComponent(newComponent);
      selectComponent(newComponent.id);
    },
    [components, pageSchema, addComponent, selectComponent],
  );

  return (
    <div className="flex h-full flex-col">
      {/* Header - compact */}
      <div className="border-b border-gray-200 bg-gray-50/50 px-3 py-2">
        <div className="flex items-center justify-between">
          <p className="text-xs text-gray-500">拖拽或双击添加</p>
          <button
            onClick={refresh}
            className="rounded p-1 text-gray-400 hover:bg-gray-100 hover:text-gray-600"
            title="刷新字段列表"
          >
            <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"
              />
            </svg>
          </button>
        </div>
      </div>

      {/* Search */}
      <div className="px-3 py-2">
        <div className="relative">
          <svg
            className="absolute top-1/2 left-2.5 h-3.5 w-3.5 -translate-y-1/2 text-gray-400"
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
            placeholder="搜索字段..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full rounded-md border border-gray-200 bg-gray-50 py-1.5 pr-3 pl-8 text-xs transition-colors focus:border-blue-500 focus:bg-white focus:ring-1 focus:ring-blue-500 focus:outline-none"
          />
        </div>
      </div>

      {/* Category Filter */}
      {categories.length > 1 && (
        <div className="border-b border-gray-200 px-4 py-2">
          <FieldCategoryFilter
            categories={categories}
            selected={selectedCategory}
            onSelect={setSelectedCategory}
          />
        </div>
      )}

      {/* Field List */}
      <div className="flex-1 overflow-y-auto px-4 py-3">
        {loading && (
          <div className="flex items-center justify-center py-8">
            <div className="h-5 w-5 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
            <span className="ml-2 text-sm text-gray-500">加载中...</span>
          </div>
        )}

        {error && (
          <div className="py-6 text-center">
            <p className="mb-2 text-sm text-red-500">{error}</p>
            <button onClick={refresh} className="text-xs text-blue-600 hover:underline">
              重试
            </button>
          </div>
        )}

        {!loading && !error && filteredFields.length === 0 && (
          <div className="px-4 py-8 text-center">
            {searchQuery ? (
              <>
                <div className="mb-2 text-3xl text-gray-400">🔍</div>
                <p className="text-sm text-gray-500">未找到匹配的字段</p>
                <button
                  onClick={() => setSearchQuery('')}
                  className="mt-2 text-xs text-blue-500 hover:text-blue-600"
                >
                  清除搜索
                </button>
              </>
            ) : !viewModelCode && !modelPid ? (
              <>
                <div className="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-full bg-blue-50">
                  <span className="text-2xl">📋</span>
                </div>
                <p className="mb-1 font-medium text-gray-600">选择数据源</p>
                <p className="text-xs leading-relaxed text-gray-400">
                  在上方选择一个 ViewModel 来<br />
                  加载可用的字段列表
                </p>
              </>
            ) : (
              <>
                <div className="mb-2 text-3xl text-gray-400">📭</div>
                <p className="text-sm text-gray-500">该模型暂无可用字段</p>
              </>
            )}
          </div>
        )}

        {!loading &&
          !error &&
          filteredFields.length > 0 &&
          (selectedCategory === 'all' && !searchQuery ? (
            // Grouped by category
            <div className="space-y-5">
              {categories.map((cat) => {
                const catFields = fieldsByCategory[cat.id] || [];
                if (catFields.length === 0) return null;
                const info = SEMANTIC_TYPE_INFO[cat.id] || { name: cat.id, icon: '📋' };

                return (
                  <div key={cat.id}>
                    <div className="mb-2 flex items-center gap-1.5">
                      <span className="text-sm">{info.icon}</span>
                      <h3 className="text-xs font-medium tracking-wide text-gray-600 uppercase">
                        {info.name}
                      </h3>
                      <span className="text-xs text-gray-400">({catFields.length})</span>
                    </div>
                    <div className="space-y-1.5">
                      {catFields.map((field) => (
                        <FieldItem
                          key={field.pid}
                          field={field}
                          onDoubleClick={handleFieldDoubleClick}
                        />
                      ))}
                    </div>
                  </div>
                );
              })}
            </div>
          ) : (
            // Flat list (filtered or category selected)
            <div className="space-y-1.5">
              {filteredFields.map((field) => (
                <FieldItem key={field.pid} field={field} onDoubleClick={handleFieldDoubleClick} />
              ))}
            </div>
          ))}
      </div>

      {/* Footer stats */}
      {!loading && filteredFields.length > 0 && (
        <div className="border-t border-gray-100 bg-gray-50/80 px-3 py-1.5">
          <div className="flex items-center justify-between text-[10px] text-gray-400">
            <span>{filteredFields.length} 个字段</span>
            {filteredFields.filter((f) => f.virtualType).length > 0 && (
              <span className="text-blue-400">
                {filteredFields.filter((f) => f.virtualType).length} 虚拟
              </span>
            )}
          </div>
        </div>
      )}
    </div>
  );
};
