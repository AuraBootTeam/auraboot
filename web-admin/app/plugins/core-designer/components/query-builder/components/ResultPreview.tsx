/**
 * ResultPreview — Docked results panel with KPI status bar and zebra table.
 */

interface ResultPreviewProps {
  data: Record<string, unknown>[];
  loading: boolean;
  error?: string;
  /** Latency in ms for last successful query, or undefined when never run / running */
  latencyMs?: number;
  /** Number of selected fields shown in the result, used for KPI display */
  fieldsCount: number;
  /** Currently selected model code, used for KPI display */
  modelCode?: string;
}

const formatCell = (v: unknown): string => {
  if (v == null) return '—';
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
};

export const ResultPreview: React.FC<ResultPreviewProps> = ({
  data,
  loading,
  error,
  latencyMs,
  fieldsCount,
  modelCode,
}) => {
  const columns = data.length > 0 ? Object.keys(data[0]) : [];
  const rows = data.length;

  return (
    <div className="flex h-full flex-col">
      <div
        data-testid="qb-result-status"
        data-rows={rows}
        data-latency-ms={latencyMs ?? ''}
        className="grid shrink-0 grid-cols-2 gap-3 border-b border-slate-200 bg-white px-5 py-3 sm:grid-cols-4"
      >
        <KpiCard
          label="Rows"
          value={rows.toLocaleString()}
          tone={error ? 'error' : rows > 0 ? 'success' : 'muted'}
        />
        <KpiCard label="Latency" value={latencyMs == null ? '—' : `${latencyMs} ms`} />
        <KpiCard label="Fields" value={`${fieldsCount || (columns.length ?? 0)}`} />
        <KpiCard label="Source" value={modelCode || '—'} mono />
      </div>

      <div className="flex-1 overflow-auto bg-slate-50">
        {loading && (
          <div className="space-y-2 p-5">
            {Array.from({ length: 6 }).map((_, i) => (
              <div key={i} className="h-7 animate-pulse rounded bg-slate-200" />
            ))}
          </div>
        )}

        {!loading && error && (
          <div className="m-5 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">
            <div className="font-medium">Query failed</div>
            <div className="mt-0.5 text-xs">{error}</div>
          </div>
        )}

        {!loading && !error && rows === 0 && (
          <div className="flex h-full min-h-[160px] items-center justify-center text-sm text-slate-400">
            {modelCode ? 'No rows returned. Adjust filters or click Run.' : 'Pick a model and click Run to see results.'}
          </div>
        )}

        {!loading && !error && rows > 0 && (
          <table data-testid="qb-result-table" className="min-w-full text-sm">
            <thead className="sticky top-0 bg-white shadow-[0_1px_0_0_rgb(226_232_240)]">
              <tr>
                {columns.map((col) => (
                  <th
                    key={col}
                    className="px-4 py-2 text-left text-xs font-semibold tracking-wide text-slate-500 uppercase"
                  >
                    {col}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {data.map((row, i) => (
                <tr
                  key={i}
                  className={i % 2 === 0 ? 'bg-white hover:bg-blue-50/40' : 'bg-slate-50 hover:bg-blue-50/40'}
                >
                  {columns.map((col) => (
                    <td key={col} className="px-4 py-2 whitespace-nowrap text-slate-700">
                      {formatCell(row[col])}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
};

interface KpiCardProps {
  label: string;
  value: string;
  tone?: 'success' | 'error' | 'muted';
  mono?: boolean;
}

const KpiCard: React.FC<KpiCardProps> = ({ label, value, tone, mono }) => {
  const valueClass =
    tone === 'success'
      ? 'text-emerald-600'
      : tone === 'error'
        ? 'text-rose-600'
        : tone === 'muted'
          ? 'text-slate-400'
          : 'text-slate-800';
  return (
    <div className="rounded-lg bg-slate-50 px-3 py-2">
      <div className="text-[10px] font-semibold tracking-wider text-slate-500 uppercase">{label}</div>
      <div className={`mt-0.5 truncate text-lg font-semibold ${mono ? 'font-mono text-base' : ''} ${valueClass}`}>
        {value}
      </div>
    </div>
  );
};
