/**
 * VersionHistoryPanel Component
 *
 * Panel for viewing version history and performing rollback operations.
 *
 * @since 3.2.0
 */

import React, { useState, useCallback, useEffect, useMemo } from 'react';
import {
  OPERATION_INFO,
  type VersionEntry,
  type VersionComparison,
  type ViewMode,
  type VersionOperation,
} from './types';
import { pageApi } from '~/studio/services/page-manager';
import { ResultHelper } from '~/utils/type';

/**
 * VersionHistoryPanel props
 */
export interface VersionHistoryPanelProps {
  /** Whether the panel is open */
  isOpen: boolean;
  /** Close callback */
  onClose: () => void;
  /** Page PID */
  pagePid: string;
  /** Page title for display */
  pageTitle?: string;
  /** Rollback success callback */
  onRollbackSuccess?: () => void;
}

/**
 * VersionHistoryPanel component
 */
export const VersionHistoryPanel: React.FC<VersionHistoryPanelProps> = ({
  isOpen,
  onClose,
  pagePid,
  pageTitle,
  onRollbackSuccess,
}) => {
  const [versions, setVersions] = useState<VersionEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [viewMode, setViewMode] = useState<ViewMode>('list');
  const [selectedVersions, setSelectedVersions] = useState<[number | null, number | null]>([
    null,
    null,
  ]);
  const [comparison, setComparison] = useState<VersionComparison | null>(null);
  const [comparing, setComparing] = useState(false);
  const [rollbackTarget, setRollbackTarget] = useState<VersionEntry | null>(null);
  const [rollbackReason, setRollbackReason] = useState('');
  const [rolling, setRolling] = useState(false);

  // Load version history
  const loadVersions = useCallback(async () => {
    if (!pagePid) return;

    setLoading(true);
    setError(null);

    try {
      const result = await pageApi.getVersionHistory(pagePid);
      if (ResultHelper.isSuccess(result) && result.data) {
        const entries: VersionEntry[] = result.data.map((v) => ({
          id: v.id,
          pagePid: v.pagePid,
          version: v.version,
          semver: v.semver,
          operation: v.operation as VersionOperation,
          operator: v.operatorPid,
          timestamp: v.operationTime || new Date().toISOString(),
          snapshot: v.snapshot,
        }));
        setVersions(entries);
      } else {
        setError(result.desc || 'Failed to load version history');
      }
    } catch (err) {
      console.error('Failed to load versions:', err);
      setError('Failed to load version history');
    } finally {
      setLoading(false);
    }
  }, [pagePid]);

  // Load on open
  useEffect(() => {
    if (isOpen && pagePid) {
      loadVersions();
    }
  }, [isOpen, pagePid, loadVersions]);

  // Reset state when closed
  useEffect(() => {
    if (!isOpen) {
      setViewMode('list');
      setSelectedVersions([null, null]);
      setComparison(null);
      setRollbackTarget(null);
      setRollbackReason('');
    }
  }, [isOpen]);

  // Handle version selection for comparison
  const handleVersionSelect = useCallback((versionId: number) => {
    setSelectedVersions((prev) => {
      if (prev[0] === null) {
        return [versionId, null];
      }
      if (prev[0] === versionId) {
        return [null, null];
      }
      if (prev[1] === null) {
        return [prev[0], versionId];
      }
      return [versionId, null];
    });
  }, []);

  // Compare versions
  const handleCompare = useCallback(async () => {
    const [fromId, toId] = selectedVersions;
    if (fromId === null || toId === null) return;

    setComparing(true);
    try {
      const result = await pageApi.compareVersions(pagePid, fromId, toId);
      if (ResultHelper.isSuccess(result) && result.data) {
        const fromVersion = versions.find((v) => v.id === fromId);
        const toVersion = versions.find((v) => v.id === toId);
        if (fromVersion && toVersion) {
          // Map API field names to local types
          const changes = (result.data.differences || []).map((diff) => ({
            field: diff.fieldPath,
            oldValue: diff.sourceValue,
            newValue: diff.targetValue,
            changeType:
              diff.type === 'added' ? 'add' : diff.type === 'removed' ? 'remove' : 'modify',
          }));
          setComparison({
            fromVersion,
            toVersion,
            changes: changes as VersionComparison['changes'],
          });
          setViewMode('compare');
        }
      }
    } catch (err) {
      console.error('Failed to compare versions:', err);
    } finally {
      setComparing(false);
    }
  }, [pagePid, selectedVersions, versions]);

  // Handle rollback
  const handleRollback = useCallback(async () => {
    if (!rollbackTarget || !rollbackReason.trim()) return;

    setRolling(true);
    try {
      const result = await pageApi.rollbackToVersion(pagePid, rollbackTarget.id, rollbackReason);
      if (ResultHelper.isSuccess(result)) {
        setRollbackTarget(null);
        setRollbackReason('');
        await loadVersions();
        onRollbackSuccess?.();
      } else {
        setError(result.desc || 'Rollback failed');
      }
    } catch (err) {
      console.error('Rollback failed:', err);
      setError('Rollback failed');
    } finally {
      setRolling(false);
    }
  }, [pagePid, rollbackTarget, rollbackReason, loadVersions, onRollbackSuccess]);

  // Format timestamp
  const formatTime = useCallback((timestamp: string) => {
    const date = new Date(timestamp);
    const now = new Date();
    const diff = now.getTime() - date.getTime();
    const minutes = Math.floor(diff / 60000);
    const hours = Math.floor(diff / 3600000);
    const days = Math.floor(diff / 86400000);

    if (minutes < 1) return '刚刚';
    if (minutes < 60) return `${minutes} 分钟前`;
    if (hours < 24) return `${hours} 小时前`;
    if (days < 7) return `${days} 天前`;

    return date.toLocaleDateString('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
  }, []);

  // Can compare check
  const canCompare = useMemo(() => {
    return selectedVersions[0] !== null && selectedVersions[1] !== null;
  }, [selectedVersions]);

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      {/* Backdrop */}
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />

      {/* Panel */}
      <div className="relative flex max-h-[85vh] w-full max-w-4xl flex-col overflow-hidden rounded-xl bg-white shadow-2xl">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-200 px-6 py-4">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-blue-100">
              <svg
                className="h-5 w-5 text-blue-600"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"
                />
              </svg>
            </div>
            <div>
              <h2 className="text-lg font-semibold text-gray-900">版本历史</h2>
              {pageTitle && <p className="text-sm text-gray-500">{pageTitle}</p>}
            </div>
          </div>
          <div className="flex items-center gap-2">
            {viewMode === 'compare' && (
              <button
                onClick={() => setViewMode('list')}
                className="rounded-lg px-3 py-1.5 text-sm text-gray-600 hover:bg-gray-100"
              >
                返回列表
              </button>
            )}
            <button onClick={onClose} className="rounded-lg p-1.5 text-gray-500 hover:bg-gray-100">
              <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M6 18L18 6M6 6l12 12"
                />
              </svg>
            </button>
          </div>
        </div>

        {/* Content */}
        <div className="flex flex-1 flex-col overflow-hidden">
          {loading ? (
            <div className="flex flex-1 items-center justify-center">
              <div className="flex flex-col items-center gap-3">
                <div className="h-8 w-8 animate-spin rounded-full border-3 border-blue-600 border-t-transparent" />
                <p className="text-sm text-gray-500">加载版本历史...</p>
              </div>
            </div>
          ) : error ? (
            <div className="flex flex-1 items-center justify-center">
              <div className="flex flex-col items-center gap-3 text-center">
                <div className="flex h-12 w-12 items-center justify-center rounded-full bg-red-100">
                  <svg
                    className="h-6 w-6 text-red-600"
                    fill="none"
                    viewBox="0 0 24 24"
                    stroke="currentColor"
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
                    />
                  </svg>
                </div>
                <p className="text-sm text-gray-600">{error}</p>
                <button
                  onClick={loadVersions}
                  className="rounded-lg px-4 py-2 text-sm text-blue-600 hover:bg-blue-50"
                >
                  重试
                </button>
              </div>
            </div>
          ) : viewMode === 'list' ? (
            <VersionList
              versions={versions}
              selectedVersions={selectedVersions}
              onVersionSelect={handleVersionSelect}
              onRollbackClick={setRollbackTarget}
              formatTime={formatTime}
            />
          ) : comparison ? (
            <VersionCompareView comparison={comparison} />
          ) : null}
        </div>

        {/* Footer */}
        {viewMode === 'list' && versions.length > 0 && (
          <div className="border-t border-gray-200 bg-gray-50 px-6 py-4">
            <div className="flex items-center justify-between">
              <p className="text-sm text-gray-500">
                {selectedVersions[0] !== null &&
                  selectedVersions[1] === null &&
                  '选择另一个版本进行对比'}
                {selectedVersions[0] !== null && selectedVersions[1] !== null && '已选择 2 个版本'}
                {selectedVersions[0] === null && `共 ${versions.length} 个版本`}
              </p>
              <div className="flex items-center gap-3">
                {(selectedVersions[0] !== null || selectedVersions[1] !== null) && (
                  <button
                    onClick={() => setSelectedVersions([null, null])}
                    className="rounded-lg px-4 py-2 text-sm text-gray-600 hover:bg-gray-200"
                  >
                    清除选择
                  </button>
                )}
                <button
                  onClick={handleCompare}
                  disabled={!canCompare || comparing}
                  className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {comparing && (
                    <div className="h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent" />
                  )}
                  对比版本
                </button>
              </div>
            </div>
          </div>
        )}
      </div>

      {/* Rollback confirmation dialog */}
      {rollbackTarget && (
        <RollbackDialog
          version={rollbackTarget}
          reason={rollbackReason}
          onReasonChange={setRollbackReason}
          onConfirm={handleRollback}
          onCancel={() => {
            setRollbackTarget(null);
            setRollbackReason('');
          }}
          loading={rolling}
        />
      )}
    </div>
  );
};

/**
 * Version list component
 */
const VersionList: React.FC<{
  versions: VersionEntry[];
  selectedVersions: [number | null, number | null];
  onVersionSelect: (id: number) => void;
  onRollbackClick: (version: VersionEntry) => void;
  formatTime: (timestamp: string) => string;
}> = ({ versions, selectedVersions, onVersionSelect, onRollbackClick, formatTime }) => {
  if (versions.length === 0) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <div className="flex flex-col items-center gap-3 text-center">
          <div className="flex h-16 w-16 items-center justify-center rounded-full bg-gray-100">
            <svg
              className="h-8 w-8 text-gray-400"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={1.5}
                d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"
              />
            </svg>
          </div>
          <p className="text-gray-600">暂无版本历史</p>
          <p className="text-sm text-gray-400">保存或发布页面后将记录版本</p>
        </div>
      </div>
    );
  }

  return (
    <div className="flex-1 overflow-y-auto">
      <div className="divide-y divide-gray-100">
        {versions.map((version, index) => {
          const opInfo = OPERATION_INFO[version.operation] || OPERATION_INFO.update;
          const isSelected =
            selectedVersions[0] === version.id || selectedVersions[1] === version.id;
          const isLatest = index === 0;

          return (
            <div
              key={version.id}
              className={`px-6 py-4 transition-colors hover:bg-gray-50 ${
                isSelected ? 'bg-blue-50' : ''
              }`}
            >
              <div className="flex items-start gap-4">
                {/* Checkbox */}
                <button
                  onClick={() => onVersionSelect(version.id)}
                  className={`mt-1 flex h-5 w-5 items-center justify-center rounded border-2 transition-colors ${
                    isSelected
                      ? 'border-blue-600 bg-blue-600'
                      : 'border-gray-300 hover:border-gray-400'
                  }`}
                >
                  {isSelected && (
                    <svg className="h-3 w-3 text-white" fill="currentColor" viewBox="0 0 20 20">
                      <path
                        fillRule="evenodd"
                        d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z"
                        clipRule="evenodd"
                      />
                    </svg>
                  )}
                </button>

                {/* Timeline indicator */}
                <div className="flex flex-col items-center">
                  <div
                    className={`flex h-10 w-10 items-center justify-center rounded-full ${opInfo.bgColor}`}
                  >
                    <svg
                      className={`h-5 w-5 ${opInfo.color}`}
                      fill="none"
                      viewBox="0 0 24 24"
                      stroke="currentColor"
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth={2}
                        d={opInfo.icon}
                      />
                    </svg>
                  </div>
                  {index < versions.length - 1 && <div className="mt-2 h-8 w-0.5 bg-gray-200" />}
                </div>

                {/* Content */}
                <div className="min-w-0 flex-1">
                  <div className="mb-1 flex items-center gap-2">
                    <span
                      className={`rounded px-2 py-0.5 text-xs font-medium ${opInfo.bgColor} ${opInfo.color}`}
                    >
                      {opInfo.label}
                    </span>
                    {version.semver && (
                      <span className="font-mono text-sm text-gray-600">{version.semver}</span>
                    )}
                    {isLatest && (
                      <span className="rounded bg-green-100 px-2 py-0.5 text-xs font-medium text-green-700">
                        当前版本
                      </span>
                    )}
                  </div>
                  <div className="flex items-center gap-4 text-sm text-gray-500">
                    <span>{formatTime(version.timestamp)}</span>
                    {version.operator && (
                      <>
                        <span>·</span>
                        <span>{version.operator}</span>
                      </>
                    )}
                  </div>
                </div>

                {/* Actions */}
                {!isLatest && (
                  <button
                    onClick={() => onRollbackClick(version)}
                    className="rounded-lg px-3 py-1.5 text-sm text-orange-600 transition-colors hover:bg-orange-50"
                  >
                    回滚到此版本
                  </button>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};

/**
 * Version compare view component
 */
const VersionCompareView: React.FC<{
  comparison: VersionComparison;
}> = ({ comparison }) => {
  const { fromVersion, toVersion, changes } = comparison;

  return (
    <div className="flex-1 overflow-y-auto p-6">
      {/* Header */}
      <div className="mb-6 flex items-center justify-between">
        <div className="flex items-center gap-4">
          <div className="text-center">
            <p className="mb-1 text-xs text-gray-500">旧版本</p>
            <span className="rounded bg-gray-100 px-3 py-1 font-mono text-sm">
              {fromVersion.semver || `v${fromVersion.version}`}
            </span>
          </div>
          <svg
            className="h-5 w-5 text-gray-400"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M14 5l7 7m0 0l-7 7m7-7H3"
            />
          </svg>
          <div className="text-center">
            <p className="mb-1 text-xs text-gray-500">新版本</p>
            <span className="rounded bg-blue-100 px-3 py-1 font-mono text-sm">
              {toVersion.semver || `v${toVersion.version}`}
            </span>
          </div>
        </div>
        <span className="text-sm text-gray-500">{changes.length} 处变更</span>
      </div>

      {/* Changes list */}
      {changes.length === 0 ? (
        <div className="py-12 text-center">
          <p className="text-gray-500">两个版本之间没有差异</p>
        </div>
      ) : (
        <div className="space-y-4">
          {changes.map((change, index) => (
            <div key={index} className="overflow-hidden rounded-lg border border-gray-200">
              <div className="flex items-center justify-between border-b border-gray-200 bg-gray-50 px-4 py-2">
                <span className="font-mono text-sm text-gray-700">{change.field}</span>
                <span
                  className={`rounded px-2 py-0.5 text-xs font-medium ${
                    change.changeType === 'add'
                      ? 'bg-green-100 text-green-700'
                      : change.changeType === 'remove'
                        ? 'bg-red-100 text-red-700'
                        : 'bg-yellow-100 text-yellow-700'
                  }`}
                >
                  {change.changeType === 'add'
                    ? '新增'
                    : change.changeType === 'remove'
                      ? '删除'
                      : '修改'}
                </span>
              </div>
              <div className="grid grid-cols-2 divide-x divide-gray-200">
                <div className="p-4">
                  <p className="mb-2 text-xs text-gray-500">旧值</p>
                  <pre className="rounded bg-red-50 p-2 text-sm break-all whitespace-pre-wrap text-gray-700">
                    {change.oldValue !== undefined
                      ? typeof change.oldValue === 'object'
                        ? JSON.stringify(change.oldValue, null, 2)
                        : String(change.oldValue)
                      : '(无)'}
                  </pre>
                </div>
                <div className="p-4">
                  <p className="mb-2 text-xs text-gray-500">新值</p>
                  <pre className="rounded bg-green-50 p-2 text-sm break-all whitespace-pre-wrap text-gray-700">
                    {change.newValue !== undefined
                      ? typeof change.newValue === 'object'
                        ? JSON.stringify(change.newValue, null, 2)
                        : String(change.newValue)
                      : '(无)'}
                  </pre>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

/**
 * Rollback confirmation dialog
 */
const RollbackDialog: React.FC<{
  version: VersionEntry;
  reason: string;
  onReasonChange: (reason: string) => void;
  onConfirm: () => void;
  onCancel: () => void;
  loading: boolean;
}> = ({ version, reason, onReasonChange, onConfirm, onCancel, loading }) => {
  const opInfo = OPERATION_INFO[version.operation] || OPERATION_INFO.update;

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center">
      <div className="absolute inset-0 bg-black/50" onClick={onCancel} />
      <div className="relative w-full max-w-md rounded-xl bg-white p-6 shadow-2xl">
        <div className="mb-4 flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-full bg-orange-100">
            <svg
              className="h-5 w-5 text-orange-600"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"
              />
            </svg>
          </div>
          <div>
            <h3 className="text-lg font-semibold text-gray-900">确认回滚</h3>
            <p className="text-sm text-gray-500">
              回滚到版本 {version.semver || `v${version.version}`}
            </p>
          </div>
        </div>

        <div className="mb-4 rounded-lg bg-gray-50 p-3">
          <div className="flex items-center gap-2 text-sm">
            <span
              className={`rounded px-2 py-0.5 text-xs font-medium ${opInfo.bgColor} ${opInfo.color}`}
            >
              {opInfo.label}
            </span>
            <span className="text-gray-600">
              {new Date(version.timestamp).toLocaleString('zh-CN')}
            </span>
          </div>
        </div>

        <div className="mb-4">
          <label className="mb-1 block text-sm font-medium text-gray-700">
            回滚原因 <span className="text-red-500">*</span>
          </label>
          <textarea
            value={reason}
            onChange={(e) => onReasonChange(e.target.value)}
            placeholder="请输入回滚原因..."
            rows={3}
            className="w-full resize-none rounded-lg border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-orange-500 focus:outline-none"
          />
        </div>

        <div className="flex items-center justify-end gap-3">
          <button
            onClick={onCancel}
            disabled={loading}
            className="rounded-lg px-4 py-2 text-sm text-gray-700 hover:bg-gray-100 disabled:opacity-50"
          >
            取消
          </button>
          <button
            onClick={onConfirm}
            disabled={loading || !reason.trim()}
            className="flex items-center gap-2 rounded-lg bg-orange-600 px-4 py-2 text-sm font-medium text-white hover:bg-orange-700 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {loading && (
              <div className="h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent" />
            )}
            确认回滚
          </button>
        </div>
      </div>
    </div>
  );
};

export default VersionHistoryPanel;
