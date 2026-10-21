import React, { useCallback, type RefObject } from 'react';
import type { ColumnConfig } from '~/framework/meta/schemas/types';
import type { ExpressionContext } from '~/framework/meta/runtime/expression/context';
import type { UsePageRuntimeResult } from '../hooks/usePageRuntime';
import {
  resolveTemporalType,
  type DateTimeFormatPreferences,
} from '~/shared/services/dateTimeFormatService';
import { sanitizeHtml } from '~/framework/meta/utils/sanitizeHtml';
import { cellRendererRegistry } from '~/framework/meta/runtime/renderers/CellRendererRegistry';
import { resolveStatusTone, StatusDot } from '~/framework/meta/runtime/renderers/statusTone';
import { resolveAuditUserCellValue } from './auditUserDisplayFields';
import {
  getListFieldValueWithAlias,
  renderComponentToValueType,
  resolveFieldMetaRenderComponent,
} from './listFieldMetadata';
import {
  collectListReferenceDisplayConfigs,
  buildListReferenceDisplayCacheKey,
} from './listReferenceDisplay';
import type { DictItem, DynamicEntity } from './listPageTypes';

interface ListCellRendererOptions extends Pick<UsePageRuntimeResult, 'runtime' | 'locale' | 't'> {
  pageContext: ExpressionContext;
  modelFieldMap: Map<string, any>;
  referenceDisplayCache: Record<string, Record<string, string>>;
  dictDataCache: RefObject<Map<string, DictItem[]>>;
  dateTimeFormats: DateTimeFormatPreferences;
  effectiveTimezone: string;
}

/** Keep the list cell callbacks and their memoization in one rendering boundary. */
export function useListCellRenderer({
  runtime,
  pageContext,
  locale,
  t,
  modelFieldMap,
  referenceDisplayCache,
  dictDataCache,
  dateTimeFormats,
  effectiveTimezone,
}: ListCellRendererOptions) {
  const inferValueType = useCallback(
    (column: ColumnConfig, value: any, record?: DynamicEntity): ColumnConfig['valueType'] => {
      if (column.valueType) {
        return column.valueType;
      }
      const field = column.field || '';
      // renderComponent-driven visual type (colorpicker/progress/rating/money): the field's
      // control comes from extension.renderComponent, not its dataType, so the name-suffix
      // heuristics below miss it. Also honor the DSL column `renderType` key as a fallback.
      const byRenderComponent =
        renderComponentToValueType(resolveFieldMetaRenderComponent(field, modelFieldMap)) ??
        renderComponentToValueType(
          typeof (column as any).renderType === 'string'
            ? (column as any).renderType.toLowerCase()
            : undefined,
        );
      if (byRenderComponent) {
        return byRenderComponent;
      }
      const declaredTemporalType = resolveTemporalType(undefined, modelFieldMap.get(field)?.dataType, undefined);
      if (declaredTemporalType) return declaredTemporalType;
      // REFERENCE field: either ends with _id, or has a {field}_display sibling in the record
      if (field.endsWith('_id') || (record && record[`${field}_display`] !== undefined)) {
        return 'reference';
      }
      const declaredDataType = modelFieldMap.get(field)?.dataType;
      const temporalType = resolveTemporalType(field, declaredDataType, value);
      if (temporalType) return temporalType;
      // Detect boolean values (native boolean or string "true"/"false")
      if (typeof value === 'boolean' || value === 'true' || value === 'false') {
        return 'boolean' as any;
      }
      return undefined;
    },
    [modelFieldMap],
  );

  // Render cell content using CellRendererRegistry
  const renderCellContent = useCallback(
    (column: ColumnConfig, record: DynamicEntity, rowIndex: number) => {
      const rawValue = getListFieldValueWithAlias(record, column.field);
      const value = resolveAuditUserCellValue(record, column.field, rawValue);
      const recordWithAliasedField = Object.prototype.hasOwnProperty.call(record, column.field)
        ? record
        : { ...record, [column.field]: value };
      const referenceConfig = collectListReferenceDisplayConfigs([column], modelFieldMap)[0];
      const cacheKey = referenceConfig
        ? buildListReferenceDisplayCacheKey(referenceConfig)
        : undefined;
      const referenceDisplayValue =
        referenceConfig && value !== null && value !== undefined
          ? recordWithAliasedField[referenceConfig.displayKey] ||
            referenceDisplayCache[cacheKey || '']?.[String(value)]
          : undefined;
      const recordForRenderer = referenceDisplayValue
        ? { ...recordWithAliasedField, [referenceConfig!.displayKey]: referenceDisplayValue }
        : recordWithAliasedField;
      const effectiveValueType = referenceConfig
        ? 'reference'
        : inferValueType(column, value, recordForRenderer);

      // Null/undefined handling
      if (
        !((column as any).allowNullRenderer === true) &&
        (value === null || value === undefined)
      ) {
        return <span className="text-text-3">-</span>;
      }

      // If dictCode exists, try to translate value to label
      if (column.dictCode) {
        const dictItems = dictDataCache.current.get(column.dictCode);
        if (dictItems) {
          const item = dictItems.find((i) => String(i.value) === String(value));
          if (item) {
            // §3 / §1.3: dict-coded status renders as 色点 + 文字, not a filled pill.
            // A dict item may carry `extension.icon` (category dims like lead source)
            // to render a leading icon instead of the semantic color dot.
            return (
              <StatusDot
                tone={resolveStatusTone(typeof item.extension?.color === 'string' ? item.extension.color : undefined)}
                label={item.label}
                icon={typeof item.extension?.icon === 'string' ? item.extension.icon : undefined}
              />
            );
          }
        }
        // Dict not loaded or no match — show raw value
        return String(value);
      }

      // Handle custom render expressions first (if runtime is available)
      if (typeof column.render === 'string' && runtime) {
        try {
          const context = runtime.getContext();
          const evaluator = runtime.getEvaluator();
          const rendered = evaluator.evaluateTemplate(column.render, {
            ...context,
            row: recordForRenderer,
          });
          return <span dangerouslySetInnerHTML={{ __html: sanitizeHtml(rendered) }} />;
        } catch (err) {
          console.error('Render expression failed:', err);
        }
      }

      // Use CellRendererRegistry for valueType-based rendering
      const rendererType = column.presentation ? 'workspace' : (column as any).cellRenderer || effectiveValueType;
      return cellRendererRegistry.render(rendererType, {
        value,
        record: recordForRenderer,
        column: {
          field: column.field,
          label: typeof column.label === 'string' ? column.label : undefined,
          valueType: effectiveValueType,
          cellRenderer: (column as any).cellRenderer,
          format: column.format,
          dateTimeFormats,
          timezone: effectiveTimezone,
          render: column.render,
          tagMap: (column as any).tagMap,
          presentation: column.presentation,
        },
        expressionContext: pageContext,
        locale,
        t,
        rowIndex,
      });
    },
    [
      runtime,
      pageContext,
      locale,
      t,
      inferValueType,
      dateTimeFormats,
      effectiveTimezone,
      modelFieldMap,
      referenceDisplayCache,
    ],
  );

  return renderCellContent;
}
