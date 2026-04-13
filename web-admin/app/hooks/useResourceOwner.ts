import { useCallback, useEffect, useRef, useState } from 'react';
import { get, post } from '~/shared/services/http-client/HttpClient';

/**
 * Resource ownership information returned by the API.
 */
export interface ResourceOwnerInfo {
  managed: boolean;
  pluginId: string | null;
  pluginName: string | null;
  pluginVersion: string | null;
  ownershipType: string | null;
  userModified: boolean;
  userModifiedAt: string | null;
  importedAt: string | null;
  protectionLevel: number;
}

/**
 * Hook to query single resource ownership.
 */
export function useResourceOwner(resourceType: string, resourceCode: string) {
  const [owner, setOwner] = useState<ResourceOwnerInfo | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!resourceType || !resourceCode) return;

    let cancelled = false;
    setLoading(true);

    get<ResourceOwnerInfo>('/api/plugins/resources/owner', {
      resourceType,
      resourceCode,
    })
      .then((result) => {
        if (!cancelled && result.success && result.data) {
          setOwner(result.data);
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [resourceType, resourceCode]);

  return { owner, loading };
}

/**
 * Hook to batch query resource ownership for list pages.
 * Returns a map of "TYPE:code" → ResourceOwnerInfo.
 */
export function useBatchResourceOwners(resources: Array<{ type: string; code: string }> | null) {
  const [owners, setOwners] = useState<Record<string, ResourceOwnerInfo>>({});
  const [loading, setLoading] = useState(false);
  const lastKeyRef = useRef<string>('');

  const fetchOwners = useCallback(async (items: Array<{ type: string; code: string }>) => {
    if (!items || items.length === 0) return;

    const key = items
      .map((r) => `${r.type}:${r.code}`)
      .sort()
      .join(',');
    if (key === lastKeyRef.current) return;
    lastKeyRef.current = key;

    setLoading(true);
    try {
      const result = await post<Record<string, ResourceOwnerInfo>>(
        '/api/plugins/resources/owners',
        { resources: items },
      );
      if (result.success && result.data) {
        setOwners(result.data);
      }
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (resources && resources.length > 0) {
      fetchOwners(resources);
    }
  }, [resources, fetchOwners]);

  return { owners, loading };
}
