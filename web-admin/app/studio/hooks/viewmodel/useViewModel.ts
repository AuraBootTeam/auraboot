import { useState, useEffect, useCallback } from 'react';
import { viewModelService } from '~/studio/services/viewmodel/ViewModelService';
import type { ResolvedField, ViewModelSummary } from '~/studio/domain/viewmodel/types';

interface UseViewModelOptions {
  viewModelCode?: string;
}

interface UseViewModelResult {
  summary: ViewModelSummary | null;
  fields: ResolvedField[];
  loading: boolean;
  error: string | null;
  refresh: () => void;
}

/**
 * Hook for loading a ViewModel's summary and resolved fields.
 *
 * @since 3.2.0
 */
export function useViewModel({ viewModelCode }: UseViewModelOptions): UseViewModelResult {
  const [summary, setSummary] = useState<ViewModelSummary | null>(null);
  const [fields, setFields] = useState<ResolvedField[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadViewModel = useCallback(async () => {
    if (!viewModelCode) {
      setSummary(null);
      setFields([]);
      return;
    }

    setLoading(true);
    setError(null);
    try {
      const [summaryData, fieldsData] = await Promise.all([
        viewModelService.getSummary(viewModelCode),
        viewModelService.getResolvedFields(viewModelCode),
      ]);
      setSummary(summaryData);
      setFields(fieldsData);
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to load ViewModel';
      setError(message);
    } finally {
      setLoading(false);
    }
  }, [viewModelCode]);

  useEffect(() => {
    loadViewModel();
  }, [loadViewModel]);

  return {
    summary,
    fields,
    loading,
    error,
    refresh: loadViewModel,
  };
}
