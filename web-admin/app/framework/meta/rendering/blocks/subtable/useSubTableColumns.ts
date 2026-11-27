import { useCallback, useMemo } from 'react';
import type { ColumnConfig } from '~/framework/meta/schemas/types';
import { resolveTemporalType } from '~/shared/services/dateTimeFormatService';
import { useDictCache } from '~/framework/meta/rendering/pages/hooks/useDictCache';
import type { DataSourceConfig as SelectDataSourceConfig } from '~/framework/smart/schema/smart-components';
import type { EnrichedColumnConfig, ResolvedSubTableViewerProps } from './subTableTypes';
import {
  getFieldMetaExtension,
  isNumericField,
  resolveChildFieldDisplayName,
  shouldReplaceGeneratedLabel,
  collectSubTableReferenceDisplayConfigs,
} from './subTableValues';

import type { useSubTableState } from './useSubTableState';

const numericTypes = new Set(['integer', 'bigint', 'long', 'double', 'number', 'decimal', 'float', 'currency', 'percent']);

function withColumnPresentation(col: EnrichedColumnConfig): EnrichedColumnConfig {
  const kind = (col.valueType || col.dataType || '').toLowerCase();
  const numeric = numericTypes.has(kind) || col.format === 'percent' || isNumericField(col);
  const temporal = resolveTemporalType(col.field, kind, undefined);
  if (!numeric && !temporal) return col;

  return {
    ...col,
    align: col.align ?? (numeric && !col.dictCode ? 'right' : undefined),
    width: col.width ?? (temporal === 'datetime' ? 176 : temporal ? 120 : 112),
  };
}

type UseSubTableColumnsArgs = Pick<ResolvedSubTableViewerProps, 'isEditable' | 'config' | 'token'> &
  Pick<ReturnType<typeof useSubTableState>, 'childFieldMap'>;

export function useSubTableColumns({
  isEditable,
  config,
  childFieldMap,
  token,
}: UseSubTableColumnsArgs) {
  // Determine if inline edit is enabled
  const canInlineEdit = isEditable && config.commands?.update && config.allowInlineEdit !== false;

  // Check if a column is editable for inline edit
  const isColumnEditable = useCallback(
    (col: ColumnConfig): boolean => {
      if (col.editable === false || col.readOnly) return false;
      if (config.editableColumns && config.editableColumns.length > 0) {
        return config.editableColumns.includes(col.field);
      }
      // REFERENCE fields are too complex for inline editing
      if (col.valueType === 'user_identity' || col.valueType === 'image') return false;
      return true;
    },
    [config.editableColumns],
  );

  const effectiveColumns = useMemo<EnrichedColumnConfig[]>(() => {
    return config.columns.map((col) => {
      const meta = childFieldMap.get(col.field);
      if (!meta) return withColumnPresentation(col);

      const anyCol = col as EnrichedColumnConfig;
      const metaExtension = getFieldMetaExtension(meta);
      const refTarget = {
        ...(metaExtension.refTarget || {}),
        ...(meta.refTarget || {}),
        ...((anyCol.extension as any)?.refTarget || {}),
        ...((anyCol as any).props?.refTarget || {}),
        ...(anyCol.refTarget || {}),
      };
      const displayName = resolveChildFieldDisplayName(meta);
      const dataType = anyCol.dataType || meta.dataType || metaExtension.dataType;
      const enriched: EnrichedColumnConfig = { ...anyCol };

      if (displayName && shouldReplaceGeneratedLabel(enriched.label, col.field)) {
        enriched.label = displayName;
      }
      if (!enriched.dictCode && (meta.dictCode || metaExtension.dictCode)) {
        enriched.dictCode = meta.dictCode || metaExtension.dictCode;
      }
      if (dataType) {
        enriched.dataType = String(dataType);
        if (String(dataType).toLowerCase() === 'reference' && !enriched.valueType) {
          enriched.valueType = 'reference';
        }
      }
      if (Object.keys(refTarget).length > 0) {
        enriched.refTarget = refTarget;
      }
      if (!enriched.referenceModelCode) {
        enriched.referenceModelCode =
          meta.referenceModelCode ||
          metaExtension.referenceModelCode ||
          metaExtension.refModelCode ||
          refTarget.targetModel ||
          refTarget.modelCode;
      }
      const maximumLength = Number(
        meta.feature?.validation?.maxLength ??
          metaExtension.constraints?.maxLength ??
          metaExtension.extension?.constraints?.maxLength,
      );
      if (enriched.maxLength === undefined && Number.isFinite(maximumLength) && maximumLength > 0) {
        enriched.maxLength = maximumLength;
      }
      if (!enriched.required && meta.required) {
        enriched.required = true;
      }

      return withColumnPresentation(enriched);
    });
  }, [childFieldMap, config.columns]);

  // Ordered list of editable field names (for Tab navigation)
  const editableFields = useMemo(
    () => effectiveColumns.filter((c) => isColumnEditable(c)).map((c) => c.field),
    [effectiveColumns, isColumnEditable],
  );

  // Dict cache for columns with dictCode (used in inline edit dropdowns)
  const dictCodes = useMemo(
    () => effectiveColumns.filter((c) => c.dictCode).map((c) => c.dictCode!),
    [effectiveColumns],
  );
  const { getDictItems, getDictLabel } = useDictCache({ dictCodes, token });

  const referenceDisplayConfigs = useMemo(
    () => collectSubTableReferenceDisplayConfigs(effectiveColumns),
    [effectiveColumns],
  );

  const referenceDisplayByField = useMemo(
    () => new Map(referenceDisplayConfigs.map((entry) => [entry.field, entry])),
    [referenceDisplayConfigs],
  );

  const referenceInputSources = useMemo(
    () =>
      new Map<string, SelectDataSourceConfig>(
        referenceDisplayConfigs.map((reference) => [
          reference.field,
          {
            type: 'api',
            modelCode: reference.modelCode,
            endpoint: '/api/dynamic/' + reference.modelCode + '/list',
            method: 'get',
            params: { pageNum: 1, pageSize: 200 },
            adaptor: 'optionList',
            valueField: reference.valueField,
            labelField: reference.displayField,
            autoFetch: true,
          },
        ]),
      ),
    [referenceDisplayConfigs],
  );
  return {
    canInlineEdit,
    isColumnEditable,
    effectiveColumns,
    editableFields,
    getDictItems,
    getDictLabel,
    referenceDisplayConfigs,
    referenceDisplayByField,
    referenceInputSources,
  };
}
