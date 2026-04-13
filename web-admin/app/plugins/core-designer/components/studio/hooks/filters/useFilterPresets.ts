/**
 * Filter Presets Hook
 *
 * Manages filter presets and current filter conditions state.
 *
 * @since 3.4.0
 */

import { useState, useEffect, useCallback } from 'react';
import { filterPresetService } from '~/plugins/core-designer/components/studio/services/filters/FilterPresetService';
import type { FilterPreset, FilterCondition } from '~/plugins/core-designer/components/studio/workbench/panels/filters/types';
import { createFilterCondition } from '~/plugins/core-designer/components/studio/workbench/panels/filters/types';

export interface UseFilterPresetsReturn {
  // Presets
  presets: FilterPreset[];
  loadingPresets: boolean;
  presetsError: string | null;
  refreshPresets: () => void;

  // Current conditions
  conditions: FilterCondition[];
  logic: 'and' | 'OR';
  setLogic: (logic: 'and' | 'OR') => void;
  addCondition: () => void;
  removeCondition: (condId: string) => void;
  updateCondition: (condId: string, updates: Partial<FilterCondition>) => void;

  // Preset operations
  savePreset: (name: string, scope: 'global' | 'personal', isDefault?: boolean) => Promise<void>;
  loadPreset: (preset: FilterPreset) => void;
  deletePreset: (id: number) => Promise<void>;
  setDefaultPreset: (id: number) => Promise<void>;

  // Save dialog state
  showSaveDialog: boolean;
  setShowSaveDialog: (show: boolean) => void;
}

export function useFilterPresets(pageCode?: string, modelCode?: string): UseFilterPresetsReturn {
  const [presets, setPresets] = useState<FilterPreset[]>([]);
  const [loadingPresets, setLoadingPresets] = useState(false);
  const [presetsError, setPresetsError] = useState<string | null>(null);
  const [conditions, setConditions] = useState<FilterCondition[]>([]);
  const [logic, setLogic] = useState<'and' | 'OR'>('and');
  const [showSaveDialog, setShowSaveDialog] = useState(false);

  // Load presets
  const loadPresets = useCallback(async () => {
    if (!pageCode) return;
    setLoadingPresets(true);
    setPresetsError(null);
    try {
      const data = await filterPresetService.listByPageCode(pageCode);
      // Parse conditions from JSON string if needed
      const parsed = data.map((p) => ({
        ...p,
        conditions: typeof p.conditions === 'string' ? JSON.parse(p.conditions) : p.conditions,
      }));
      setPresets(parsed);

      // Auto-load default preset
      const defaultPreset = parsed.find((p) => p.isDefault);
      if (defaultPreset && conditions.length === 0) {
        setConditions(defaultPreset.conditions);
        setLogic(defaultPreset.logic);
      }
    } catch (err) {
      setPresetsError(err instanceof Error ? err.message : '加载过滤器失败');
    } finally {
      setLoadingPresets(false);
    }
  }, [pageCode]);

  useEffect(() => {
    loadPresets();
  }, [loadPresets]);

  // Condition operations
  const addCondition = useCallback(() => {
    setConditions((prev) => [...prev, createFilterCondition()]);
  }, []);

  const removeCondition = useCallback((condId: string) => {
    setConditions((prev) => prev.filter((c) => c.id !== condId));
  }, []);

  const updateCondition = useCallback((condId: string, updates: Partial<FilterCondition>) => {
    setConditions((prev) => prev.map((c) => (c.id === condId ? { ...c, ...updates } : c)));
  }, []);

  // Preset operations
  const savePreset = useCallback(
    async (name: string, scope: 'global' | 'personal', isDefault = false) => {
      if (!pageCode || !modelCode) return;
      await filterPresetService.create({
        pageCode,
        modelCode,
        name,
        conditions: JSON.stringify(conditions),
        logic,
        isDefault,
        scope,
      });
      await loadPresets();
      setShowSaveDialog(false);
    },
    [pageCode, modelCode, conditions, logic, loadPresets],
  );

  const loadPreset = useCallback((preset: FilterPreset) => {
    setConditions(preset.conditions);
    setLogic(preset.logic);
  }, []);

  const deletePreset = useCallback(
    async (id: number) => {
      await filterPresetService.delete(id);
      await loadPresets();
    },
    [loadPresets],
  );

  const setDefaultPreset = useCallback(
    async (id: number) => {
      await filterPresetService.setDefault(id);
      await loadPresets();
    },
    [loadPresets],
  );

  return {
    presets,
    loadingPresets,
    presetsError,
    refreshPresets: loadPresets,
    conditions,
    logic,
    setLogic,
    addCondition,
    removeCondition,
    updateCondition,
    savePreset,
    loadPreset,
    deletePreset,
    setDefaultPreset,
    showSaveDialog,
    setShowSaveDialog,
  };
}
