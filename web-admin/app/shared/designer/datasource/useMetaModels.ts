/**
 * Hook to fetch meta models and their fields.
 * Shared across Dashboard, Report, and other designers that need model selection.
 */

import { useState, useEffect, useCallback } from 'react';
import { ResultHelper } from '~/utils/type';
import type { ModelOption, FieldOption, NamedQueryOption } from './types';

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
