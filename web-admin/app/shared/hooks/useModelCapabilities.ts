import { useEffect, useState } from 'react';

export interface ModelCapabilities {
  list: boolean;
  detail: boolean;
  create: boolean;
  update: boolean;
  delete: boolean;
  bulkDelete: boolean;
  export: boolean;
  sort: boolean;
  filter: boolean;
  paginate: boolean;
  sortableFields: string[];
  filterableFields: string[];
  detailKeyField?: string;
}

export interface UseModelCapabilitiesResult {
  data: ModelCapabilities | undefined;
  loading: boolean;
  error: Error | undefined;
  refetch: () => void;
}

export function useModelCapabilities(code: string | undefined): UseModelCapabilitiesResult {
  const [data, setData] = useState<ModelCapabilities | undefined>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<Error | undefined>();
  const [version, setVersion] = useState(0);

  useEffect(() => {
    if (!code) {
      setData(undefined);
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(undefined);
    fetch(`/api/meta/models/${encodeURIComponent(code)}/capabilities`)
      .then((r) => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        return r.json();
      })
      .then((body) => {
        if (cancelled) return;
        setData(body?.data as ModelCapabilities);
      })
      .catch((e: Error) => {
        if (cancelled) return;
        setError(e);
        setData(undefined);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [code, version]);

  return { data, loading, error, refetch: () => setVersion((v) => v + 1) };
}
