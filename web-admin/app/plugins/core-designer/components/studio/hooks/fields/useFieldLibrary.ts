import { useState, useEffect, useMemo, useCallback } from 'react';
import { fieldLibraryService } from '~/plugins/core-designer/components/studio/services/fields/FieldLibraryService';
import { viewModelService } from '~/plugins/core-designer/components/studio/services/viewmodel/ViewModelService';
import type { MetaFieldDTO, FieldCategoryInfo } from '~/plugins/core-designer/components/studio/workbench/panels/fields/types';
import { SEMANTIC_TYPE_INFO } from '~/plugins/core-designer/components/studio/workbench/panels/fields/types';
import type { ResolvedField } from '~/plugins/core-designer/components/studio/domain/viewmodel/types';

interface UseFieldLibraryOptions {
  modelPid?: string;
  modelCode?: string;
  viewModelCode?: string;
}

interface UseFieldLibraryResult {
  fields: MetaFieldDTO[];
  fieldsByCategory: Record<string, MetaFieldDTO[]>;
  categories: FieldCategoryInfo[];
  loading: boolean;
  error: string | null;
  searchQuery: string;
  setSearchQuery: (query: string) => void;
  selectedCategory: string;
  setSelectedCategory: (category: string) => void;
  filteredFields: MetaFieldDTO[];
  refresh: () => void;
}

/**
 * Hook for managing field library data.
 * Loads fields from backend and provides filtering/search capabilities.
 *
 * @since 3.1.0
 */
export function useFieldLibrary({
  modelPid,
  modelCode,
  viewModelCode,
}: UseFieldLibraryOptions = {}): UseFieldLibraryResult {
  const [fields, setFields] = useState<MetaFieldDTO[]>([]);
  const [fieldsByCategory, setFieldsByCategory] = useState<Record<string, MetaFieldDTO[]>>({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedCategory, setSelectedCategory] = useState('all');

  const loadFields = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      if (viewModelCode) {
        // Load resolved fields from ViewModel
        const resolvedFields = await viewModelService.getResolvedFields(viewModelCode);
        const converted = resolvedFields.map(resolvedFieldToMetaFieldDTO);
        setFields(converted);

        // Group by source type as category
        const grouped: Record<string, MetaFieldDTO[]> = {};
        for (const field of converted) {
          const category = field.semanticType || 'other';
          if (!grouped[category]) grouped[category] = [];
          grouped[category].push(field);
        }
        setFieldsByCategory(grouped);
      } else if (modelCode) {
        // Load fields by model code — resolve PID first via /api/meta/models/code/{code}
        const { get: httpGet } = await import('~/services/http-client');
        const modelResp = await httpGet<{ pid: string }>(`/api/meta/models/code/${modelCode}`);
        const pid = modelResp?.data?.pid;
        if (pid) {
          const modelFields = await fieldLibraryService.getModelFields(pid);
          setFields(modelFields);
          const grouped: Record<string, MetaFieldDTO[]> = {};
          for (const field of modelFields) {
            const category = field.semanticType || 'other';
            if (!grouped[category]) grouped[category] = [];
            grouped[category].push(field);
          }
          setFieldsByCategory(grouped);
        }
      } else if (modelPid) {
        // Load model-bound fields
        const modelFields = await fieldLibraryService.getModelFields(modelPid);
        setFields(modelFields);

        // Group by semantic type
        const grouped: Record<string, MetaFieldDTO[]> = {};
        for (const field of modelFields) {
          const category = field.semanticType || 'other';
          if (!grouped[category]) grouped[category] = [];
          grouped[category].push(field);
        }
        setFieldsByCategory(grouped);
      } else {
        // Load all fields from library grouped by semantic type
        const grouped = await fieldLibraryService.listBySemanticType();
        setFieldsByCategory(grouped);

        // Flatten to single list
        const all = Object.values(grouped).flat();
        setFields(all);
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to load fields';
      setError(message);
    } finally {
      setLoading(false);
    }
  }, [modelPid, modelCode, viewModelCode]);

  useEffect(() => {
    loadFields();
  }, [loadFields]);

  const categories = useMemo<FieldCategoryInfo[]>(() => {
    const cats: FieldCategoryInfo[] = [];
    for (const [id, fields] of Object.entries(fieldsByCategory)) {
      const info = SEMANTIC_TYPE_INFO[id] || { name: id, icon: '📋' };
      cats.push({ id, name: info.name, icon: info.icon, count: fields.length });
    }
    return cats.sort((a, b) => b.count - a.count);
  }, [fieldsByCategory]);

  const filteredFields = useMemo<MetaFieldDTO[]>(() => {
    let result = selectedCategory === 'all' ? fields : fieldsByCategory[selectedCategory] || [];

    if (searchQuery.trim()) {
      const query = searchQuery.toLowerCase();
      result = result.filter(
        (f) =>
          f.code.toLowerCase().includes(query) ||
          f.displayName?.toLowerCase().includes(query) ||
          f.description?.toLowerCase().includes(query) ||
          f.dataType?.toLowerCase().includes(query),
      );
    }

    return result;
  }, [fields, fieldsByCategory, selectedCategory, searchQuery]);

  return {
    fields,
    fieldsByCategory,
    categories,
    loading,
    error,
    searchQuery,
    setSearchQuery,
    selectedCategory,
    setSelectedCategory,
    filteredFields,
    refresh: loadFields,
  };
}

/**
 * Convert a ResolvedField from ViewModel API to MetaFieldDTO format
 * for compatibility with the existing FieldLibraryPanel.
 */
function resolvedFieldToMetaFieldDTO(resolved: ResolvedField): MetaFieldDTO {
  return {
    pid: resolved.code, // Use code as pid for ViewModel fields
    code: resolved.aliasCode || resolved.code,
    dataType: resolved.dataType || 'string',
    displayName: resolved.displayName,
    description: resolved.description,
    semanticType: resolved.sourceType === 'computed_only' ? 'system' : 'other',
    virtualType: resolved.virtual ? 'computed_readonly' : undefined,
    computeExpression: resolved.computeExpression,
    required: resolved.required,
    visible: resolved.visible,
    editable: resolved.editable,
  };
}
