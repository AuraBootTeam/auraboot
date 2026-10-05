import React, { useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';

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
export const SampleDataLoader: React.FC<SampleDataLoaderProps> = ({ modelCode, onLoaded }) => {
  const { t } = useI18n();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);
  const [count, setCount] = useState<number | undefined>();

  const load = async () => {
    if (!modelCode) return;
    setLoading(true);
    setError(false);
    try {
      const resp = await fetch(
        `/api/dynamic/${encodeURIComponent(modelCode)}/list?pageNum=1&pageSize=3`,
      );
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      const body = await resp.json();
      const rows = body?.data?.records;
      if (
        body?.code !== '0' ||
        !Array.isArray(rows) ||
        rows.some((row) => row === null || typeof row !== 'object' || Array.isArray(row))
      ) {
        throw new Error('Invalid sample-data response');
      }
      setCount(rows.length);
      onLoaded?.(rows);
    } catch {
      setError(true);
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
        <div className="text-[11px] font-semibold tracking-[0.14em] text-slate-400 uppercase">
          {t('list_sample.heading')}
        </div>
        <div className="mt-1 text-sm text-slate-600">{t('list_sample.description')}</div>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          onClick={load}
          disabled={loading || !modelCode}
          className="rounded-full border border-slate-300 bg-white px-3 py-1.5 text-slate-700 transition hover:bg-slate-100 disabled:opacity-50"
          data-testid="sample-data-load-btn"
        >
          {t(loading ? 'list_sample.loading' : 'list_sample.load')}
        </button>
        {count !== undefined && !error && (
          <span className="text-emerald-600" data-testid="sample-data-count">
            {t('list_sample.loaded', { count })}
          </span>
        )}
      </div>
      {error && (
        <div className="mt-2 text-red-600" data-testid="sample-data-error">
          {t('list_sample.failed')}
        </div>
      )}
    </div>
  );
};

export default SampleDataLoader;
