/**
 * KeyValueEditor
 *
 * A small, reusable key/value pair editor backed by a `Record<string, unknown>`.
 *
 * Used by DataSourceConfig for two runtime-consumed maps that previously had no UI:
 *  - API data source query params (`ChartDataSource.params`, sent as GET query params)
 *  - Named query parameters (`ChartDataSource.parameters`, passed to the backend query)
 *
 * The public value is an unordered `Record`, but users edit an ordered list of rows
 * (so an in-progress row with an empty key doesn't vanish mid-typing). We keep the
 * ordered rows in local state and project them back to a Record on every edit. When
 * the parent resets the value externally (e.g. switching the query drops parameters to
 * `{}`), we re-seed the rows from the incoming Record.
 */

import React, { useEffect, useRef, useState } from 'react';

export interface KeyValueEditorProps {
  /** Current map value. */
  value: Record<string, unknown>;
  /** Called with the next map whenever a row is added, edited, or removed. */
  onChange: (value: Record<string, unknown>) => void;
  /** Optional field label. */
  label?: string;
  /**
   * Prefix for the `data-testid` attributes so multiple editors on one panel stay
   * distinguishable (e.g. `dashboard-datasource-api-params`). Emits:
   *  `<prefix>` (container), `<prefix>-row`, `<prefix>-key`, `<prefix>-value`,
   *  `<prefix>-remove`, `<prefix>-add`.
   */
  testIdPrefix: string;
  keyPlaceholder?: string;
  valuePlaceholder?: string;
  addLabel?: string;
  /** Empty-state hint shown when there are no rows. */
  emptyHint?: string;
}

interface Row {
  key: string;
  value: string;
}

function recordToRows(record: Record<string, unknown>): Row[] {
  return Object.entries(record ?? {}).map(([key, value]) => ({
    key,
    value: value == null ? '' : String(value),
  }));
}

function rowsToRecord(rows: Row[]): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const row of rows) {
    const key = row.key.trim();
    if (key) out[key] = row.value;
  }
  return out;
}

export const KeyValueEditor: React.FC<KeyValueEditorProps> = ({
  value,
  onChange,
  label,
  testIdPrefix,
  keyPlaceholder = '参数名',
  valuePlaceholder = '参数值',
  addLabel = '+ 添加参数',
  emptyHint,
}) => {
  const [rows, setRows] = useState<Row[]>(() => recordToRows(value));

  // Re-seed rows when the external record no longer matches what our rows project.
  // Guards against clobbering in-progress typing: our own onChange updates `value`
  // to exactly `rowsToRecord(rows)`, so the comparison is stable and only an
  // outside reset (query switch, type switch) re-seeds.
  const rowsRef = useRef(rows);
  rowsRef.current = rows;
  useEffect(() => {
    const projected = rowsToRecord(rowsRef.current);
    if (JSON.stringify(projected) !== JSON.stringify(value ?? {})) {
      setRows(recordToRows(value));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [JSON.stringify(value ?? {})]);

  const commit = (next: Row[]) => {
    setRows(next);
    onChange(rowsToRecord(next));
  };

  const add = () => commit([...rows, { key: '', value: '' }]);
  const update = (index: number, patch: Partial<Row>) =>
    commit(rows.map((r, i) => (i === index ? { ...r, ...patch } : r)));
  const remove = (index: number) => commit(rows.filter((_, i) => i !== index));

  return (
    <div data-testid={testIdPrefix}>
      {label && <label className="mb-1 block text-sm font-medium text-gray-700">{label}</label>}

      {rows.length === 0 && emptyHint && <p className="mb-2 text-sm text-gray-400">{emptyHint}</p>}

      {rows.map((row, index) => (
        <div key={index} className="mb-2 flex items-center gap-2" data-testid={`${testIdPrefix}-row`}>
          <input
            type="text"
            data-testid={`${testIdPrefix}-key`}
            value={row.key}
            onChange={(e) => update(index, { key: e.target.value })}
            placeholder={keyPlaceholder}
            className="flex-1 rounded-md border border-gray-300 px-2 py-1.5 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          />
          <input
            type="text"
            data-testid={`${testIdPrefix}-value`}
            value={row.value}
            onChange={(e) => update(index, { value: e.target.value })}
            placeholder={valuePlaceholder}
            className="flex-1 rounded-md border border-gray-300 px-2 py-1.5 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          />
          <button
            type="button"
            data-testid={`${testIdPrefix}-remove`}
            onClick={() => remove(index)}
            className="px-2 py-1 text-sm text-gray-400 hover:text-red-500"
            aria-label="移除参数"
          >
            ✕
          </button>
        </div>
      ))}

      <button
        type="button"
        data-testid={`${testIdPrefix}-add`}
        onClick={add}
        className="text-sm text-blue-600 hover:text-blue-700"
      >
        {addLabel}
      </button>
    </div>
  );
};

export default KeyValueEditor;
