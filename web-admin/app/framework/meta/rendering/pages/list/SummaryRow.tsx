/**
 * SummaryRow — column aggregation footer row for the list table (T10).
 *
 * Renders a single <tfoot> row aligned to the table's columns. Each column that
 * declares an `aggregate` shows its computed value (sum / avg / count / min /
 * max) over the CURRENT page's rows, prefixed with a short localized label
 * (Σ / Avg / Count / Min / Max). Non-aggregated columns, the selection column,
 * and the action column render blank cells so the layout stays aligned.
 *
 * Token-styled (design system §1): bg-subtle / border-border / text-text-2,
 * tabular-nums for numeric alignment. i18n: labels via t('list.summary.*') with
 * English fallbacks (matches the backend-driven resource resolution pattern).
 *
 * Spec: auraboot-enterprise/docs/standards/core/ux-design-system.md §3.
 */
import React, { useMemo } from 'react';
import type { ColumnConfig } from '~/framework/meta/schemas/types';
import {
  aggregateColumn,
  formatAggregateValue,
  isAggregateKind,
  type AggregateKind,
} from './columnAggregation';

export interface SummaryRowProps {
  /** Ordered data columns (excludes the action column). */
  columns: ColumnConfig[];
  /** Rows currently rendered on this page. */
  rows: Record<string, unknown>[];
  /** Whether a leading selection (checkbox) column is present. */
  enableSelection: boolean;
  /** Whether a trailing action column is present. */
  hasActionColumn: boolean;
  /** Per-column rendered width (px) — keeps footer cells aligned to the body. */
  getColumnWidth: (column: ColumnConfig) => number;
  /** Active locale for number/currency formatting. */
  locale: string;
  /** i18n translator (returns the key unchanged when unresolved). */
  t: (key: string) => string;
}

const SELECTION_COLUMN_WIDTH = 40;

const KIND_LABEL_FALLBACK: Record<AggregateKind, string> = {
  sum: 'Σ',
  avg: 'Avg',
  count: 'Count',
  min: 'Min',
  max: 'Max',
};

function resolveKindLabel(kind: AggregateKind, t: (key: string) => string): string {
  const key = `list.summary.${kind}`;
  const resolved = t(key);
  return resolved && resolved !== key ? resolved : KIND_LABEL_FALLBACK[kind];
}

/**
 * Whether the columns declare at least one aggregate — i.e. the footer has
 * anything to show. Used by the table to decide whether to mount this row.
 */
export function hasAnyAggregate(columns: ColumnConfig[]): boolean {
  return columns.some((c) => !c.isActionColumn && isAggregateKind(c.aggregate));
}

export function SummaryRow({
  columns,
  rows,
  enableSelection,
  hasActionColumn,
  getColumnWidth,
  locale,
  t,
}: SummaryRowProps) {
  const summaryLabel = useMemo(() => {
    const key = 'list.summary.label';
    const resolved = t(key);
    return resolved && resolved !== key ? resolved : 'Summary';
  }, [t]);

  // Precompute each column's aggregate so render stays cheap.
  const cellValues = useMemo(() => {
    return columns.map((column) => {
      if (column.isActionColumn || !isAggregateKind(column.aggregate)) {
        return null;
      }
      const kind = column.aggregate;
      const value = aggregateColumn(rows, column.field, kind);
      const formatted = formatAggregateValue(
        value,
        {
          valueType: column.valueType,
          currencyCode: column.currencyCode,
          kind,
        },
        locale,
      );
      return { kind, label: resolveKindLabel(kind, t), formatted };
    });
  }, [columns, rows, locale, t]);

  return (
    <tfoot
      className="bg-subtle border-border text-text-2 border-t text-sm font-medium"
      data-testid="list-summary-row"
    >
      <tr>
        {enableSelection && (
          <td
            className="px-3 py-2"
            style={{ width: `${SELECTION_COLUMN_WIDTH}px` }}
            aria-hidden="true"
          />
        )}
        {columns.map((column, index) => {
          const cell = cellValues[index];
          const width = getColumnWidth(column);
          const alignClass =
            column.align === 'right'
              ? 'text-right'
              : column.align === 'center'
                ? 'text-center'
                : index === 0 && !enableSelection
                  ? 'text-left'
                  : 'text-right';

          if (!cell) {
            // Non-aggregated column. The first textual cell carries the row
            // label ("Summary") so the footer reads clearly; the rest are blank.
            const isLabelCell = index === 0;
            return (
              <td
                key={column.field}
                className={`text-text-3 px-6 py-2 whitespace-nowrap ${
                  isLabelCell ? 'text-left' : ''
                }`}
                style={{ width: `${width}px`, maxWidth: `${width}px` }}
                data-testid={`summary-cell-${column.field}`}
              >
                {isLabelCell ? summaryLabel : null}
              </td>
            );
          }

          return (
            <td
              key={column.field}
              className={`px-6 py-2 whitespace-nowrap tabular-nums ${alignClass}`}
              style={{ width: `${width}px`, maxWidth: `${width}px` }}
              data-testid={`summary-cell-${column.field}`}
              data-aggregate={cell.kind}
            >
              <span className="text-text-3 mr-1.5 text-xs font-normal">{cell.label}</span>
              <span className="text-text font-semibold">{cell.formatted}</span>
            </td>
          );
        })}
        {hasActionColumn && (
          <td className="px-2 py-2" aria-hidden="true" data-testid="summary-cell-actions" />
        )}
      </tr>
    </tfoot>
  );
}
