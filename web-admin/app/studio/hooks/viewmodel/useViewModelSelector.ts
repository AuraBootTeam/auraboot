import { useState, useEffect, useCallback } from 'react';
import { viewModelService } from '~/studio/services/viewmodel/ViewModelService';
import type { ViewModelSummary } from '~/studio/domain/viewmodel/types';

interface UseViewModelSelectorResult {
  viewModels: ViewModelSummary[];
  selectedCode: string | null;
  setSelectedCode: (code: string | null) => void;
  loading: boolean;
  error: string | null;
  refresh: () => void;
}

/**
 * Hook for managing ViewModel selection state.
 * Loads the list of available ViewModels and manages selection.
 *
 * @since 3.2.0
 */
export function useViewModelSelector(): UseViewModelSelectorResult {
  const [viewModels, setViewModels] = useState<ViewModelSummary[]>([]);
  const [selectedCode, setSelectedCode] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadViewModels = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const models = await viewModelService.listViewModels();
      // Convert to ViewModelSummary format
      const summaries: ViewModelSummary[] = models.map((m) => ({
        code: m.code,
        displayName: m.displayName ?? m.code,
        description: m.description,
        mode: m.extension?.viewModel?.mode ?? 'inherit',
        baseEntityCode: m.extension?.viewModel?.baseEntityCode,
        namedQueryCode: m.extension?.viewModel?.namedQueryCode,
        fieldCount: 0, // Will be populated when selected
        status: m.status,
      }));
      setViewModels(summaries);
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to load ViewModels';
      setError(message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadViewModels();
  }, [loadViewModels]);

  return {
    viewModels,
    selectedCode,
    setSelectedCode,
    loading,
    error,
    refresh: loadViewModels,
  };
}
