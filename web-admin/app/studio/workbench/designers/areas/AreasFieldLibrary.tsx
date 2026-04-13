/**
 * Areas Field Library
 *
 * Field library panel adapted for AreasDesigner.
 * Provides ViewModel selection and draggable fields.
 * Supports CUSTOM API mode with auto-detected fields.
 */

import React, { useMemo, useState, useEffect } from 'react';
import { useDraggable } from '@dnd-kit/core';
import { useFieldLibrary } from '~/studio/hooks/fields/useFieldLibrary';
import { useViewModelSelector } from '~/studio/hooks/viewmodel/useViewModelSelector';
import { DRAG_TYPES } from '~/studio/workbench/constants';
import { DATA_TYPE_COMPONENT_MAP, type MetaFieldDTO } from '~/studio/workbench/panels/fields/types';
import type { ApiDetectedField } from '~/studio/hooks/fields/useApiSchemaDetection';

export interface AreasFieldLibraryProps {
  viewModelCode: string | null;
  onViewModelChange: (code: string | null) => void;
  readonly?: boolean;
  isCustomApiMode?: boolean;
  apiDetectedFields?: ApiDetectedField[];
  apiLoading?: boolean;
  apiError?: string | null;
  apiConnected?: boolean;
  apiDataSource?: { type?: string; endpoint?: string; method?: string };
  dsl?: any;
}

/**
 * Draggable field item for AreasDesigner
 */
interface DraggableFieldProps {
  field: MetaFieldDTO;
  disabled?: boolean;
}

const DATA_TYPE_ICONS: Record<string, string> = {
  STRING: 'Aa',
  TEXT: 'Tx',
  INTEGER: '#',
  DECIMAL: '#.#',
  BOOLEAN: '?',
  DATE: 'D',
  DATETIME: 'DT',
  ENUM: 'E',
  REFERENCE: 'R',
  JSON: '{}',
  EMAIL: '@',
  PHONE: 'Ph',
  URL: '///',
};

const API_TYPE_ICONS: Record<string, string> = {
  string: 'Aa',
  number: '#',
  boolean: '?',
  datetime: 'DT',
  object: '{}',
  array: '[]',
  unknown: '?',
};

const DraggableField: React.FC<DraggableFieldProps> = ({ field, disabled }) => {
  const [isClient, setIsClient] = useState(false);

  useEffect(() => {
    setIsClient(true);
  }, []);

  const componentConfig = useMemo(() => {
    const mapping = DATA_TYPE_COMPONENT_MAP[field.dataType] || DATA_TYPE_COMPONENT_MAP.STRING;
    return {
      type: mapping.type,
      name: field.displayName || field.code,
      props: {
        label: field.displayName || field.code,
        name: field.code,
        placeholder: field.displayName || field.code,
        required: field.required || false,
        _fieldMeta: {
          pid: field.pid,
          code: field.code,
          dataType: field.dataType,
        },
      },
    };
  }, [field]);

  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id: `field-${field.pid}`,
    data: {
      type: DRAG_TYPES.PALETTE_ITEM,
      component: componentConfig,
    },
    disabled: disabled || !isClient,
  });

  const dataTypeIcon = DATA_TYPE_ICONS[field.dataType] || '?';

  return (
    <div
      ref={setNodeRef}
      {...(isClient ? attributes : {})}
      {...(isClient ? listeners : {})}
      className={`flex cursor-grab items-center gap-2 rounded-md border px-2.5 py-1.5 transition-all duration-150 select-none active:cursor-grabbing ${
        isDragging
          ? 'scale-[1.02] border-blue-400 bg-blue-50 opacity-50 shadow-lg'
          : disabled
            ? 'cursor-not-allowed border-gray-100 bg-gray-50 opacity-50'
            : 'border-gray-200 bg-white hover:border-blue-300 hover:bg-blue-50/50 hover:shadow-sm'
      } `}
      title={`拖拽到 Block 中添加字段`}
    >
      <span className="flex h-6 w-6 flex-shrink-0 items-center justify-center rounded bg-gray-100 font-mono text-[10px] text-gray-600">
        {dataTypeIcon}
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-1">
          <span className="truncate text-xs font-medium text-gray-800">
            {field.displayName || field.code}
          </span>
          {field.required && <span className="text-[10px] text-red-500">*</span>}
        </div>
        <span className="block truncate text-[10px] text-gray-400">{field.code}</span>
      </div>
    </div>
  );
};

/* ─── ApiFieldItem: draggable API-detected field ─── */

function ApiFieldItem({
  field,
  inUse,
  readonly,
}: {
  field: ApiDetectedField;
  inUse: boolean;
  readonly?: boolean;
}) {
  const [isClient, setIsClient] = useState(false);

  useEffect(() => {
    setIsClient(true);
  }, []);

  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id: `api-field:${field.key}`,
    data: {
      type: DRAG_TYPES.PALETTE_ITEM,
      component: {
        type: 'field',
        name: field.key,
        props: { label: field.key, name: field.key },
      },
    },
    disabled: readonly || !isClient,
  });

  const typeIcon = API_TYPE_ICONS[field.inferredType] || '?';

  return (
    <div
      ref={setNodeRef}
      {...(isClient ? attributes : {})}
      {...(isClient ? listeners : {})}
      className={`mb-1 flex cursor-grab items-center justify-between rounded px-2 py-1.5 text-xs select-none ${
        isDragging ? 'opacity-50' : ''
      } ${inUse ? 'border-l-2 border-green-500 bg-green-50' : 'border-l-2 border-gray-200 bg-white'}`}
      data-testid={`api-field-${field.key}`}
    >
      <div className="flex items-center gap-1.5">
        <span className="flex h-5 w-5 flex-shrink-0 items-center justify-center rounded bg-gray-100 font-mono text-[10px] text-gray-500">
          {typeIcon}
        </span>
        <span className={inUse ? 'text-gray-900' : 'text-gray-500'}>{field.key}</span>
      </div>
      <span className={`text-[10px] ${inUse ? 'text-green-600' : 'text-gray-400'}`}>
        {inUse ? 'in use' : field.inferredType}
      </span>
    </div>
  );
}

/* ─── CustomApiFieldPanel: CUSTOM mode field library ─── */

function CustomApiFieldPanel({
  apiDetectedFields = [],
  apiLoading,
  apiError,
  apiConnected,
  apiDataSource,
  dsl,
  readonly,
}: AreasFieldLibraryProps) {
  const [searchQuery, setSearchQuery] = useState('');

  // Collect used fields from DSL blocks
  const usedFields = useMemo(() => {
    if (!dsl?.areas) return new Set<string>();
    const used = new Set<string>();
    Object.values(dsl.areas as Record<string, any>).forEach((area: any) => {
      area?.blocks?.forEach((block: any) => {
        if (block.table?.columns) {
          block.table.columns.forEach((col: any) => {
            if (typeof col === 'string') used.add(col);
            else if (col.field) used.add(col.field);
          });
        }
        if (Array.isArray(block.columns)) {
          block.columns.forEach((col: any) => {
            if (typeof col === 'string') used.add(col);
            else if (col.field) used.add(col.field);
          });
        }
      });
    });
    return used;
  }, [dsl]);

  // Filter by search
  const filteredFields = useMemo(() => {
    if (!searchQuery.trim()) return apiDetectedFields;
    const q = searchQuery.toLowerCase();
    return apiDetectedFields.filter((f) => f.key.toLowerCase().includes(q));
  }, [apiDetectedFields, searchQuery]);

  const inUseFields = filteredFields.filter((f) => usedFields.has(f.key));
  const availableFields = filteredFields.filter((f) => !usedFields.has(f.key));

  return (
    <div className="flex h-full flex-col" data-testid="api-field-panel">
      {/* API connection status */}
      <div className="border-b border-gray-200 px-3 py-2">
        <div className="flex items-center gap-2 text-xs text-gray-500">
          <span className="font-medium">Data Source</span>
          {apiConnected ? (
            <span className="rounded bg-green-100 px-1.5 py-0.5 text-green-700">Connected</span>
          ) : apiError ? (
            <span className="rounded bg-red-100 px-1.5 py-0.5 text-red-700">Error</span>
          ) : null}
        </div>
        <div className="mt-1 truncate font-mono text-xs text-gray-400">
          {apiDataSource?.method || 'get'} {apiDataSource?.endpoint || 'No endpoint'}
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
            placeholder="Search fields..."
            className="w-full rounded-md border border-gray-200 bg-gray-50 py-1.5 pr-3 pl-8 text-xs transition-colors focus:border-blue-500 focus:bg-white focus:ring-1 focus:ring-blue-500 focus:outline-none"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
          />
        </div>
      </div>

      {/* Field list */}
      <div className="flex-1 overflow-y-auto px-3 py-2">
        {apiLoading ? (
          <div className="flex items-center justify-center py-8">
            <div className="h-5 w-5 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
            <span className="ml-2 text-xs text-gray-500">Detecting fields...</span>
          </div>
        ) : apiError && !apiConnected ? (
          <div className="space-y-2 px-1 py-4">
            <div className="text-center text-xs text-red-500">{apiError}</div>
          </div>
        ) : filteredFields.length === 0 ? (
          <div className="px-2 py-6 text-center">
            {searchQuery ? (
              <>
                <p className="text-xs text-gray-500">No fields match your search</p>
                <button
                  onClick={() => setSearchQuery('')}
                  className="mt-1 text-[10px] text-blue-500 hover:text-blue-600"
                >
                  Clear search
                </button>
              </>
            ) : (
              <>
                <div className="mx-auto mb-2 flex h-10 w-10 items-center justify-center rounded-full bg-blue-50">
                  <span className="text-lg">🔌</span>
                </div>
                <p className="text-xs text-gray-500">No fields detected</p>
                <p className="mt-1 text-[10px] text-gray-400">
                  Configure a data source endpoint to detect available fields
                </p>
              </>
            )}
          </div>
        ) : (
          <div className="space-y-1">
            {inUseFields.length > 0 && (
              <>
                <div className="px-1 pt-1 pb-0.5 text-[10px] font-medium tracking-wide text-gray-400 uppercase">
                  In Use ({inUseFields.length})
                </div>
                {inUseFields.map((field) => (
                  <ApiFieldItem key={field.key} field={field} inUse={true} readonly={readonly} />
                ))}
              </>
            )}
            {availableFields.length > 0 && (
              <>
                <div className="px-1 pt-2 pb-0.5 text-[10px] font-medium tracking-wide text-gray-400 uppercase">
                  Available ({availableFields.length})
                </div>
                {availableFields.map((field) => (
                  <ApiFieldItem key={field.key} field={field} inUse={false} readonly={readonly} />
                ))}
              </>
            )}
          </div>
        )}
      </div>

      {/* Manual field add */}
      <div className="border-t border-gray-200 px-3 py-2">
        <input
          type="text"
          placeholder="Add field manually..."
          className="w-full rounded border border-gray-200 bg-white px-2 py-1 text-xs text-gray-700 placeholder-gray-400"
          disabled={readonly}
        />
      </div>

      {/* Footer stats */}
      {!apiLoading && filteredFields.length > 0 && (
        <div className="border-t border-gray-100 bg-gray-50/80 px-3 py-1.5">
          <div className="text-[10px] text-gray-400">
            {filteredFields.length} fields ({inUseFields.length} in use)
          </div>
        </div>
      )}
    </div>
  );
}

/* ─── ModelFieldLibrary: original ViewModel-based field library ─── */

const ModelFieldLibrary: React.FC<AreasFieldLibraryProps> = ({
  viewModelCode,
  onViewModelChange,
  readonly,
}) => {
  const { viewModels, loading: vmLoading } = useViewModelSelector();
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
  } = useFieldLibrary({ viewModelCode: viewModelCode || undefined });

  return (
    <div className="flex h-full flex-col">
      {/* ViewModel Selector */}
      <div className="border-b border-gray-200 px-3 py-2">
        <label className="mb-1 block text-xs font-medium text-gray-500">数据模型</label>
        <select
          value={viewModelCode ?? ''}
          onChange={(e) => onViewModelChange(e.target.value || null)}
          disabled={vmLoading}
          className="w-full rounded-md border border-gray-200 px-2 py-1.5 text-xs focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none"
        >
          <option value="">-- 选择模型 --</option>
          {viewModels.map((vm) => (
            <option key={vm.code} value={vm.code}>
              {vm.displayName || vm.code}
            </option>
          ))}
        </select>
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
        <div className="border-b border-gray-100 px-3 py-1">
          <div className="flex flex-wrap gap-1">
            <button
              onClick={() => setSelectedCategory('all')}
              className={`rounded px-2 py-0.5 text-[10px] ${
                selectedCategory === 'all'
                  ? 'bg-blue-100 text-blue-700'
                  : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
              }`}
            >
              全部
            </button>
            {categories.slice(0, 4).map((cat) => (
              <button
                key={cat.id}
                onClick={() => setSelectedCategory(cat.id)}
                className={`rounded px-2 py-0.5 text-[10px] ${
                  selectedCategory === cat.id
                    ? 'bg-blue-100 text-blue-700'
                    : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
                }`}
              >
                {cat.name}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Field List */}
      <div className="flex-1 overflow-y-auto px-3 py-2">
        {loading && (
          <div className="flex items-center justify-center py-8">
            <div className="h-5 w-5 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
            <span className="ml-2 text-xs text-gray-500">加载中...</span>
          </div>
        )}

        {error && (
          <div className="py-4 text-center">
            <p className="mb-2 text-xs text-red-500">{error}</p>
            <button onClick={refresh} className="text-xs text-blue-600 hover:underline">
              重试
            </button>
          </div>
        )}

        {!loading && !error && filteredFields.length === 0 && (
          <div className="px-2 py-6 text-center">
            {!viewModelCode ? (
              <>
                <div className="mx-auto mb-2 flex h-10 w-10 items-center justify-center rounded-full bg-blue-50">
                  <span className="text-lg">📋</span>
                </div>
                <p className="text-xs text-gray-500">选择一个模型</p>
                <p className="mt-1 text-[10px] text-gray-400">加载可用的字段列表</p>
              </>
            ) : searchQuery ? (
              <>
                <p className="text-xs text-gray-500">未找到匹配的字段</p>
                <button
                  onClick={() => setSearchQuery('')}
                  className="mt-1 text-[10px] text-blue-500 hover:text-blue-600"
                >
                  清除搜索
                </button>
              </>
            ) : (
              <p className="text-xs text-gray-500">该模型暂无可用字段</p>
            )}
          </div>
        )}

        {!loading && !error && filteredFields.length > 0 && (
          <div className="space-y-1.5">
            {filteredFields.map((field) => (
              <DraggableField key={field.pid} field={field} disabled={readonly} />
            ))}
          </div>
        )}
      </div>

      {/* Footer stats */}
      {!loading && filteredFields.length > 0 && (
        <div className="border-t border-gray-100 bg-gray-50/80 px-3 py-1.5">
          <div className="text-[10px] text-gray-400">{filteredFields.length} 个字段</div>
        </div>
      )}
    </div>
  );
};

/* ─── AreasFieldLibrary: top-level wrapper that delegates by mode ─── */

export const AreasFieldLibrary: React.FC<AreasFieldLibraryProps> = (props) => {
  if (props.isCustomApiMode) {
    return <CustomApiFieldPanel {...props} />;
  }
  return <ModelFieldLibrary {...props} />;
};

export default AreasFieldLibrary;
