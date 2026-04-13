/**
 * Computed Field List Component
 *
 * List view for computed fields.
 *
 * @since 3.2.0
 */

import React from 'react';
import { getComputedTypeInfo, RETURN_TYPES, type ComputedFieldDefinition } from './types';

interface ComputedFieldListProps {
  /** List of computed fields */
  fields: ComputedFieldDefinition[];
  /** Selected field code */
  selectedCode?: string;
  /** On field select */
  onSelect?: (field: ComputedFieldDefinition) => void;
  /** On field edit */
  onEdit?: (field: ComputedFieldDefinition) => void;
  /** On field delete */
  onDelete?: (field: ComputedFieldDefinition) => void;
  /** On field toggle enabled */
  onToggleEnabled?: (field: ComputedFieldDefinition) => void;
}

/**
 * Computed Field List Component
 */
export const ComputedFieldList: React.FC<ComputedFieldListProps> = ({
  fields,
  selectedCode,
  onSelect,
  onEdit,
  onDelete,
  onToggleEnabled,
}) => {
  if (fields.length === 0) {
    return (
      <div className="p-4 text-center text-sm text-gray-500">
        <div className="mb-2 text-2xl">🔢</div>
        <p>暂无计算字段</p>
        <p className="mt-1 text-xs">点击上方按钮创建新的计算字段</p>
      </div>
    );
  }

  return (
    <div className="divide-y divide-gray-100">
      {fields.map((field) => (
        <ComputedFieldItem
          key={field.code}
          field={field}
          isSelected={selectedCode === field.code}
          onSelect={() => onSelect?.(field)}
          onEdit={() => onEdit?.(field)}
          onDelete={() => onDelete?.(field)}
          onToggleEnabled={() => onToggleEnabled?.(field)}
        />
      ))}
    </div>
  );
};

/**
 * Single computed field item
 */
interface ComputedFieldItemProps {
  field: ComputedFieldDefinition;
  isSelected: boolean;
  onSelect?: () => void;
  onEdit?: () => void;
  onDelete?: () => void;
  onToggleEnabled?: () => void;
}

const ComputedFieldItem: React.FC<ComputedFieldItemProps> = ({
  field,
  isSelected,
  onSelect,
  onEdit,
  onDelete,
  onToggleEnabled,
}) => {
  const typeInfo = getComputedTypeInfo(field.virtualType);
  const returnTypeInfo = RETURN_TYPES.find((t) => t.value === field.returnType);

  return (
    <div
      className={`group cursor-pointer p-3 transition-colors ${isSelected ? 'bg-blue-50' : 'hover:bg-gray-50'} ${!field.enabled ? 'opacity-50' : ''} `}
      onClick={onSelect}
    >
      <div className="flex items-start gap-3">
        {/* Type icon */}
        <div
          className={`flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg text-lg ${typeInfo.persisted ? 'bg-green-100' : 'bg-gray-100'} `}
          title={typeInfo.label}
        >
          {typeInfo.icon}
        </div>

        {/* Content */}
        <div className="min-w-0 flex-1">
          {/* Header */}
          <div className="mb-1 flex items-center gap-2">
            <span className="truncate text-sm font-medium text-gray-900">{field.label}</span>
            <span className="font-mono text-xs text-gray-400">{field.code}</span>
            {returnTypeInfo && (
              <span className="rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-400">
                {returnTypeInfo.icon} {returnTypeInfo.label}
              </span>
            )}
          </div>

          {/* Expression */}
          <div className="mb-1 truncate font-mono text-xs text-gray-500">{field.expression}</div>

          {/* Dependencies */}
          {field.dependencies && field.dependencies.length > 0 && (
            <div className="flex flex-wrap gap-1">
              {field.dependencies.slice(0, 3).map((dep) => (
                <span
                  key={dep}
                  className="rounded bg-blue-50 px-1.5 py-0.5 text-[10px] text-blue-600"
                >
                  #{dep}
                </span>
              ))}
              {field.dependencies.length > 3 && (
                <span className="text-[10px] text-gray-400">+{field.dependencies.length - 3}</span>
              )}
            </div>
          )}
        </div>

        {/* Actions */}
        <div className="flex flex-shrink-0 items-center gap-1 opacity-0 transition-opacity group-hover:opacity-100">
          {/* Toggle enabled */}
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              onToggleEnabled?.();
            }}
            className={`rounded p-1 transition-colors ${field.enabled ? 'text-green-600 hover:bg-green-50' : 'text-gray-400 hover:bg-gray-100'} `}
            title={field.enabled ? '禁用' : '启用'}
          >
            <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              {field.enabled ? (
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                />
              ) : (
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l3.59 3.59m0 0A9.953 9.953 0 0112 5c4.478 0 8.268 2.943 9.543 7a10.025 10.025 0 01-4.132 5.411m0 0L21 21"
                />
              )}
            </svg>
          </button>

          {/* Edit */}
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              onEdit?.();
            }}
            className="rounded p-1 text-gray-400 transition-colors hover:bg-blue-50 hover:text-blue-600"
            title="编辑"
          >
            <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"
              />
            </svg>
          </button>

          {/* Delete */}
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              onDelete?.();
            }}
            className="rounded p-1 text-gray-400 transition-colors hover:bg-red-50 hover:text-red-600"
            title="删除"
          >
            <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
              />
            </svg>
          </button>
        </div>
      </div>
    </div>
  );
};

export default ComputedFieldList;
