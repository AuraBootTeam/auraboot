import type { ExpressionContext } from '~/framework/meta/runtime/expression/context';
import type { DateTimeFormatPreferences } from '~/shared/services/dateTimeFormatService';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import type { ColumnConfig, FieldConfig } from '~/framework/meta/schemas/types';
import { createFieldRenderer } from '~/framework/meta/utils/createFieldRenderer';
import { getLegacyCompatibleRecordPid } from '~/framework/meta/utils/publicRecordId';
import { type ViewConfig, type ViewFilterConfig } from '~/framework/smart/types/savedView';
import { buildApiEndpoint } from '~/routes/_shared/dynamic-route-utils';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useListColumnLabel, useListTableColumns } from './listColumnModel';
import { getSystemFieldI18nKey, resolveFieldMetaDisplayName } from './listFieldMetadata';
import type { ListLoadDataParams, PaginationResult } from './listPageStateUtils';
import type { DictItem, DynamicEntity } from './listPageTypes';
import {
  buildListReferenceDisplayCacheKey,
  collectListReferenceDisplayConfigs,
  collectListReferenceValues,
  pickSystemReferenceLabel,
  resolveListSystemReferenceDisplayConfig,
  shouldResolveListSystemReferenceValue,
} from './listReferenceDisplay';
import { useListCellRenderer } from './useListCellRenderer';

interface ListColumnsInput {
  filters: Record<string, any>;
  setFilters: (
    filters: Record<string, any> | ((prev: Record<string, any>) => Record<string, any>),
  ) => void;
  pageContext: ExpressionContext;
  runtime: SchemaRuntime | null;
  locale: string;
  t: (key: string) => string;
  modelFieldMap: Map<string, any>;
  referenceDisplayCache: Record<string, Record<string, string>>;
  dictDataCache: React.RefObject<Map<string, DictItem[]>>;
  dateTimeFormats: DateTimeFormatPreferences;
  effectiveTimezone: string;
  schema: any;
  tableName: string;
  token: string | null | undefined;
  loadDataRef: React.RefObject<((params?: ListLoadDataParams) => Promise<void>) | null>;
  pagination: { current: number; pageSize: number; total: number };
  namedQueryCode: any;
  tableBlock: any;
  effectiveViewConfig: ViewConfig | undefined;
  chipFilters: ViewFilterConfig[];
  data: DynamicEntity[];
  setReferenceDisplayCache: React.Dispatch<
    React.SetStateAction<Record<string, Record<string, string>>>
  >;
}

/** Derive filter/table schema, reference labels and persisted column layout. */
export function useListColumns({
  filters,
  setFilters,
  pageContext,
  runtime,
  locale,
  t,
  modelFieldMap,
  referenceDisplayCache,
  dictDataCache,
  dateTimeFormats,
  effectiveTimezone,
  schema,
  tableName,
  token,
  loadDataRef,
  pagination,
  namedQueryCode,
  tableBlock,
  effectiveViewConfig,
  chipFilters,
  data,
  setReferenceDisplayCache,
}: ListColumnsInput) {
  // Render filter field using utility (P1-5 fix: extract repeated logic)
  const renderSmartField = useMemo(
    () => createFieldRenderer(filters, setFilters, pageContext),
    [filters, pageContext],
  );

  const renderCellContent = useListCellRenderer({
    runtime,
    pageContext,
    locale,
    t,
    modelFieldMap,
    referenceDisplayCache,
    dictDataCache,
    dateTimeFormats,
    effectiveTimezone,
  });

  // Inline edit: save a single field value via dynamic data PUT API
  const handleInlineSave = useCallback(
    async (field: string, value: any, record: Record<string, any>) => {
      const pid = getLegacyCompatibleRecordPid(record);
      if (!pid) throw new Error('Record has no public pid');
      const slug = schema?.modelCode || tableName;
      const result = await fetchResult<any>(`/api/dynamic/${slug}/${pid}`, {
        method: 'put',
        token: token || undefined,
        params: { [field]: value },
      });
      if (!ResultHelper.isSuccess(result)) {
        throw new Error(result.desc || 'Save failed');
      }
      // Refresh list data
      loadDataRef.current?.({ page: pagination.current - 1, size: pagination.pageSize, filters });
    },
    [schema?.modelCode, tableName, token, pagination, filters],
  );

  // Fetch namedQuery filter schema when page uses namedQuery data source
  const [nqFilterFields, setNqFilterFields] = useState<any[] | null>(null);
  useEffect(() => {
    if (!namedQueryCode || !token) return;
    const fetchFilterSchema = async () => {
      try {
        const result = await fetchResult<any[]>(
          `${buildApiEndpoint(schema?.modelCode || tableName)}/filter-schema?queryCode=${encodeURIComponent(namedQueryCode)}`,
          { method: 'get', token: token || undefined },
        );
        if (ResultHelper.isSuccess(result) && result.data) {
          setNqFilterFields(result.data);
        }
      } catch (err) {
        console.warn('[ListPageContent] Failed to fetch namedQuery filter schema:', err);
      }
    };
    fetchFilterSchema();
  }, [namedQueryCode, token, tableName]);

  // Extract blocks from schema (UnifiedSchema.blocks)
  // NOTE: These must be computed before early returns to keep hook count stable
  const allBlocks = useMemo(() => {
    return schema?.blocks || [];
  }, [schema]);

  // Map NamedQuery uiComponent to SmartField component name
  const mapNqComponent = useCallback((uiComponent?: string): string => {
    switch (uiComponent) {
      case 'number':
      case 'numberRange':
        return 'SmartNumber';
      case 'select':
        return 'SmartSelect';
      case 'dateRange':
      case 'date':
        return 'SmartDate';
      case 'switch':
        return 'SmartSwitch';
      case 'search':
      case 'cascader':
      case 'userPicker':
        return 'SmartInput';
      default:
        return 'SmartInput';
    }
  }, []);

  const resolveModelFieldLabel = useCallback(
    (fieldCode: string) => {
      const systemKey = getSystemFieldI18nKey(fieldCode);
      if (systemKey) {
        const systemLabel = t(systemKey);
        if (systemLabel && systemLabel !== systemKey) {
          return systemLabel;
        }
      }
      const mc = schema?.modelCode || tableName;
      const modelKey = `model.${mc}.${fieldCode}.label`;
      const modelLabel = t(modelKey);
      if (modelLabel && modelLabel !== modelKey) {
        return modelLabel;
      }
      const modelFieldKey = `field.${mc}.${fieldCode}.label`;
      const modelFieldLabel = t(modelFieldKey);
      if (modelFieldLabel && modelFieldLabel !== modelFieldKey) {
        return modelFieldLabel;
      }
      const fieldKey = `field.${fieldCode}.label`;
      const fieldLabel = t(fieldKey);
      if (fieldLabel && fieldLabel !== fieldKey) {
        return fieldLabel;
      }
      return resolveFieldMetaDisplayName(fieldCode, modelFieldMap) ?? fieldCode;
    },
    [schema?.modelCode, tableName, t, modelFieldMap],
  );

  const filterBlock = useMemo(() => {
    const tableBlock = allBlocks.find((b: any) => b.blockType === 'table');
    const rawColumns = (tableBlock as any)?.table?.columns || (tableBlock as any)?.columns;
    const columns: ColumnConfig[] = Array.isArray(rawColumns) ? rawColumns : [];
    const columnLabelMap = new Map<string, any>();
    columns.forEach((col) => {
      if (!col.field || col.isActionColumn) return;
      if (col.label) {
        columnLabelMap.set(col.field, col.label);
      }
    });

    const patchFilterFields = (fields: FieldConfig[]): FieldConfig[] =>
      fields.map((field) => {
        if (field.label) return field;
        const columnLabel = columnLabelMap.get(field.field);
        return {
          ...field,
          label: columnLabel || resolveModelFieldLabel(field.field),
        };
      });

    const found = allBlocks.find((block: any) => block.blockType === 'filters');
    if (found) {
      const foundFields = Array.isArray((found as any).fields) ? (found as any).fields : [];
      return {
        ...found,
        fields: patchFilterFields(foundFields),
      };
    }

    // NamedQuery data source: synthesize filters from param-schema
    if (namedQueryCode && nqFilterFields && nqFilterFields.length > 0) {
      return {
        blockType: 'filters' as const,
        fields: nqFilterFields.map((f: any) => ({
          field: f.fieldCode,
          label:
            f.displayName || columnLabelMap.get(f.fieldCode) || resolveModelFieldLabel(f.fieldCode),
          component: mapNqComponent(f.uiComponent),
          props: {
            ...(f.placeholder ? { placeholder: f.placeholder } : {}),
            ...(f.defaultValue ? { defaultValue: f.defaultValue } : {}),
          },
        })),
        buttons: [],
      };
    }

    // Auto-synthesize from table searchFields
    if (
      tableBlock &&
      (tableBlock as any).searchFields &&
      Array.isArray((tableBlock as any).searchFields)
    ) {
      const searchFields = (tableBlock as any).searchFields as string[];
      return {
        blockType: 'filters' as const,
        fields: searchFields.map((fieldCode: string) => ({
          field: fieldCode,
          label: columnLabelMap.get(fieldCode) || resolveModelFieldLabel(fieldCode),
          component: 'SmartInput',
          props: {},
        })),
        buttons: [],
      };
    }

    return undefined;
  }, [allBlocks, namedQueryCode, nqFilterFields, mapNqComponent, resolveModelFieldLabel]);

  const actionBlock = useMemo(() => {
    const found = allBlocks.find(
      (block: any) => block.blockType === 'form-buttons' || block.blockType === 'toolbar',
    );
    if (!found) return found;
    // Toolbar DSL may declare its entries as `actions` (localized-label navigate
    // / command entries) instead of `buttons`; the chrome consumes `buttons`
    // only, so merge both shapes into one renderable list.
    const actions = Array.isArray(found.actions) ? found.actions : [];
    const buttons = Array.isArray(found.buttons) ? found.buttons : [];
    if (actions.length === 0) return found;
    return { ...found, buttons: [...buttons, ...actions] };
  }, [allBlocks]);

  const { SYSTEM_FIELD_DEFS, tableColumns } = useListTableColumns({
    tableBlock,
    effectiveViewConfig,
    modelFieldMap,
    t,
  });

  const referenceDisplayConfigs = useMemo(
    () =>
      collectListReferenceDisplayConfigs(
        [
          ...tableColumns,
          ...chipFilters.map((filter) => ({ field: filter.fieldCode, label: filter.fieldCode })),
        ],
        modelFieldMap,
      ),
    [tableColumns, modelFieldMap, chipFilters],
  );

  useEffect(() => {
    if (referenceDisplayConfigs.length === 0) return;
    let cancelled = false;

    async function loadReferenceDisplays(): Promise<void> {
      const pendingByConfig = referenceDisplayConfigs
        .map((config) => {
          const cacheKey = buildListReferenceDisplayCacheKey(config);
          const cached = referenceDisplayCache[cacheKey] || {};
          const values = collectListReferenceValues(config, data, chipFilters, cached);
          return { config, cacheKey, values };
        })
        .filter((entry) => entry.values.length > 0);

      if (pendingByConfig.length === 0) return;

      const updates: Record<string, Record<string, string>> = {};
      await Promise.all(
        pendingByConfig.map(async ({ config, cacheKey, values }) => {
          try {
            const systemConfig = resolveListSystemReferenceDisplayConfig(config.modelCode);
            if (systemConfig) {
              await Promise.all(
                values.filter(shouldResolveListSystemReferenceValue).map(async (value) => {
                  const result = await fetchResult<Record<string, any>>(
                    `${systemConfig.detailEndpoint}/${encodeURIComponent(value)}`,
                    {
                      method: 'get',
                      token: token || undefined,
                    },
                  );
                  if (cancelled || !ResultHelper.isSuccess(result) || !result.data) return;
                  const label = pickSystemReferenceLabel(
                    result.data,
                    config.displayField,
                    systemConfig.labelFields,
                  );
                  if (!label) return;
                  updates[cacheKey] = updates[cacheKey] || {};
                  updates[cacheKey][value] = label;
                }),
              );
              return;
            }

            const result = await fetchResult<PaginationResult<DynamicEntity> | DynamicEntity[]>(
              `${buildApiEndpoint(config.modelCode)}/list`,
              {
                method: 'get',
                params: {
                  pageNum: 1,
                  pageSize: Math.max(values.length, 1),
                  filters: JSON.stringify([
                    { fieldName: config.valueField, operator: 'IN', value: values },
                  ]),
                },
                token: token || undefined,
              },
            );
            if (cancelled || !ResultHelper.isSuccess(result) || !result.data) return;

            const responseData = result.data;
            const rows = Array.isArray(responseData) ? responseData : responseData.records || [];
            for (const row of rows) {
              const value = row[config.valueField];
              const label = row[config.displayField];
              if (value === null || value === undefined || label === null || label === undefined) {
                continue;
              }
              const text = String(label).trim();
              if (!text) continue;
              updates[cacheKey] = updates[cacheKey] || {};
              updates[cacheKey][String(value)] = text;
            }
          } catch (error) {
            console.warn(
              `[ListPageContent] Failed to resolve reference labels for ${config.field}:`,
              error,
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
  }, [referenceDisplayConfigs, data, chipFilters, referenceDisplayCache, token]);

  // Column order — derived from SavedView or default column order
  const [columnOrder, setColumnOrder] = useState<string[]>([]);

  // Initialize column order from SavedView or table columns
  useEffect(() => {
    const viewColumns = effectiveViewConfig?.columns;
    if (viewColumns && viewColumns.length > 0) {
      const hasOrder = viewColumns.some((vc) => vc.order !== undefined && vc.order !== null);
      if (hasOrder) {
        const ordered = [...viewColumns]
          .filter((vc) => vc.visible !== false)
          .sort((a, b) => (a.order ?? 999) - (b.order ?? 999))
          .map((vc) => vc.fieldCode);
        setColumnOrder(ordered);
        return;
      }
    }
    // Default: use tableColumns order
    setColumnOrder(tableColumns.filter((c) => !c.isActionColumn).map((c) => c.field));
  }, [effectiveViewConfig?.columns, tableColumns]);

  const resolveColumnLabel = useListColumnLabel({ locale, t, schema, tableName, modelFieldMap });

  // Column widths map from SavedView
  const columnWidths = useMemo(() => {
    const widths: Record<string, number> = {};
    const viewColumns = effectiveViewConfig?.columns;
    if (viewColumns) {
      for (const vc of viewColumns) {
        if (vc.width) widths[vc.fieldCode] = vc.width;
      }
    }
    return widths;
  }, [effectiveViewConfig?.columns]);

  return {
    renderSmartField,
    renderCellContent,
    handleInlineSave,
    allBlocks,
    filterBlock,
    actionBlock,
    SYSTEM_FIELD_DEFS,
    tableColumns,
    referenceDisplayConfigs,
    columnOrder,
    setColumnOrder,
    resolveColumnLabel,
    columnWidths,
  };
}
