/**
 * ModelSelector — Models rail with search and accent-bar selection.
 */

import { useState, useEffect, useRef } from 'react';
import { queryBuilderService, type ModelInfo } from '../services/queryBuilderService';
import { ResultHelper } from '~/utils/type';

interface ModelSelectorProps {
  value?: string;
  onChange: (modelCode: string) => void;
  /** Forwarded from parent so ⌘K can focus this input */
  searchInputRef?: React.RefObject<HTMLInputElement | null>;
}

export const ModelSelector: React.FC<ModelSelectorProps> = ({ value, onChange, searchInputRef }) => {
  const [models, setModels] = useState<ModelInfo[]>([]);
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(false);
  const requestSeqRef = useRef(0);

  useEffect(() => {
    let cancelled = false;
    const requestSeq = ++requestSeqRef.current;
    setLoading(true);
    const timer = window.setTimeout(async () => {
    try {
      const resp = await queryBuilderService.getModels(search || undefined);
      if (
        !cancelled &&
        requestSeq === requestSeqRef.current &&
        ResultHelper.isSuccess(resp) &&
        resp.data
      ) {
        setModels(resp.data);
      }
    } catch {
      /* ignore */
    } finally {
      if (!cancelled && requestSeq === requestSeqRef.current) {
        setLoading(false);
      }
    }
    }, 150);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [search]);

  return (
    <div
      className="flex h-full flex-col gap-3"
      data-testid="qb-model-selector"
      data-loading={loading ? 'true' : 'false'}
      data-query={search}
      data-result-count={models.length}
    >
      <div className="px-1">
        <h2 className="text-xs font-semibold tracking-wide text-slate-500 uppercase">Data Models</h2>
        <p className="mt-1 text-xs text-slate-400">Pick a model to start</p>
      </div>
      <input
        ref={searchInputRef}
        type="text"
        placeholder="Search models… (⌘K)"
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        className="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm placeholder:text-slate-400 focus:border-blue-500 focus:ring-2 focus:ring-blue-100 focus:outline-none"
        data-testid="qb-model-search"
      />
      <div className="-mr-1 flex-1 overflow-y-auto pr-1">
        {loading && <div className="px-3 py-4 text-center text-xs text-slate-400">Loading…</div>}
        {!loading && models.length === 0 && (
          <div className="px-3 py-4 text-center text-xs text-slate-400">No models found</div>
        )}
        <div className="space-y-1">
          {models.map((m) => {
            const active = value === m.code;
            return (
              <button
                key={m.code}
                type="button"
                onClick={() => onChange(m.code)}
                data-testid={`qb-model-${m.code}`}
                className={`relative flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left transition-colors ${
                  active ? 'bg-blue-50 text-blue-900' : 'text-slate-700 hover:bg-slate-100'
                }`}
              >
                <span
                  className={`absolute top-2 bottom-2 left-0 w-1 rounded-r ${
                    active ? 'bg-blue-600' : 'bg-transparent'
                  }`}
                />
                <div className="ml-2 min-w-0 flex-1">
                  <div className="truncate text-sm font-medium">{m.name || m.code}</div>
                  <div className="truncate text-xs text-slate-500">{m.code}</div>
                </div>
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
};
