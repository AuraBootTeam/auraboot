/**
 * DashboardContent — Extracted dashboard rendering path from ListPageContent.
 *
 * Contains DashboardStatCard, DashboardBlockTable, and the dashboard layout.
 * Behavior-preserving extraction — no functional changes.
 */

import React, { useState, useEffect } from 'react';
import { fetchResult } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { DataSourceProvider } from '~/framework/meta/contexts/DataSourceContext';
import { deriveTestId } from '~/framework/meta/rendering/utils/deriveTestId';

import { getChartComponent, getSupportedChartTypes } from '~/smart/charts/SharedChartFactory';

// Dashboard stat card — renders stat cards from API datasource
function DashboardStatCard({
  block,
  token,
  locale,
  t,
}: {
  block: any;
  token: string | null | undefined;
  locale: string;
  t?: (key: string) => string;
}) {
  const [stats, setStats] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const dsUrl = block.dataSource?.url || block.dataSource?.endpoint;
    if (!dsUrl) {
      setLoading(false);
      return;
    }
    const params = new URLSearchParams();
    if (block.dataSource?.datasourceId) params.set('datasourceId', block.dataSource.datasourceId);
    if (block.dataSource?.maxItems) params.set('maxItems', String(block.dataSource.maxItems || 10));
    if (block.dataSource?.params) {
      Object.entries(block.dataSource.params).forEach(([k, v]) => params.set(k, String(v)));
    }
    const qs = params.toString();
    const url = dsUrl + (qs ? (dsUrl.includes('?') ? '&' : '?') + qs : '');

    fetchResult<any>(url, { method: 'get', token: token || undefined })
      .then((res) => {
        if (ResultHelper.isSuccess(res) && res.data) {
          const records = res.data.records || res.data || [];
          setStats(Array.isArray(records) ? records : [records]);
        }
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [block, token]);

  const cards = block.cards || [];
  if (loading) {
    return (
      <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
        {(cards.length > 0 ? cards : [1, 2, 3, 4]).map((_: any, i: number) => (
          <div key={i} className="h-24 animate-pulse rounded-lg bg-gray-100" />
        ))}
      </div>
    );
  }

  // If block has explicit card definitions, use them
  if (cards.length > 0) {
    return (
      <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
        {cards.map((card: any, i: number) => {
          const record = stats[0] || {};
          const value = card.field
            ? record[card.field]
            : card.valueField
              ? record[card.valueField]
              : card.value || 0;
          return (
            <div key={i} className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm">
              <p className="text-sm font-medium text-gray-500">
                {getLocalizedText(card.label || card.title, locale, t) || `Stat ${i + 1}`}
              </p>
              <p className="mt-1 text-2xl font-semibold text-gray-900">{value ?? '—'}</p>
              {card.description && (
                <p className="mt-1 text-xs text-gray-400">
                  {getLocalizedText(card.description, locale, t)}
                </p>
              )}
            </div>
          );
        })}
      </div>
    );
  }

  // Fallback: render each record as a stat card
  return (
    <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
      {stats.slice(0, 8).map((stat: any, i: number) => {
        const entries = Object.entries(stat).filter(
          ([k]) => !['id', 'pid', 'tenant_id'].includes(k),
        );
        const label = entries[0]?.[1];
        const value = entries[1]?.[1];
        return (
          <div key={i} className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm">
            <p className="text-sm font-medium text-gray-500">{String(label || `Item ${i + 1}`)}</p>
            <p className="mt-1 text-2xl font-semibold text-gray-900">{String(value ?? '—')}</p>
          </div>
        );
      })}
    </div>
  );
}

// Dashboard block — independently fetches data for its modelCode
function DashboardBlockTable({ block, token }: { block: any; token: string | null | undefined }) {
  const [rows, setRows] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    const dsUrl = block.dataSource?.url || block.dataSource?.endpoint;
    if (!block.modelCode && !dsUrl) {
      setLoading(false);
      return;
    }

    let fetchUrl: string;
    if (dsUrl) {
      // API datasource: use the configured URL directly
      const params = new URLSearchParams();
      if (block.dataSource?.datasourceId) params.set('datasourceId', block.dataSource.datasourceId);
      if (block.dataSource?.maxItems) params.set('maxItems', String(block.dataSource.maxItems));
      if (block.dataSource?.params) {
        Object.entries(block.dataSource.params).forEach(([k, v]) => params.set(k, String(v)));
      }
      const qs = params.toString();
      fetchUrl = dsUrl + (qs ? (dsUrl.includes('?') ? '&' : '?') + qs : '');
    } else {
      const slug = block.modelCode;
      const params: Record<string, any> = { page: 0, size: 10 };
      // Support both defaultFilters (array, canonical) and defaultFilter (singular, deprecated)
      const blockFilters =
        block.defaultFilters || (block.defaultFilter ? [block.defaultFilter] : undefined);
      if (blockFilters && blockFilters.length > 0) {
        const normalizedFilters = blockFilters.map((f: any) => {
          if (f.fieldName || f.field) return f;
          const entries = Object.entries(f);
          if (entries.length > 0) {
            const [key, val] = entries[0];
            return { fieldName: key, operator: 'EQ', value: val };
          }
          return f;
        });
        params.filters = JSON.stringify(normalizedFilters);
      }
      const qs = new URLSearchParams();
      Object.entries(params).forEach(([k, v]) => qs.set(k, String(v)));
      fetchUrl = `/api/dynamic/${slug}/list?${qs.toString()}`;
    }

    fetchResult<any>(fetchUrl, {
      method: 'get',
      token: token || undefined,
    })
      .then((res) => {
        if (ResultHelper.isSuccess(res) && res.data) {
          setRows(res.data.records || res.data || []);
        } else {
          setErr(res.desc || 'Failed to load');
        }
      })
      .catch((e) => setErr(e.message))
      .finally(() => setLoading(false));
  }, [block, token]);

  const columns = block.columns || block.table?.columns || [];

  if (loading) {
    return <div className="px-4 py-6 text-center text-sm text-gray-400">Loading...</div>;
  }
  if (err) {
    return <div className="px-4 py-6 text-center text-sm text-red-500">{err}</div>;
  }
  return (
    <table className="min-w-full divide-y divide-gray-200">
      <thead className="bg-gray-50">
        <tr>
          {columns.map((col: any) => (
            <th
              key={col.field}
              className={`px-4 py-2 text-xs font-medium text-gray-500 uppercase ${
                col.align === 'right' ? 'text-right' : 'text-left'
              }`}
              style={col.width ? { width: `${col.width}px` } : undefined}
            >
              {col.label
                ? typeof col.label === 'string'
                  ? col.label
                  : col.label['zh-CN'] || col.label['en'] || col.field
                : col.field}
            </th>
          ))}
        </tr>
      </thead>
      <tbody className="divide-y divide-gray-200">
        {rows.length === 0 ? (
          <tr>
            <td
              colSpan={columns.length || 1}
              className="px-4 py-4 text-center text-sm text-gray-400"
            >
              No data
            </td>
          </tr>
        ) : (
          rows.map((row: any, i: number) => (
            <tr key={row.pid || row.id || i}>
              {columns.map((col: any) => (
                <td
                  key={col.field}
                  className={`px-4 py-2 text-sm text-gray-900 ${col.align === 'right' ? 'text-right' : ''}`}
                >
                  {row[col.field] ?? '-'}
                </td>
              ))}
            </tr>
          ))
        )}
      </tbody>
    </table>
  );
}

export interface DashboardContentProps {
  schema: any;
  token: string | null | undefined;
  locale: string;
  t: (key: string) => string;
  modelCode: string;
  dataSourceManager: any;
  navigate: (path: string) => void;
}

export function DashboardContent({
  schema,
  token,
  locale,
  t,
  modelCode,
  dataSourceManager,
  navigate,
}: DashboardContentProps) {
  const dashBlocks = schema.blocks || [];
  return (
    <DataSourceProvider manager={dataSourceManager}>
      <div
        className="mx-auto w-full px-2 py-3"
        data-testid={deriveTestId('dashboard', modelCode, 'container')}
      >
        <h2 className="mb-6 text-lg font-medium text-gray-900">
          {schema.title ? getLocalizedText(schema.title, locale, t) : ''}
        </h2>
        <div className="grid grid-cols-12 gap-4">
          {dashBlocks.map((block: any) => {
            const isChartBlock = block.blockType === 'chart';
            const isStatCard = block.blockType === 'stat-card';
            return (
              <div
                key={block.id}
                className={
                  isChartBlock || isStatCard
                    ? ''
                    : 'overflow-hidden rounded-lg bg-white shadow-sm'
                }
                style={{ gridColumn: `span ${block.layout?.colSpan || 12}` }}
                data-testid={`dashboard-block-${block.id}`}
                data-ab-testid={deriveTestId('dashboard', modelCode, 'block', block.id)}
                data-block-id={block.id}
              >
                {isChartBlock ? (
                  (() => {
                    const chartType = block.chartType || 'bar';
                    const ChartComp = getChartComponent(chartType);
                    if (!ChartComp) {
                      return (
                        <div className="flex h-64 items-center justify-center rounded-lg border border-yellow-200 bg-yellow-50">
                          <div className="text-center">
                            <p className="font-medium text-yellow-800">Unsupported chart type</p>
                            <p className="mt-1 text-sm text-yellow-600">
                              <code className="rounded bg-yellow-100 px-1 py-0.5">{chartType}</code>
                            </p>
                            <p className="mt-2 text-xs text-yellow-500">
                              Supported: {getSupportedChartTypes().join(', ')}
                            </p>
                          </div>
                        </div>
                      );
                    }
                    const config = block.chartConfig || {};
                    const { dataSource: ds, ...chartProps } = config;
                    const title = block.title
                      ? typeof block.title === 'string'
                        ? block.title
                        : getLocalizedText(block.title, locale, t)
                      : '';
                    return (
                      <React.Suspense
                        fallback={
                          <div className="flex h-64 items-center justify-center rounded-lg border border-gray-200 bg-white p-4">
                            <div className="h-5 w-5 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
                          </div>
                        }
                      >
                        <ChartComp title={title} dataSource={ds} {...chartProps} />
                      </React.Suspense>
                    );
                  })()
                ) : isStatCard ? (
                  <DashboardStatCard block={block} token={token} locale={locale} t={t} />
                ) : (
                  <>
                    <div className="border-b border-gray-200 bg-gray-50 px-4 py-3">
                      <h3 className="text-sm font-medium text-gray-700">
                        {block.title ? getLocalizedText(block.title, locale, t) : block.id}
                      </h3>
                    </div>
                    <DashboardBlockTable block={block} token={token} />
                  </>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </DataSourceProvider>
  );
}
