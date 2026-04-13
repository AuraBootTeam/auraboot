/**
 * FieldPalette — Left panel tab for browsing and dragging model fields
 *
 * - Top dropdown: select a model via useViewModelSelector
 * - Field list: loads fields via useFieldLibrary, each item is draggable
 * - Drag id format: `field:{fieldCode}`
 * - Click fallback: calls onAddField(fieldCode, fieldMeta)
 *
 * @since 4.0.0
 */

import React, { useState, useEffect, useCallback } from 'react';
import { useDraggable } from '@dnd-kit/core';
import { useFieldLibrary } from '~/plugins/core-designer/components/studio/hooks/fields/useFieldLibrary';
import { get } from '~/shared/services/http-client';
import type { MetaFieldDTO } from '~/plugins/core-designer/components/studio/workbench/panels/fields/types';

interface ModelOption {
  code: string;
  name: string;
  modelType: string;
}

/** Load ALL published models (not just VIEW type) */
function useAllModels() {
  const [models, setModels] = useState<ModelOption[]>([]);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await get<{ data: Array<{ code: string; name: string; modelType: string }> }>(
        '/api/meta/models',
        { currentOnly: 'true', pageSize: '500' },
      );
      const raw = result?.data as unknown as { records: Array<{ code: string; name: string; displayName?: string; modelType: string }> };
      const items = raw.records;
      if (Array.isArray(items)) {
        setModels(
          items.map((m: any) => ({
            code: m.code,
            name: m.displayName || m.name || m.code,
            modelType: m.modelType || 'STANDARD',
          })),
        );
      }
    } catch {
      // silently fail — models will be empty
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  return { models, loading };
}

export interface FieldPaletteProps {
  onAddField?: (fieldCode: string, field: MetaFieldDTO) => void;
  modelCode?: string;
  readonly?: boolean;
}

// ─── Field type icon mapping ──────────────────────────────────────────────────

const DATA_TYPE_ICONS: Record<string, string> = {
  STRING: 'Aa',
  TEXT: 'Tx',
  INTEGER: '#',
  DECIMAL: '##',
  BOOLEAN: '?',
  DATE: 'D',
  DATETIME: 'DT',
  ENUM: 'E',
  REFERENCE: 'R',
  JSON: '{}',
  EMAIL: '@',
  PHONE: 'Ph',
  URL: '//',
};

const DATA_TYPE_BADGE_COLORS: Record<string, string> = {
  STRING: 'bg-blue-50 text-blue-600',
  TEXT: 'bg-blue-50 text-blue-600',
  INTEGER: 'bg-orange-50 text-orange-600',
  DECIMAL: 'bg-orange-50 text-orange-600',
  BOOLEAN: 'bg-green-50 text-green-600',
  DATE: 'bg-purple-50 text-purple-600',
  DATETIME: 'bg-purple-50 text-purple-600',
  ENUM: 'bg-yellow-50 text-yellow-600',
  REFERENCE: 'bg-pink-50 text-pink-600',
  JSON: 'bg-gray-100 text-gray-600',
  EMAIL: 'bg-teal-50 text-teal-600',
  PHONE: 'bg-teal-50 text-teal-600',
  URL: 'bg-indigo-50 text-indigo-600',
};

function getTypeIcon(dataType: string): string {
  return DATA_TYPE_ICONS[dataType] ?? '?';
}

function getTypeBadgeColor(dataType: string): string {
  return DATA_TYPE_BADGE_COLORS[dataType] ?? 'bg-gray-100 text-gray-600';
}

// ─── DraggableFieldItem ───────────────────────────────────────────────────────

interface DraggableFieldItemProps {
  field: MetaFieldDTO;
  onAddField?: (fieldCode: string, field: MetaFieldDTO) => void;
  disabled?: boolean;
}

const DraggableFieldItem: React.FC<DraggableFieldItemProps> = ({ field, onAddField, disabled }) => {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id: `field:${field.code}`,
    disabled,
    data: {
      fieldCode: field.code,
      fieldMeta: {
        pid: field.pid,
        code: field.code,
        dataType: field.dataType,
        displayName: field.displayName,
        required: field.required,
      },
    },
  });

  const typeIcon = getTypeIcon(field.dataType);
  const typeBadgeColor = getTypeBadgeColor(field.dataType);

  return (
    <div
      ref={setNodeRef}
      {...listeners}
      {...attributes}
      onClick={() => !disabled && onAddField?.(field.code, field)}
      title={`Click or drag to add field: ${field.displayName || field.code}`}
      className={`flex cursor-pointer items-center gap-2 rounded-md border px-2 py-1.5 transition-all select-none ${
        isDragging
          ? 'border-blue-300 bg-blue-50 opacity-50'
          : disabled
            ? 'cursor-not-allowed border-gray-100 bg-gray-50 opacity-50'
            : 'border-gray-200 bg-white hover:border-blue-300 hover:bg-blue-50/30 hover:shadow-sm active:scale-[0.98]'
      }`}
      data-testid={`field-palette-item-${field.code}`}
    >
      {/* Type icon badge */}
      <span
        className={`flex h-6 w-6 flex-shrink-0 items-center justify-center rounded font-mono text-[10px] font-bold ${typeBadgeColor}`}
      >
        {typeIcon}
      </span>

      {/* Field name + code */}
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-1">
          <span className="truncate text-xs font-medium text-gray-800">
            {field.displayName || field.code}
          </span>
          {field.required && (
            <span className="flex-shrink-0 text-[10px] text-red-500">*</span>
          )}
        </div>
        <span className="block truncate font-mono text-[10px] text-gray-400">{field.code}</span>
      </div>

      {/* Data type badge */}
      <span
        className={`flex-shrink-0 rounded px-1 py-0.5 text-[9px] font-medium uppercase ${typeBadgeColor}`}
      >
        {field.dataType.replace('_', '')}
      </span>
    </div>
  );
};

// ─── FieldPalette ─────────────────────────────────────────────────────────────

export const FieldPalette: React.FC<FieldPaletteProps> = ({ onAddField, modelCode, readonly }) => {
  const [selectedModelCode, setSelectedModelCode] = useState<string | null>(modelCode ?? null);

  // Auto-select model when prop changes
  useEffect(() => {
    if (modelCode && !selectedModelCode) {
      setSelectedModelCode(modelCode);
    }
  }, [modelCode]);

  const { models: allModels, loading: vmLoading } = useAllModels();

  const { filteredFields, loading: fieldsLoading, error, searchQuery, setSearchQuery, refresh } =
    useFieldLibrary({ modelCode: selectedModelCode ?? undefined });

  return (
    <div className="flex h-full flex-col" data-testid="field-palette">
      {/* Model selector */}
      <div className="border-b border-gray-100 px-3 py-2">
        <label className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-gray-400">
          Model
        </label>
        <select
          value={selectedModelCode ?? ''}
          onChange={(e) => setSelectedModelCode(e.target.value || null)}
          disabled={vmLoading || readonly}
          className="w-full rounded border border-gray-200 bg-gray-50 px-2 py-1 text-xs text-gray-700 focus:border-blue-400 focus:bg-white focus:outline-none disabled:opacity-60"
          data-testid="field-palette-model-select"
        >
          <option value="">— Select model —</option>
          {allModels.map((m) => (
            <option key={m.code} value={m.code}>
              {m.name} ({m.modelType.toLowerCase()})
            </option>
          ))}
        </select>
        {vmLoading && (
          <div className="mt-1 flex items-center gap-1 text-[10px] text-gray-400">
            <div className="h-3 w-3 animate-spin rounded-full border border-blue-400 border-t-transparent" />
            Loading models...
          </div>
        )}
      </div>

      {/* Search (only show when model selected) */}
      {selectedModelCode && (
        <div className="border-b border-gray-100 px-3 py-2">
          <div className="relative">
            <svg
              className="absolute top-1/2 left-2 h-3 w-3 -translate-y-1/2 text-gray-400"
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
              placeholder="Search fields..."
              className="w-full rounded border border-gray-200 bg-gray-50 py-1 pr-2 pl-6 text-xs placeholder-gray-400 focus:border-blue-400 focus:bg-white focus:outline-none"
              data-testid="field-palette-search"
            />
          </div>
        </div>
      )}

      {/* Field list */}
      <div className="flex-1 overflow-y-auto px-3 py-2">
        {/* No model selected */}
        {!selectedModelCode && (
          <div className="py-8 text-center">
            <div className="mx-auto mb-2 flex h-10 w-10 items-center justify-center rounded-full bg-blue-50 text-lg">
              📋
            </div>
            <p className="text-xs text-gray-500">Select a model to see fields</p>
            <p className="mt-1 text-[10px] text-gray-400">
              Fields can be dragged into form sections
            </p>
          </div>
        )}

        {/* Loading */}
        {selectedModelCode && fieldsLoading && (
          <div className="flex items-center justify-center py-8">
            <div className="h-5 w-5 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
            <span className="ml-2 text-xs text-gray-500">Loading fields...</span>
          </div>
        )}

        {/* Error */}
        {selectedModelCode && !fieldsLoading && error && (
          <div className="py-4 text-center">
            <p className="mb-2 text-xs text-red-500">{error}</p>
            <button
              onClick={refresh}
              className="text-xs text-blue-600 hover:underline"
            >
              Retry
            </button>
          </div>
        )}

        {/* Fields list */}
        {selectedModelCode && !fieldsLoading && !error && filteredFields.length > 0 && (
          <div className="space-y-1">
            {filteredFields.map((field) => (
              <DraggableFieldItem
                key={field.pid}
                field={field}
                onAddField={onAddField}
                disabled={readonly}
              />
            ))}
          </div>
        )}

        {/* Empty */}
        {selectedModelCode && !fieldsLoading && !error && filteredFields.length === 0 && (
          <div className="py-6 text-center">
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
              <p className="text-xs text-gray-500">No fields available for this model</p>
            )}
          </div>
        )}
      </div>

      {/* Footer */}
      {selectedModelCode && !fieldsLoading && filteredFields.length > 0 && (
        <div className="border-t border-gray-100 px-3 py-1.5 text-[10px] text-gray-400">
          {filteredFields.length} {filteredFields.length === 1 ? 'field' : 'fields'} — click or drag to add
        </div>
      )}
    </div>
  );
};

export default FieldPalette;
