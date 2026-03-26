/**
 * Binding List Component
 *
 * Displays list of field-component bindings.
 *
 * @since 3.2.0
 */

import React, { useMemo, useState } from 'react';
import { BindingStatus } from './BindingStatus';
import type {
  FieldBindingGroup,
  FieldBinding,
  BindingStatus as BindingStatusType,
} from '~/studio/services/binding';

interface BindingListProps {
  /** Binding groups */
  groups: FieldBindingGroup[];
  /** Selected binding ID */
  selectedId?: string;
  /** On binding select */
  onSelect?: (binding: FieldBinding) => void;
  /** On unbind */
  onUnbind?: (binding: FieldBinding) => void;
  /** On rebind */
  onRebind?: (fieldPath: string) => void;
  /** Show unbound fields */
  showUnbound?: boolean;
  /** Filter by status */
  statusFilter?: BindingStatusType['status'] | 'unbound' | 'all';
}

/**
 * Binding List Component
 */
export const BindingList: React.FC<BindingListProps> = ({
  groups,
  selectedId,
  onSelect,
  onUnbind,
  onRebind,
  showUnbound = true,
  statusFilter = 'all',
}) => {
  const [expandedFields, setExpandedFields] = useState<Set<string>>(new Set());

  const filteredGroups = useMemo(() => {
    let filtered = groups;

    if (!showUnbound) {
      filtered = filtered.filter((g) => g.status !== 'unbound');
    }

    if (statusFilter !== 'all') {
      filtered = filtered.filter((g) => g.status === statusFilter);
    }

    return filtered;
  }, [groups, showUnbound, statusFilter]);

  const toggleExpand = (fieldPath: string) => {
    setExpandedFields((prev) => {
      const next = new Set(prev);
      if (next.has(fieldPath)) {
        next.delete(fieldPath);
      } else {
        next.add(fieldPath);
      }
      return next;
    });
  };

  if (filteredGroups.length === 0) {
    return <div className="p-4 text-center text-sm text-gray-500">没有绑定关系</div>;
  }

  return (
    <div className="divide-y divide-gray-100">
      {filteredGroups.map((group) => (
        <div key={group.fieldPath} className="py-2">
          {/* Field header */}
          <div
            className={`flex cursor-pointer items-center gap-2 rounded-md px-3 py-1.5 transition-colors hover:bg-gray-50 ${group.bindings.length > 1 ? 'cursor-pointer' : ''} `}
            onClick={() => group.bindings.length > 1 && toggleExpand(group.fieldPath)}
          >
            {/* Expand icon */}
            {group.bindings.length > 1 && (
              <svg
                className={`h-3 w-3 text-gray-400 transition-transform ${
                  expandedFields.has(group.fieldPath) ? 'rotate-90' : ''
                }`}
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M9 5l7 7-7 7"
                />
              </svg>
            )}

            {/* Status */}
            <BindingStatus status={group.status} size="sm" />

            {/* Field info */}
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-2">
                <span className="truncate text-sm font-medium text-gray-700">
                  {group.fieldLabel || group.fieldPath}
                </span>
                {group.fieldType && (
                  <span className="rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-400">
                    {group.fieldType}
                  </span>
                )}
              </div>
              <span className="text-xs text-gray-400">{group.fieldPath}</span>
            </div>

            {/* Binding count */}
            {group.bindings.length > 0 && (
              <span className="rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-500">
                {group.bindings.length}
              </span>
            )}

            {/* Actions */}
            {group.status === 'unbound' && onRebind && (
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  onRebind(group.fieldPath);
                }}
                className="text-xs text-blue-600 hover:text-blue-700"
              >
                绑定
              </button>
            )}
          </div>

          {/* Bindings list */}
          {group.bindings.length > 0 &&
            (group.bindings.length === 1 || expandedFields.has(group.fieldPath)) && (
              <div className="mt-1 ml-6 space-y-1">
                {group.bindings.map((binding) => (
                  <BindingItem
                    key={binding.id}
                    binding={binding}
                    isSelected={selectedId === binding.id}
                    onSelect={() => onSelect?.(binding)}
                    onUnbind={() => onUnbind?.(binding)}
                  />
                ))}
              </div>
            )}
        </div>
      ))}
    </div>
  );
};

/**
 * Single binding item
 */
interface BindingItemProps {
  binding: FieldBinding;
  isSelected: boolean;
  onSelect?: () => void;
  onUnbind?: () => void;
}

const BindingItem: React.FC<BindingItemProps> = ({ binding, isSelected, onSelect, onUnbind }) => {
  return (
    <div
      className={`flex cursor-pointer items-center gap-2 rounded-md px-3 py-1.5 transition-colors ${isSelected ? 'border border-blue-200 bg-blue-50' : 'hover:bg-gray-50'} `}
      onClick={onSelect}
    >
      {/* Arrow icon */}
      <svg className="h-3 w-3 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth={2}
          d="M17 8l4 4m0 0l-4 4m4-4H3"
        />
      </svg>

      {/* Component info */}
      <div className="min-w-0 flex-1">
        <span className="block truncate text-xs text-gray-600">{binding.componentId}</span>
        <span className="text-xs text-gray-400">.{binding.propertyPath}</span>
      </div>

      {/* Mode badge */}
      <span
        className={`rounded px-1 py-0.5 text-[10px] ${
          binding.mode === 'two-way' ? 'bg-blue-100 text-blue-600' : 'bg-gray-100 text-gray-500'
        }`}
      >
        {binding.mode === 'two-way' ? '↔' : '→'}
      </span>

      {/* Sync toggle */}
      <span
        className={`h-2 w-2 rounded-full ${binding.syncEnabled ? 'bg-green-400' : 'bg-gray-300'}`}
        title={binding.syncEnabled ? '同步已启用' : '同步已禁用'}
      />

      {/* Unbind button */}
      {onUnbind && (
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            onUnbind();
          }}
          className="p-0.5 text-gray-400 opacity-0 transition-opacity group-hover:opacity-100 hover:text-red-500"
          title="解除绑定"
        >
          <svg className="h-3 w-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
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

export default BindingList;
