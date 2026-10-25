import { useEffect, useCallback, useMemo } from 'react';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import type { useSubTableState } from './useSubTableState';
import type { ResolvedSubTableViewerProps } from './subTableTypes';

type UseSubTableDataArgs = Pick<
  ReturnType<typeof useSubTableState>,
  'setRefreshCounter' | 'setLoading' | 'setError' | 'setRows' | 'refreshCounter'
> &
  Pick<
    ResolvedSubTableViewerProps,
    'parentRecordPid' | 'parentRecordData' | 'config' | 'token' | 't'
  >;

export function useSubTableData({
  setRefreshCounter,
  parentRecordPid,
  parentRecordData,
  config,
  setLoading,
  setError,
  setRows,
  token,
  t,
  refreshCounter,
}: UseSubTableDataArgs) {
  const reloadRows = useCallback(() => {
    setRefreshCounter((c) => c + 1);
  }, [setRefreshCounter]);

  const interpolateRecordValue = useCallback(
    (raw: string): string => {
      const legacyRecordKey = 'record' + 'Id';
      const publicRecordPlaceholder = new RegExp(
        `\\$\\{recordPid\\}|\\$\\{pid\\}|\\$\\{${legacyRecordKey}\\}`,
        'g',
      );
      let value = raw.replace(publicRecordPlaceholder, parentRecordPid);
      value = value.replace(/\$\{record\.(\w+)\}/g, (_: string, field: string) => {
        return String(parentRecordData?.[field] ?? parentRecordPid);
      });
      value = value.replace(/\$\{(\w+)\}/g, (_: string, field: string) => {
        return String(parentRecordData?.[field] ?? '');
      });
      return value;
    },
    [parentRecordData, parentRecordPid],
  );

  const resolveDefaultValue = useCallback(
    (raw: unknown): unknown => {
      if (typeof raw === 'string') return interpolateRecordValue(raw);
      if (Array.isArray(raw)) return raw.map((item) => resolveDefaultValue(item));
      if (raw && typeof raw === 'object') {
        return Object.fromEntries(
          Object.entries(raw as Record<string, unknown>).map(([key, value]) => [
            key,
            resolveDefaultValue(value),
          ]),
        );
      }
      return raw;
    },
    [interpolateRecordValue],
  );

  const resolvedDefaultValues = useMemo(() => {
    if (!config.defaultValues) return {};
    return Object.fromEntries(
      Object.entries(config.defaultValues).map(([key, value]) => [key, resolveDefaultValue(value)]),
    );
  }, [config.defaultValues, resolveDefaultValue]);

  useEffect(() => {
    if (!parentRecordPid) return;

    const loadData = async () => {
      setLoading(true);
      setError(null);

      try {
        let endpoint: string;
        let filters: Record<string, any> = {};

        if ((config as any).dataSource) {
          // API data source mode (e.g., NamedQuery)
          const ds = (config as any).dataSource;
          // Interpolate public record pid and ${record.field} placeholders in URL
          let rawUrl = ds.endpoint || ds.url || '/api/datasource/list';
          rawUrl = interpolateRecordValue(rawUrl);
          endpoint = rawUrl;
          const params: Record<string, string> = {};
          if (ds.params) {
            for (const [k, v] of Object.entries(ds.params)) {
              params[k] = interpolateRecordValue(String(v));
            }
          }
          const hasUnresolvedRequiredParam = Object.values(params).some(
            (value) => typeof value === 'string' && value.trim() === '',
          );
          if (hasUnresolvedRequiredParam) {
            setRows([]);
            setLoading(false);
            return;
          }
          if ((ds.kind === 'namedQuery' || ds.type === 'namedQuery') && ds.queryCode) {
            params.datasourceId = `nq:${String(ds.queryCode)}`;
            params.format = params.format || 'records';
          }
          const result = await fetchResult<any>(endpoint, {
            method: 'get',
            params,
            token,
          });
          if (!ResultHelper.isSuccess(result)) {
            setRows([]);
            throw new Error(result.message || t('common.loadDataFailed'));
          }
          // Only a valid empty collection is an empty state; failures are errors.
          const rawData = result.data;
          const resultRecords = Array.isArray(rawData) ? rawData : rawData?.records;
          if (!Array.isArray(resultRecords)) {
            setRows([]);
            throw new Error(t('common.loadDataFailed'));
          }
          setRows(resultRecords);
          setLoading(false);
          return;
        } else if (config.resolveVia) {
          // Indirect query
          const rv = config.resolveVia;
          const intermediateModel = rv.model;
          const intermediateFilters: Array<{ fieldName: string; operator: string; value: string }> =
            [{ fieldName: rv.parentField, operator: 'EQ', value: parentRecordPid }];
          if (rv.filterField && rv.filterValue) {
            intermediateFilters.push({
              fieldName: rv.filterField,
              operator: 'EQ',
              value: rv.filterValue,
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

          if (!ResultHelper.isSuccess(intermediateRes)) {
            setRows([]);
            throw new Error(intermediateRes.message || t('common.loadDataFailed'));
          }
          const intermediateRecords = intermediateRes.data?.records;
          if (!Array.isArray(intermediateRecords)) {
            setRows([]);
            throw new Error(t('common.loadDataFailed'));
          }
          if (intermediateRecords.length === 0) {
            setRows([]);
            setLoading(false);
            return;
          }

          const intermediatePid = intermediateRecords[0].pid;
          const childModel = config.childModel;
          endpoint = `/api/dynamic/${childModel}/list`;
          filters = {
            pageNum: 1,
            pageSize: 200,
            filters: JSON.stringify([
              { fieldName: config.parentField, operator: 'EQ', value: intermediatePid },
            ]),
          };
        } else {
          // Direct query
          const childModel = config.childModel;
          endpoint = `/api/dynamic/${childModel}/list`;
          filters = {
            pageNum: 1,
            pageSize: 200,
            filters: JSON.stringify([
              { fieldName: config.parentField, operator: 'EQ', value: parentRecordPid },
            ]),
          };
        }

        if (config.defaultSort) {
          filters = {
            ...filters,
            sortField: config.defaultSort.field,
            sortOrder: config.defaultSort.order,
          };
        }
        const result = await fetchResult<any>(endpoint, {
          method: 'get',
          params: filters,
          token,
        });

        if (!ResultHelper.isSuccess(result)) {
          setRows([]);
          throw new Error(result.message || t('common.loadDataFailed'));
        }
        const resultRecords = result.data?.records;
        if (!Array.isArray(resultRecords)) {
          setRows([]);
          throw new Error(t('common.loadDataFailed'));
        }
        setRows(resultRecords);
      } catch (err) {
        console.error('[SubTableViewer] Failed to load data:', err);
        setError(err instanceof Error ? err.message : 'Failed to load data');
      } finally {
        setLoading(false);
      }
    };

    loadData();
  }, [
    parentRecordPid,
    parentRecordData,
    config,
    token,
    refreshCounter,
    interpolateRecordValue,
    setError,
    setLoading,
    setRows,
  ]);
  return { reloadRows, resolvedDefaultValues };
}
