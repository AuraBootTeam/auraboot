import React, { useMemo } from 'react';
import type { ListViewModel } from '../list-config/mapper';

export interface StructuralPreviewProps {
  vm: ListViewModel;
  fields?: Array<{ code: string; dataType?: string; displayName?: string }>;
  overrideRows?: Array<Record<string, unknown>>;
}

/**
 * Structural preview of a list page: filter chips + toolbar chips + mock
 * table driven by the current ViewModel. Mock data is generated from field
 * metadata; pass `overrideRows` to render real sample data fetched by
 * `SampleDataLoader`.
 *
 * Part of P3-T6 (virtual model backend plan).
 */
export const StructuralPreview: React.FC<StructuralPreviewProps> = ({
  vm,
  fields,
  overrideRows,
}) => {
  const rows = useMemo(() => {
    if (overrideRows && overrideRows.length > 0) return overrideRows;
    return generateMockRows(
      vm.columns.map((c) => c.field),
      fields ?? [],
      3,
    );
  }, [vm.columns, fields, overrideRows]);

  return (
    <div className="border-l bg-gray-50 p-4" data-testid="structural-preview">
      <h3 className="mb-3 text-xs font-semibold uppercase text-gray-500">
        Structural Preview
      </h3>

      {vm.filters.length > 0 && (
        <div className="mb-3 rounded bg-white p-2 text-xs">
          <div className="mb-1 text-gray-500">Filters</div>
          <div className="flex flex-wrap gap-1">
            {vm.filters.map((f) => (
              <span
                key={f.field}
                className="rounded bg-gray-100 px-2 py-0.5"
                data-testid={`preview-filter-${f.field}`}
              >
                {f.field}
              </span>
            ))}
          </div>
        </div>
      )}

      {(vm.toolbar.presets.length > 0 || vm.toolbar.customButtons.length > 0) && (
        <div className="mb-3 flex flex-wrap gap-1">
          {vm.toolbar.presets.map((p) => (
            <span
              key={p}
              className="rounded bg-blue-100 px-2 py-1 text-xs text-blue-700"
              data-testid={`preview-toolbar-${p}`}
            >
              {presetLabel(p)}
            </span>
          ))}
          {vm.toolbar.customButtons.map((b, i) => (
            <span
              key={`${b.command}-${i}`}
              className="rounded bg-purple-100 px-2 py-1 text-xs text-purple-700"
            >
              {b.label}
            </span>
          ))}
        </div>
      )}

      {vm.columns.length === 0 ? (
        <div
          className="rounded border border-dashed p-6 text-center text-xs text-gray-400"
          data-testid="preview-empty"
        >
          Add columns to render the table preview
        </div>
      ) : (
        <div
          className="overflow-auto rounded border bg-white text-xs"
          data-testid="preview-table"
        >
          <table className="w-full">
            <thead className="bg-gray-100">
              <tr>
                {vm.columns.map((c) => (
                  <th
                    key={c.field}
                    className="whitespace-nowrap px-2 py-1 text-left font-medium text-gray-600"
                  >
                    {c.field}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((row, i) => (
                <tr key={i} className="border-t">
                  {vm.columns.map((c) => (
                    <td
                      key={c.field}
                      className="whitespace-nowrap px-2 py-1 text-gray-700"
                    >
                      {String(row[c.field] ?? '')}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};

function presetLabel(p: string): string {
  switch (p) {
    case 'create':
      return '+ Create';
    case 'export':
      return 'Export';
    case 'bulkDelete':
      return 'Bulk Delete';
    default:
      return p;
  }
}

function generateMockRows(
  columnFields: string[],
  fields: Array<{ code: string; dataType?: string }>,
  count: number,
): Array<Record<string, unknown>> {
  const typeByCode = new Map(fields.map((f) => [f.code, f.dataType ?? 'string']));
  return Array.from({ length: count }, (_, i) => {
    const row: Record<string, unknown> = {};
    for (const code of columnFields) {
      row[code] = mockValue(typeByCode.get(code) ?? 'string', i);
    }
    return row;
  });
}

function mockValue(type: string, seed: number): unknown {
  switch (type.toLowerCase()) {
    case 'string':
    case 'text':
      return `sample-${seed + 1}`;
    case 'integer':
    case 'long':
    case 'bigint':
      return seed * 100 + 42;
    case 'decimal':
    case 'number':
      return Number(((seed + 1) * 12.34).toFixed(2));
    case 'boolean':
      return seed % 2 === 0;
    case 'date':
      return new Date(2026, 3, seed + 1).toISOString().slice(0, 10);
    case 'datetime':
      return new Date(2026, 3, seed + 1, 10, 30)
        .toISOString()
        .slice(0, 16)
        .replace('T', ' ');
    default:
      return `value-${seed}`;
  }
}

export default StructuralPreview;
