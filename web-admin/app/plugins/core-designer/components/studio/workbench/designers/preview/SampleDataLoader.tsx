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
    <div className="mt-3 border-t pt-3 text-xs" data-testid="sample-data-loader">
      <button
        type="button"
        onClick={load}
        disabled={loading || !modelCode}
        className="rounded border px-2 py-1 text-gray-600 hover:bg-gray-100 disabled:opacity-50"
        data-testid="sample-data-load-btn"
      >
        {loading ? 'Loading...' : 'Load sample data'}
      </button>
      {count !== undefined && !error && (
        <span className="ml-2 text-green-600" data-testid="sample-data-count">
          Loaded {count} row{count === 1 ? '' : 's'}
        </span>
      )}
      {error && (
        <div className="mt-1 text-red-600" data-testid="sample-data-error">
          {error}
        </div>
      )}
    </div>
  );
};

export default SampleDataLoader;
