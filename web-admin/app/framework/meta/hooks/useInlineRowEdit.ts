/**
 * useInlineRowEdit — Hook for managing inline row editing state,
 * row-level validation, and command-based persistence.
 *
 * Supports:
 * - Single-row edit mode with Enter/Esc/Tab keyboard navigation
 * - Per-field validation (required, min, max, pattern)
 * - Cross-row validation rules (e.g., SUM(amount) <= budget)
 * - Optimistic UI with rollback on failure
 */

import { useState, useCallback, useRef } from 'react';
import type {
  ColumnConfig,
  SubTableConfig,
  ValidationRule,
  LocalizedText,
} from '~/framework/meta/schemas/types';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

export interface RowValidationError {
  field: string;
  message: string;
}

export interface InlineRowEditState {
  /** Row ID (pid) currently being edited, or null */
  editingRowId: string | null;
  /** Edited field values for the current row */
  editingData: Record<string, any>;
  /** Per-field validation errors for the current row */
  fieldErrors: Record<string, string>;
  /** Cross-row validation errors */
  crossRowErrors: string[];
  /** Whether a save is in progress */
  isSaving: boolean;
}

export interface UseInlineRowEditOptions {
  config: SubTableConfig;
  rows: Record<string, any>[];
  parentRecordData?: Record<string, any>;
  token?: string;
  onSaveSuccess?: () => void;
  t?: (key: string) => string;
}

export interface UseInlineRowEditReturn extends InlineRowEditState {
  /** Start editing a row */
  startEdit: (rowId: string, rowData: Record<string, any>) => void;
  /** Cancel editing */
  cancelEdit: () => void;
  /** Update a field value in the editing row */
  updateField: (field: string, value: any) => void;
  /** Validate the current editing row — returns true if valid */
  validateRow: () => boolean;
  /** Validate all rows for cross-row rules — returns error messages */
  validateCrossRow: (allRows: Record<string, any>[]) => string[];
  /** Save the current editing row via update command */
  saveRow: () => Promise<boolean>;
  /** Check if a column is editable */
  isColumnEditable: (col: ColumnConfig) => boolean;
}

export function useInlineRowEdit({
  config,
  rows,
  parentRecordData,
  token,
  onSaveSuccess,
  t = (k: string) => k,
}: UseInlineRowEditOptions): UseInlineRowEditReturn {
  const [editingRowId, setEditingRowId] = useState<string | null>(null);
  const [editingData, setEditingData] = useState<Record<string, any>>({});
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [crossRowErrors, setCrossRowErrors] = useState<string[]>([]);
  const [isSaving, setIsSaving] = useState(false);
  const originalDataRef = useRef<Record<string, any>>({});

  const editableColumnSet = config.editableColumns ? new Set(config.editableColumns) : null;

  const isColumnEditable = useCallback(
    (col: ColumnConfig): boolean => {
      if (col.readOnly || col.editable === false) return false;
      if (editableColumnSet) return editableColumnSet.has(col.field);
      return true;
    },
    [editableColumnSet],
  );

  const startEdit = useCallback(
    (rowId: string, rowData: Record<string, any>) => {
      setEditingRowId(rowId);
      const data: Record<string, any> = {};
      for (const col of config.columns) {
        if (isColumnEditable(col)) {
          data[col.field] = rowData[col.field] ?? '';
        }
      }
      setEditingData(data);
      originalDataRef.current = { ...data };
      setFieldErrors({});
      setCrossRowErrors([]);
    },
    [config.columns, isColumnEditable],
  );

  const cancelEdit = useCallback(() => {
    setEditingRowId(null);
    setEditingData({});
    setFieldErrors({});
    setCrossRowErrors([]);
  }, []);

  const updateField = useCallback(
    (field: string, value: any) => {
      setEditingData((prev) => ({ ...prev, [field]: value }));
      // Clear field error on change
      if (fieldErrors[field]) {
        setFieldErrors((prev) => {
          const next = { ...prev };
          delete next[field];
          return next;
        });
      }
    },
    [fieldErrors],
  );

  const validateField = useCallback(
    (col: ColumnConfig, value: any): string | null => {
      // Required check
      if (col.required && (value === undefined || value === null || value === '')) {
        const reqMsg = t('common.validation.required');
        return reqMsg !== 'common.validation.required' ? reqMsg : 'This field is required';
      }

      // Numeric min/max
      if (col.min !== undefined && typeof value === 'number' && value < col.min) {
        const minMsg = t('validation.min');
        return minMsg !== 'validation.min' ? `${minMsg}: ${col.min}` : `Minimum value: ${col.min}`;
      }
      if (col.max !== undefined && typeof value === 'number' && value > col.max) {
        const maxMsg = t('validation.max');
        return maxMsg !== 'validation.max' ? `${maxMsg}: ${col.max}` : `Maximum value: ${col.max}`;
      }

      // Pattern
      if (col.pattern && typeof value === 'string' && value) {
        const regex = new RegExp(col.pattern);
        if (!regex.test(value)) {
          const patMsg = t('validation.pattern');
          return patMsg !== 'validation.pattern' ? patMsg : 'Invalid format';
        }
      }

      // Custom validation rules
      if (col.validation) {
        for (const rule of col.validation) {
          const msg = evaluateValidationRule(rule, value, t);
          if (msg) return msg;
        }
      }

      return null;
    },
    [t],
  );

  const validateRow = useCallback((): boolean => {
    const errors: Record<string, string> = {};
    for (const col of config.columns) {
      if (!isColumnEditable(col)) continue;
      const value = editingData[col.field];
      const error = validateField(col, value);
      if (error) errors[col.field] = error;
    }
    setFieldErrors(errors);
    return Object.keys(errors).length === 0;
  }, [config.columns, editingData, isColumnEditable, validateField]);

  const validateCrossRow = useCallback(
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

        // Resolve threshold value
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
              : getLocalizedMessage(rule.message);
          errors.push(msg);
        }
      }

      setCrossRowErrors(errors);
      return errors;
    },
    [config.crossRowRules, parentRecordData, t],
  );

  const saveRow = useCallback(async (): Promise<boolean> => {
    if (!editingRowId || !config.commands?.update) return false;

    // Validate first
    if (!validateRow()) return false;

    // Check cross-row rules with merged data
    if (config.crossRowRules && config.crossRowRules.length > 0) {
      const mergedRows = rows.map((r) => {
        if ((r.pid || r.id) === editingRowId) {
          return { ...r, ...editingData };
        }
        return r;
      });
      const crossErrors = validateCrossRow(mergedRows);
      if (crossErrors.length > 0) return false;
    }

    // Check if anything changed
    const changed: Record<string, any> = {};
    for (const [key, val] of Object.entries(editingData)) {
      if (val !== originalDataRef.current[key]) {
        changed[key] = val;
      }
    }
    if (Object.keys(changed).length === 0) {
      cancelEdit();
      return true;
    }

    setIsSaving(true);
    try {
      const result = await fetchResult(`/api/meta/commands/execute/${config.commands.update}`, {
        method: 'post',
        params: {
          targetRecordId: editingRowId,
          payload: changed,
          operationType: 'update',
        },
        token,
      });

      if (!ResultHelper.isSuccess(result)) {
        throw new Error(result.desc || result.message || 'Failed to update');
      }

      setEditingRowId(null);
      setEditingData({});
      setFieldErrors({});
      onSaveSuccess?.();
      return true;
    } catch (err) {
      setFieldErrors((prev) => ({
        ...prev,
        _form: err instanceof Error ? err.message : 'Save failed',
      }));
      return false;
    } finally {
      setIsSaving(false);
    }
  }, [
    editingRowId,
    editingData,
    config.commands,
    config.crossRowRules,
    rows,
    token,
    validateRow,
    validateCrossRow,
    cancelEdit,
    onSaveSuccess,
  ]);

  return {
    editingRowId,
    editingData,
    fieldErrors,
    crossRowErrors,
    isSaving,
    startEdit,
    cancelEdit,
    updateField,
    validateRow,
    validateCrossRow,
    saveRow,
    isColumnEditable,
  };
}

// Helper: evaluate a single ValidationRule
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
    return getLocalizedMessage(rule.message as LocalizedText);
  };

  switch (rule.type) {
    case 'required':
      if (value === undefined || value === null || value === '') {
        return getMessage();
      }
      break;
    case 'min':
      if (rule.min !== undefined && Number(value) < rule.min) {
        return getMessage();
      }
      break;
    case 'max':
      if (rule.max !== undefined && Number(value) > rule.max) {
        return getMessage();
      }
      break;
    case 'pattern':
      if (rule.pattern && typeof value === 'string') {
        if (!new RegExp(rule.pattern).test(value)) {
          return getMessage();
        }
      }
      break;
  }
  return null;
}

function getLocalizedMessage(msg: LocalizedText): string {
  return msg['zh-CN'] || msg['en-US'] || Object.values(msg).find(Boolean) || '';
}
