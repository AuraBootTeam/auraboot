/**
 * Reusable Version History Panel.
 * Slide-in panel that displays version history for any designer.
 * Based on the BPMN Designer's VersionHistoryPanel but generalized.
 */

import React, { useEffect, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import type { VersionEntry } from './types';
import { getOperationConfig } from './types';
import { RollbackDialog } from './RollbackDialog';

function formatDate(dateStr?: string): string {
  if (!dateStr) return '-';
  try {
    const date = new Date(dateStr);
    return date.toLocaleDateString('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return dateStr;
  }
}

interface VersionItemProps {
  version: VersionEntry;
  isActive: boolean;
  isLatest: boolean;
  onClick: () => void;
  labels: {
    latest: string;
    by: string;
    operation: (operation: string, fallback: string) => string;
  };
}

function VersionItem({ version, isActive, isLatest, onClick, labels }: VersionItemProps) {
  const opConfig = getOperationConfig(version.operation);

  return (
    <button
      type="button"
      onClick={onClick}
      className={`mb-2 w-full rounded-r-md border-l-4 p-3 text-left transition-colors ${opConfig.borderColor} ${
        isActive ? 'bg-blue-50 ring-1 ring-blue-300' : 'bg-white hover:bg-gray-50'
      }`}
    >
      <div className="mb-1 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="text-sm font-semibold text-gray-900">v{version.version}</span>
          {isLatest && (
            <span className="rounded bg-blue-100 px-1.5 py-0.5 text-xs font-medium text-blue-700">
              {labels.latest}
            </span>
          )}
        </div>
        <span
          className={`rounded px-1.5 py-0.5 text-xs font-medium ${opConfig.badgeBg} ${opConfig.badgeText}`}
        >
          {labels.operation(version.operation, opConfig.label)}
        </span>
      </div>
      <div className="text-xs text-gray-500">
        <span>{formatDate(version.operationAt)}</span>
        {version.operationBy && <span className="ml-2">{labels.by} {version.operationBy}</span>}
      </div>
      {version.description && (
        <p className="mt-1 line-clamp-2 text-xs text-gray-400">{version.description}</p>
      )}
    </button>
  );
}

export interface VersionHistoryPanelProps {
  /** Whether the panel is visible */
  isOpen: boolean;
  /** Toggle panel visibility */
  onClose: () => void;
  /** Version list */
  versions: VersionEntry[];
  /** Whether versions are loading */
  isLoading: boolean;
  /** Currently previewing version PID */
  viewingVersionPid: string | null;
  /** Preview a historical version */
  onPreview: (versionPid: string) => void;
  /** Exit preview mode */
  onExitPreview: () => void;
  /** Rollback to version */
  onRollback: (versionPid: string) => Promise<void>;
  /** Whether rollback is in progress */
  isRollingBack: boolean;
}

export function VersionHistoryPanel({
  isOpen,
  onClose,
  versions,
  isLoading,
  viewingVersionPid,
  onPreview,
  onExitPreview,
  onRollback,
  isRollingBack,
}: VersionHistoryPanelProps) {
  const [rollbackTarget, setRollbackTarget] = useState<VersionEntry | null>(null);
  const { t } = useI18n();
  const labels = {
    title: t('version.history.title', undefined, '版本历史'),
    close: t('version.history.close', undefined, '关闭版本面板'),
    previewing: t('version.history.previewing', undefined, '正在查看历史版本（只读）'),
    backToCurrent: t('version.history.backToCurrent', undefined, '返回当前版本'),
    loading: t('version.history.loading', undefined, '正在加载版本...'),
    emptyTitle: t('version.history.empty.title', undefined, '暂无版本记录'),
    emptyDescription: t('version.history.empty.description', undefined, '保存后会生成第一个版本'),
    footerEmpty: t('version.history.footer.empty', undefined, '暂无可用版本'),
    footerCount: (count: number) =>
      t('version.history.footer.count', { count }, `${count} 个可用版本`),
    latest: t('version.history.latest', undefined, '最新'),
    by: t('version.history.by', undefined, '由'),
    back: t('version.history.back', undefined, '返回'),
    rollback: t('version.history.rollback', undefined, '回滚'),
    rollingBack: t('version.history.rollingBack', undefined, '回滚中...'),
    operation: (operation: string, fallback: string) =>
      t(`version.history.operation.${operation.toLowerCase()}`, undefined, fallback),
  };

  // Handle ESC key to close panel
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && isOpen) {
        onClose();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, onClose]);

  // The first version is the latest (list is ordered by operation_at DESC)
  const latestVersionPid = versions.length > 0 ? versions[0].pid : null;

  return (
    <>
      <div
        data-testid="version-history-panel"
        className={`fixed top-14 right-0 z-50 flex h-[calc(100vh-3.5rem)] w-[min(20rem,100vw)] flex-col bg-white shadow-lg transition-transform duration-300 ease-in-out ${
          isOpen ? 'translate-x-0' : 'translate-x-full'
        }`}
      >
        {/* Panel header */}
        <div className="flex items-center justify-between border-b border-gray-200 bg-gray-50 px-4 py-3">
          <h2 className="text-base font-semibold text-gray-900">{labels.title}</h2>
          <button
            type="button"
            onClick={onClose}
            className="text-gray-400 transition-colors hover:text-gray-600"
            aria-label={labels.close}
          >
            <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M6 18L18 6M6 6l12 12"
              />
            </svg>
          </button>
        </div>

        {/* Previewing old version banner */}
        {viewingVersionPid && (
          <div className="flex items-center justify-between border-b border-yellow-200 bg-yellow-50 px-4 py-2">
            <span className="text-xs font-medium text-yellow-800">
              {labels.previewing}
            </span>
            <button
              type="button"
              onClick={onExitPreview}
              className="text-xs font-medium text-blue-600 underline hover:text-blue-800"
            >
              {labels.backToCurrent}
            </button>
          </div>
        )}

        {/* Version list */}
        <div className="flex-1 overflow-y-auto px-3 py-3">
          {isLoading ? (
            <div className="flex flex-col items-center justify-center py-12 text-gray-400">
              <svg className="mb-2 h-8 w-8 animate-spin" fill="none" viewBox="0 0 24 24">
                <circle
                  className="opacity-25"
                  cx="12"
                  cy="12"
                  r="10"
                  stroke="currentColor"
                  strokeWidth="4"
                />
                <path
                  className="opacity-75"
                  fill="currentColor"
                  d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"
                />
              </svg>
              <span className="text-sm">{labels.loading}</span>
            </div>
          ) : versions.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-12 text-gray-400">
              <svg className="mb-2 h-10 w-10" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={1.5}
                  d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"
                />
              </svg>
              <span className="text-sm">{labels.emptyTitle}</span>
              <span className="mt-1 text-xs">{labels.emptyDescription}</span>
            </div>
          ) : (
            versions.map((version) => (
              <VersionItem
                key={version.pid}
                version={version}
                isActive={viewingVersionPid === version.pid}
                isLatest={version.pid === latestVersionPid}
                labels={labels}
                onClick={() => {
                  if (version.pid === latestVersionPid) {
                    if (viewingVersionPid) {
                      onExitPreview();
                    }
                  } else {
                    onPreview(version.pid);
                  }
                }}
              />
            ))
          )}
        </div>

        {/* Panel footer */}
        <div className="border-t border-gray-200 bg-gray-50 px-4 py-3">
          {viewingVersionPid ? (
            <div className="flex gap-2">
              <button
                type="button"
                onClick={onExitPreview}
                className="flex-1 rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-50"
              >
                {labels.back}
              </button>
              <button
                type="button"
                onClick={() => {
                  const target = versions.find((v) => v.pid === viewingVersionPid);
                  if (target) setRollbackTarget(target);
                }}
                disabled={isRollingBack}
                className="flex-1 rounded-md bg-purple-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-purple-700 disabled:opacity-50"
              >
                {isRollingBack ? labels.rollingBack : labels.rollback}
              </button>
            </div>
          ) : (
            <p className="text-center text-xs text-gray-500">
              {versions.length === 0 ? labels.footerEmpty : labels.footerCount(versions.length)}
            </p>
          )}
        </div>
      </div>

      {/* Rollback confirmation dialog */}
      {rollbackTarget && (
        <RollbackDialog
          version={rollbackTarget}
          isRollingBack={isRollingBack}
          onConfirm={async () => {
            await onRollback(rollbackTarget.pid);
            setRollbackTarget(null);
          }}
          onCancel={() => setRollbackTarget(null)}
        />
      )}
    </>
  );
}
