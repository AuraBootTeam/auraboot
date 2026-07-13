/**
 * Hook to fetch meta models and their fields.
 * Shared across Dashboard, Report, and other designers that need model selection.
 */

import { useState, useEffect, useCallback } from 'react';
import { ResultHelper } from '~/utils/type';
import type {
  ModelOption,
  FieldOption,
  NamedQueryOption,
  SemanticMetricOption,
  SemanticDimensionOption,
} from './types';

interface SemanticMetaModel {
  code: string;
  label?: Record<string, string>;
  metrics?: Array<{ code: string; type?: string; label?: Record<string, string>; description?: string }>;
  dimensions?: Array<{
    code: string;
    type?: string;
    label?: Record<string, string>;
    timeGrains?: string[];
    primaryTime?: boolean;
  }>;
}

function localize(label: Record<string, string> | undefined, code: string): string {
  return label?.['zh-CN'] || label?.['en'] || code;
}

/**
 * Fetch all available models
 */
export function useMetaModels() {
  const [models, setModels] = useState<ModelOption[]>([]);
  const [isLoading, setIsLoading] = useState(false);

  useEffect(() => {
    let mounted = true;
    setIsLoading(true);

    fetch('/api/meta/models')
      .then((res) => res.json())
      .then((result) => {
        if (!mounted) return;
        if (ResultHelper.isSuccess(result) && result.data) {
          const records = Array.isArray(result.data) ? result.data : result.data.records || [];
          setModels(
            records.map(
              (m: { pid: string; code: string; displayName?: string; name?: string }) => ({
                pid: m.pid,
                code: m.code,
                name: m.displayName || m.name || m.code,
              }),
            ),
          );
        }
      })
      .catch((error) => console.error('Failed to fetch models:', error))
      .finally(() => {
        if (mounted) setIsLoading(false);
      });

    return () => {
      mounted = false;
    };
  }, []);

  return { models, isLoading };
}

/** Shape of a row from `/api/dynamic/{modelCode}/field-meta`. */
interface FieldMetaRow {
  code: string;
  dataType: string;
  displayName?: string;
}

/**
 * Fetch fields for a specific model.
 *
 * Backed by `/api/dynamic/{modelCode}/field-meta`. The previous endpoint
 * (`/api/meta/models/code/{modelCode}/fields`) does not exist — it 404s, and the
 * error was swallowed into an empty list, so the designer's data-source panel
 * showed "No fields available" for every model and no dimension or metric could be
 * picked at all. An empty result and a broken request must not look the same, hence
 * `error`.
 */
export function useModelFields(modelCode: string | undefined) {
  const [fields, setFields] = useState<FieldOption[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);

  useEffect(() => {
    if (!modelCode) {
      setFields([]);
      setError(null);
      return;
    }

    let mounted = true;
    setIsLoading(true);
    setError(null);

    fetch(`/api/dynamic/${modelCode}/field-meta`)
      .then(async (res) => {
        if (!res.ok) throw new Error(`field-meta ${res.status} for model ${modelCode}`);
        return res.json();
      })
      .then((result) => {
        if (!mounted) return;
        if (!ResultHelper.isSuccess(result) || !Array.isArray(result.data)) {
          throw new Error(result?.message || `Malformed field-meta response for ${modelCode}`);
        }
        setFields(
          (result.data as FieldMetaRow[]).map((f) => ({
            code: f.code,
            // The picker labels fields for a human; fall back to the code so a field
            // without a display name is still selectable rather than blank.
            name: f.displayName || f.code,
            fieldType: f.dataType,
          })),
        );
      })
      .catch((err: Error) => {
        if (!mounted) return;
        console.error('Failed to fetch fields:', err);
        setFields([]);
        setError(err);
      })
      .finally(() => {
        if (mounted) setIsLoading(false);
      });

    return () => {
      mounted = false;
    };
  }, [modelCode]);

  return { fields, isLoading, error };
}

/**
 * Fetch the list of published semantic models (code + display name) from
 * GET /api/semantic/meta. Drives the Dashboard widget semantic model dropdown.
 */
export function useSemanticModels() {
  const [models, setModels] = useState<ModelOption[]>([]);
  const [isLoading, setIsLoading] = useState(false);

  useEffect(() => {
    let mounted = true;
    setIsLoading(true);

    fetch('/api/semantic/meta')
      .then((res) => res.json())
      .then((result) => {
        if (!mounted) return;
        if (ResultHelper.isSuccess(result) && result.data?.models) {
          setModels(
            (result.data.models as SemanticMetaModel[]).map((m) => ({
              pid: m.code,
              code: m.code,
              name: localize(m.label, m.code),
            })),
          );
        }
      })
      .catch((error) => console.error('Failed to fetch semantic models:', error))
      .finally(() => {
        if (mounted) setIsLoading(false);
      });

    return () => {
      mounted = false;
    };
  }, []);

  return { models, isLoading };
}

/**
 * Fetch the metrics + dimensions of a single semantic model from
 * GET /api/semantic/meta (PRD 16 §6.2). Drives the Dashboard widget semantic
 * metric / dimension pickers. Returns empty lists until a code is supplied or
 * if the model is not found in the catalog.
 */
export function useSemanticModelMeta(semanticModelCode: string | undefined) {
  const [metrics, setMetrics] = useState<SemanticMetricOption[]>([]);
  const [dimensions, setDimensions] = useState<SemanticDimensionOption[]>([]);
  const [isLoading, setIsLoading] = useState(false);

  useEffect(() => {
    if (!semanticModelCode) {
      setMetrics([]);
      setDimensions([]);
      return;
    }

    let mounted = true;
    setIsLoading(true);

    fetch('/api/semantic/meta')
      .then((res) => res.json())
      .then((result) => {
        if (!mounted) return;
        if (ResultHelper.isSuccess(result) && result.data?.models) {
          const model = (result.data.models as SemanticMetaModel[]).find(
            (m) => m.code === semanticModelCode,
          );
          setMetrics(
            (model?.metrics || []).map((m) => ({
              code: m.code,
              name: localize(m.label, m.code),
              type: m.type,
              description: m.description,
            })),
          );
          setDimensions(
            (model?.dimensions || []).map((d) => ({
              code: d.code,
              name: localize(d.label, d.code),
              type: d.type,
              timeGrains: d.timeGrains,
              primaryTime: d.primaryTime,
            })),
          );
        }
      })
      .catch((error) => console.error('Failed to fetch semantic meta:', error))
      .finally(() => {
        if (mounted) setIsLoading(false);
      });

    return () => {
      mounted = false;
    };
  }, [semanticModelCode]);

  return { metrics, dimensions, isLoading };
}

/**
 * Fetch named queries
 */
export function useNamedQueries() {
  const [namedQueries, setNamedQueries] = useState<NamedQueryOption[]>([]);
  const [isLoading, setIsLoading] = useState(false);

  useEffect(() => {
    let mounted = true;
    setIsLoading(true);

    fetch('/api/meta/named-queries?status=enabled')
      .then((res) => res.json())
      .then((result) => {
        if (!mounted) return;
        if (ResultHelper.isSuccess(result) && result.data?.content) {
          setNamedQueries(
            result.data.content.map((q: { pid: string; code: string; title: string }) => ({
              pid: q.pid,
              code: q.code,
              title: q.title,
            })),
          );
        }
      })
      .catch((error) => console.error('Failed to fetch named queries:', error))
      .finally(() => {
        if (mounted) setIsLoading(false);
      });

    return () => {
      mounted = false;
    };
  }, []);

  return { namedQueries, isLoading };
}
