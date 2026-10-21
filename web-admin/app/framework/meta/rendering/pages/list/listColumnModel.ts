import { useCallback, useMemo } from 'react';
import type { PageContentProps } from '@auraboot/runtime-kernel';
import type { BlockConfig, ColumnConfig } from '~/framework/meta/schemas/types';
import type { ViewConfig } from '~/framework/smart/types/savedView';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import {
  buildListColumnSettingsDefinitions,
  buildListFilterFieldMetadata,
  buildViewManageFieldOptions,
  getSystemFieldI18nKey,
  renderComponentToValueType,
  resolveColumnCapabilityDataType,
  resolveFieldMetaDataType,
  resolveFieldMetaDisplayName,
  resolveFieldMetaRenderComponent,
} from './listFieldMetadata';
import { resolveTableBlockRowActions } from './listSchemaPolicy';

interface ListTableColumnsOptions {
  tableBlock: BlockConfig | null | undefined;
  effectiveViewConfig: Partial<ViewConfig> | null | undefined;
  modelFieldMap: Map<string, any>;
  t: (key: string) => string;
}

/** Project DSL, field metadata and SavedView settings into visible table columns. */
export function useListTableColumns({
  tableBlock,
  effectiveViewConfig,
  modelFieldMap,
  t,
}: ListTableColumnsOptions) {
  // System fields on every dynamic entity — not in DSL but available in API response
  const SYSTEM_FIELD_DEFS: ColumnConfig[] = useMemo(
    () => [
      {
        field: 'created_at',
        label: t(getSystemFieldI18nKey('created_at') || 'common.created_at') || 'Created At',
        valueType: 'datetime' as any,
      },
      {
        field: 'updated_at',
        label: t(getSystemFieldI18nKey('updated_at') || 'common.updated_at') || 'Updated At',
        valueType: 'datetime' as any,
      },
      {
        field: 'created_by',
        label: t('common.creator') || 'Created By',
      },
      {
        field: 'updated_by',
        label: t('common.modifier') || 'Updated By',
      },
    ],
    [t],
  );

  // Get columns: prefer table.columns, fallback to block-level columns
  // Then apply SavedView column visibility/order overrides
  const tableColumns: ColumnConfig[] = useMemo(() => {
    if (!tableBlock) return [];
    const cols = (tableBlock as BlockConfig).table?.columns || tableBlock.columns;
    if (!Array.isArray(cols)) return [];

    // If the block has rowActions defined either at block level or under table,
    // synthesize an action column. Backend PageSchemaDTO commonly keeps actions
    // inside `table.rowActions`; dropping that path makes API-backed DSL pages
    // render only generated defaults.
    const rowActions = resolveTableBlockRowActions(tableBlock);
    let baseCols = cols as ColumnConfig[];
    if (rowActions && rowActions.length > 0) {
      const hasActionCol = cols.some((c: any) => c.isActionColumn);
      if (!hasActionCol) {
        baseCols = [
          ...cols,
          { field: '_actions', isActionColumn: true, buttons: rowActions },
        ] as ColumnConfig[];
      } else {
        // An action column already exists (e.g. default view/edit/delete injected
        // for existing models). Merge in the block-level custom rowActions (dedup
        // by code) so page-defined commands surface alongside the defaults — without
        // this, custom rowActions on existing models are silently dropped.
        baseCols = cols.map((c: any) => {
          if (!c.isActionColumn) return c;
          const existing = Array.isArray(c.buttons) ? c.buttons : [];
          const existingCodes = new Set(existing.map((b: any) => b.code));
          const merged = [
            ...existing,
            ...rowActions.filter((ra: any) => !existingCodes.has(ra.code)),
          ];
          return { ...c, buttons: merged };
        }) as ColumnConfig[];
      }
    }

    // Apply SavedView column config (visibility + order + width)
    const viewColumns = effectiveViewConfig?.columns;
    if (!viewColumns || viewColumns.length === 0) return baseCols;

    const viewColMap = new Map(viewColumns.map((vc) => [vc.fieldCode, vc]));
    // Filter visible columns and apply order
    // Include model and system fields that are explicitly enabled in viewConfig.
    // This lets SavedView promote a valid model field that the DSL table does
    // not show by default, while the page schema remains the default layout.
    const baseFields = new Set(baseCols.map((c) => c.field));
    const modelCols: ColumnConfig[] = [];
    for (const vc of viewColumns) {
      if (vc.visible === false || baseFields.has(vc.fieldCode)) continue;
      const meta = modelFieldMap.get(vc.fieldCode);
      if (!meta) continue;
      const renderComponent = resolveFieldMetaRenderComponent(vc.fieldCode, modelFieldMap);
      const dataType = resolveFieldMetaDataType(vc.fieldCode, modelFieldMap);
      modelCols.push({
        field: vc.fieldCode,
        label: resolveFieldMetaDisplayName(vc.fieldCode, modelFieldMap) ?? vc.fieldCode,
        valueType: renderComponentToValueType(renderComponent),
        sorter: dataType,
        sortable: true,
        dictCode: meta.dictCode || meta.extension?.dictCode || undefined,
        refTarget: meta.refTarget || meta.extension?.refTarget || undefined,
      } as ColumnConfig);
      baseFields.add(vc.fieldCode);
    }
    const sysCols = SYSTEM_FIELD_DEFS.filter((sf) => {
      const vc = viewColMap.get(sf.field);
      return vc && vc.visible !== false && !baseFields.has(sf.field);
    });
    const allCols = [...baseCols, ...modelCols, ...sysCols];
    const visibleCols = allCols
      .map((col) => {
        const vc = viewColMap.get(col.field);
        if (vc && vc.visible === false) return null;
        return {
          ...col,
          ...(vc?.width ? { width: vc.width } : {}),
          ...(vc?.frozen && vc.frozenPosition
            ? { fixed: vc.frozenPosition }
            : vc?.frozen === false
              ? { fixed: undefined }
              : {}),
        };
      })
      .filter((col): col is ColumnConfig => col !== null);

    // Sort by SavedView order if specified
    const hasOrder = viewColumns.some((vc) => vc.order !== undefined && vc.order !== null);
    if (hasOrder) {
      visibleCols.sort((a, b) => {
        const orderA = viewColMap.get(a.field)?.order ?? 999;
        const orderB = viewColMap.get(b.field)?.order ?? 999;
        return orderA - orderB;
      });
    }

    return visibleCols;
  }, [tableBlock, effectiveViewConfig, modelFieldMap, SYSTEM_FIELD_DEFS]);

  return { SYSTEM_FIELD_DEFS, tableColumns };
}

interface ListColumnLabelOptions {
  locale: string;
  t: (key: string) => string;
  schema: PageContentProps['schema'];
  tableName: PageContentProps['tableName'];
  modelFieldMap: Map<string, any>;
}

export function useListColumnLabel({
  locale,
  t,
  schema,
  tableName,
  modelFieldMap,
}: ListColumnLabelOptions) {
  // Resolve column label via i18n
  const resolveColumnLabel = useCallback(
    (column: ColumnConfig): string => {
      if (column.label) {
        return getLocalizedText(column.label, locale, t);
      }
      if (column.isActionColumn) {
        return t('table.actions');
      }
      const systemKey = getSystemFieldI18nKey(column.field);
      if (systemKey) {
        const systemLabel = t(systemKey);
        if (systemLabel !== systemKey) return systemLabel;
      }
      const mc = schema?.modelCode || tableName;
      const modelKey = `model.${mc}.${column.field}.label`;
      const modelLabel = t(modelKey);
      if (modelLabel !== modelKey) return modelLabel;
      const modelFieldKey = `field.${mc}.${column.field}.label`;
      const modelFieldLabel = t(modelFieldKey);
      if (modelFieldLabel !== modelFieldKey) return modelFieldLabel;
      const fieldKey = `field.${column.field}.label`;
      const fieldLabel = t(fieldKey);
      if (fieldLabel !== fieldKey) return fieldLabel;
      // Probe `common.field.<code>` namespaced under `common.field.*` (legacy slot)
      const commonFieldKey = `common.field.${column.field}`;
      const commonFieldLabel = t(commonFieldKey);
      if (commonFieldLabel !== commonFieldKey) return commonFieldLabel;
      // Probe top-level `common.<code>` — covers system audit fields like
      // created_at / updated_at / created_by / updated_by which live directly
      // under the `common:` namespace in i18n yaml resources.
      const commonKey = `common.${column.field}`;
      const commonLabel = t(commonKey);
      if (commonLabel !== commonKey) return commonLabel;
      // Last resort before leaking the raw field code: the model field
      // metadata carries the imported displayName (zh-CN first).
      return resolveFieldMetaDisplayName(column.field, modelFieldMap) ?? column.field;
    },
    [locale, t, schema?.modelCode, tableName, modelFieldMap],
  );

  return resolveColumnLabel;
}

interface ListFieldOptions {
  tableBlock: BlockConfig | null | undefined;
  tableColumns: ColumnConfig[];
  modelFieldMap: Map<string, any>;
  SYSTEM_FIELD_DEFS: ColumnConfig[];
  resolveColumnLabel: (column: ColumnConfig) => string;
}

export function useListFieldOptions({
  tableBlock,
  tableColumns,
  modelFieldMap,
  SYSTEM_FIELD_DEFS,
  resolveColumnLabel,
}: ListFieldOptions) {
  // All model-backed column definitions for ColumnSettingsPanel. DSL columns
  // define the default visible subset; other readable model fields begin hidden.
  const allColumnDefs = useMemo(() => {
    if (!tableBlock) return [];
    const cols = (tableBlock as BlockConfig).table?.columns || tableBlock.columns;
    if (!Array.isArray(cols)) return [];
    return buildListColumnSettingsDefinitions(
      cols as ColumnConfig[],
      modelFieldMap,
      SYSTEM_FIELD_DEFS,
      resolveColumnLabel,
    );
  }, [tableBlock, modelFieldMap, resolveColumnLabel, SYSTEM_FIELD_DEFS]);

  const viewManageFields = useMemo(() => {
    const fieldMap = new Map(
      buildViewManageFieldOptions(tableColumns, modelFieldMap).map((field) => [field.code, field]),
    );

    for (const column of tableColumns) {
      if (!column.field || column.isActionColumn) continue;
      fieldMap.set(column.field, {
        code: column.field,
        name: resolveColumnLabel(column),
        dataType: resolveColumnCapabilityDataType(column, modelFieldMap),
      });
    }

    return Array.from(fieldMap.values());
  }, [tableColumns, modelFieldMap, resolveColumnLabel]);

  const filterFieldMetadata = useMemo(() => {
    const businessFields = buildListFilterFieldMetadata(
      tableColumns,
      modelFieldMap,
      resolveColumnLabel,
    );
    const known = new Set(businessFields.map((field) => field.fieldCode));
    const systemFields = buildListFilterFieldMetadata(
      SYSTEM_FIELD_DEFS.filter((column) => !known.has(column.field)),
      modelFieldMap,
      resolveColumnLabel,
    );
    return [...businessFields, ...systemFields];
  }, [tableColumns, modelFieldMap, resolveColumnLabel, SYSTEM_FIELD_DEFS]);

  return { allColumnDefs, viewManageFields, filterFieldMetadata };
}
