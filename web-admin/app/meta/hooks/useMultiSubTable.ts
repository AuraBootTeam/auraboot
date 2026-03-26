/**
 * useMultiSubTable Hook
 *
 * Manages multiple sub-tables in a master-detail form.
 * Handles joint submission (master + all sub-tables in one request),
 * dirty tracking, and validation across all tables.
 *
 * @since 3.7.0
 *
 * @example
 * ```tsx
 * const { tables, addRow, removeRow, updateCell, submit, isDirty } = useMultiSubTable({
 *   masterModelCode: 'order',
 *   tables: [
 *     { key: 'items', modelCode: 'order_item', columns: [...] },
 *     { key: 'payments', modelCode: 'order_payment', columns: [...] },
 *   ],
 *   onSubmit: async (data) => await api.saveOrder(data),
 * });
 * ```
 */

import { useState, useCallback, useRef } from 'react';
import type { SubTableColumn } from '~/meta/components/types';

export interface SubTableDef {
  /** Unique key for this sub-table */
  key: string;
  /** Display label */
  label?: string;
  /** Associated model code */
  modelCode?: string;
  /** Column definitions */
  columns: SubTableColumn[];
  /** Maximum rows allowed */
  maxRows?: number;
  /** Minimum rows required */
  minRows?: number;
}

export interface MultiSubTableOptions {
  /** Master record model code */
  masterModelCode?: string;
  /** Sub-table definitions */
  tables: SubTableDef[];
  /** Initial data for each table (key → rows) */
  initialData?: Record<string, Record<string, any>[]>;
  /** Submit callback - receives master + all sub-table data */
  onSubmit?: (data: MultiSubTableSubmitData) => Promise<void>;
  /** Validation callback */
  onValidate?: (data: MultiSubTableSubmitData) => string[] | null;
}

export interface MultiSubTableSubmitData {
  masterData: Record<string, any>;
  tables: Record<string, Record<string, any>[]>;
}

export interface MultiSubTableReturn {
  /** Current data for each table */
  tables: Record<string, Record<string, any>[]>;
  /** Add a row to a specific table */
  addRow: (tableKey: string) => void;
  /** Remove a row from a specific table */
  removeRow: (tableKey: string, rowIndex: number) => void;
  /** Update a cell in a specific table */
  updateCell: (tableKey: string, rowIndex: number, field: string, value: any) => void;
  /** Replace all rows for a table */
  setTableData: (tableKey: string, rows: Record<string, any>[]) => void;
  /** Submit all tables with master data */
  submit: (masterData: Record<string, any>) => Promise<{ success: boolean; errors: string[] }>;
  /** Whether any table has unsaved changes */
  isDirty: boolean;
  /** Reset all tables to initial state */
  reset: () => void;
  /** Validation errors (if any) */
  validationErrors: string[];
  /** Whether submission is in progress */
  submitting: boolean;
  /** Get row count for a table */
  getRowCount: (tableKey: string) => number;
  /** Move a row within a table */
  moveRow: (tableKey: string, fromIndex: number, toIndex: number) => void;
}

export function useMultiSubTable(options: MultiSubTableOptions): MultiSubTableReturn {
  const { tables: tableDefs, initialData = {}, onSubmit, onValidate } = options;

  const [tableData, setTableData] = useState<Record<string, Record<string, any>[]>>(() => {
    const initial: Record<string, Record<string, any>[]> = {};
    for (const def of tableDefs) {
      initial[def.key] = initialData[def.key] ?? [];
    }
    return initial;
  });

  const [isDirty, setIsDirty] = useState(false);
  const [validationErrors, setValidationErrors] = useState<string[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const initialDataRef = useRef(initialData);

  const addRow = useCallback(
    (tableKey: string) => {
      const def = tableDefs.find((t) => t.key === tableKey);
      if (!def) return;

      const currentRows = tableData[tableKey] ?? [];
      if (def.maxRows && currentRows.length >= def.maxRows) return;

      const newRow: Record<string, any> = {
        _key: `${tableKey}_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`,
      };
      for (const col of def.columns) {
        newRow[col.field] = getDefaultValue(col.type);
      }

      setTableData((prev) => ({
        ...prev,
        [tableKey]: [...(prev[tableKey] ?? []), newRow],
      }));
      setIsDirty(true);
    },
    [tableDefs, tableData],
  );

  const removeRow = useCallback(
    (tableKey: string, rowIndex: number) => {
      const def = tableDefs.find((t) => t.key === tableKey);
      if (!def) return;

      const currentRows = tableData[tableKey] ?? [];
      if (def.minRows && currentRows.length <= def.minRows) return;

      setTableData((prev) => ({
        ...prev,
        [tableKey]: (prev[tableKey] ?? []).filter((_, i) => i !== rowIndex),
      }));
      setIsDirty(true);
    },
    [tableDefs, tableData],
  );

  const updateCell = useCallback(
    (tableKey: string, rowIndex: number, field: string, value: any) => {
      setTableData((prev) => ({
        ...prev,
        [tableKey]: (prev[tableKey] ?? []).map((row, i) =>
          i === rowIndex ? { ...row, [field]: value } : row,
        ),
      }));
      setIsDirty(true);
    },
    [],
  );

  const setTableDataForKey = useCallback((tableKey: string, rows: Record<string, any>[]) => {
    setTableData((prev) => ({ ...prev, [tableKey]: rows }));
    setIsDirty(true);
  }, []);

  const moveRow = useCallback((tableKey: string, fromIndex: number, toIndex: number) => {
    setTableData((prev) => {
      const rows = [...(prev[tableKey] ?? [])];
      if (toIndex < 0 || toIndex >= rows.length) return prev;
      const [moved] = rows.splice(fromIndex, 1);
      rows.splice(toIndex, 0, moved);
      return { ...prev, [tableKey]: rows };
    });
    setIsDirty(true);
  }, []);

  const submit = useCallback(
    async (masterData: Record<string, any>) => {
      // Clean internal keys from rows
      const cleanedTables: Record<string, Record<string, any>[]> = {};
      for (const [key, rows] of Object.entries(tableData)) {
        cleanedTables[key] = rows.map((row) => {
          const { _key, ...rest } = row;
          return rest;
        });
      }

      const submitData: MultiSubTableSubmitData = { masterData, tables: cleanedTables };

      // Validate
      if (onValidate) {
        const errors = onValidate(submitData);
        if (errors && errors.length > 0) {
          setValidationErrors(errors);
          return { success: false, errors };
        }
      }

      // Validate required fields
      const requiredErrors = validateRequired(tableDefs, cleanedTables);
      if (requiredErrors.length > 0) {
        setValidationErrors(requiredErrors);
        return { success: false, errors: requiredErrors };
      }

      setValidationErrors([]);

      if (!onSubmit) {
        return { success: true, errors: [] };
      }

      setSubmitting(true);
      try {
        await onSubmit(submitData);
        setIsDirty(false);
        initialDataRef.current = tableData;
        return { success: true, errors: [] };
      } catch (err) {
        const message = err instanceof Error ? err.message : '提交失败';
        return { success: false, errors: [message] };
      } finally {
        setSubmitting(false);
      }
    },
    [tableData, tableDefs, onSubmit, onValidate],
  );

  const reset = useCallback(() => {
    const initial: Record<string, Record<string, any>[]> = {};
    for (const def of tableDefs) {
      initial[def.key] = initialDataRef.current[def.key] ?? [];
    }
    setTableData(initial);
    setIsDirty(false);
    setValidationErrors([]);
  }, [tableDefs]);

  const getRowCount = useCallback(
    (tableKey: string) => {
      return tableData[tableKey]?.length ?? 0;
    },
    [tableData],
  );

  return {
    tables: tableData,
    addRow,
    removeRow,
    updateCell,
    setTableData: setTableDataForKey,
    submit,
    isDirty,
    reset,
    validationErrors,
    submitting,
    getRowCount,
    moveRow,
  };
}

function getDefaultValue(type: string): any {
  switch (type) {
    case 'number':
      return null;
    case 'boolean':
      return false;
    default:
      return '';
  }
}

function validateRequired(
  tableDefs: SubTableDef[],
  data: Record<string, Record<string, any>[]>,
): string[] {
  const errors: string[] = [];

  for (const def of tableDefs) {
    const rows = data[def.key] ?? [];
    const requiredCols = def.columns.filter((c) => c.required);

    for (let i = 0; i < rows.length; i++) {
      for (const col of requiredCols) {
        const val = rows[i][col.field];
        if (val === null || val === undefined || val === '') {
          errors.push(`${def.label ?? def.key} 第 ${i + 1} 行: ${col.label} 不能为空`);
        }
      }
    }

    if (def.minRows && rows.length < def.minRows) {
      errors.push(`${def.label ?? def.key} 至少需要 ${def.minRows} 行`);
    }
  }

  return errors;
}
