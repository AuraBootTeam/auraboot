import React, { useEffect, useMemo, useState } from 'react';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import type { MonthlyGridConfig } from '~/framework/meta/schemas/types';

interface MonthlyGridViewerProps {
  config: MonthlyGridConfig;
  parentRecordPid: string;
  token?: string;
  locale?: string;
  t?: (key: string) => string;
}

const MONTHS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12];

export const MonthlyGridViewer: React.FC<MonthlyGridViewerProps> = ({
  config,
  parentRecordPid,
  token,
  locale = 'zh-CN',
  t = (key: string) => key,
}) => {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [parentRows, setParentRows] = useState<Record<string, any>[]>([]);
  const [monthlyByParent, setMonthlyByParent] = useState<
    Record<string, Record<number, Record<string, any>>>
  >({});

  useEffect(() => {
    if (!parentRecordPid) return;

    const load = async () => {
      setLoading(true);
      setError(null);

      try {
        const parentRecords = await loadParentRows(config, parentRecordPid, token);
        const sortedParents = [...parentRecords].sort((a, b) => {
          const sortField = config.parentSortField;
          if (!sortField) return 0;
          const av = Number(a[sortField] ?? 0);
          const bv = Number(b[sortField] ?? 0);
          return av - bv;
        });

        setParentRows(sortedParents);

        const grouped: Record<string, Record<number, Record<string, any>>> = {};
        for (const parent of sortedParents) {
          const parentPid = parent.pid || parent.id;
          if (!parentPid) continue;

          const childRows = await loadChildRows(config, String(parentPid), token);
          const monthMap: Record<number, Record<string, any>> = {};
          for (const row of childRows) {
            const monthVal = Number(row[config.monthField || 'ap_month']);
            if (Number.isInteger(monthVal) && monthVal >= 1 && monthVal <= 12) {
              monthMap[monthVal] = row;
            }
          }
          grouped[String(parentPid)] = monthMap;
        }

        setMonthlyByParent(grouped);
      } catch (e) {
        console.error('[MonthlyGridViewer] load failed', e);
        setError(e instanceof Error ? e.message : 'Failed to load monthly grid data');
      } finally {
        setLoading(false);
      }
    };

    load();
  }, [config, parentRecordPid, token]);

  const metrics = config.metrics || [];
  const displayField = config.parentDisplayField || 'ap_wp_name';

  const overallTotals = useMemo(() => {
    const totals: Record<string, number> = {};
    for (const metric of metrics) {
      totals[metric.field] = 0;
    }

    for (const parent of parentRows) {
      const parentPid = String(parent.pid || parent.id || '');
      const monthMap = monthlyByParent[parentPid] || {};
      for (const month of MONTHS) {
        const row = monthMap[month];
        if (!row) continue;
        for (const metric of metrics) {
          totals[metric.field] += Number(row[metric.field] || 0);
        }
      }
    }

    return totals;
  }, [metrics, parentRows, monthlyByParent]);

  if (loading) {
    return (
      <div className="text-text-3 py-8 text-center text-sm" data-testid="monthly-grid-viewer">
        {t('common.loading') || 'Loading...'}
      </div>
    );
  }

  if (error) {
    return (
      <div className="text-status-red py-4 text-center text-sm" data-testid="monthly-grid-viewer">
        {error}
      </div>
    );
  }

  if (parentRows.length === 0) {
    return (
      <div className="text-text-3 py-6 text-center text-sm" data-testid="monthly-grid-viewer">
        {t('common.noData') || 'No data'}
      </div>
    );
  }

  return (
    <div
      className="rounded-card border-border overflow-auto border"
      data-testid="monthly-grid-viewer"
    >
      <table className="divide-border w-full min-w-[1400px] divide-y">
        <thead className="bg-subtle">
          <tr>
            <th
              rowSpan={2}
              className="text-text-2 px-3 py-2 text-left text-xs font-medium uppercase"
            >
              {t('annualPlan.workPackage') || '工作包'}
            </th>
            {MONTHS.map((m) => (
              <th
                key={`month-${m}`}
                colSpan={Math.max(metrics.length, 1)}
                className="border-border text-text-2 border-l px-2 py-2 text-center text-xs font-medium uppercase"
              >
                {m}月
              </th>
            ))}
            <th
              colSpan={Math.max(metrics.length, 1)}
              className="border-border text-text-2 border-l px-2 py-2 text-center text-xs font-medium uppercase"
            >
              {t('common.total') || '合计'}
            </th>
          </tr>
          <tr>
            {MONTHS.map((m) =>
              metrics.map((metric) => (
                <th
                  key={`metric-${m}-${metric.field}`}
                  className="text-text-2 border-l border-border px-2 py-2 text-right text-xs font-medium"
                >
                  {metric.label ? getLocalizedText(metric.label as any, locale, t) : metric.field}
                </th>
              )),
            )}
            {metrics.map((metric) => (
              <th
                key={`total-${metric.field}`}
                className="text-text-2 border-l border-border px-2 py-2 text-right text-xs font-medium"
              >
                {metric.label ? getLocalizedText(metric.label as any, locale, t) : metric.field}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="bg-panel divide-y divide-gray-100">
          {parentRows.map((parent, index) => {
            const parentPid = String(parent.pid || parent.id || '');
            const monthMap = monthlyByParent[parentPid] || {};
            const rowTotals: Record<string, number> = {};
            for (const metric of metrics) {
              rowTotals[metric.field] = 0;
            }

            return (
              <tr key={parentPid || index} data-testid={`monthly-grid-row-${index}`}>
                <td className="text-text-2 px-3 py-2 text-sm whitespace-nowrap">
                  {parent[displayField] ?? parentPid}
                </td>
                {MONTHS.map((month) =>
                  metrics.map((metric) => {
                    const value = Number(monthMap[month]?.[metric.field] || 0);
                    rowTotals[metric.field] += value;
                    return (
                      <td
                        key={`${parentPid}-${month}-${metric.field}`}
                        className="text-text-2 border-l border-border px-2 py-2 text-right text-sm"
                      >
                        {formatNumber(value)}
                      </td>
                    );
                  }),
                )}
                {metrics.map((metric) => (
                  <td
                    key={`${parentPid}-total-${metric.field}`}
                    className="border-border bg-subtle text-text border-l px-2 py-2 text-right text-sm font-semibold"
                  >
                    {formatNumber(rowTotals[metric.field])}
                  </td>
                ))}
              </tr>
            );
          })}
        </tbody>
        <tfoot className="border-border bg-subtle border-t">
          <tr>
            <td className="text-text-2 px-3 py-2 text-sm font-semibold">
              {t('common.total') || '合计'}
            </td>
            {MONTHS.map((month) =>
              metrics.map((metric) => {
                let monthTotal = 0;
                for (const parent of parentRows) {
                  const parentPid = String(parent.pid || parent.id || '');
                  const row = monthlyByParent[parentPid]?.[month];
                  monthTotal += Number(row?.[metric.field] || 0);
                }
                return (
                  <td
                    key={`summary-${month}-${metric.field}`}
                    className="text-text border-l border-border px-2 py-2 text-right text-sm font-semibold"
                  >
                    {formatNumber(monthTotal)}
                  </td>
                );
              }),
            )}
            {metrics.map((metric) => (
              <td
                key={`overall-${metric.field}`}
                className="border-border text-text border-l px-2 py-2 text-right text-sm font-semibold"
              >
                {formatNumber(overallTotals[metric.field] || 0)}
              </td>
            ))}
          </tr>
        </tfoot>
      </table>
    </div>
  );
};

async function loadParentRows(config: MonthlyGridConfig, parentRecordPid: string, token?: string) {
  if (config.resolveVia) {
    const rv = config.resolveVia;
    const intermediateModel = rv.intermediateModel;
    const intermediateFilters: Array<{ fieldName: string; operator: string; value: any }> = [
      { fieldName: rv.intermediateParentField, operator: 'EQ', value: parentRecordPid },
    ];
    if (rv.filterCondition?.field) {
      intermediateFilters.push({
        fieldName: rv.filterCondition.field,
        operator: rv.filterCondition.operator || 'EQ',
        value: rv.filterCondition.value,
      });
    }

    const intermediateRes = await fetchResult<any>(`/api/dynamic/${intermediateModel}/list`, {
      method: 'get',
      params: {
        pageNum: 1,
        pageSize: 100,
        filters: JSON.stringify(intermediateFilters),
      },
      token,
    });
    const records = intermediateRes.data?.records ?? [];
    if (
      !ResultHelper.isSuccess(intermediateRes) ||
      !Array.isArray(records) ||
      records.length === 0
    ) {
      return [];
    }

    const parentPid = records[0].pid;
    const model = config.parentModel;
    const parentRes = await fetchResult<any>(`/api/dynamic/${model}/list`, {
      method: 'get',
      params: {
        pageNum: 1,
        pageSize: 500,
        filters: JSON.stringify([
          { fieldName: config.parentField, operator: 'EQ', value: parentPid },
        ]),
      },
      token,
    });
    return parentRes.data?.records ?? [];
  }

  const model = config.parentModel;
  const parentRes = await fetchResult<any>(`/api/dynamic/${model}/list`, {
    method: 'get',
    params: {
      pageNum: 1,
      pageSize: 500,
      filters: JSON.stringify([
        { fieldName: config.parentField, operator: 'EQ', value: parentRecordPid },
      ]),
    },
    token,
  });
  return parentRes.data?.records ?? [];
}

async function loadChildRows(config: MonthlyGridConfig, parentPid: string, token?: string) {
  const childModel = config.childModel;
  const childRes = await fetchResult<any>(`/api/dynamic/${childModel}/list`, {
    method: 'get',
    params: {
      pageNum: 1,
      pageSize: 500,
      filters: JSON.stringify([
        { fieldName: config.childParentField, operator: 'EQ', value: parentPid },
      ]),
    },
    token,
  });
  return childRes.data?.records ?? [];
}

function formatNumber(value: number): string {
  return value.toLocaleString('zh-CN', {
    minimumFractionDigits: 0,
    maximumFractionDigits: 2,
  });
}

export default MonthlyGridViewer;
