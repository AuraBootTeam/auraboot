import { useEffect, useMemo, useState } from 'react';
import { get } from '~/shared/services/http-client';
import type { Result } from '~/shared/services/http-client';
import {
  APP_TEMPLATES,
  mergeTemplateCatalog,
  type AppTemplate,
  type TemplateRegistryEntry,
} from './templateCatalog';

export function useTemplateCatalog() {
  const [templates, setTemplates] = useState<AppTemplate[]>(APP_TEMPLATES);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    get<TemplateRegistryEntry[]>('/api/templates')
      .then((result: Result<TemplateRegistryEntry[]>) => {
        if (cancelled) {
          return;
        }
        const discovered = result.data ?? [];
        setTemplates(mergeTemplateCatalog(discovered));
      })
      .catch((err: unknown) => {
        if (cancelled) {
          return;
        }
        const message = err instanceof Error ? err.message : 'Failed to load templates';
        setError(message);
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, []);

  return useMemo(
    () => ({
      templates,
      loading,
      error,
    }),
    [templates, loading, error],
  );
}
