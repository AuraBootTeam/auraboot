import { useEffect } from 'react';
import { useUser } from '~/contexts/AuthContext';
import type { DataSourceManager } from '~/meta/runtime/data-pipeline/DataSourceManager';
import { fetchResult } from '~/services/http-client';

/**
 * Hook for real-time data sync on DSL pages.
 * Subscribes to backend SSE data change events and triggers
 * debounced reload of affected DataSources.
 *
 * @param manager - DataSourceManager instance (from usePageDataSources)
 * @param modelCodes - Set of modelCodes the current page uses
 */
export function useDataSync(manager: DataSourceManager | null, modelCodes: Set<string>) {
  const { user } = useUser();
  const currentUserId = user?.pid;

  useEffect(() => {
    if (!manager || modelCodes.size === 0) return;

    const doSubscribe = () => {
      const connectionId = (window as any).__auraSSEConnectionId;
      if (!connectionId) return;
      fetchResult('/api/data-sync/subscribe', {
        method: 'post',
        params: {
          connectionId,
          modelCodes: [...modelCodes],
        },
      }).catch(() => {
        // Silent — non-critical
      });
    };

    // Subscribe now
    doSubscribe();

    // Re-subscribe on SSE reconnect
    const onReconnect = () => doSubscribe();
    window.addEventListener('aura:sse-connected', onReconnect);

    // Debounced reload handler
    const pendingReloads = new Set<string>();
    let debounceTimer: ReturnType<typeof setTimeout>;

    const onDataChanged = (e: Event) => {
      const { modelCode, userId } = (e as CustomEvent).detail;

      // Self-change suppression
      if (userId && String(userId) === String(currentUserId)) return;

      // Skip if this page doesn't use this modelCode
      if (!modelCodes.has(modelCode)) return;

      // Skip if tab is not visible
      if (document.hidden) return;

      const affectedIds = manager.getDataSourceIdsByModel(modelCode);
      affectedIds.forEach((id) => pendingReloads.add(id));

      clearTimeout(debounceTimer);
      debounceTimer = setTimeout(() => {
        if (pendingReloads.size > 0) {
          manager.reload([...pendingReloads]);
          pendingReloads.clear();
        }
      }, 500);
    };

    window.addEventListener('aura:data-changed', onDataChanged);

    return () => {
      window.removeEventListener('aura:data-changed', onDataChanged);
      window.removeEventListener('aura:sse-connected', onReconnect);
      clearTimeout(debounceTimer);
    };
    // Serialize modelCodes for stable dependency comparison
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [manager, JSON.stringify([...modelCodes].sort()), currentUserId]);
}
