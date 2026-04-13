/**
 * DataLimitBanner — Informs the user when a view is showing a subset of total records.
 * Optionally offers a "Switch to Table view" link for full pagination.
 */

import React from 'react';

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
  if (totalCount <= fetchedCount) return null;

  return (
    <div
      data-testid="data-limit-banner"
      className={`rounded-md border border-blue-200 bg-blue-50 px-3 py-2 text-xs text-blue-800 dark:border-blue-800 dark:bg-blue-950 dark:text-blue-300 ${className || ''}`}
    >
      Showing {fetchedCount} of {totalCount} records.{' '}
      {onSwitchToTableView && (
        <button
          type="button"
          onClick={onSwitchToTableView}
          className="font-medium underline hover:text-blue-900 dark:hover:text-blue-200"
        >
          Switch to Table view for full pagination.
        </button>
      )}
    </div>
  );
};

export default DataLimitBanner;
