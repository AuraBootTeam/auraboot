/**
 * ResultPreview — Display query results as table or chart
 */

import { useState } from 'react';

type ViewMode = 'table' | 'bar' | 'line';

interface ResultPreviewProps {
  data: Record<string, unknown>[];
  loading: boolean;
  error?: string;
}

export const ResultPreview: React.FC<ResultPreviewProps> = ({ data, loading, error }) => {
  const [viewMode, setViewMode] = useState<ViewMode>('table');

  const columns = data.length > 0 ? Object.keys(data[0]) : [];

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-medium text-gray-700">
          Results {data.length > 0 && <span className="text-gray-400">({data.length} rows)</span>}
        </h3>
        <div className="flex items-center gap-1 rounded-md bg-gray-100 p-0.5">
          {(['table', 'bar', 'line'] as ViewMode[]).map((mode) => (
            <button
              key={mode}
              type="button"
              onClick={() => setViewMode(mode)}
              data-testid={`qb-view-${mode}`}
              className={`rounded px-2 py-1 text-xs ${
                viewMode === mode
                  ? 'bg-white text-gray-900 shadow-sm'
                  : 'text-gray-600 hover:text-gray-900'
              }`}
            >
              {mode === 'table' ? 'Table' : mode === 'bar' ? 'Bar' : 'Line'}
            </button>
          ))}
        </div>
      </div>

      {loading && (
        <div className="flex h-48 items-center justify-center text-gray-500">
          <span className="mr-2 h-5 w-5 animate-spin rounded-full border-2 border-gray-300 border-t-blue-500" />
          Running query...
        </div>
      )}

      {error && <div className="rounded-md bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>}

      {!loading && !error && data.length === 0 && (
        <div className="flex h-48 items-center justify-center text-sm text-gray-400">
          No results. Configure your query and click Run.
        </div>
      )}

      {!loading && !error && data.length > 0 && viewMode === 'table' && (
        <div className="max-h-[500px] overflow-auto rounded-md border border-gray-200">
          <table className="min-w-full divide-y divide-gray-200">
            <thead className="sticky top-0 bg-gray-50">
              <tr>
                {columns.map((col) => (
                  <th
                    key={col}
                    className="px-3 py-2 text-left text-xs font-medium tracking-wider text-gray-500 uppercase"
                  >
                    {col}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-200">
              {data.map((row, i) => (
                <tr key={i} className="hover:bg-gray-50">
                  {columns.map((col) => (
                    <td key={col} className="px-3 py-2 text-sm whitespace-nowrap text-gray-700">
                      {row[col] == null ? '—' : String(row[col])}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {!loading && !error && data.length > 0 && (viewMode === 'bar' || viewMode === 'line') && (
        <div className="flex h-64 items-center justify-center rounded-md border border-gray-200 p-4 text-sm text-gray-400">
          Chart view requires data with at least one dimension and one metric.
          <br />
          Full chart rendering will use the dashboard chart components.
        </div>
      )}
    </div>
  );
};
