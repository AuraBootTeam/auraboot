/**
 * DataLimitBanner — Informs the user when a view is showing a subset of total records.
 * Optionally offers a "Switch to Table view" link for full pagination.
 */

import React from 'react';
import { useI18n } from '~/contexts/I18nContext';

export interface DataLimitBannerProps {
  fetchedCount: number;
  totalCount: number;
  onSwitchToTableView?: () => void;
  className?: string;
}

export const DataLimitBanner: React.FC<DataLimitBannerProps> = ({
  fetchedCount,
  totalCount,
  onSwitchToTableView,
  className,
}) => {
  const { t } = useI18n();
  if (totalCount <= fetchedCount) return null;

  return (
    <div
      data-testid="data-limit-banner"
      className={`rounded-md border border-blue-200 bg-blue-50 px-3 py-2 text-xs text-blue-800 dark:border-blue-800 dark:bg-blue-950 dark:text-blue-300 ${className || ''}`}
    >
      {t(
        'common.saved_view_data_limit_summary',
        { fetchedCount, totalCount },
        '当前显示 {fetchedCount}/{totalCount} 条记录。',
      )}{' '}
      {onSwitchToTableView && (
        <button
          type="button"
          onClick={onSwitchToTableView}
          className="font-medium underline hover:text-blue-900 dark:hover:text-blue-200"
        >
          {t(
            'common.saved_view_switch_to_table_full',
            undefined,
            '切换到表格查看完整分页。',
          )}
        </button>
      )}
    </div>
  );
};

export default DataLimitBanner;
