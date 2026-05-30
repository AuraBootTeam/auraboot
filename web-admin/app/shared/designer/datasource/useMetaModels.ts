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

/**
 * Fetch fields for a specific model
 */
export function useModelFields(modelCode: string | undefined) {
  const [fields, setFields] = useState<FieldOption[]>([]);
  const [isLoading, setIsLoading] = useState(false);

  useEffect(() => {
    if (!modelCode) {
      setFields([]);
      return;
    }

    let mounted = true;
    setIsLoading(true);

    fetch(`/api/meta/models/code/${modelCode}/fields`)
      .then((res) => res.json())
      .then((result) => {
        if (!mounted) return;
        if (ResultHelper.isSuccess(result) && result.data) {
          setFields(
            result.data.map((f: { code: string; name: string; fieldType: string }) => ({
              code: f.code,
              name: f.name,
              fieldType: f.fieldType,
            })),
          );
        }
      })
      .catch((error) => console.error('Failed to fetch fields:', error))
      .finally(() => {
        if (mounted) setIsLoading(false);
      });

    return () => {
      mounted = false;
    };
  }, [modelCode]);

  return { fields, isLoading };
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
