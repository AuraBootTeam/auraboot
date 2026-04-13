/**
 * Fetch Report Data
 * Resolves all data sources in a ReportDsl and returns datasets
 */

import type { ReportDsl, ReportDataSource } from '../types';

interface FetchResult {
  code: number | string;
  message: string;
  data: {
    records: Record<string, unknown>[];
    total?: number;
  };
}

async function fetchModelData(ds: ReportDataSource): Promise<Record<string, unknown>[]> {
  if (!ds.modelCode) return [];

  const params = new URLSearchParams({ pageNum: '1', pageSize: '500' });
  if (ds.filters?.length) {
    params.set(
      'filters',
      JSON.stringify(
        ds.filters.map((f) => ({
          fieldName: f.field,
          operator: f.operator,
          value: f.value,
        })),
      ),
    );
  }
  if (ds.sortBy?.length) {
    params.set('sortField', ds.sortBy[0].field);
    params.set('sortOrder', ds.sortBy[0].order);
  }

  const response = await fetch(`/api/dynamic/${ds.modelCode}/list?${params.toString()}`);
  if (!response.ok) throw new Error(`Failed to fetch model data: ${response.status}`);

  const result: FetchResult = await response.json();
  return result.data?.records || [];
}

async function fetchNamedQueryData(ds: ReportDataSource): Promise<Record<string, unknown>[]> {
  if (!ds.queryCode) return [];

  const params = new URLSearchParams({
    datasourceId: `nq:${ds.queryCode}`,
    format: 'records',
    maxItems: '500',
  });

  const response = await fetch(`/api/datasource/list?${params.toString()}`);
  if (!response.ok) throw new Error(`Failed to fetch named query data: ${response.status}`);

  const result = await response.json();
  const code = typeof result.code === 'string' ? parseInt(result.code, 10) : result.code;
  if (code !== 0 && code !== 200) throw new Error(result.desc || result.message || 'Query failed');

  return result.data?.records || result.data || [];
}

async function fetchApiData(ds: ReportDataSource): Promise<Record<string, unknown>[]> {
  if (!ds.url) return [];

  const response = await fetch(ds.url);
  if (!response.ok) throw new Error(`Failed to fetch API data: ${response.status}`);

  const result = await response.json();
  if (Array.isArray(result)) return result;
  if (result.data?.records) return result.data.records;
  if (Array.isArray(result.data)) return result.data;
  return [];
}

/**
 * Fetch all data sources defined in a report
 * @returns Record<dataSourceKey, rows[]>
 */
export async function fetchReportData(
  report: ReportDsl,
): Promise<Record<string, Record<string, unknown>[]>> {
  const results: Record<string, Record<string, unknown>[]> = {};
  const entries = Object.entries(report.dataSources);

  const fetches = entries.map(async ([key, ds]) => {
    try {
      switch (ds.type) {
        case 'model':
          results[key] = await fetchModelData(ds);
          break;
        case 'namedQuery':
          results[key] = await fetchNamedQueryData(ds);
          break;
        case 'api':
          results[key] = await fetchApiData(ds);
          break;
        default:
          results[key] = [];
      }
    } catch (error) {
      console.error(`Failed to fetch data source "${key}":`, error);
      results[key] = [];
    }
  });

  await Promise.all(fetches);
  return results;
}
