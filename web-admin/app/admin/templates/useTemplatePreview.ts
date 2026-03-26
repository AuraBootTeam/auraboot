/**
 * useTemplatePreview — Fetches and groups plugin resource changes for a template.
 *
 * Calls the parse-directory API to get a dry-run preview of what installing
 * a template would create/update, then groups changes by resource type for
 * sidebar display.
 */

import { useState, useEffect, useMemo } from 'react';
import { get } from '~/services/http-client';
import type { AppTemplate } from './templateCatalog';

export interface ResourceChange {
  resourceType: string; // MODEL, FIELD, COMMAND, PAGE, MENU, PERMISSION, DICT, NAMED_QUERY, I18N
  resourceCode: string;
  resourceName: string;
  action: string; // CREATE, UPDATE, SKIP
  details?: Record<string, unknown>;
}

export interface PreviewResult {
  changes: ResourceChange[];
  actionCounts: Record<string, number>;
  conflicts: unknown[];
}

export interface PreviewGroup {
  type: string;
  label: string;
  icon: string;
  items: ResourceChange[];
}

const GROUP_ORDER = ['MODEL', 'FIELD', 'COMMAND', 'PAGE', 'MENU', 'PERMISSION', 'DICT', 'NAMED_QUERY', 'I18N'];

const GROUP_LABELS: Record<string, string> = {
  MODEL: 'Data Models',
  FIELD: 'Fields',
  COMMAND: 'Commands',
  PAGE: 'Pages',
  MENU: 'Menus',
  PERMISSION: 'Permissions',
  DICT: 'Dictionaries',
  NAMED_QUERY: 'Named Queries',
  I18N: 'Translations',
};

const GROUP_ICONS: Record<string, string> = {
  MODEL: 'cube',
  FIELD: 'list-bullet',
  COMMAND: 'command-line',
  PAGE: 'document-text',
  MENU: 'bars-3',
  PERMISSION: 'shield-check',
  DICT: 'tag',
  NAMED_QUERY: 'circle-stack',
  I18N: 'language',
};

export function useTemplatePreview(template: AppTemplate | null) {
  const [preview, setPreview] = useState<PreviewResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!template) return;
    setLoading(true);
    setError(null);
    get<PreviewResult>(`/api/templates/${template.id}/preview`)
      .then((res) => {
        if (res?.data) setPreview(res.data);
        else setError('Failed to load template preview');
      })
      .catch((err: unknown) => {
        const message = err instanceof Error ? err.message : 'Unknown error';
        setError(message);
      })
      .finally(() => setLoading(false));
  }, [template?.id]);

  const groups: PreviewGroup[] = useMemo(() => {
    if (!preview) return [];
    const typeMap = new Map<string, ResourceChange[]>();
    for (const change of preview.changes) {
      const list = typeMap.get(change.resourceType) || [];
      list.push(change);
      typeMap.set(change.resourceType, list);
    }

    return GROUP_ORDER
      .filter((type) => typeMap.has(type))
      .map((type) => ({
        type,
        label: GROUP_LABELS[type] || type,
        icon: GROUP_ICONS[type] || 'cube',
        items: typeMap.get(type)!,
      }));
  }, [preview]);

  return { preview, groups, loading, error };
}
