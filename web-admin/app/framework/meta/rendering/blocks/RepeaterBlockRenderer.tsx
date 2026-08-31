/**
 * RepeaterBlockRenderer — renders a repeatable group of fields (rows).
 *
 * Each row renders the child `field` blocks via the shared FieldRenderer.
 * Supports add / remove row; row data is stored as an array of records on the
 * parent form field (`block.field`).
 */

import React, { useCallback, useState } from 'react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { FieldRenderer } from '~/framework/meta/rendering/FieldRenderer';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';

export interface RepeaterBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

type RepeaterRow = Record<string, unknown>;

export const RepeaterBlockRenderer: React.FC<RepeaterBlockRendererProps> = ({ block, runtime }) => {
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);
  const stateManager = runtime.getStateManager();
  const scopeId = runtime.getScopeId();

  const repeaterKey = block.field || block.id;
  const childFields = (block as any).fields as BlockConfig[] | undefined;
  const fieldDefs = Array.isArray(childFields) ? childFields : [];

  const initialRows: RepeaterRow[] = (() => {
    const existing = stateManager.getFieldValue(scopeId, repeaterKey);
    if (Array.isArray(existing)) return existing as RepeaterRow[];
    const rows = (block as any).props?.rows;
    return Array.isArray(rows) ? (rows as RepeaterRow[]) : [];
  })();

  const [rows, setRows] = useState<RepeaterRow[]>(initialRows);

  const commitRows = useCallback(
    (next: RepeaterRow[]) => {
      setRows(next);
      stateManager.updateField(scopeId, repeaterKey, next);
    },
    [repeaterKey, scopeId, stateManager],
  );

  const addRow = useCallback(() => {
    commitRows([...rows, {}]);
  }, [commitRows, rows]);

  const removeRow = useCallback(
    (index: number) => {
      commitRows(rows.filter((_, i) => i !== index));
    },
    [commitRows, rows],
  );

  const updateCell = useCallback(
    (rowIndex: number, fieldKey: string, value: unknown) => {
      commitRows(
        rows.map((row, i) => (i === rowIndex ? { ...row, [fieldKey]: value } : row)),
      );
    },
    [commitRows, rows],
  );

  const title = block.title ? getLocalizedText(block.title, locale, t) : null;

  return (
    <section
      className="rounded-lg border border-slate-200 bg-white p-4"
      data-testid={`runtime-repeater-${block.id}`}
    >
      <div className="flex items-center justify-between gap-3 border-b border-slate-100 pb-3">
        <div className="min-w-0">
          {title ? <h3 className="text-sm font-medium text-slate-800">{title}</h3> : null}
          <p className="text-xs text-slate-400">
            {rows.length} {rows.length === 1 ? 'row' : 'rows'}
          </p>
        </div>
        <button
          type="button"
          className="rounded border border-blue-200 bg-blue-50 px-2 py-1 text-xs font-medium text-blue-600 hover:bg-blue-100"
          onClick={addRow}
        >
          + Add row
        </button>
      </div>
      <div className="space-y-3 pt-3">
        {rows.map((row, rowIndex) => (
          <div
            key={rowIndex}
            className="rounded-md border border-slate-100 bg-slate-50 p-3"
            data-testid={`repeater-row-${block.id}-${rowIndex}`}
          >
            <div className="mb-2 flex items-center justify-between">
              <span className="text-xs font-medium text-slate-500">
                #{rowIndex + 1}
              </span>
              <button
                type="button"
                className="text-xs text-red-400 hover:text-red-600"
                onClick={() => removeRow(rowIndex)}
                aria-label={`Remove row ${rowIndex + 1}`}
              >
                ✕
              </button>
            </div>
            <div className="grid grid-cols-12 gap-2">
              {fieldDefs.map((fieldDef) => {
                const fieldKey =
                  (fieldDef as any).field || (fieldDef as any).id || `field_${fieldDefs.indexOf(fieldDef)}`;
                return (
                  <div key={fieldKey} className="col-span-12 sm:col-span-6">
                    <FieldRenderer
                      field={{
                        ...(fieldDef as any),
                        field: `${repeaterKey}.${rowIndex}.${fieldKey}`,
                      }}
                      runtime={runtime}
                    />
                  </div>
                );
              })}
            </div>
          </div>
        ))}
        {rows.length === 0 ? (
          <p className="text-center text-xs text-slate-400" data-testid={`repeater-empty-${block.id}`}>
            No rows yet
          </p>
        ) : null}
      </div>
    </section>
  );
};

export default RepeaterBlockRenderer;
