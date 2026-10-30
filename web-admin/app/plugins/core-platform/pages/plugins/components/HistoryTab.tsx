import type { components } from '~/generated/internal-api/schema';
import ImportWarnings from './ImportWarnings';
/**
 * Import history tab — list of past plugin import attempts.
 *
 * Extracted from the legacy /system/plugins page.
 */

import { useState, useEffect, useCallback } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';
import IMPORT_TEXT from './InstalledTab.i18n.json';
import {
  DocumentTextIcon,
  ArrowPathIcon,
  CheckCircleIcon,
  XCircleIcon,
} from '@heroicons/react/24/outline';

export type ImportHistory = Required<Pick<components['schemas']['ImportHistoryDTO'],
  'importId' | 'pluginId' | 'namespace' | 'version' | 'status' | 'importType' | 'sourceType' | 'sourceName' | 'startedAt'
>> & Pick<components['schemas']['ImportHistoryDTO'], 'completedAt' | 'errorMessage' | 'warnings' | 'preservedResourceCount'>;

interface Props {
  onCountChange?: (count: number) => void;
  refreshToken?: number;
}

export default function HistoryTab({ onCountChange, refreshToken }: Props) {
  const { t, locale } = useI18n();
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
        <table className="w-full table-fixed divide-y divide-gray-200">
          <colgroup><col style={{ width: '21%' }} /><col style={{ width: '8%' }} /><col style={{ width: '8%' }} /><col style={{ width: '34%' }} /><col style={{ width: '15%' }} /><col style={{ width: '14%' }} /></colgroup>
          <thead className="bg-gray-50">
            <tr>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">{t('plugin.column.plugin')}</th>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">{t('plugin.column.version')}</th>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">{t('plugin.import.column.type')}</th>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">{t('plugin.column.status')}</th>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">{t('plugin.import.column.source')}</th>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">{t('plugin.import.column.time')}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-200 bg-white">
            {importHistory.map((record) => (
              <tr key={record.importId} className="hover:bg-gray-50">
                <td className="break-words px-4 py-4 [overflow-wrap:anywhere]">
                  <div className="text-sm font-medium text-gray-900">{record.pluginId}</div>
                  <div className="text-xs text-gray-500">{record.namespace}</div>
                </td>
                <td className="break-words px-4 py-4 text-sm text-gray-500 [overflow-wrap:anywhere]">{record.version}</td>
                <td className="break-words px-4 py-4 text-sm text-gray-500 [overflow-wrap:anywhere]">
                  {record.importType === 'install'
                    ? t('plugin.import.type.install')
                    : t('plugin.import.type.upgrade')}
                </td>
                <td className="break-words px-4 py-4 [overflow-wrap:anywhere]">
                  <StatusBadge status={record.status} />
                  {!!record.preservedResourceCount && <p className="mt-2 text-sm text-amber-900">{getLocalizedText(IMPORT_TEXT.preservedCountLabel, locale)}：{record.preservedResourceCount}</p>}
                  {record.errorMessage && <p className="mt-2 max-w-md text-sm text-red-700">{record.errorMessage}</p>}
                  {!!record.warnings?.length && <ImportWarnings warnings={record.warnings} />}
                </td>
                <td className="break-words px-4 py-4 text-sm text-gray-500 [overflow-wrap:anywhere]">
                  <span title={record.sourceName}>{record.sourceType} · {record.sourceName?.split(/[\\/]/).pop()}</span>
                </td>
                <td className="break-words px-4 py-4 text-sm text-gray-500 [overflow-wrap:anywhere]">
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
