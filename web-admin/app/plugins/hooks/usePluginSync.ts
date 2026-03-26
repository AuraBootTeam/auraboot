import { useEffect, useCallback, useRef } from 'react';
import { create } from 'zustand';
import { useFederationStore } from '../FederationManager';
import type { PluginInfo, PluginManifest, PluginSyncState } from '../types';

/**
 * Store for plugin sync state.
 */
interface PluginSyncStore extends PluginSyncState {
  setLastSyncAt: (timestamp: number) => void;
  setSyncing: (isSyncing: boolean) => void;
  setSyncError: (error: string | null) => void;
  setEnabledPlugins: (plugins: string[]) => void;
}

const usePluginSyncStore = create<PluginSyncStore>((set) => ({
  lastSyncAt: null,
  isSyncing: false,
  syncError: null,
  enabledPlugins: [],

  setLastSyncAt: (timestamp) => set({ lastSyncAt: timestamp }),
  setSyncing: (isSyncing) => set({ isSyncing }),
  setSyncError: (error) => set({ syncError: error }),
  setEnabledPlugins: (plugins) => set({ enabledPlugins: plugins }),
}));

/**
 * API client for plugin operations.
 */
const pluginApi = {
  /**
   * Fetch all enabled plugins from the backend.
   */
  async fetchEnabledPlugins(): Promise<PluginInfo[]> {
    const response = await fetch('/api/plugins?status=enabled');
    if (!response.ok) {
      throw new Error(`Failed to fetch plugins: ${response.statusText}`);
    }
    const data = await response.json();
    return data.plugins || data;
  },

  /**
   * Fetch a single plugin's details.
   */
  async fetchPlugin(pluginId: string): Promise<PluginInfo | null> {
    const response = await fetch(`/api/plugins/${encodeURIComponent(pluginId)}`);
    if (response.status === 404) {
      return null;
    }
    if (!response.ok) {
      throw new Error(`Failed to fetch plugin: ${response.statusText}`);
    }
    return response.json();
  },

  /**
   * Check plugin status.
   */
  async checkPluginStatus(pluginId: string): Promise<'enabled' | 'disabled' | 'not_found'> {
    const plugin = await this.fetchPlugin(pluginId);
    if (!plugin) {
      return 'not_found';
    }
    return plugin.status === 'enabled' ? 'enabled' : 'disabled';
  },

  /**
   * Get unified package status.
   */
  async getPackageStatus(pluginPid: string): Promise<import('../types').PackageStatus | null> {
    const response = await fetch(`/api/plugins/packages/${encodeURIComponent(pluginPid)}/status`);
    if (response.status === 404) {
      return null;
    }
    if (!response.ok) {
      throw new Error(`Failed to fetch package status: ${response.statusText}`);
    }
    return response.json();
  },
};

/**
 * Options for usePluginSync hook.
 */
interface UsePluginSyncOptions {
  /**
   * Enable automatic sync on mount.
   * @default true
   */
  autoSync?: boolean;

  /**
   * Sync interval in milliseconds.
   * Set to 0 to disable periodic sync.
   * @default 60000 (1 minute)
   */
  syncInterval?: number;

  /**
   * Callback when plugins are synced.
   */
  onSync?: (plugins: PluginInfo[]) => void;

  /**
   * Callback when sync fails.
   */
  onError?: (error: Error) => void;
}

/**
 * Hook to synchronize plugin state with the backend.
 *
 * This hook:
 * - Fetches enabled plugins from the backend
 * - Loads new plugins via Federation Manager
 * - Unloads disabled plugins
 * - Provides manual sync and status methods
 *
 * @example
 * ```tsx
 * function PluginProvider({ children }) {
 *   const { isReady, error, syncNow } = usePluginSync({
 *     autoSync: true,
 *     syncInterval: 60000,
 *     onSync: (plugins) => console.log('Synced plugins:', plugins),
 *   });
 *
 *   if (!isReady) {
 *     return <LoadingScreen />;
 *   }
 *
 *   if (error) {
 *     return <ErrorScreen error={error} onRetry={syncNow} />;
 *   }
 *
 *   return children;
 * }
 * ```
 */
export function usePluginSync(options: UsePluginSyncOptions = {}) {
  const {
    autoSync = true,
    syncInterval = 60000,
    onSync,
    onError,
  } = options;

  const { lastSyncAt, isSyncing, syncError, enabledPlugins } = usePluginSyncStore();
  const { setLastSyncAt, setSyncing, setSyncError, setEnabledPlugins } = usePluginSyncStore.getState();

  const loadPlugin = useFederationStore((state) => state.loadPlugin);
  const unloadPlugin = useFederationStore((state) => state.unloadPlugin);
  const plugins = useFederationStore((state) => state.plugins);

  const syncInProgressRef = useRef(false);

  /**
   * Sync plugins with the backend.
   */
  const syncPlugins = useCallback(async () => {
    if (syncInProgressRef.current) {
      return;
    }

    syncInProgressRef.current = true;
    setSyncing(true);
    setSyncError(null);

    try {
      // Fetch enabled plugins from backend
      const backendPlugins = await pluginApi.fetchEnabledPlugins();
      const backendPluginIds = new Set(backendPlugins.map((p) => p.pluginId));

      // Find plugins to load (enabled on backend but not loaded)
      // Support both legacy manifest.clientConfig.remoteEntry and new frontendRemoteUrl
      const pluginsToLoad = backendPlugins.filter((p) => {
        if (plugins.has(p.pluginId)) {
          return false;
        }
        // Check for frontend component via unified package
        if (p.hasFrontend && p.frontendRemoteUrl && p.frontendStatus !== 'failed') {
          return true;
        }
        // Legacy check via manifest
        return p.manifest?.clientConfig?.remoteEntry;
      });

      // Find plugins to unload (loaded but not enabled on backend)
      const loadedPluginIds = Array.from(plugins.keys());
      const pluginsToUnload = loadedPluginIds.filter(
        (id) => !backendPluginIds.has(id)
      );

      // Load new plugins
      for (const plugin of pluginsToLoad) {
        // Build manifest from unified package or legacy format
        const manifest: PluginManifest = plugin.manifest || {
          pluginId: plugin.pluginId,
          namespace: plugin.namespace,
          version: plugin.version,
          displayName: plugin.displayName,
        };

        // Use frontendRemoteUrl from unified package if available
        if (plugin.hasFrontend && plugin.frontendRemoteUrl) {
          manifest.clientConfig = {
            ...manifest.clientConfig,
            remoteEntry: plugin.frontendRemoteUrl,
          };
        }

        if (manifest.clientConfig?.remoteEntry) {
          await loadPlugin(manifest);
        }
      }

      // Unload disabled plugins
      for (const pluginId of pluginsToUnload) {
        unloadPlugin(pluginId);
      }

      setEnabledPlugins(Array.from(backendPluginIds));
      setLastSyncAt(Date.now());

      onSync?.(backendPlugins);
    } catch (error) {
      console.error('[PluginSync] Sync failed:', error);
      const errorMessage = error instanceof Error ? error.message : String(error);
      setSyncError(errorMessage);
      onError?.(error instanceof Error ? error : new Error(errorMessage));
    } finally {
      syncInProgressRef.current = false;
      setSyncing(false);
    }
  }, [loadPlugin, unloadPlugin, plugins, setLastSyncAt, setSyncing, setSyncError, setEnabledPlugins, onSync, onError]);

  // Auto sync on mount
  useEffect(() => {
    if (autoSync) {
      syncPlugins();
    }
  }, [autoSync, syncPlugins]);

  // Periodic sync
  useEffect(() => {
    if (syncInterval <= 0) {
      return;
    }

    const intervalId = setInterval(() => {
      syncPlugins();
    }, syncInterval);

    return () => clearInterval(intervalId);
  }, [syncInterval, syncPlugins]);

  return {
    /**
     * Whether the initial sync has completed.
     */
    isReady: lastSyncAt !== null,

    /**
     * Whether a sync is currently in progress.
     */
    isSyncing,

    /**
     * Last sync error, if any.
     */
    error: syncError,

    /**
     * Timestamp of the last successful sync.
     */
    lastSyncAt,

    /**
     * List of enabled plugin IDs.
     */
    enabledPlugins,

    /**
     * Manually trigger a sync.
     */
    syncNow: syncPlugins,

    /**
     * Check if a specific plugin is enabled.
     */
    isPluginEnabled: (pluginId: string) => enabledPlugins.includes(pluginId),
  };
}

/**
 * Hook to check a single plugin's status.
 */
export function usePluginStatus(pluginId: string) {
  const plugin = useFederationStore((state) => state.plugins.get(pluginId));
  const { enabledPlugins } = usePluginSyncStore();

  return {
    isLoaded: plugin?.state === 'loaded',
    isLoading: plugin?.state === 'loading',
    hasError: plugin?.state === 'error',
    error: plugin?.error,
    isEnabled: enabledPlugins.includes(pluginId),
    state: plugin?.state || 'unloaded',
  };
}

/**
 * Hook to get all loaded plugins.
 */
export function useLoadedPlugins() {
  const plugins = useFederationStore((state) => state.plugins);

  return Array.from(plugins.values()).filter((p) => p.state === 'loaded');
}

export default usePluginSync;
