/**
 * SubTableViewer - Sub-table viewer with inline editing, row-level validation,
 * and summary row support.
 *
 * Features:
 * - Read-only display of child records in a table format
 * - Inline row editing (double-click or edit button) with keyboard navigation
 *   (Enter = save, Esc = cancel, Tab = next field)
 * - Row-level validation (required, min/max, pattern, custom rules)
 * - Cross-row validation (e.g., SUM(amount) <= budget)
 * - Summary/aggregation footer row (SUM, AVG, COUNT, MIN, MAX) — reactive during editing
 * - DnD drag sorting and tree hierarchical display
 * - Command-based CRUD (create, update, delete)
 */

import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { useNavigate } from 'react-router';
import type {
  ButtonConfig,
  ColumnConfig,
  SubTableConfig,
  ValidationRule,
} from '~/framework/meta/schemas/types';
import { buildApiEndpoint, getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useTreeData } from '~/framework/meta/hooks/useTreeData';
import { useDictCache } from '~/framework/meta/rendering/pages/hooks/useDictCache';
import { DndSubTableWrapper } from '~/framework/meta/components/subtable/DndSubTableWrapper';
import { SortableSubTableRow } from '~/framework/meta/components/subtable/SortableSubTableRow';
import { InlineEditableCell } from '~/framework/meta/components/subtable/InlineEditableCell';
import { SubTableSummaryRow } from '~/framework/meta/components/subtable/SubTableSummaryRow';
import { isDescendant } from '~/framework/meta/components/subtable/dndUtils';
import { useTimezone } from '~/contexts/TimezoneContext';
import {
  formatInTimezone,
  resolveTemporalType,
  resolveTemporalFormat,
  type DateTimeFormatPreferences,
} from '~/shared/services/dateTimeFormatService';
import { evaluateVisibleWhen as evaluateVisibleWhenExpression } from '~/framework/meta/rendering/pages/utils/visibleWhen';
import {
  buildCommandTargetParams,
  getLegacyCompatibleRecordPid,
} from '~/framework/meta/utils/publicRecordId';

export interface SubTableViewerProps {
  config: SubTableConfig;
  parentRecordId: string;
  parentRecordData?: Record<string, any>;
  token?: string;
  locale?: string;
  t?: (key: string) => string;
  isEditable?: boolean;
  onDataChange?: () => void;
}

interface ChildFieldMeta {
  code: string;
  displayName?: string;
  dataType?: string;
  dictCode?: string;
  referenceModelCode?: string;
  refTarget?: Record<string, any>;
  constraints?: { required?: boolean };
  extension?: Record<string, any>;
}

type EnrichedColumnConfig = ColumnConfig & {
  dataType?: string;
  refTarget?: Record<string, any>;
  referenceModelCode?: string;
  extension?: Record<string, any>;
};

interface SubTableReferenceDisplayConfig {
  field: string;
  modelCode: string;
  valueField: string;
  displayField: string;
  displayKey: string;
}

interface PaginationResult<T> {
  records?: T[];
}

export const SubTableViewer: React.FC<SubTableViewerProps> = ({
  config,
  parentRecordId,
  parentRecordData,
  token,
  locale = 'zh-CN',
  t = (key: string) => key,
  isEditable = false,
  onDataChange,
}) => {
  const navigate = useNavigate();
  const { timezone, formats } = useTimezone();
  const [rows, setRows] = useState<Record<string, any>[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [refreshCounter, setRefreshCounter] = useState(0);

  // Inline add form state
  const [isAdding, setIsAdding] = useState(false);
  const [newRowData, setNewRowData] = useState<Record<string, any>>({});
  const [addErrors, setAddErrors] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [rowActionLoadingKey, setRowActionLoadingKey] = useState<string | null>(null);

  // Inline edit state
  const [editingRowId, setEditingRowId] = useState<string | null>(null);
  const [editingValues, setEditingValues] = useState<Record<string, any>>({});
  const [editErrors, setEditErrors] = useState<Record<string, string>>({});
  const [editSaving, setEditSaving] = useState(false);
  const [crossRowErrors, setCrossRowErrors] = useState<string[]>([]);
  const originalEditDataRef = useRef<Record<string, any>>({});
  const [childFieldMap, setChildFieldMap] = useState<Map<string, ChildFieldMeta>>(new Map());
  const [columnMetaLoading, setColumnMetaLoading] = useState(false);
  const [referenceDisplayCache, setReferenceDisplayCache] = useState<
    Record<string, Record<string, string>>
  >({});

  const shouldLoadChildFieldMeta = useMemo(() => {
    if (!config.childModel) return false;
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
  }, [config.childModel, config.columns]);

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
  }, [config.childModel, shouldLoadChildFieldMeta, token]);

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
    if (childFieldMap.size === 0) return config.columns as EnrichedColumnConfig[];

    return config.columns.map((col) => {
      const meta = childFieldMap.get(col.field);
      if (!meta) return col as EnrichedColumnConfig;

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
      if (!enriched.required && meta.constraints?.required) {
        enriched.required = true;
      }

      return enriched;
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

  const reloadRows = useCallback(() => {
    setRefreshCounter((c) => c + 1);
  }, []);

  const interpolateRecordValue = useCallback(
    (raw: string): string => {
      const legacyRecordKey = 'record' + 'Id';
      const publicRecordPlaceholder = new RegExp(
        `\\$\\{recordPid\\}|\\$\\{pid\\}|\\$\\{${legacyRecordKey}\\}`,
        'g',
      );
      let value = raw.replace(publicRecordPlaceholder, parentRecordId);
      value = value.replace(/\$\{record\.(\w+)\}/g, (_: string, field: string) => {
        return String(parentRecordData?.[field] ?? parentRecordId);
      });
      value = value.replace(/\$\{(\w+)\}/g, (_: string, field: string) => {
        return String(parentRecordData?.[field] ?? '');
      });
      return value;
    },
    [parentRecordData, parentRecordId],
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
    if (!parentRecordId) return;

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
          // Support both paginated (data.records) and direct array (data) responses
          const rawData = result.data;
          const resultRecords = Array.isArray(rawData) ? rawData : (rawData?.records ?? []);
          if (ResultHelper.isSuccess(result) && Array.isArray(resultRecords)) {
            setRows(resultRecords);
          } else {
            setRows([]);
          }
          setLoading(false);
          return;
        } else if (config.resolveVia) {
          // Indirect query
          const rv = config.resolveVia;
          const intermediateModel = rv.model;
          const intermediateFilters: Array<{ fieldName: string; operator: string; value: string }> =
            [{ fieldName: rv.parentField, operator: 'EQ', value: parentRecordId }];
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

          const intermediateRecords = intermediateRes.data?.records ?? [];
          if (
            !ResultHelper.isSuccess(intermediateRes) ||
            !Array.isArray(intermediateRecords) ||
            intermediateRecords.length === 0
          ) {
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
              { fieldName: config.parentField, operator: 'EQ', value: parentRecordId },
            ]),
          };
        }

        const result = await fetchResult<any>(endpoint, {
          method: 'get',
          params: filters,
          token,
        });

        const resultRecords = result.data?.records ?? [];
        if (ResultHelper.isSuccess(result) && Array.isArray(resultRecords)) {
          setRows(resultRecords);
        } else {
          setRows([]);
        }
      } catch (err) {
        console.error('[SubTableViewer] Failed to load data:', err);
        setError(err instanceof Error ? err.message : 'Failed to load data');
      } finally {
        setLoading(false);
      }
    };

    loadData();
  }, [parentRecordId, parentRecordData, config, token, refreshCounter, interpolateRecordValue]);

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
  }, [referenceDisplayCache, referenceDisplayConfigs, rows, token]);

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

  // ------- Field Validation -------
  const validateFieldValue = useCallback(
    (col: ColumnConfig, value: any): string | null => {
      // Required
      if (col.required && (value === undefined || value === null || value === '')) {
        const msg = t('common.validation.required');
        return msg !== 'common.validation.required' ? msg : 'This field is required';
      }

      if (value === undefined || value === null || value === '') return null;

      // Numeric min/max
      const numVal = typeof value === 'number' ? value : Number(value);
      if (!isNaN(numVal)) {
        if (col.min !== undefined && numVal < col.min) {
          const msg = t('validation.min');
          return msg !== 'validation.min' ? `${msg}: ${col.min}` : `Minimum value: ${col.min}`;
        }
        if (col.max !== undefined && numVal > col.max) {
          const msg = t('validation.max');
          return msg !== 'validation.max' ? `${msg}: ${col.max}` : `Maximum value: ${col.max}`;
        }
      }

      // Pattern
      if (col.pattern && typeof value === 'string') {
        if (!new RegExp(col.pattern).test(value)) {
          const msg = t('validation.pattern');
          return msg !== 'validation.pattern' ? msg : 'Invalid format';
        }
      }

      // Custom validation rules
      if (col.validation) {
        for (const rule of col.validation) {
          const ruleError = evaluateValidationRule(rule, value, t);
          if (ruleError) return ruleError;
        }
      }

      return null;
    },
    [t],
  );

  // ------- Cross-Row Validation -------
  const validateCrossRowRules = useCallback(
    (allRows: Record<string, any>[]): string[] => {
      if (!config.crossRowRules || config.crossRowRules.length === 0) return [];

      const errors: string[] = [];
      for (const rule of config.crossRowRules) {
        const values = allRows.map((r) => Number(r[rule.field]) || 0);
        let aggregated = 0;
        switch (rule.aggregation) {
          case 'sum':
            aggregated = values.reduce((a, b) => a + b, 0);
            break;
          case 'avg':
            aggregated = values.length > 0 ? values.reduce((a, b) => a + b, 0) / values.length : 0;
            break;
          case 'count':
            aggregated = values.length;
            break;
          case 'min':
            aggregated = values.length > 0 ? Math.min(...values) : 0;
            break;
          case 'max':
            aggregated = values.length > 0 ? Math.max(...values) : 0;
            break;
        }

        let threshold: number;
        const recordFieldMatch =
          typeof rule.value === 'string'
            ? rule.value.match(/^\$\{record\.([A-Za-z0-9_.-]+)\}$/)
            : null;
        if (recordFieldMatch) {
          const fieldName = recordFieldMatch[1];
          threshold = Number(parentRecordData?.[fieldName]) || 0;
        } else {
          threshold = Number(rule.value) || 0;
        }

        let violated = false;
        switch (rule.operator) {
          case 'lte':
            violated = aggregated > threshold;
            break;
          case 'gte':
            violated = aggregated < threshold;
            break;
          case 'LT':
            violated = aggregated >= threshold;
            break;
          case 'GT':
            violated = aggregated <= threshold;
            break;
          case 'EQ':
            violated = aggregated !== threshold;
            break;
        }

        if (violated) {
          const msg =
            typeof rule.message === 'string'
              ? t(rule.message) !== rule.message
                ? t(rule.message)
                : rule.message
              : (rule.message as any)?.['zh-CN'] || (rule.message as any)?.['en-US'] || '';
          errors.push(msg);
        }
      }
      return errors;
    },
    [config.crossRowRules, parentRecordData, t],
  );

  // Create command handler
  const handleAddRow = useCallback(async () => {
    if (!config.commands?.create) return;

    // Validate all editable fields (required, min/max, pattern, custom rules)
    const errors: Record<string, string> = {};
    for (const col of effectiveColumns) {
      if (col.editable !== false) {
        const fieldError = validateFieldValue(col, newRowData[col.field]);
        if (fieldError) errors[col.field] = fieldError;
      }
    }
    if (Object.keys(errors).length > 0) {
      setAddErrors(errors);
      return;
    }

    setSaving(true);
    setAddErrors({});
    try {
      const payload: Record<string, any> = {
        ...resolvedDefaultValues,
        ...newRowData,
        [config.parentField]: parentRecordId,
      };

      const result = await fetchResult(`/api/meta/commands/execute/${config.commands.create}`, {
        method: 'post',
        params: {
          payload,
          operationType: 'create',
        },
        token,
      });

      if (!ResultHelper.isSuccess(result)) {
        throw new Error(result.desc || result.message || 'Failed to add line');
      }

      setNewRowData({});
      setIsAdding(false);
      reloadRows();
      onDataChange?.();
    } catch (err) {
      setAddErrors({ _form: err instanceof Error ? err.message : 'Failed to add' });
    } finally {
      setSaving(false);
    }
  }, [
    config,
    effectiveColumns,
    newRowData,
    parentRecordId,
    token,
    reloadRows,
    onDataChange,
    resolvedDefaultValues,
  ]);

  // Delete command handler
  const handleDeleteRow = useCallback(
    async (rowPid: string) => {
      if (!config.commands?.delete || !rowPid) return;

      setDeletingId(rowPid);
      try {
        const result = await fetchResult(`/api/meta/commands/execute/${config.commands.delete}`, {
          method: 'post',
          params: {
            ...buildCommandTargetParams(rowPid),
            operationType: 'delete',
          },
          token,
        });

        if (!ResultHelper.isSuccess(result)) {
          throw new Error(result.desc || result.message || 'Failed to delete');
        }

        reloadRows();
        onDataChange?.();
      } catch (err) {
        console.error('[SubTableViewer] Delete failed:', err);
      } finally {
        setDeletingId(null);
      }
    },
    [config.commands, token, reloadRows, onDataChange],
  );

  const resolveButtonLabel = useCallback(
    (button: ButtonConfig): string => {
      if (button.content) return getLocalizedText(button.content, locale, t);
      if (button.label) return getLocalizedText(button.label, locale, t);
      if (button.commandCode && button.commandCode.includes(':')) {
        const [ns, actionCode] = button.commandCode.split(':');
        const namespaced = `${ns}.action.${actionCode}`;
        const translated = t(namespaced);
        if (translated && translated !== namespaced) return translated;
      }
      if (button.action && typeof button.action === 'string') {
        const key = `action.${button.action}`;
        const translated = t(key);
        if (translated && translated !== key) return translated;
      }
      const codeKey = `action.${button.code}`;
      const codeTranslated = t(codeKey);
      if (codeTranslated && codeTranslated !== codeKey) return codeTranslated;
      return button.code;
    },
    [locale, t],
  );

  const resolveRowActionCommand = useCallback((button: ButtonConfig): string | null => {
    const actionDef = button.action && typeof button.action === 'object' ? button.action : null;
    const command = (actionDef as any)?.command || button.commandCode;
    return typeof command === 'string' && command.length > 0 ? command : null;
  }, []);

  const interpolateRowPath = useCallback((raw: string, row: Record<string, any>): string => {
    const rowId = getLegacyCompatibleRecordPid(row) || '';
    let value = raw.replace(/\{(\w+)\}/g, (_: string, field: string) => {
      if (field === 'pid' || field === 'id') return encodeURIComponent(String(rowId));
      return encodeURIComponent(String(row[field] ?? ''));
    });
    value = value.replace(/\$\{row\.(\w+)\}/g, (_: string, field: string) => {
      return encodeURIComponent(String(row[field] ?? ''));
    });
    value = value.replace(/\$\{(\w+)\}/g, (_: string, field: string) => {
      if (field === 'pid' || field === 'id') return encodeURIComponent(String(rowId));
      return encodeURIComponent(String(row[field] ?? ''));
    });
    return value;
  }, []);

  const resolveNavigatePath = useCallback(
    (target: string, row: Record<string, any>): string => {
      const rowId = getLegacyCompatibleRecordPid(row);
      if (target.startsWith('/')) return interpolateRowPath(target, row);

      const lastUnderscoreIdx = target.lastIndexOf('_');
      if (lastUnderscoreIdx === -1) return `/p/${target}`;

      const suffix = target.substring(lastUnderscoreIdx + 1);
      const modelCodePart = target.substring(0, lastUnderscoreIdx);
      switch (suffix) {
        case 'form':
          return rowId ? `/p/${modelCodePart}/edit/${rowId}` : `/p/${modelCodePart}/new`;
        case 'detail':
        case 'view':
          return `/p/${modelCodePart}/view/${rowId}`;
        case 'list':
          return `/p/${modelCodePart}`;
        default:
          return `/p/${modelCodePart}`;
      }
    },
    [interpolateRowPath],
  );

  const evaluateRowCondition = useCallback(
    (expression: string | undefined, row: Record<string, any>) => {
      return evaluateVisibleWhenExpression(expression, {
        record: row,
        row,
        form: parentRecordData || {},
      });
    },
    [parentRecordData],
  );

  const isRowActionVisible = useCallback(
    (button: ButtonConfig, row: Record<string, any>) =>
      evaluateRowCondition(button.visibleWhen, row),
    [evaluateRowCondition],
  );

  const isRowActionDisabled = useCallback(
    (button: ButtonConfig, row: Record<string, any>) => {
      if (button.disabled) return true;
      if (button.disableWhen && evaluateRowCondition(button.disableWhen, row)) return true;
      if (button.enableWhen && !evaluateRowCondition(button.enableWhen, row)) return true;
      return false;
    },
    [evaluateRowCondition],
  );

  const handleRowAction = useCallback(
    async (button: ButtonConfig, row: Record<string, any>, rowIndex: number) => {
      const rowPid = getLegacyCompatibleRecordPid(row);
      const actionDef = button.action && typeof button.action === 'object' ? button.action : null;
      const actionType = (actionDef as any)?.type || (button.commandCode ? 'command' : null);
      if (actionType === 'navigate') {
        const target = (actionDef as any)?.to || button.navigateTo;
        if (!target) return;
        const path = resolveNavigatePath(String(target), row);
        const command = (actionDef as any)?.command || button.commandCode;
        if (command) {
          const sep = path.includes('?') ? '&' : '?';
          navigate(`${path}${sep}commandCode=${encodeURIComponent(command)}`);
        } else {
          navigate(path);
        }
        return;
      }

      const command = resolveRowActionCommand(button);
      if (!rowPid || !command) return;

      if (actionType && !['command', 'state_transition'].includes(actionType)) {
        setEditErrors({
          _form: `Unsupported sub-table row action type: ${actionType}`,
        });
        return;
      }

      const loadingKey = `${rowPid}:${button.code}:${rowIndex}`;
      setRowActionLoadingKey(loadingKey);
      setEditErrors({});
      try {
        const result = await fetchResult(`/api/meta/commands/execute/${command}`, {
          method: 'post',
          params: {
            ...buildCommandTargetParams(rowPid),
            payload: row,
            operationType: actionType === 'state_transition' ? 'UPDATE' : 'update',
          },
          token,
        });

        if (!ResultHelper.isSuccess(result)) {
          throw new Error(result.desc || result.message || `Command ${command} failed`);
        }

        reloadRows();
        onDataChange?.();
      } catch (err) {
        setEditErrors({
          _form: err instanceof Error ? err.message : `Command ${command} failed`,
        });
      } finally {
        setRowActionLoadingKey(null);
      }
    },
    [navigate, onDataChange, reloadRows, resolveNavigatePath, resolveRowActionCommand, token],
  );

  // Enter edit mode for a row
  const handleStartEdit = useCallback(
    (row: Record<string, any>) => {
      if (!canInlineEdit) return;
      const rowId = getLegacyCompatibleRecordPid(row);
      if (!rowId) return;
      const values: Record<string, any> = {};
      for (const col of effectiveColumns) {
        values[col.field] = row[col.field] ?? '';
      }
      setEditingRowId(rowId);
      setEditingValues(values);
      originalEditDataRef.current = { ...values };
      setEditErrors({});
      setCrossRowErrors([]);
    },
    [effectiveColumns, canInlineEdit],
  );

  // Cancel edit mode
  const handleCancelEdit = useCallback(() => {
    setEditingRowId(null);
    setEditingValues({});
    setEditErrors({});
    setCrossRowErrors([]);
  }, []);

  // Save edited row via update command with full validation
  const handleSaveEdit = useCallback(async () => {
    if (!editingRowId || !config.commands?.update) return;

    // Row-level field validation (required, min/max, pattern, custom rules)
    const errors: Record<string, string> = {};
    for (const col of effectiveColumns) {
      if (isColumnEditable(col)) {
        const fieldError = validateFieldValue(col, editingValues[col.field]);
        if (fieldError) errors[col.field] = fieldError;
      }
    }
    if (Object.keys(errors).length > 0) {
      setEditErrors(errors);
      return;
    }

    // Cross-row validation (merge edited row into full dataset)
    if (config.crossRowRules && config.crossRowRules.length > 0) {
      const mergedRows = rows.map((r) => {
        if (getLegacyCompatibleRecordPid(r) === editingRowId) {
          return { ...r, ...editingValues };
        }
        return r;
      });
      const crossErrors = validateCrossRowRules(mergedRows);
      if (crossErrors.length > 0) {
        setCrossRowErrors(crossErrors);
        return;
      }
    }

    // Compute changed fields only (skip unchanged)
    const changed: Record<string, any> = {};
    for (const [key, val] of Object.entries(editingValues)) {
      if (val !== originalEditDataRef.current[key]) {
        changed[key] = val;
      }
    }
    if (Object.keys(changed).length === 0) {
      handleCancelEdit();
      return;
    }

    setEditSaving(true);
    setEditErrors({});
    setCrossRowErrors([]);
    try {
      const result = await fetchResult(`/api/meta/commands/execute/${config.commands.update}`, {
        method: 'post',
        params: {
          ...buildCommandTargetParams(editingRowId),
          payload: changed,
          operationType: 'update',
        },
        token,
      });

      if (!ResultHelper.isSuccess(result)) {
        throw new Error(result.desc || result.message || 'Failed to update');
      }

      // Optimistically update local row data
      setRows((prev) =>
        prev.map((r) => {
          if ((r.pid || String(r.id)) === editingRowId) {
            return { ...r, ...editingValues };
          }
          return r;
        }),
      );
      setEditingRowId(null);
      setEditingValues({});
      setCrossRowErrors([]);
      reloadRows();
      onDataChange?.();
    } catch (err) {
      setEditErrors({ _form: err instanceof Error ? err.message : 'Failed to update' });
    } finally {
      setEditSaving(false);
    }
  }, [
    editingRowId,
    editingValues,
    config,
    effectiveColumns,
    rows,
    token,
    reloadRows,
    onDataChange,
    isColumnEditable,
    validateFieldValue,
    validateCrossRowRules,
    handleCancelEdit,
  ]);

  // Keyboard handler for edit mode with Tab navigation
  const handleEditKeyDown = useCallback(
    (e: React.KeyboardEvent, fieldName?: string) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        handleSaveEdit();
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        handleCancelEdit();
      }
      // Tab: navigate between editable fields within the row
      if (e.key === 'Tab' && fieldName) {
        const currentIdx = editableFields.indexOf(fieldName);
        if (currentIdx !== -1) {
          const nextIdx = e.shiftKey ? currentIdx - 1 : currentIdx + 1;
          if (nextIdx >= 0 && nextIdx < editableFields.length) {
            e.preventDefault();
            const nextField = editableFields[nextIdx];
            const nextInput = document.querySelector(
              `[data-testid="inline-edit-${nextField}"]`,
            ) as HTMLElement;
            if (nextInput) {
              setTimeout(() => nextInput.focus(), 0);
            }
          } else if (!e.shiftKey && nextIdx >= editableFields.length) {
            // Tab past last field = save
            e.preventDefault();
            handleSaveEdit();
          }
        }
      }
    },
    [handleSaveEdit, handleCancelEdit, editableFields],
  );

  const hasConfiguredRowActions = Boolean(config.actions && config.actions.length > 0);
  const hasActions =
    hasConfiguredRowActions ||
    (isEditable && (config.commands?.create || config.commands?.delete || canInlineEdit));
  const isSortable = config.sortable && isEditable;
  const sortField = config.sortField || 'sort_order';
  const totalCols = effectiveColumns.length + (hasActions ? 1 : 0) + (isSortable ? 1 : 0);
  const extraColCount = (hasActions ? 1 : 0) + (isSortable ? 1 : 0);
  const emptyStateTitle = config.emptyState?.title
    ? getLocalizedText(config.emptyState.title, locale, t)
    : t('common.noData') || 'No data';
  const emptyStateDescription = config.emptyState?.description
    ? getLocalizedText(config.emptyState.description, locale, t)
    : '';
  const emptyStateActionLabel = config.emptyState?.actionLabel
    ? getLocalizedText(config.emptyState.actionLabel, locale, t)
    : t('common.addLine') !== 'common.addLine'
      ? t('common.addLine')
      : '添加明细';

  // Tree data management
  const { visibleRows: treeRows, toggleExpand } = useTreeData(rows, config.treeConfig);
  const displayRows = config.treeConfig ? treeRows : rows;

  // DnD item IDs
  const dndItems = useMemo(
    () => displayRows.map((r) => r.pid || String(r.id || '')),
    [displayRows],
  );

  // DnD drop handler → batch API update
  const handleDragEnd = useCallback(
    async (activeId: string, overId: string, _deltaX: number) => {
      if (!isSortable) return;

      // Simple flat sort: swap positions
      const oldIndex = displayRows.findIndex((r) => (r.pid || r.id) === activeId);
      const newIndex = displayRows.findIndex((r) => (r.pid || r.id) === overId);
      if (oldIndex === -1 || newIndex === -1 || oldIndex === newIndex) return;

      // Tree mode: check for cycle
      if (config.treeConfig) {
        if (isDescendant(activeId, overId, treeRows)) return;
      }

      // Reorder locally
      const reordered = [...displayRows];
      const [moved] = reordered.splice(oldIndex, 1);
      reordered.splice(newIndex, 0, moved);

      // Compute batch update payload
      const updates = reordered.map((r, i) => ({
        pid: r.pid || r.id,
        [sortField]: (i + 1) * 1000,
      }));

      // Optimistic update
      setRows(reordered.map((r, i) => ({ ...r, [sortField]: (i + 1) * 1000 })));

      // Persist via batch API
      try {
        await fetchResult(`/api/dynamic/${config.childModel}/batch`, {
          method: 'put',
          token,
          params: updates,
        });
      } catch (err) {
        console.error('[SubTableViewer] Batch sort update failed:', err);
        reloadRows(); // Rollback by reloading
      }
    },
    [isSortable, displayRows, treeRows, config, sortField, token, reloadRows],
  );

  if (loading || columnMetaLoading) {
    return (
      <div className="text-text-3 py-8 text-center text-sm">
        {t('common.loading') || 'Loading...'}
      </div>
    );
  }

  if (error) {
    return <div className="text-status-red py-4 text-center text-sm">{error}</div>;
  }

  return (
    <div
      className="rounded-card border-border overflow-hidden border"
      data-testid="subtable-viewer"
    >
      {/* Cross-row validation errors banner */}
      {crossRowErrors.length > 0 && (
        <div
          className="bg-status-red-bg text-status-red border-b border-red-200 px-4 py-2 text-sm"
          data-testid="subtable-cross-row-errors"
          role="alert"
        >
          {crossRowErrors.map((msg, i) => (
            <p key={i}>{msg}</p>
          ))}
        </div>
      )}

      {displayRows.length === 0 && !isAdding ? (
        <div
          className="flex flex-col items-center justify-center px-6 py-10 text-center"
          data-testid="subtable-empty-state"
        >
          <div className="text-text-2 text-sm font-medium">{emptyStateTitle}</div>
          {emptyStateDescription ? (
            <div className="text-text-2 mt-2 max-w-md text-sm leading-6">
              {emptyStateDescription}
            </div>
          ) : null}
          {isEditable && config.commands?.create ? (
            <button
              onClick={() => setIsAdding(true)}
              data-testid="subtable-empty-action"
              className="rounded-control bg-accent-weak text-accent mt-5 border border-blue-200 px-4 py-2 text-sm font-medium transition-colors hover:border-blue-300 hover:bg-blue-100"
            >
              {emptyStateActionLabel}
            </button>
          ) : null}
        </div>
      ) : (
        <table className="divide-border min-w-full divide-y" data-testid="subtable-table">
          <thead className="bg-subtle">
            <tr>
              {isSortable && <th className="text-text-3 w-10 px-1 py-2.5 text-xs font-medium"></th>}
              {effectiveColumns.map((col: EnrichedColumnConfig) => (
                <th
                  key={col.field}
                  className={`text-text-2 px-4 py-2.5 text-xs font-medium tracking-wider uppercase ${
                    col.align === 'right'
                      ? 'text-right'
                      : col.align === 'center'
                        ? 'text-center'
                        : 'text-left'
                  }`}
                  style={
                    col.width
                      ? { width: typeof col.width === 'number' ? `${col.width}px` : col.width }
                      : undefined
                  }
                >
                  {col.label
                    ? getLocalizedText(col.label, locale, t)
                    : resolveColumnLabel(col.field)}
                  {col.required && <span className="text-status-red ml-0.5">*</span>}
                </th>
              ))}
              {hasActions && (
                <th className="text-text-2 w-28 px-4 py-2.5 text-center text-xs font-medium">
                  {t('common.actions') !== 'common.actions' ? t('common.actions') : 'Actions'}
                </th>
              )}
            </tr>
          </thead>
          <tbody className="bg-panel divide-y divide-gray-100">
            <DndSubTableWrapper
              items={dndItems}
              onDragEnd={handleDragEnd}
              disabled={!isSortable || !!editingRowId}
            >
              {displayRows.map((row, rowIndex) => {
                const rowId = row.pid || String(rowIndex);
                const isEditingThis = editingRowId === rowId;
                return (
                  <SortableSubTableRow
                    key={rowId}
                    id={rowId}
                    depth={row._depth || 0}
                    hasChildren={row._hasChildren || false}
                    expanded={row._expanded || false}
                    onToggleExpand={() => toggleExpand(rowId)}
                    sortable={!!isSortable && !editingRowId}
                  >
                    {effectiveColumns.map((col: EnrichedColumnConfig) => {
                      const cellAlign =
                        col.align === 'right'
                          ? 'text-right'
                          : col.align === 'center'
                            ? 'text-center'
                            : 'text-left';

                      // --- Editing mode ---
                      if (isEditingThis) {
                        const editable = isColumnEditable(col);
                        const referenceDisplayValue = getReferenceDisplayValue(row, col);
                        const dictOptions = col.dictCode
                          ? (getDictItems(col.dictCode) || []).map((d) => ({
                              value: d.value,
                              label: d.label,
                            }))
                          : undefined;
                        return (
                          <td
                            key={col.field}
                            className={`px-4 py-1.5 ${cellAlign} bg-blue-50/40 transition-colors`}
                          >
                            <InlineEditableCell
                              col={col}
                              value={editable ? editingValues[col.field] : row[col.field]}
                              displayValue={formatCellValue(
                                row[col.field],
                                col,
                                getDictLabel,
                                timezone,
                                formats,
                                referenceDisplayValue,
                              )}
                              isEditing={editable}
                              error={editErrors[col.field]}
                              onChange={(val) => {
                                setEditingValues((prev) => ({ ...prev, [col.field]: val }));
                                if (editErrors[col.field]) {
                                  setEditErrors((prev) => {
                                    const n = { ...prev };
                                    delete n[col.field];
                                    return n;
                                  });
                                }
                              }}
                              onKeyDown={(e) => handleEditKeyDown(e, col.field)}
                              dictOptions={dictOptions}
                              autoFocus={editableFields[0] === col.field}
                              t={t}
                            />
                          </td>
                        );
                      }

                      // --- Read mode ---
                      const referenceDisplayValue = getReferenceDisplayValue(row, col);
                      return (
                        <td
                          key={col.field}
                          className={`text-text-2 px-4 py-2 text-sm ${cellAlign}${
                            canInlineEdit ? 'cursor-pointer hover:bg-gray-50/50' : ''
                          }`}
                          onDoubleClick={canInlineEdit ? () => handleStartEdit(row) : undefined}
                        >
                          {formatCellValue(
                            row[col.field],
                            col,
                            getDictLabel,
                            timezone,
                            formats,
                            referenceDisplayValue,
                          )}
                        </td>
                      );
                    })}
                    {hasActions && (
                      <td className="px-4 py-2 text-center whitespace-nowrap">
                        {isEditingThis ? (
                          <>
                            <button
                              onClick={handleSaveEdit}
                              disabled={editSaving}
                              data-testid={`subtable-edit-save-${rowIndex}`}
                              className="text-accent mr-2 text-xs hover:text-blue-800 disabled:opacity-50"
                            >
                              {editSaving
                                ? '...'
                                : t('common.save') !== 'common.save'
                                  ? t('common.save')
                                  : 'Save'}
                            </button>
                            <button
                              onClick={handleCancelEdit}
                              data-testid={`subtable-edit-cancel-${rowIndex}`}
                              className="text-text-2 hover:text-text-2 text-xs"
                            >
                              {t('common.cancel') !== 'common.cancel'
                                ? t('common.cancel')
                                : 'Cancel'}
                            </button>
                          </>
                        ) : (
                          <div className="flex items-center justify-center gap-2">
                            {config.actions
                              ?.filter((button): button is ButtonConfig => {
                                if (!button) return false;
                                return isRowActionVisible(button, row);
                              })
                              .map((button) => {
                                const rowPid = getLegacyCompatibleRecordPid(row);
                                const loadingKey = `${rowPid || rowIndex}:${button.code}:${rowIndex}`;
                                const isLoading = rowActionLoadingKey === loadingKey;
                                const disabled =
                                  !!editingRowId || isLoading || isRowActionDisabled(button, row);
                                return (
                                  <button
                                    key={button.code}
                                    onClick={() => handleRowAction(button, row, rowIndex)}
                                    disabled={disabled}
                                    data-testid={`subtable-row-action-${button.code}-${rowIndex}`}
                                    className={`text-xs disabled:cursor-not-allowed disabled:opacity-40 ${
                                      button.danger || button.variant === 'danger'
                                        ? 'text-status-red hover:text-red-700'
                                        : 'text-accent hover:text-blue-700'
                                    }`}
                                    title={resolveButtonLabel(button)}
                                  >
                                    {isLoading ? '...' : resolveButtonLabel(button)}
                                  </button>
                                );
                              })}
                            {canInlineEdit && (
                              <button
                                onClick={() => handleStartEdit(row)}
                                disabled={!!editingRowId}
                                data-testid={`subtable-edit-${rowIndex}`}
                                className="text-accent text-xs hover:text-blue-700 disabled:cursor-not-allowed disabled:opacity-30"
                                title={
                                  t('common.edit') !== 'common.edit' ? t('common.edit') : 'Edit'
                                }
                              >
                                <svg
                                  className="inline h-3.5 w-3.5"
                                  fill="none"
                                  viewBox="0 0 24 24"
                                  strokeWidth={1.5}
                                  stroke="currentColor"
                                >
                                  <path
                                    strokeLinecap="round"
                                    strokeLinejoin="round"
                                    d="M16.862 4.487l1.687-1.688a1.875 1.875 0 112.652 2.652L10.582 16.07a4.5 4.5 0 01-1.897 1.13L6 18l.8-2.685a4.5 4.5 0 011.13-1.897l8.932-8.931zm0 0L19.5 7.125M18 14v4.75A2.25 2.25 0 0115.75 21H5.25A2.25 2.25 0 013 18.75V8.25A2.25 2.25 0 015.25 6H10"
                                  />
                                </svg>
                              </button>
                            )}
                            {config.commands?.delete && (
                              <button
                                onClick={() => handleDeleteRow(getLegacyCompatibleRecordPid(row) || '')}
                                disabled={
                                  deletingId === getLegacyCompatibleRecordPid(row) || !!editingRowId
                                }
                                data-testid={`subtable-delete-${rowIndex}`}
                                className="text-status-red text-xs hover:text-red-700 disabled:opacity-50"
                              >
                                {deletingId === getLegacyCompatibleRecordPid(row)
                                  ? '...'
                                  : t('common.delete') !== 'common.delete'
                                    ? t('common.delete')
                                    : 'Delete'}
                              </button>
                            )}
                          </div>
                        )}
                      </td>
                    )}
                  </SortableSubTableRow>
                );
              })}
            </DndSubTableWrapper>

            {/* Inline add form row */}
            {isEditable && isAdding && (
              <tr
                className="border-t border-blue-100 bg-blue-50/30"
                data-testid="subtable-add-form"
              >
                {effectiveColumns.map((col: EnrichedColumnConfig) => (
                  <td
                    key={col.field}
                    className={`px-4 py-2 ${col.align === 'right' ? 'text-right' : 'text-left'}`}
                  >
                    {col.editable === false ? (
                      <span className="text-text-3 text-xs">-</span>
                    ) : (
                      <div>
                        <input
                          type={isNumericField(col) ? 'number' : 'text'}
                          value={newRowData[col.field] ?? ''}
                          onChange={(e) => {
                            const val =
                              isNumericField(col) && e.target.value
                                ? Number(e.target.value)
                                : e.target.value;
                            setNewRowData((prev) => ({ ...prev, [col.field]: val }));
                            if (addErrors[col.field]) {
                              setAddErrors((prev) => {
                                const next = { ...prev };
                                delete next[col.field];
                                return next;
                              });
                            }
                          }}
                          placeholder={
                            col.required
                              ? `${resolveColumnLabel(col.field)} *`
                              : resolveColumnLabel(col.field)
                          }
                          data-testid={`subtable-add-${col.field}`}
                          className={`w-full rounded border px-2 py-1 text-sm focus:ring-1 focus:outline-none ${
                            addErrors[col.field]
                              ? 'border-status-red focus:ring-red-400'
                              : 'border-border-strong focus:ring-blue-400'
                          }`}
                          onKeyDown={(e) => {
                            if (e.key === 'Enter') handleAddRow();
                            if (e.key === 'Escape') {
                              setIsAdding(false);
                              setNewRowData({});
                              setAddErrors({});
                            }
                          }}
                        />
                        {addErrors[col.field] && (
                          <p
                            className="text-status-red mt-0.5 text-xs"
                            data-testid={`subtable-error-${col.field}`}
                          >
                            {addErrors[col.field]}
                          </p>
                        )}
                      </div>
                    )}
                  </td>
                ))}
                <td className="px-4 py-2 text-center whitespace-nowrap">
                  <button
                    onClick={handleAddRow}
                    disabled={saving}
                    data-testid="subtable-save-btn"
                    className="text-accent mr-2 text-xs hover:text-blue-800 disabled:opacity-50"
                  >
                    {saving
                      ? '...'
                      : t('common.save') !== 'common.save'
                        ? t('common.save')
                        : 'Save'}
                  </button>
                  <button
                    onClick={() => {
                      setIsAdding(false);
                      setNewRowData({});
                      setAddErrors({});
                    }}
                    data-testid="subtable-cancel-btn"
                    className="text-text-2 hover:text-text-2 text-xs"
                  >
                    {t('common.cancel') !== 'common.cancel' ? t('common.cancel') : 'Cancel'}
                  </button>
                </td>
              </tr>
            )}
            {addErrors._form && (
              <tr>
                <td
                  colSpan={totalCols}
                  className="text-status-red px-4 py-2 text-sm"
                  data-testid="subtable-form-error"
                >
                  {addErrors._form}
                </td>
              </tr>
            )}
            {editErrors._form && (
              <tr>
                <td
                  colSpan={totalCols}
                  className="text-status-red px-4 py-2 text-sm"
                  data-testid="subtable-edit-form-error"
                >
                  {editErrors._form}
                </td>
              </tr>
            )}
          </tbody>

          {/* Summary row — reactive during inline editing */}
          {config.summary && (
            <SubTableSummaryRow
              columns={effectiveColumns}
              rows={
                editingRowId
                  ? rows.map((r) =>
                      (r.pid || String(r.id)) === editingRowId ? { ...r, ...editingValues } : r,
                    )
                  : rows
              }
              summary={config.summary}
              locale={locale}
              t={t}
              extraColCount={extraColCount}
            />
          )}
        </table>
      )}

      {/* Add Line button */}
      {isEditable && config.commands?.create && !isAdding && displayRows.length > 0 && (
        <div className="border-border border-t">
          <button
            onClick={() => setIsAdding(true)}
            data-testid="subtable-add-row"
            className="text-text-3 hover:bg-hover hover:text-accent w-full py-2 text-xs transition-colors"
          >
            + {emptyStateActionLabel}
          </button>
        </div>
      )}
    </div>
  );
};

// ------- Utility Functions -------

export function formatCellValue(
  value: any,
  col: ColumnConfig | EnrichedColumnConfig,
  getDictLabel?: (code: string, value: string) => string | undefined,
  timeZone?: string,
  formats?: Partial<DateTimeFormatPreferences>,
  referenceDisplayValue?: string,
): string {
  if (value == null) return '-';

  if (isReferenceColumn(col as EnrichedColumnConfig)) {
    return referenceDisplayValue || '-';
  }

  // Dict label lookup
  if (col.dictCode && getDictLabel) {
    const label = getDictLabel(col.dictCode, String(value));
    if (label) return label;
  }

  // Temporal values: backend emits UTC, convert to the effective display
  // timezone via the canonical formatter (never slice the raw ISO string).
  const temporalType = resolveTemporalType(col.field, col.valueType as string | undefined, value);
  if (temporalType) {
    const fmt = resolveTemporalFormat(temporalType, formats, col.format);
    return formatInTimezone(value, fmt, timeZone);
  }

  if (col.format === 'currency' || col.valueType === 'currency') {
    return formatNumber(Number(value));
  }

  if (col.format === 'percent') {
    return `${(Number(value) * 100).toFixed(1)}%`;
  }

  if (typeof value === 'boolean') {
    return value ? 'Yes' : 'No';
  }

  if (typeof value === 'number') {
    return formatNumber(value);
  }

  return String(value);
}

function getFieldMetaExtension(meta?: ChildFieldMeta): Record<string, any> {
  const extension = meta?.extension || {};
  const nested =
    extension.extension && typeof extension.extension === 'object'
      ? (extension.extension as Record<string, any>)
      : {};
  return { ...nested, ...extension };
}

function resolveChildFieldDisplayName(meta?: ChildFieldMeta): string | undefined {
  if (!meta) return undefined;
  const extension = getFieldMetaExtension(meta);
  const candidate =
    meta.displayName ||
    extension.displayName ||
    (meta as any)['displayName:zh-CN'] ||
    (meta as any)['displayName:en'] ||
    (meta as any)['displayName:en-US'];
  if (typeof candidate !== 'string') return undefined;
  const trimmed = candidate.trim();
  return trimmed && trimmed !== meta.code ? trimmed : undefined;
}

function shouldReplaceGeneratedLabel(label: ColumnConfig['label'], fieldCode: string): boolean {
  if (!label) return true;
  if (typeof label === 'string') {
    const trimmed = label.trim();
    return !trimmed || trimmed === fieldCode;
  }
  const values = Object.values(label).filter((value): value is string => typeof value === 'string');
  if (values.length === 0) return true;
  return values.every((value) => !value.trim() || value.trim() === fieldCode);
}

function readSubTableRefTargetConfig(column: EnrichedColumnConfig): Record<string, any> {
  return {
    ...((column.extension as any)?.refTarget || {}),
    ...((column as any).props?.refTarget || {}),
    ...(column.refTarget || {}),
  };
}

function collectSubTableReferenceDisplayConfigs(
  columns: EnrichedColumnConfig[],
): SubTableReferenceDisplayConfig[] {
  const configs: SubTableReferenceDisplayConfig[] = [];
  const seen = new Set<string>();

  for (const column of columns) {
    if (!column.field || column.isActionColumn) continue;
    const refTarget = readSubTableRefTargetConfig(column);
    const dataType = String(column.dataType || column.valueType || '').toLowerCase();
    const hasReferenceShape =
      dataType === 'reference' ||
      column.valueType === 'reference' ||
      Object.keys(refTarget).length > 0;
    if (!hasReferenceShape) continue;

    const modelCode = String(
      refTarget.modelCode ||
        refTarget.targetModel ||
        refTarget.targetEntity ||
        column.referenceModelCode ||
        '',
    ).trim();
    const displayField = String(
      refTarget.displayField || refTarget.labelField || refTarget.targetField || '',
    ).trim();
    const valueField = String(
      refTarget.valueField || refTarget.targetValueField || refTarget.idField || 'pid',
    ).trim();
    if (!modelCode || !displayField || !valueField) continue;

    const config: SubTableReferenceDisplayConfig = {
      field: column.field,
      modelCode,
      valueField,
      displayField,
      displayKey: `${column.field}_display`,
    };
    const key = buildSubTableReferenceDisplayCacheKey(config);
    if (seen.has(key)) continue;
    seen.add(key);
    configs.push(config);
  }

  return configs;
}

function buildSubTableReferenceDisplayCacheKey(config: SubTableReferenceDisplayConfig): string {
  return `${config.field}|${config.modelCode}|${config.valueField}|${config.displayField}`;
}

function isReferenceColumn(col: EnrichedColumnConfig): boolean {
  const dataType = String(col.dataType || col.valueType || '').toLowerCase();
  return (
    dataType === 'reference' ||
    col.valueType === 'reference' ||
    Object.keys(readSubTableRefTargetConfig(col)).length > 0 ||
    Boolean(col.referenceModelCode)
  );
}

function formatNumber(num: number): string {
  return num.toLocaleString('zh-CN', {
    minimumFractionDigits: 0,
    maximumFractionDigits: 2,
  });
}

function isNumericField(col: ColumnConfig): boolean {
  return (
    col.format === 'currency' ||
    col.format === 'number' ||
    col.field.endsWith('_qty') ||
    col.field.endsWith('_price') ||
    col.field.endsWith('_amount') ||
    col.field.endsWith('_count')
  );
}

/** Evaluate a single ValidationRule against a value */
function evaluateValidationRule(
  rule: ValidationRule,
  value: any,
  t: (key: string) => string,
): string | null {
  const getMessage = () => {
    if (typeof rule.message === 'string') {
      const resolved = t(rule.message);
      return resolved !== rule.message ? resolved : rule.message;
    }
    const msg = rule.message as any;
    return msg?.['zh-CN'] || msg?.['en-US'] || '';
  };

  switch (rule.type) {
    case 'required':
      if (value === undefined || value === null || value === '') return getMessage();
      break;
    case 'min':
      if (rule.min !== undefined && Number(value) < rule.min) return getMessage();
      break;
    case 'max':
      if (rule.max !== undefined && Number(value) > rule.max) return getMessage();
      break;
    case 'pattern':
      if (rule.pattern && typeof value === 'string' && !new RegExp(rule.pattern).test(value))
        return getMessage();
      break;
  }
  return null;
}

export default SubTableViewer;
