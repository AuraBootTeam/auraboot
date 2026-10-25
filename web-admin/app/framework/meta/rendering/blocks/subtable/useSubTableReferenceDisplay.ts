import { useEffect, useCallback } from 'react';
import { buildApiEndpoint } from '~/routes/_shared/dynamic-route-utils';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import type {
  EnrichedColumnConfig,
  PaginationResult,
  ResolvedSubTableViewerProps,
} from './subTableTypes';
import {
  resolveChildFieldDisplayName,
  buildSubTableReferenceDisplayCacheKey,
} from './subTableValues';

import type { useSubTableState } from './useSubTableState';
import type { useSubTableColumns } from './useSubTableColumns';

type UseSubTableReferenceDisplayArgs = Pick<ResolvedSubTableViewerProps, 'config' | 't' | 'token'> &
  Pick<
    ReturnType<typeof useSubTableState>,
    'childFieldMap' | 'rows' | 'referenceDisplayCache' | 'setReferenceDisplayCache'
  > &
  Pick<
    ReturnType<typeof useSubTableColumns>,
    'referenceDisplayConfigs' | 'referenceDisplayByField'
  >;

export function useSubTableReferenceDisplay({
  config,
  t,
  childFieldMap,
  referenceDisplayConfigs,
  rows,
  referenceDisplayCache,
  token,
  setReferenceDisplayCache,
  referenceDisplayByField,
}: UseSubTableReferenceDisplayArgs) {
  // Resolve column label from i18n with model-qualified fallback
  const resolveColumnLabel = useCallback(
    (fieldCode: string): string => {
      if (config.childModel) {
        const modelKey = `model.${config.childModel}.${fieldCode}.label`;
        const resolved = t(modelKey);
        if (resolved !== modelKey) return resolved;
      }
      const fieldKey = `field.${fieldCode}.label`;
      const fieldResolved = t(fieldKey);
      if (fieldResolved !== fieldKey) return fieldResolved;
      const metaDisplayName = resolveChildFieldDisplayName(childFieldMap.get(fieldCode));
      if (metaDisplayName) return metaDisplayName;
      return fieldCode;
    },
    [childFieldMap, config.childModel, t],
  );

  useEffect(() => {
    if (referenceDisplayConfigs.length === 0 || rows.length === 0) return;
    let cancelled = false;

    async function loadReferenceDisplays(): Promise<void> {
      const pendingByConfig = referenceDisplayConfigs
        .map((refConfig) => {
          const cacheKey = buildSubTableReferenceDisplayCacheKey(refConfig);
          const cached = referenceDisplayCache[cacheKey] || {};
          const values = Array.from(
            new Set(
              rows
                .map((row) => row[refConfig.field])
                .filter((value) => value !== null && value !== undefined && value !== '')
                .map((value) => String(value)),
            ),
          ).filter((value) => cached[value] === undefined);
          return { refConfig, cacheKey, values };
        })
        .filter((entry) => entry.values.length > 0);

      if (pendingByConfig.length === 0) return;

      const updates: Record<string, Record<string, string>> = {};
      await Promise.all(
        pendingByConfig.map(async ({ refConfig, cacheKey, values }) => {
          try {
            const result = await fetchResult<
              PaginationResult<Record<string, any>> | Record<string, any>[]
            >(`${buildApiEndpoint(refConfig.modelCode)}/list`, {
              method: 'get',
              params: {
                pageNum: 1,
                pageSize: Math.max(values.length, 1),
                filters: JSON.stringify([
                  { fieldName: refConfig.valueField, operator: 'IN', value: values },
                ]),
              },
              token: token || undefined,
            });
            if (cancelled || !ResultHelper.isSuccess(result) || !result.data) return;

            const responseData = result.data;
            const referenceRows = Array.isArray(responseData)
              ? responseData
              : responseData.records || [];
            for (const row of referenceRows) {
              const value = row[refConfig.valueField];
              const label = row[refConfig.displayField];
              if (value === null || value === undefined || label === null || label === undefined) {
                continue;
              }
              const text = String(label).trim();
              if (!text) continue;
              updates[cacheKey] = updates[cacheKey] || {};
              updates[cacheKey][String(value)] = text;
            }
          } catch (err) {
            console.warn(
              `[SubTableViewer] Failed to resolve reference labels for ${refConfig.field}:`,
              err,
            );
          }
        }),
      );

      if (cancelled || Object.keys(updates).length === 0) return;
      setReferenceDisplayCache((prev) => {
        const next = { ...prev };
        for (const [cacheKey, labels] of Object.entries(updates)) {
          next[cacheKey] = { ...(next[cacheKey] || {}), ...labels };
        }
        return next;
      });
    }

    loadReferenceDisplays();
    return () => {
      cancelled = true;
    };
  }, [referenceDisplayCache, referenceDisplayConfigs, rows, token, setReferenceDisplayCache]);

  const getReferenceDisplayValue = useCallback(
    (row: Record<string, any>, col: EnrichedColumnConfig): string | undefined => {
      const refConfig = referenceDisplayByField.get(col.field);
      if (!refConfig) return undefined;
      const rawValue = row[col.field];
      if (rawValue === null || rawValue === undefined || rawValue === '') return undefined;
      const resolvedDisplay =
        referenceDisplayCache[buildSubTableReferenceDisplayCacheKey(refConfig)]?.[String(rawValue)];
      if (resolvedDisplay) return resolvedDisplay;
      if (row[refConfig.displayKey]) return String(row[refConfig.displayKey]);
      return undefined;
    },
    [referenceDisplayByField, referenceDisplayCache],
  );
  return { resolveColumnLabel, getReferenceDisplayValue };
}
