/**
 * TransactionPageRenderer — GAP-086 ERP read-only ledger view
 *
 * Renders a TRANSACTION page type:
 *   - Read-only table with Date | Description | Debit | Credit | Balance columns
 *   - Pagination
 *   - Export to Excel button
 *
 * Used for: GL transactions, inventory movements, payroll history, etc.
 *
 * DSL usage:
 *   kind: "Transaction"
 *   modelCode: "gl-entry"
 *   columns:           # override default ledger columns
 *     - field: txnDate
 *     - field: description
 *     - field: debitAmount
 *     - field: creditAmount
 *     - field: balance
 *   filters:           # optional pre-filter blocks
 *     - blockType: filter-form
 */

import { useState, useEffect, useCallback, useMemo } from 'react';
import type { PageContentProps } from '~/meta/profiles/types';
import { usePageRuntime } from '~/meta/rendering/pages/hooks/usePageRuntime';
import { buildApiEndpoint, getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { fetchResult } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useToastContext } from '~/contexts/ToastContext';
import { LoadingSpinner } from '~/components/LoadingSpinner';
import { Pagination } from '~/components/Pagination';
import type { FieldConfig } from '~/meta/schemas/types';

interface TransactionRow {
  [key: string]: any;
  id?: string;
  pid?: string;
}

// Default ledger column keys (used when DSL doesn't specify columns)
const DEFAULT_COLUMNS = [
  { field: 'txnDate', label: 'Date', width: '120px' },
  { field: 'description', label: 'Description', width: 'auto' },
  { field: 'debitAmount', label: 'Debit', width: '110px', align: 'right' as const },
  { field: 'creditAmount', label: 'Credit', width: '110px', align: 'right' as const },
  { field: 'balance', label: 'Balance', width: '120px', align: 'right' as const },
];

function formatCurrency(val: any): string {
  if (val == null || val === '') return '—';
  const num = Number(val);
  if (isNaN(num)) return String(val);
  return num.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatDate(val: any): string {
  if (!val) return '—';
  try {
    return new Date(val).toLocaleDateString();
  } catch {
    return String(val);
  }
}

// ─── Main component ───────────────────────────────────────────────────────────

export function TransactionPageRenderer(props: PageContentProps) {
  const { schema, tableName, recordId, token } = props;

  const [rows, setRows] = useState<TransactionRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(1);
  const [pageSize] = useState(50);
  const [total, setTotal] = useState(0);
  const [exporting, setExporting] = useState(false);

  const { showErrorToast } = useToastContext();

  const { t, locale } = usePageRuntime(schema, {
    token: token || undefined,
    additionalContext: {},
  });

  // ── Extract DSL columns ───────────────────────────────────────────────────

  const dslColumns = useMemo(() => {
    const allBlocks: any[] = [];
    if (schema?.areas) {
      Object.values(schema.areas).forEach((area: any) => {
        (area.blocks ?? []).forEach((b: any) => allBlocks.push(b));
      });
    }
    const tableBlock = allBlocks.find(
      (b) => b.blockType === 'table' || b.blockType === 'data-table',
    );
    return (tableBlock?.columns ?? schema?.columns ?? []) as FieldConfig[];
  }, [schema]);

  const columns: Array<{
    field: string;
    label: string;
    width: string;
    align?: 'left' | 'right' | 'center';
  }> = useMemo(() => {
    if (dslColumns.length > 0) {
      return dslColumns.map((col) => ({
        field: col.field,
        label: getLocalizedText(col.label, locale, t) || col.field,
        width: 'auto',
        align: ['debit', 'credit', 'balance', 'amount'].some((k) =>
          col.field.toLowerCase().includes(k),
        )
          ? 'right'
          : 'left',
      }));
    }
    return DEFAULT_COLUMNS.map((col) => ({
      ...col,
      label: t(`transaction.col.${col.field}`) || col.label,
    }));
  }, [dslColumns, locale, t]);

  // ── Detect numeric / date columns for formatting ──────────────────────────

  const isNumericCol = useCallback((field: string): boolean => {
    return ['debit', 'credit', 'balance', 'amount', 'qty', 'quantity', 'price', 'total'].some((k) =>
      field.toLowerCase().includes(k),
    );
  }, []);

  const isDateCol = useCallback((field: string): boolean => {
    return ['date', 'time', 'at', 'on'].some((k) => field.toLowerCase().includes(k));
  }, []);

  const formatCell = useCallback(
    (field: string, val: any): string => {
      if (val == null) return '—';
      if (isNumericCol(field)) return formatCurrency(val);
      if (isDateCol(field)) return formatDate(val);
      return String(val);
    },
    [isNumericCol, isDateCol],
  );

  // ── Load data ─────────────────────────────────────────────────────────────

  const loadData = useCallback(
    async (currentPage: number) => {
      if (!tableName) return;
      setLoading(true);
      try {
        let endpoint = `${buildApiEndpoint(tableName)}?pageNum=${currentPage}&pageSize=${pageSize}`;
        if (recordId) endpoint += `&parentId=${recordId}`;
        const result = await fetchResult<any>(endpoint, {
          method: 'get',
          token: token || undefined,
        });
        if (ResultHelper.isSuccess(result)) {
          const data = result.data;
          const records = data?.records ?? data?.list ?? data ?? [];
          const tot = data?.total ?? records.length;
          setRows(records);
          setTotal(tot);
        }
      } catch (err: any) {
        showErrorToast(err?.message ?? 'Failed to load transactions');
      } finally {
        setLoading(false);
      }
    },
    [tableName, pageSize, recordId, token, showErrorToast],
  );

  useEffect(() => {
    loadData(page);
  }, [loadData, page]);

  // ── Running balance ────────────────────────────────────────────────────────

  // Detect debit/credit columns and compute running balance if not already in data
  const hasExplicitBalance = useMemo(
    () => columns.some((c) => c.field.toLowerCase().includes('balance')),
    [columns],
  );
  const debitField = useMemo(
    () => columns.find((c) => c.field.toLowerCase().includes('debit'))?.field,
    [columns],
  );
  const creditField = useMemo(
    () => columns.find((c) => c.field.toLowerCase().includes('credit'))?.field,
    [columns],
  );

  const rowsWithBalance = useMemo((): TransactionRow[] => {
    if (hasExplicitBalance || (!debitField && !creditField)) return rows;
    let runningBalance = 0;
    return rows.map((row) => {
      const debit = Number(row[debitField!] ?? 0);
      const credit = Number(row[creditField!] ?? 0);
      runningBalance += debit - credit;
      return { ...row, balance: runningBalance } as TransactionRow;
    });
  }, [rows, hasExplicitBalance, debitField, creditField]);

  // ── Column totals ──────────────────────────────────────────────────────────

  const columnTotals = useMemo(() => {
    const totals: Record<string, number> = {};
    columns.forEach((col) => {
      if (isNumericCol(col.field) && !col.field.toLowerCase().includes('balance')) {
        totals[col.field] = rowsWithBalance.reduce(
          (sum, row) => sum + Number(row[col.field] ?? 0),
          0,
        );
      }
    });
    return totals;
  }, [columns, rowsWithBalance, isNumericCol]);

  // ── Excel export ──────────────────────────────────────────────────────────

  const handleExport = useCallback(async () => {
    if (!tableName) return;
    setExporting(true);
    try {
      const endpoint = `${buildApiEndpoint(tableName)}/export?format=xlsx&pageSize=10000`;
      const res = await fetch(endpoint, {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${tableName}_transactions.xlsx`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (err: any) {
      showErrorToast(err?.message ?? 'Export failed');
    } finally {
      setExporting(false);
    }
  }, [tableName, token, showErrorToast]);

  // ── Render ────────────────────────────────────────────────────────────────

  const title =
    typeof schema?.title === 'string'
      ? schema.title
      : getLocalizedText(schema?.title, locale, t) || tableName;

  return (
    <div className="mx-auto w-full px-6 py-6" data-testid="transaction-page">
      {/* Header */}
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-lg font-semibold text-gray-900 dark:text-white">{title}</h1>
        <button
          onClick={handleExport}
          disabled={exporting || loading}
          className="flex items-center gap-1.5 rounded-lg border border-gray-300 px-3 py-1.5 text-sm text-gray-600 transition-colors hover:bg-gray-50 disabled:opacity-50 dark:border-gray-600 dark:text-gray-300 dark:hover:bg-gray-700"
          data-testid="transaction-export-btn"
        >
          {exporting ? 'Exporting...' : 'Export Excel'}
        </button>
      </div>

      {/* Ledger table */}
      <div className="overflow-hidden rounded-xl border border-gray-100 bg-white shadow-sm dark:border-gray-700 dark:bg-gray-800">
        {loading ? (
          <div className="p-8 text-center">
            <LoadingSpinner />
          </div>
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className="w-full text-sm" data-testid="transaction-table">
                <thead>
                  <tr className="border-b border-gray-100 bg-gray-50 dark:border-gray-700 dark:bg-gray-900/30">
                    <th className="w-8 px-4 py-2.5 text-left text-xs font-medium text-gray-500 dark:text-gray-400">
                      #
                    </th>
                    {columns.map((col) => (
                      <th
                        key={col.field}
                        style={{ width: col.width !== 'auto' ? col.width : undefined }}
                        className={`px-4 py-2.5 text-xs font-medium text-gray-500 dark:text-gray-400 ${
                          col.align === 'right' ? 'text-right' : 'text-left'
                        }`}
                      >
                        {col.label}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {rowsWithBalance.length === 0 ? (
                    <tr>
                      <td
                        colSpan={columns.length + 1}
                        className="px-4 py-8 text-center text-sm text-gray-400"
                      >
                        {t('common.noData') || 'No transactions found.'}
                      </td>
                    </tr>
                  ) : (
                    rowsWithBalance.map((row, idx) => (
                      <tr
                        key={row.pid ?? row.id ?? idx}
                        className="border-b border-gray-50 transition-colors hover:bg-gray-50/50 dark:border-gray-700/40 dark:hover:bg-gray-700/20"
                      >
                        <td className="px-4 py-2 text-xs text-gray-400">
                          {(page - 1) * pageSize + idx + 1}
                        </td>
                        {columns.map((col) => {
                          const cellVal = row[col.field];
                          const isBalance = col.field.toLowerCase().includes('balance');
                          const numVal = Number(cellVal);
                          return (
                            <td
                              key={col.field}
                              className={`px-4 py-2 ${
                                col.align === 'right' ? 'text-right' : 'text-left'
                              } ${
                                isBalance && numVal < 0
                                  ? 'text-red-600 dark:text-red-400'
                                  : 'text-gray-700 dark:text-gray-300'
                              }`}
                            >
                              {formatCell(col.field, cellVal)}
                            </td>
                          );
                        })}
                      </tr>
                    ))
                  )}
                </tbody>
                {/* Totals footer */}
                {Object.keys(columnTotals).length > 0 && (
                  <tfoot>
                    <tr className="border-t-2 border-gray-200 bg-gray-50 font-semibold dark:border-gray-600 dark:bg-gray-900/30">
                      <td className="px-4 py-2.5 text-xs text-gray-500">
                        {t('common.total') || 'Total'}
                      </td>
                      {columns.map((col) => (
                        <td
                          key={col.field}
                          className={`px-4 py-2.5 text-sm ${
                            col.align === 'right' ? 'text-right' : 'text-left'
                          } text-gray-900 dark:text-white`}
                        >
                          {columnTotals[col.field] !== undefined
                            ? formatCurrency(columnTotals[col.field])
                            : ''}
                        </td>
                      ))}
                    </tr>
                  </tfoot>
                )}
              </table>
            </div>

            {/* Pagination */}
            {total > pageSize && (
              <div className="border-t border-gray-100 px-4 py-3 dark:border-gray-700">
                <Pagination current={page} total={total} pageSize={pageSize} onChange={setPage} />
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}

export default TransactionPageRenderer;
