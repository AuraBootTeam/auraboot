import React from 'react';
import type { ComputedFieldResult } from './types';

interface ComputedFieldIndicatorProps {
  result?: ComputedFieldResult;
  showValue?: boolean;
  className?: string;
}

/**
 * ComputedFieldIndicator - visual indicator for computed fields in forms.
 * Shows a small badge with computation status (computed, stale, error).
 *
 * @since 3.7.0
 */
export const ComputedFieldIndicator: React.FC<ComputedFieldIndicatorProps> = ({
  result,
  showValue = false,
  className = '',
}) => {
  if (!result) return null;

  const statusColor = result.error
    ? 'text-red-500 bg-red-50'
    : result.stale
      ? 'text-amber-500 bg-amber-50'
      : 'text-blue-500 bg-blue-50';

  return (
    <span
      className={`inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] font-medium ${statusColor} ${className}`}
      title={result.error ?? `Computed at ${new Date(result.evaluatedAt).toLocaleTimeString()}`}
    >
      <svg className="h-3 w-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth={2}
          d={
            result.error
              ? 'M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L4.082 16.5c-.77.833.192 2.5 1.732 2.5z'
              : 'M9 7h6m0 10v-3m-3 3h.01M9 17h.01M9 14h.01M12 14h.01M15 11h.01M12 11h.01M9 11h.01M7 21h10a2 2 0 002-2V5a2 2 0 00-2-2H7a2 2 0 00-2 2v14a2 2 0 002 2z'
          }
        />
      </svg>
      {showValue && !result.error && (
        <span className="max-w-[60px] truncate">{formatShort(result.value)}</span>
      )}
      {result.error && <span>error</span>}
    </span>
  );
};

function formatShort(value: any): string {
  if (value === null || value === undefined) return '—';
  if (typeof value === 'number') return value.toLocaleString();
  if (typeof value === 'boolean') return value ? 'Y' : 'N';
  return String(value).slice(0, 10);
}
