/**
 * ViewEmptyState — Unified empty/error/not-configured state for all view types.
 */

import React from 'react';

export type EmptyStateVariant = 'not-configured' | 'no-data' | 'error';

export interface ViewEmptyStateProps {
  variant: EmptyStateVariant;
  title?: string;
  description?: string;
  error?: string;
  onConfigure?: () => void;
  onSwitchToTableView?: () => void;
  onRetry?: () => void;
  className?: string;
}

const CogIcon: React.FC<{ className?: string }> = ({ className }) => (
  <svg className={className} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
    <path
      strokeLinecap="round"
      strokeLinejoin="round"
      d="M9.594 3.94c.09-.542.56-.94 1.11-.94h2.593c.55 0 1.02.398 1.11.94l.213 1.281c.063.374.313.686.645.87.074.04.147.083.22.127.325.196.72.257 1.075.124l1.217-.456a1.125 1.125 0 011.37.49l1.296 2.247a1.125 1.125 0 01-.26 1.431l-1.003.827c-.293.241-.438.613-.43.992a7.723 7.723 0 010 .255c-.008.378.137.75.43.991l1.004.827c.424.35.534.955.26 1.43l-1.298 2.248a1.125 1.125 0 01-1.369.491l-1.217-.456c-.355-.133-.75-.072-1.076.124a6.47 6.47 0 01-.22.128c-.331.183-.581.495-.644.869l-.213 1.281c-.09.543-.56.94-1.11.94h-2.594c-.55 0-1.019-.398-1.11-.94l-.213-1.281c-.062-.374-.312-.686-.644-.87a6.52 6.52 0 01-.22-.127c-.325-.196-.72-.257-1.076-.124l-1.217.456a1.125 1.125 0 01-1.369-.49l-1.297-2.247a1.125 1.125 0 01.26-1.431l1.004-.827c.292-.24.437-.613.43-.991a6.932 6.932 0 010-.255c.007-.38-.138-.751-.43-.992l-1.004-.827a1.125 1.125 0 01-.26-1.43l1.297-2.247a1.125 1.125 0 011.37-.491l1.216.456c.356.133.751.072 1.076-.124.072-.044.146-.086.22-.128.332-.183.582-.495.644-.869l.214-1.28z"
    />
    <path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
  </svg>
);

const InboxIcon: React.FC<{ className?: string }> = ({ className }) => (
  <svg className={className} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
    <path
      strokeLinecap="round"
      strokeLinejoin="round"
      d="M20 13V6a2 2 0 00-2-2H6a2 2 0 00-2 2v7m16 0v5a2 2 0 01-2 2H6a2 2 0 01-2-2v-5m16 0h-2.586a1 1 0 00-.707.293l-2.414 2.414a1 1 0 01-.707.293h-3.172a1 1 0 01-.707-.293l-2.414-2.414A1 1 0 006.586 13H4"
    />
  </svg>
);

const ExclamationIcon: React.FC<{ className?: string }> = ({ className }) => (
  <svg className={className} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
    <path
      strokeLinecap="round"
      strokeLinejoin="round"
      d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126zM12 15.75h.007v.008H12v-.008z"
    />
  </svg>
);

export const ViewEmptyState: React.FC<ViewEmptyStateProps> = ({
  variant,
  title,
  description,
  error,
  onConfigure,
  onSwitchToTableView,
  onRetry,
  className,
}) => {
  const borderColor = variant === 'error' ? 'border-red-200 dark:border-red-800' : 'border-gray-200 dark:border-gray-700';

  return (
    <div
      data-testid={`view-empty-${variant}`}
      className={`flex items-center justify-center rounded-lg border bg-white p-8 dark:bg-gray-800 ${borderColor} ${className || ''}`}
      style={{ minHeight: 400 }}
    >
      <div className="flex max-w-sm flex-col items-center text-center">
        {variant === 'not-configured' && (
          <>
            <CogIcon className="mb-4 h-12 w-12 text-gray-400 dark:text-gray-500" />
            <h3 className="mb-1 text-sm font-medium text-gray-900 dark:text-gray-100">
              {title || 'View not configured'}
            </h3>
            {description && (
              <p className="mb-4 text-xs text-gray-500 dark:text-gray-400">{description}</p>
            )}
            <div className="flex gap-2">
              {onConfigure && (
                <button
                  type="button"
                  onClick={onConfigure}
                  className="rounded-md bg-blue-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-blue-700"
                >
                  Configure
                </button>
              )}
              {onSwitchToTableView && (
                <button
                  type="button"
                  onClick={onSwitchToTableView}
                  className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-xs font-medium text-gray-700 hover:bg-gray-50 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600"
                >
                  Switch to Table
                </button>
              )}
            </div>
          </>
        )}

        {variant === 'no-data' && (
          <>
            <InboxIcon className="mb-4 h-12 w-12 text-gray-400 dark:text-gray-500" />
            <h3 className="mb-1 text-sm font-medium text-gray-900 dark:text-gray-100">
              {title || 'No data'}
            </h3>
            {description && (
              <p className="text-xs text-gray-500 dark:text-gray-400">{description}</p>
            )}
          </>
        )}

        {variant === 'error' && (
          <>
            <ExclamationIcon className="mb-4 h-12 w-12 text-red-400 dark:text-red-500" />
            <h3 className="mb-1 text-sm font-medium text-red-800 dark:text-red-300">
              {title || 'Something went wrong'}
            </h3>
            {error && (
              <p className="mb-4 rounded bg-red-50 px-3 py-1.5 text-xs text-red-600 dark:bg-red-900/30 dark:text-red-400">
                {error}
              </p>
            )}
            {onRetry && (
              <button
                type="button"
                onClick={onRetry}
                className="rounded-md bg-red-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-red-700"
              >
                Retry
              </button>
            )}
          </>
        )}
      </div>
    </div>
  );
};

export default ViewEmptyState;
