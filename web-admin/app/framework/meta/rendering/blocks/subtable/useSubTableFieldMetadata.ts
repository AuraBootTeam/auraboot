import { useEffect, useMemo } from 'react';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import type {
  ChildFieldMeta,
  EnrichedColumnConfig,
  ResolvedSubTableViewerProps,
} from './subTableTypes';

import type { useSubTableState } from './useSubTableState';

type UseSubTableFieldMetadataArgs = Pick<ResolvedSubTableViewerProps, 'config' | 'token'> &
  Pick<ReturnType<typeof useSubTableState>, 'setChildFieldMap' | 'setColumnMetaLoading'>;

export function useSubTableFieldMetadata({
  config,
  setChildFieldMap,
  setColumnMetaLoading,
  token,
}: UseSubTableFieldMetadataArgs) {
  const shouldLoadChildFieldMeta = useMemo(() => {
    if (!config.childModel) return false;
    if (config.commands?.create || config.commands?.update) return true;
    return config.columns.some((col) => {
      const anyCol = col as EnrichedColumnConfig;
      return (
        !col.label ||
        col.field.endsWith('_id') ||
        col.field.endsWith('_pid') ||
        col.valueType === 'reference' ||
        anyCol.dataType === 'reference' ||
        Boolean(anyCol.refTarget || anyCol.referenceModelCode)
      );
    });
  }, [config.childModel, config.columns, config.commands?.create, config.commands?.update]);

  useEffect(() => {
    if (!shouldLoadChildFieldMeta || !config.childModel) {
      setChildFieldMap(new Map());
      setColumnMetaLoading(false);
      return;
    }

    let cancelled = false;
    setColumnMetaLoading(true);

    async function loadChildFieldMeta(): Promise<void> {
      try {
        const fieldsRes = await fetchResult<ChildFieldMeta[]>(
          `/api/dynamic/${config.childModel}/field-meta`,
          {
            method: 'get',
            token: token || undefined,
          },
        );
        if (cancelled) return;
        if (!ResultHelper.isSuccess(fieldsRes) || !Array.isArray(fieldsRes.data)) {
          setChildFieldMap(new Map());
          return;
        }
        const next = new Map<string, ChildFieldMeta>();
        for (const field of fieldsRes.data) {
          if (field?.code) next.set(field.code, field);
        }
        setChildFieldMap(next);
      } catch (err) {
        if (!cancelled) {
          console.warn('[SubTableViewer] Failed to load child field metadata:', err);
          setChildFieldMap(new Map());
        }
      } finally {
        if (!cancelled) setColumnMetaLoading(false);
      }
    }

    loadChildFieldMeta();
    return () => {
      cancelled = true;
    };
  }, [config.childModel, shouldLoadChildFieldMeta, token, setChildFieldMap, setColumnMetaLoading]);
}
