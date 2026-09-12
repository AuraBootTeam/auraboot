/**
 * Rollback Confirmation Dialog.
 * Asks the user to confirm before rolling back to a historical version.
 */

import React from 'react';
import { useSmartText } from '~/utils/i18n';
import { useI18n } from '~/contexts/I18nContext';
import type { VersionEntry } from './types';
import { getOperationConfig } from './types';

interface RollbackDialogProps {
  version: VersionEntry;
  isRollingBack: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

export function RollbackDialog({
  version,
  isRollingBack,
  onConfirm,
  onCancel,
}: RollbackDialogProps) {
  const text = useSmartText();
  const { t, locale } = useI18n();
  const opConfig = getOperationConfig(version.operation);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="w-[420px] rounded-lg bg-white shadow-xl">
        <div className="border-b border-gray-200 px-6 py-4">
          <h2 className="text-lg font-semibold text-gray-900">
            {text({ zh: '确认回滚', en: 'Confirm Rollback' })}
          </h2>
        </div>
        <div className="p-6">
          <p className="mb-4 text-sm text-gray-600">
            {text({
              zh: '确认回滚到此版本？回滚前会将当前状态保存为备份。',
              en: 'Are you sure you want to rollback to this version? The current state will be saved as a backup before the rollback.',
            })}
          </p>
          <div className={`rounded-r-md border-l-4 bg-gray-50 p-3 ${opConfig.borderColor}`}>
            <div className="mb-1 flex items-center justify-between">
              <span className="text-sm font-semibold text-gray-900">v{version.version}</span>
              <span
                className={`rounded px-1.5 py-0.5 text-xs font-medium ${opConfig.badgeBg} ${opConfig.badgeText}`}
              >
                {t(
                  `version.history.operation.${version.operation.toLowerCase()}`,
                  undefined,
                  opConfig.label,
                )}
              </span>
            </div>
            <div className="text-xs text-gray-500">
              {version.operationAt && (
                <span>
                  {new Date(version.operationAt).toLocaleDateString(locale, {
                    year: 'numeric',
                    month: '2-digit',
                    day: '2-digit',
                    hour: '2-digit',
                    minute: '2-digit',
                  })}
                </span>
              )}
            </div>
            {version.description && (
              <p className="mt-1 text-xs text-gray-400">{version.description}</p>
            )}
          </div>
        </div>
        <div className="flex justify-end gap-3 border-t border-gray-200 px-6 py-4">
          <button
            onClick={onCancel}
            disabled={isRollingBack}
            className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
          >
            {text({ zh: '取消', en: 'Cancel' })}
          </button>
          <button
            onClick={onConfirm}
            disabled={isRollingBack}
            className="rounded-md bg-purple-600 px-4 py-2 text-sm font-medium text-white hover:bg-purple-700 disabled:opacity-50"
          >
            {isRollingBack
              ? text({ zh: '回滚中…', en: 'Rolling back...' })
              : text({ zh: '确认回滚', en: 'Confirm Rollback' })}
          </button>
        </div>
      </div>
    </div>
  );
}
