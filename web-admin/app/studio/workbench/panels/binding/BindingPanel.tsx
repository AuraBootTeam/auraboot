/**
 * Binding Panel Component
 *
 * Main panel for viewing and managing field-component bindings.
 *
 * @since 3.2.0
 */

import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { BindingList } from './BindingList';
import { BindingStatus } from './BindingStatus';
import {
  fieldBindingService,
  bindingValidator,
  type FieldBinding,
  type FieldBindingGroup,
  type BindingChangeEvent,
  type ValidationSummary,
} from '~/studio/services/binding';
import { confirmDialog } from '~/utils/confirmDialog';
import { useToastContext } from '~/contexts/ToastContext';

interface BindingPanelProps {
  /** ViewModel code for context */
  viewModelCode?: string;
  /** Whether panel is visible */
  isVisible?: boolean;
  /** On binding select */
  onBindingSelect?: (binding: FieldBinding) => void;
  /** On component focus request */
  onFocusComponent?: (componentId: string) => void;
}

type ViewMode = 'byField' | 'byComponent';
type StatusFilter = 'all' | 'valid' | 'warning' | 'error' | 'orphan' | 'unbound';

/**
 * Binding Panel Component
 */
export const BindingPanel: React.FC<BindingPanelProps> = ({
  viewModelCode,
  isVisible = true,
  onBindingSelect,
  onFocusComponent,
}) => {
  const { showSuccessToast, showInfoToast } = useToastContext();
  const [viewMode, setViewMode] = useState<ViewMode>('byField');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all');
  const [selectedBindingId, setSelectedBindingId] = useState<string>();
  const [groups, setGroups] = useState<FieldBindingGroup[]>([]);
  const [validation, setValidation] = useState<ValidationSummary | null>(null);
  const [showUnbound, setShowUnbound] = useState(true);

  // Load and refresh bindings
  const refreshBindings = useCallback(() => {
    const bindingGroups = fieldBindingService.getBindingsByField();
    setGroups(bindingGroups);

    const validationResult = bindingValidator.validateAll();
    setValidation(validationResult);
  }, []);

  // Initial load and subscribe to changes
  useEffect(() => {
    if (!isVisible) return;

    refreshBindings();

    const unsubscribe = fieldBindingService.subscribe((event: BindingChangeEvent) => {
      refreshBindings();
    });

    return () => unsubscribe();
  }, [isVisible, refreshBindings]);

  // Handle binding select
  const handleBindingSelect = useCallback(
    (binding: FieldBinding) => {
      setSelectedBindingId(binding.id);
      onBindingSelect?.(binding);
    },
    [onBindingSelect],
  );

  // Handle unbind
  const handleUnbind = useCallback(async (binding: FieldBinding) => {
    if (await confirmDialog({ content: `确定要解除 "${binding.fieldPath}" 与组件的绑定吗？` })) {
      fieldBindingService.unbind(binding.id);
    }
  }, []);

  // Handle unbind all
  const handleUnbindAll = useCallback(async () => {
    if (
      await confirmDialog({ content: '确定要解除所有绑定吗？此操作不可撤销。', variant: 'danger' })
    ) {
      fieldBindingService.clear();
    }
  }, []);

  // Handle fix orphans
  const handleFixOrphans = useCallback(() => {
    const count = bindingValidator.fixOrphans();
    if (count > 0) {
      showSuccessToast(`已清理 ${count} 个孤立绑定`);
      refreshBindings();
    } else {
      showInfoToast('没有需要清理的孤立绑定');
    }
  }, [refreshBindings, showSuccessToast, showInfoToast]);

  // Filter stats
  const stats = useMemo(() => {
    if (!validation) {
      return { total: 0, valid: 0, warning: 0, error: 0, orphan: 0, unbound: 0 };
    }

    const unboundCount = groups.filter((g) => g.status === 'unbound').length;

    return {
      total: validation.total,
      valid: validation.valid,
      warning: validation.warnings,
      error: validation.errors,
      orphan: validation.orphans,
      unbound: unboundCount,
    };
  }, [validation, groups]);

  if (!isVisible) return null;

  return (
    <div className="flex h-full flex-col bg-white">
      {/* Header */}
      <div className="flex-shrink-0 border-b border-gray-200 px-4 py-3">
        <div className="mb-2 flex items-center justify-between">
          <h3 className="text-sm font-semibold text-gray-900">字段绑定</h3>
          <div className="flex items-center gap-1">
            <button
              type="button"
              onClick={refreshBindings}
              className="rounded p-1 text-gray-400 hover:text-gray-600"
              title="刷新"
            >
              <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
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

        {/* ViewModel info */}
        {viewModelCode && (
          <div className="mb-2 text-xs text-gray-500">
            ViewModel: <span className="font-medium">{viewModelCode}</span>
          </div>
        )}

        {/* Stats */}
        <div className="flex items-center gap-3 text-xs">
          <StatsItem
            label="总数"
            value={stats.total}
            onClick={() => setStatusFilter('all')}
            active={statusFilter === 'all'}
          />
          <StatsItem
            label="正常"
            value={stats.valid}
            color="text-green-600"
            onClick={() => setStatusFilter('valid')}
            active={statusFilter === 'valid'}
          />
          <StatsItem
            label="警告"
            value={stats.warning}
            color="text-yellow-600"
            onClick={() => setStatusFilter('warning')}
            active={statusFilter === 'warning'}
          />
          <StatsItem
            label="错误"
            value={stats.error}
            color="text-red-600"
            onClick={() => setStatusFilter('error')}
            active={statusFilter === 'error'}
          />
          <StatsItem
            label="未绑定"
            value={stats.unbound}
            color="text-gray-400"
            onClick={() => setStatusFilter('unbound')}
            active={statusFilter === 'unbound'}
          />
        </div>
      </div>

      {/* Toolbar */}
      <div className="flex flex-shrink-0 items-center justify-between border-b border-gray-100 bg-gray-50 px-4 py-2">
        {/* View mode toggle */}
        <div className="flex items-center gap-1 rounded-md border border-gray-200 bg-white p-0.5">
          <button
            type="button"
            onClick={() => setViewMode('byField')}
            className={`rounded px-2 py-1 text-xs ${
              viewMode === 'byField'
                ? 'bg-gray-100 text-gray-700'
                : 'text-gray-500 hover:text-gray-700'
            }`}
          >
            按字段
          </button>
          <button
            type="button"
            onClick={() => setViewMode('byComponent')}
            className={`rounded px-2 py-1 text-xs ${
              viewMode === 'byComponent'
                ? 'bg-gray-100 text-gray-700'
                : 'text-gray-500 hover:text-gray-700'
            }`}
          >
            按组件
          </button>
        </div>

        {/* Actions */}
        <div className="flex items-center gap-2">
          <label className="flex items-center gap-1 text-xs text-gray-600">
            <input
              type="checkbox"
              checked={showUnbound}
              onChange={(e) => setShowUnbound(e.target.checked)}
              className="h-3 w-3"
            />
            显示未绑定
          </label>
        </div>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto">
        <BindingList
          groups={groups}
          selectedId={selectedBindingId}
          onSelect={handleBindingSelect}
          onUnbind={handleUnbind}
          showUnbound={showUnbound}
          statusFilter={statusFilter === 'all' ? 'all' : statusFilter}
        />
      </div>

      {/* Footer */}
      <div className="flex-shrink-0 border-t border-gray-200 bg-gray-50 px-4 py-2">
        <div className="flex items-center justify-between">
          <button
            type="button"
            onClick={handleFixOrphans}
            disabled={stats.orphan === 0}
            className={`rounded px-2 py-1 text-xs ${
              stats.orphan > 0
                ? 'text-orange-600 hover:bg-orange-50'
                : 'cursor-not-allowed text-gray-400'
            }`}
          >
            清理孤立绑定 ({stats.orphan})
          </button>
          <button
            type="button"
            onClick={handleUnbindAll}
            disabled={stats.total === 0}
            className={`rounded px-2 py-1 text-xs ${
              stats.total > 0 ? 'text-red-600 hover:bg-red-50' : 'cursor-not-allowed text-gray-400'
            }`}
          >
            全部解绑
          </button>
        </div>
      </div>
    </div>
  );
};

/**
 * Stats item component
 */
interface StatsItemProps {
  label: string;
  value: number;
  color?: string;
  onClick?: () => void;
  active?: boolean;
}

const StatsItem: React.FC<StatsItemProps> = ({
  label,
  value,
  color = 'text-gray-600',
  onClick,
  active,
}) => (
  <button
    type="button"
    onClick={onClick}
    className={`flex items-center gap-1 rounded px-1.5 py-0.5 transition-colors ${active ? 'bg-gray-200' : 'hover:bg-gray-100'} `}
  >
    <span className="text-gray-500">{label}:</span>
    <span className={`font-medium ${color}`}>{value}</span>
  </button>
);

export default BindingPanel;
