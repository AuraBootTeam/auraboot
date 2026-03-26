/**
 * RecordPageRenderer — GAP-086 ERP-style header+lines layout
 *
 * Renders a RECORD page type:
 *   - Top section: master record fields (2–3 column grid)
 *   - Middle section: line items sub-table (inline-editable)
 *   - Bottom section: totals summary + action buttons
 *
 * DSL usage:
 *   kind: "Record"
 *   sections:
 *     header: { fields: [...] }          # master record fields
 *     lines:  { subTable: { ... } }      # line items config
 *     summary: { fields: [...] }         # totals / computed fields
 *   toolbar: { buttons: [...] }
 */

import { useState, useEffect, useCallback, useMemo } from 'react';
import type { PageContentProps } from '~/meta/profiles/types';
import { usePageRuntime } from '~/meta/rendering/pages/hooks/usePageRuntime';
import { buildApiEndpoint, getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { fetchResult } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useActionHandler } from '~/meta/hooks/useActionHandler';
import { useToastContext } from '~/contexts/ToastContext';
import { LoadingSpinner } from '~/components/LoadingSpinner';
import { ErrorAlert } from '~/components/ErrorAlert';
import type { FieldConfig, ButtonConfig, BlockConfig } from '~/meta/schemas/types';

interface RecordData {
  [key: string]: any;
  id?: string;
  pid?: string;
}

interface LineItem {
  [key: string]: any;
  _isNew?: boolean;
  _localId?: string;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function generateLocalId() {
  return `new_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;
}

function extractSectionConfig(schema: any, sectionKey: string): any {
  // Support schema.sections.{key} or schema.areas.{key}.blocks[0]
  if (schema?.sections?.[sectionKey]) return schema.sections[sectionKey];
  if (schema?.areas?.[sectionKey]) {
    const area = schema.areas[sectionKey];
    const blocks: any[] = area.blocks ?? [];
    return blocks[0] ?? null;
  }
  return null;
}

// ─── Main component ───────────────────────────────────────────────────────────

export function RecordPageRenderer(props: PageContentProps) {
  const { schema, tableName, recordId, token } = props;

  const [recordData, setRecordData] = useState<RecordData>({});
  const [recordLoading, setRecordLoading] = useState(true);
  const [lineItems, setLineItems] = useState<LineItem[]>([]);
  const [linesLoading, setLinesLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [editingRecord, setEditingRecord] = useState<RecordData>({});
  const [dirty, setDirty] = useState(false);

  const { showSuccessToast, showErrorToast } = useToastContext();

  const showToast = useCallback(
    (message: string, type: 'success' | 'error' | 'warning' | 'info') => {
      if (type === 'success') showSuccessToast(message);
      else showErrorToast(message);
    },
    [showSuccessToast, showErrorToast],
  );

  const { runtime, dataSourceManager, t, locale, navigate } = usePageRuntime(schema, {
    token: token || undefined,
    additionalContext: { record: recordData },
  });

  const { handleAction, loading: actionLoading } = useActionHandler({
    runtime,
    navigate,
    tableName,
    context: { record: recordData },
    dataSourceManager,
    locale,
    t,
    token: token || undefined,
    showToast,
  });

  // ── Load master record ────────────────────────────────────────────────────

  useEffect(() => {
    if (!recordId || !tableName) {
      setRecordLoading(false);
      return;
    }
    const endpoint = `${buildApiEndpoint(tableName)}/${recordId}`;
    fetchResult<RecordData>(endpoint, { method: 'get', token: token || undefined })
      .then((result) => {
        if (ResultHelper.isSuccess(result) && result.data) {
          setRecordData(result.data);
          setEditingRecord(result.data);
        }
      })
      .catch(() => {})
      .finally(() => setRecordLoading(false));
  }, [recordId, tableName, token]);

  // ── Load line items ───────────────────────────────────────────────────────

  const linesConfig = useMemo(() => extractSectionConfig(schema, 'lines'), [schema]);
  const linesModel: string | undefined = linesConfig?.subTable?.model || linesConfig?.model;
  const linesFk: string | undefined = linesConfig?.subTable?.foreignKey || linesConfig?.foreignKey;

  useEffect(() => {
    if (!recordId || !linesModel || !linesFk) return;
    setLinesLoading(true);
    const endpoint = `${buildApiEndpoint(linesModel)}?${linesFk}=${recordId}&pageSize=500`;
    fetchResult<any>(endpoint, { method: 'get', token: token || undefined })
      .then((result) => {
        if (ResultHelper.isSuccess(result)) {
          const records = result.data?.records ?? result.data ?? [];
          setLineItems(records);
        }
      })
      .catch(() => {})
      .finally(() => setLinesLoading(false));
  }, [recordId, linesModel, linesFk, token]);

  // ── Schema sections ───────────────────────────────────────────────────────

  const headerConfig = useMemo(() => extractSectionConfig(schema, 'header'), [schema]);
  const summaryConfig = useMemo(() => extractSectionConfig(schema, 'summary'), [schema]);
  const allBlocks: BlockConfig[] = useMemo(() => {
    if (!schema?.areas) return [];
    return Object.values(schema.areas).flatMap((area: any) => area.blocks ?? []);
  }, [schema]);
  const toolbarBlock = useMemo(() => allBlocks.find((b) => b.blockType === 'toolbar'), [allBlocks]);

  const headerFields: FieldConfig[] = useMemo(
    () => headerConfig?.fields ?? headerConfig?.blocks?.[0]?.fields ?? [],
    [headerConfig],
  );

  const linesColumns: FieldConfig[] = useMemo(
    () => linesConfig?.subTable?.columns ?? linesConfig?.columns ?? [],
    [linesConfig],
  );

  const summaryFields: FieldConfig[] = useMemo(() => summaryConfig?.fields ?? [], [summaryConfig]);

  // ── Line item mutations ───────────────────────────────────────────────────

  const addLine = () => {
    const newLine: LineItem = { _isNew: true, _localId: generateLocalId() };
    if (linesFk && recordId) newLine[linesFk] = recordId;
    setLineItems((prev) => [...prev, newLine]);
    setDirty(true);
  };

  const updateLine = (idx: number, field: string, value: any) => {
    setLineItems((prev) => {
      const next = [...prev];
      next[idx] = { ...next[idx], [field]: value };
      return next;
    });
    setDirty(true);
  };

  const removeLine = (idx: number) => {
    setLineItems((prev) => prev.filter((_, i) => i !== idx));
    setDirty(true);
  };

  const updateHeader = (field: string, value: any) => {
    setEditingRecord((prev) => ({ ...prev, [field]: value }));
    setDirty(true);
  };

  // ── Save ──────────────────────────────────────────────────────────────────

  const handleSave = useCallback(async () => {
    if (!tableName) return;
    setSaving(true);
    try {
      const url = recordId
        ? `${buildApiEndpoint(tableName)}/${recordId}`
        : buildApiEndpoint(tableName);
      const method = recordId ? 'put' : 'post';
      const res = await fetch(url, {
        method,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(editingRecord),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const json = await res.json();
      const newRecord = json.data ?? editingRecord;
      setRecordData(newRecord);
      setEditingRecord(newRecord);
      setDirty(false);
      showSuccessToast(t('common.saveSuccess') || 'Saved successfully');
    } catch (err: any) {
      showErrorToast(err?.message ?? 'Save failed');
    } finally {
      setSaving(false);
    }
  }, [tableName, recordId, editingRecord, t, showSuccessToast, showErrorToast]);

  // ── Render ────────────────────────────────────────────────────────────────

  if (recordLoading) return <LoadingSpinner />;

  const title =
    typeof schema?.title === 'string'
      ? schema.title
      : getLocalizedText(schema?.title, locale, t) || tableName;

  return (
    <div className="mx-auto w-full space-y-4 px-6 py-6" data-testid="record-page">
      {/* Page Header */}
      <div className="rounded-xl border border-gray-100 bg-white shadow-sm dark:border-gray-700 dark:bg-gray-800">
        <div className="flex items-center justify-between border-b border-gray-100 px-6 py-4 dark:border-gray-700">
          <h1 className="text-lg font-semibold text-gray-900 dark:text-white">{title}</h1>

          {/* Toolbar buttons */}
          <div className="flex items-center gap-2">
            {(toolbarBlock?.buttons ?? []).map((btn: ButtonConfig) => (
              <button
                key={btn.code}
                onClick={() => handleAction(btn, recordData)}
                disabled={actionLoading}
                className="rounded-lg bg-blue-600 px-3 py-1.5 text-sm font-medium text-white transition-colors hover:bg-blue-700 disabled:opacity-50"
              >
                {getLocalizedText(btn.label, locale, t) || btn.code}
              </button>
            ))}
            {dirty && (
              <button
                onClick={handleSave}
                disabled={saving}
                data-testid="record-save-btn"
                className="rounded-lg bg-green-600 px-3 py-1.5 text-sm font-medium text-white transition-colors hover:bg-green-700 disabled:opacity-50"
              >
                {saving ? t('common.saving') || 'Saving...' : t('action.save') || 'Save'}
              </button>
            )}
          </div>
        </div>

        {/* Master record fields (header section) */}
        {headerFields.length > 0 && (
          <div className="px-6 py-4" data-testid="record-header-fields">
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {headerFields.map((field: FieldConfig) => (
                <div key={field.field}>
                  <label className="mb-1 block text-xs font-medium text-gray-500 dark:text-gray-400">
                    {getLocalizedText(field.label, locale, t) || field.field}
                    {field.required && <span className="ml-0.5 text-red-500">*</span>}
                  </label>
                  <RecordFieldInput
                    field={field}
                    value={editingRecord[field.field]}
                    onChange={(val: any) => updateHeader(field.field, val)}
                    locale={locale}
                  />
                </div>
              ))}
            </div>
          </div>
        )}
      </div>

      {/* Line items section */}
      {linesColumns.length > 0 && (
        <div className="rounded-xl border border-gray-100 bg-white shadow-sm dark:border-gray-700 dark:bg-gray-800">
          <div className="flex items-center justify-between border-b border-gray-100 px-6 py-3 dark:border-gray-700">
            <h2 className="text-sm font-semibold text-gray-700 dark:text-gray-300">
              {t('record.lineItems') || 'Line Items'}
            </h2>
            <button
              onClick={addLine}
              className="flex items-center gap-1.5 rounded-lg border border-blue-200 px-3 py-1 text-xs font-medium text-blue-600 transition-colors hover:bg-blue-50 dark:border-blue-800 dark:text-blue-400 dark:hover:bg-blue-900/20"
              data-testid="record-add-line-btn"
            >
              + {t('action.add') || 'Add Line'}
            </button>
          </div>

          {linesLoading ? (
            <div className="p-6 text-center">
              <LoadingSpinner />
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm" data-testid="record-lines-table">
                <thead>
                  <tr className="border-b border-gray-100 bg-gray-50 dark:border-gray-700 dark:bg-gray-900/30">
                    <th className="w-8 px-3 py-2 text-left text-xs font-medium text-gray-500 dark:text-gray-400">
                      #
                    </th>
                    {linesColumns.map((col: FieldConfig) => (
                      <th
                        key={col.field}
                        className="px-3 py-2 text-left text-xs font-medium text-gray-500 dark:text-gray-400"
                      >
                        {getLocalizedText(col.label, locale, t) || col.field}
                      </th>
                    ))}
                    <th className="w-10 px-3 py-2" />
                  </tr>
                </thead>
                <tbody>
                  {lineItems.length === 0 ? (
                    <tr>
                      <td
                        colSpan={linesColumns.length + 2}
                        className="px-3 py-6 text-center text-xs text-gray-400"
                      >
                        {t('common.noData') || 'No line items. Click "Add Line" to start.'}
                      </td>
                    </tr>
                  ) : (
                    lineItems.map((line, idx) => (
                      <tr
                        key={line.pid ?? line._localId ?? idx}
                        className="border-b border-gray-50 hover:bg-gray-50/50 dark:border-gray-700/50 dark:hover:bg-gray-700/20"
                      >
                        <td className="px-3 py-1.5 text-xs text-gray-400">{idx + 1}</td>
                        {linesColumns.map((col: FieldConfig) => (
                          <td key={col.field} className="px-2 py-1">
                            <RecordFieldInput
                              field={col}
                              value={line[col.field]}
                              onChange={(val: any) => updateLine(idx, col.field, val)}
                              locale={locale}
                              compact
                            />
                          </td>
                        ))}
                        <td className="px-2 py-1 text-center">
                          <button
                            onClick={() => removeLine(idx)}
                            className="text-xs text-red-400 hover:text-red-600 dark:hover:text-red-300"
                            title="Remove line"
                          >
                            ×
                          </button>
                        </td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
          )}

          {/* Summary / totals row */}
          {summaryFields.length > 0 && (
            <div
              className="flex flex-wrap items-center gap-6 border-t border-gray-100 bg-gray-50/50 px-6 py-3 dark:border-gray-700 dark:bg-gray-900/20"
              data-testid="record-summary-row"
            >
              {summaryFields.map((field: FieldConfig) => {
                const val = recordData[field.field] ?? editingRecord[field.field] ?? '';
                return (
                  <div key={field.field} className="flex items-center gap-2">
                    <span className="text-xs text-gray-500 dark:text-gray-400">
                      {getLocalizedText(field.label, locale, t) || field.field}:
                    </span>
                    <span className="text-sm font-semibold text-gray-900 dark:text-white">
                      {val}
                    </span>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

// ─── Lightweight field input for record fields and line items ─────────────────

function RecordFieldInput({
  field,
  value,
  onChange,
  locale,
  compact,
}: {
  field: FieldConfig;
  value: any;
  onChange: (v: any) => void;
  locale?: string;
  compact?: boolean;
}) {
  const base = compact
    ? 'w-full px-2 py-1 text-xs border border-gray-200 dark:border-gray-600 rounded bg-white dark:bg-gray-700 text-gray-900 dark:text-white focus:ring-1 focus:ring-blue-500 focus:border-blue-500 outline-none'
    : 'w-full px-3 py-2 text-sm border border-gray-300 dark:border-gray-600 rounded-lg bg-white dark:bg-gray-700 text-gray-900 dark:text-white focus:ring-1 focus:ring-blue-500 focus:border-blue-500 outline-none';

  const component = (field.component ?? 'input').toLowerCase();
  const readOnly = field.readOnly === true;

  if (readOnly) {
    return <div className={`${base} bg-gray-50 text-gray-500 dark:bg-gray-800`}>{value ?? ''}</div>;
  }

  if (component === 'textarea') {
    return (
      <textarea
        value={value ?? ''}
        onChange={(e) => onChange(e.target.value)}
        rows={compact ? 1 : 3}
        className={base}
      />
    );
  }

  if (component === 'select' || component === 'smartselect') {
    const options: Array<{ value: string; label: any }> = field.options ?? [];
    return (
      <select value={value ?? ''} onChange={(e) => onChange(e.target.value)} className={base}>
        <option value="">—</option>
        {options.map((opt) => (
          <option key={opt.value} value={opt.value}>
            {typeof opt.label === 'string'
              ? opt.label
              : (opt.label?.[locale ?? 'zh-CN'] ?? opt.label?.['en-US'] ?? opt.value)}
          </option>
        ))}
      </select>
    );
  }

  if (component === 'datepicker' || component === 'date') {
    return (
      <input
        type="date"
        value={value ? String(value).slice(0, 10) : ''}
        onChange={(e) => onChange(e.target.value)}
        className={base}
      />
    );
  }

  if (component === 'number' || (component === 'smartinput' && field.type === 'number')) {
    return (
      <input
        type="number"
        value={value ?? ''}
        onChange={(e) => onChange(e.target.value === '' ? null : Number(e.target.value))}
        className={base}
      />
    );
  }

  return (
    <input
      type="text"
      value={value ?? ''}
      onChange={(e) => onChange(e.target.value)}
      className={base}
    />
  );
}

export default RecordPageRenderer;
