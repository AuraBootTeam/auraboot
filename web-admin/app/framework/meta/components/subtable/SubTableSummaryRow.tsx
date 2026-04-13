/**
 * SubTableSummaryRow — Fixed footer row showing aggregated values (SUM, AVG, COUNT, MIN, MAX).
 *
 * Updates reactively when row data changes.
 * Styled with bold text and gray background for visual distinction.
 */

import React, { useMemo } from 'react';
import type { ColumnConfig, SummaryConfig } from '~/framework/meta/schemas/types';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';

export interface SubTableSummaryRowProps {
  columns: ColumnConfig[];
  rows: Record<string, any>[];
  summary: SummaryConfig;
  locale?: string;
  t?: (key: string) => string;
  /** Extra columns count (actions, sortable handle, etc.) */
  extraColCount?: number;
}

export interface ComputedSummaryValue {
  field: string;
  value: number;
  label?: string | any;
}

export const SubTableSummaryRow: React.FC<SubTableSummaryRowProps> = ({
  columns,
  rows,
  summary,
  locale = 'zh-CN',
  t = (k: string) => k,
  extraColCount = 0,
}) => {
  const summaryValues = useMemo(() => computeSummary(rows, summary), [rows, summary]);

  if (summaryValues.length === 0 || rows.length === 0) return null;

  return (
    <tfoot className="border-t-2 border-gray-200 bg-gray-50" data-testid="subtable-summary">
      <tr>
        {columns.map((col: ColumnConfig, colIndex: number) => {
          const summaryItem = summaryValues.find((s) => s.field === col.field);
          if (summaryItem) {
            return (
              <td
                key={col.field}
                className={`px-4 py-2.5 text-sm font-semibold text-gray-900 ${
                  col.align === 'right'
                    ? 'text-right'
                    : col.align === 'center'
                      ? 'text-center'
                      : 'text-left'
                }`}
              >
                {summaryItem.label && (
                  <span className="mr-1 text-gray-500">
                    {getLocalizedText(summaryItem.label, locale, t)}:
                  </span>
                )}
                {formatSummaryNumber(summaryItem.value)}
              </td>
            );
          }
          // First column without a summary value shows "Total" label
          if (colIndex === 0 && !summaryValues.some((s) => s.field === col.field)) {
            return (
              <td key={col.field} className="px-4 py-2.5 text-sm font-semibold text-gray-500">
                {t('common.total') !== 'common.total' ? t('common.total') : 'Total'}
              </td>
            );
          }
          return <td key={col.field} className="px-4 py-2.5" />;
        })}
        {/* Fill extra columns (actions, drag handle) */}
        {Array.from({ length: extraColCount }).map((_, i) => (
          <td key={`extra-${i}`} className="px-4 py-2.5" />
        ))}
      </tr>
    </tfoot>
  );
};

/**
 * Compute aggregated summary values from rows based on SummaryConfig.
 */
export function computeSummary(
  rows: Record<string, any>[],
  summary?: SummaryConfig,
): ComputedSummaryValue[] {
  if (!summary?.fields || rows.length === 0) return [];

  return summary.fields.map((sf) => {
    const values = rows.map((r) => Number(r[sf.field]) || 0);
    let value = 0;

    switch (sf.aggregation) {
      case 'sum':
        value = values.reduce((a, b) => a + b, 0);
        break;
      case 'avg':
        value = values.length > 0 ? values.reduce((a, b) => a + b, 0) / values.length : 0;
        break;
      case 'count':
        value = rows.length;
        break;
      case 'min':
        value = values.length > 0 ? Math.min(...values) : 0;
        break;
      case 'max':
        value = values.length > 0 ? Math.max(...values) : 0;
        break;
    }

    return { field: sf.field, value, label: sf.label };
  });
}

function formatSummaryNumber(num: number): string {
  return num.toLocaleString(undefined, {
    minimumFractionDigits: 0,
    maximumFractionDigits: 2,
  });
}
