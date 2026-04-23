import React, { useState } from 'react';

export interface SampleDataLoaderProps {
  modelCode?: string;
  onLoaded?: (rows: Array<Record<string, unknown>>) => void;
}

/**
 * Manual loader for real sample data via `GET /api/dynamic/{code}/list`.
 * Triggered on button click; feeds rows to the parent via `onLoaded` so
 * `StructuralPreview` can render real values instead of mocks.
 *
 * Part of P3-T7 (virtual model backend plan).
 */
export const SampleDataLoader: React.FC<SampleDataLoaderProps> = ({
  modelCode,
  onLoaded,
}) => {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | undefined>();
  const [count, setCount] = useState<number | undefined>();

  const load = async () => {
    if (!modelCode) return;
    setLoading(true);
    setError(undefined);
    try {
      const resp = await fetch(
        `/api/dynamic/${encodeURIComponent(modelCode)}/list?pageNum=1&pageSize=3`,
      );
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      const body = await resp.json();
      const rows = (body?.data?.records ?? body?.data ?? []) as Array<
        Record<string, unknown>
      >;
      const arr = Array.isArray(rows) ? rows : [];
      setCount(arr.length);
      onLoaded?.(arr);
    } catch (e) {
      setError(String(e instanceof Error ? e.message : e));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div
      className="rounded-3xl border border-slate-200 bg-slate-50 p-4 text-xs"
      data-testid="sample-data-loader"
    >
      <div className="mb-3">
        <div className="text-[11px] font-semibold uppercase tracking-[0.14em] text-slate-400">
          样例数据
        </div>
        <div className="mt-1 text-sm text-slate-600">
          拉取 3 条真实数据，验证字段命名与列宽是否合理。
        </div>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          onClick={load}
          disabled={loading || !modelCode}
          className="rounded-full border border-slate-300 bg-white px-3 py-1.5 text-slate-700 transition hover:bg-slate-100 disabled:opacity-50"
          data-testid="sample-data-load-btn"
        >
          {loading ? '加载中...' : '加载样例数据'}
        </button>
        {count !== undefined && !error && (
          <span className="text-emerald-600" data-testid="sample-data-count">
            已加载 {count} 条
          </span>
        )}
      </div>
      {error && (
        <div className="mt-2 text-red-600" data-testid="sample-data-error">
          {error}
        </div>
      )}
    </div>
  );
};

export default SampleDataLoader;
