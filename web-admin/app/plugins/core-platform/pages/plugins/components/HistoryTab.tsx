/**
 * Import history tab — list of past plugin import attempts.
 *
 * Extracted from the legacy /system/plugins page.
 */

import { useState, useEffect, useCallback } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import {
  DocumentTextIcon,
  ArrowPathIcon,
  CheckCircleIcon,
  XCircleIcon,
} from '@heroicons/react/24/outline';

export interface ImportHistory {
  importId: string;
  pluginId: string;
  namespace: string;
  version: string;
  status: 'success' | 'failed' | 'rolled_back';
  importType: 'install' | 'upgrade';
  sourceType: 'json' | 'zip';
  sourceName: string;
  startedAt: string;
  completedAt?: string;
  errorMessage?: string;
}

interface Props {
  onCountChange?: (count: number) => void;
  refreshToken?: number;
}

export default function HistoryTab({ onCountChange, refreshToken }: Props) {
  const { t } = useI18n();
  const [importHistory, setImportHistory] = useState<ImportHistory[]>([]);

  const fetchHistory = useCallback(async () => {
    const response = await fetch('/api/plugins/import/history?limit=20');
    if (response.ok) {
      const result = await response.json();
      const data = result.data ?? result;
      const list = Array.isArray(data) ? data : [];
      setImportHistory(list);
      onCountChange?.(list.length);
    }
  }, [onCountChange]);

  useEffect(() => {
    fetchHistory();
  }, [fetchHistory, refreshToken]);

  const StatusBadge = ({ status }: { status: string }) => {
    const config: Record<
      string,
      { bg: string; text: string; icon: typeof CheckCircleIcon; labelKey: string }
    > = {
      success: { bg: 'bg-green-100', text: 'text-green-800', icon: CheckCircleIcon, labelKey: 'plugin.import.status.success' },
      failed: { bg: 'bg-red-100', text: 'text-red-800', icon: XCircleIcon, labelKey: 'plugin.status.failed' },
      rolled_back: { bg: 'bg-yellow-100', text: 'text-yellow-800', icon: ArrowPathIcon, labelKey: 'plugin.import.status.rolledBack' },
    };
    const c = config[status] || config.success;
    const Icon = c.icon;
    return (
      <span className={`inline-flex items-center gap-1 rounded-full px-2 py-1 text-xs font-medium ${c.bg} ${c.text}`}>
        <Icon className="h-3 w-3" />
        {t(c.labelKey)}
      </span>
    );
  };

  return (
    <div className="overflow-hidden rounded-lg bg-white shadow">
      {importHistory.length === 0 ? (
        <div className="py-12 text-center">
          <DocumentTextIcon className="mx-auto h-12 w-12 text-gray-300" />
          <p className="mt-2 text-gray-500">{t('plugin.empty.history')}</p>
        </div>
      ) : (
        <table className="min-w-full divide-y divide-gray-200">
          <thead className="bg-gray-50">
            <tr>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">{t('plugin.column.plugin')}</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">{t('plugin.column.version')}</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">{t('plugin.import.column.type')}</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">{t('plugin.column.status')}</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">{t('plugin.import.column.source')}</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">{t('plugin.import.column.time')}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-200 bg-white">
            {importHistory.map((record) => (
              <tr key={record.importId} className="hover:bg-gray-50">
                <td className="px-6 py-4">
                  <div className="text-sm font-medium text-gray-900">{record.pluginId}</div>
                  <div className="text-xs text-gray-500">{record.namespace}</div>
                </td>
                <td className="px-6 py-4 text-sm text-gray-500">{record.version}</td>
                <td className="px-6 py-4 text-sm text-gray-500">
                  {record.importType === 'install'
                    ? t('plugin.import.type.install')
                    : t('plugin.import.type.upgrade')}
                </td>
                <td className="px-6 py-4">
                  <StatusBadge status={record.status} />
                </td>
                <td className="px-6 py-4 text-sm text-gray-500">
                  {record.sourceType} - {record.sourceName}
                </td>
                <td className="px-6 py-4 text-sm text-gray-500">
                  {new Date(record.startedAt).toLocaleString()}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
