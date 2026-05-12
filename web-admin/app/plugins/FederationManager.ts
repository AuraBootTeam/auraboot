import { create } from 'zustand';
import type {
  FederationStore,
  PluginManifest,
  RemotePlugin,
  RemoteModule,
  SlotId,
  SlotContribution,
  SlotContributionWithComponent,
  SlotComponentProps,
} from './types';
import {
  DEFAULT_RUNTIME_PROFILE,
  getDefaultPluginRuntimeProfiles,
  type RuntimeProfile,
} from '~/framework/runtime';

export function isPluginEnabledForRuntime(
  manifest: PluginManifest,
  runtimeProfile: RuntimeProfile,
): boolean {
  const runtimeProfiles = manifest.clientConfig?.runtimeProfiles ?? getDefaultPluginRuntimeProfiles();
  return runtimeProfiles.includes(runtimeProfile);
}

export function isSlotEnabledForRuntime(
  slot: SlotContribution,
  runtimeProfile: RuntimeProfile,
): boolean {
  if (!slot.runtimeProfiles || slot.runtimeProfiles.length === 0) {
    return true;
  }
  return slot.runtimeProfiles.includes(runtimeProfile);
}

/**
 * Dynamic import for federated modules.
 * This is the core mechanism for loading remote plugins at runtime.
 */
async function loadRemoteEntry(remoteEntry: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = remoteEntry;
    script.type = 'module';
    script.async = true;

    script.onload = () => {
      resolve();
    };

    script.onerror = (error) => {
      console.error(`[Federation] Failed to load remote entry: ${remoteEntry}`, error);
      reject(new Error(`Failed to load remote entry: ${remoteEntry}`));
    };

    document.head.appendChild(script);
  });
}

/**
 * Get a module from a loaded remote container.
 */
async function getRemoteModule(
  containerName: string,
  moduleName: string
): Promise<{ default: React.ComponentType<unknown> }> {
  // @ts-expect-error - Dynamic federation module access
  const container = window[containerName];
  if (!container) {
    throw new Error(`Container ${containerName} not found`);
  }

  // Initialize the container if needed
  // @ts-expect-error - Dynamic federation initialization
  await container.init(__webpack_share_scopes__.default);

  // Get the module
  const factory = await container.get(moduleName);
  return factory();
}

/**
 * Create the Federation Manager store using Zustand.
 */
export const useFederationStore = create<FederationStore>((set, get) => ({
  // Initial state
  runtimeProfile: DEFAULT_RUNTIME_PROFILE,
  plugins: new Map(),
  slots: new Map(),
  isInitialized: false,
  error: null,

  // Actions
  loadPlugin: async (manifest: PluginManifest) => {
    const { pluginId, namespace, version, displayName, clientConfig } = manifest;
    const runtimeProfile = get().runtimeProfile;

    if (!isPluginEnabledForRuntime(manifest, runtimeProfile)) {
      console.info(
        `[Federation] Plugin ${pluginId} is not enabled for runtime profile ${runtimeProfile}, skipping`,
      );
      return;
    }

    if (!clientConfig?.remoteEntry) {
      console.warn(`[Federation] Plugin ${pluginId} has no remoteEntry, skipping`);
      return;
    }

    // Check if already loaded
    const existing = get().plugins.get(pluginId);
    if (existing && existing.state === 'loaded') {
      return;
    }

    // Create plugin entry
    const plugin: RemotePlugin = {
      pluginId,
      namespace,
      version,
      displayName,
      remoteEntry: clientConfig.remoteEntry,
      state: 'loading',
      modules: new Map(),
    };

    set((state) => ({
      plugins: new Map(state.plugins).set(pluginId, plugin),
    }));

    try {
      // Load the remote entry script
      await loadRemoteEntry(clientConfig.remoteEntry);

      // Update plugin state to loaded
      plugin.state = 'loaded';
      plugin.loadedAt = Date.now();

      // Process slot contributions
      if (clientConfig.slots) {
        for (const slot of clientConfig.slots) {
          if (!isSlotEnabledForRuntime(slot, runtimeProfile)) {
            continue;
          }

          // Load the component for this slot
          const component = await get().loadModule(pluginId, slot.componentName);
          if (component) {
            const contribution: SlotContributionWithComponent = {
              ...slot,
              pluginId,
              component: component as React.ComponentType<SlotComponentProps>,
            };

            set((state) => {
              const newSlots = new Map(state.slots);
              const existing = newSlots.get(slot.slotId) || [];
              newSlots.set(slot.slotId, [...existing, contribution].sort(
                (a, b) => (a.priority || 100) - (b.priority || 100)
              ));
              return { slots: newSlots };
            });
          }
        }
      }

      set((state) => ({
        plugins: new Map(state.plugins).set(pluginId, plugin),
      }));

    } catch (error) {
      console.error(`[Federation] Failed to load plugin: ${pluginId}`, error);
      plugin.state = 'error';
      plugin.error = error instanceof Error ? error.message : String(error);

      set((state) => ({
        plugins: new Map(state.plugins).set(pluginId, plugin),
      }));
    }
  },

  unloadPlugin: (pluginId: string) => {
    set((state) => {
      const newPlugins = new Map(state.plugins);
      const plugin = newPlugins.get(pluginId);

      if (plugin) {
        plugin.state = 'unloaded';
        plugin.modules = new Map(); // Clear cached modules
        newPlugins.set(pluginId, plugin);

        // Try to remove the script element to allow reload
        const scriptElements = document.querySelectorAll(`script[src*="${plugin.namespace}"]`);
        scriptElements.forEach((el) => el.remove());

        // Clear from webpack container cache if possible
        try {
          const containerName = plugin.namespace.replace(/-/g, '_');
          // @ts-expect-error - Dynamic federation cleanup
          if (window[containerName]) {
            // @ts-expect-error - Dynamic federation cleanup
            delete window[containerName];
          }
        } catch (e) {
          console.warn(`[Federation] Could not clear container for ${pluginId}`, e);
        }
      }

      // Remove slot contributions from this plugin
      const newSlots = new Map(state.slots);
      for (const [slotId, contributions] of newSlots) {
        const filtered = contributions.filter((c) => c.pluginId !== pluginId);
        if (filtered.length > 0) {
          newSlots.set(slotId, filtered);
        } else {
          newSlots.delete(slotId);
        }
      }

      return { plugins: newPlugins, slots: newSlots };
    });
  },

  reloadPlugin: async (pluginId: string) => {
    const plugin = get().plugins.get(pluginId);
    if (!plugin) {
      console.warn(`[Federation] Plugin ${pluginId} not found for reload`);
      return;
    }

    // Unload first
    get().unloadPlugin(pluginId);

    // Then reload - we need the manifest
    // In a real implementation, you'd fetch the manifest from the backend
    // Reload requires manifest - fetch from backend in real implementation
  },

  loadModule: async (pluginId: string, moduleName: string) => {
    const plugin = get().plugins.get(pluginId);
    if (!plugin) {
      console.error(`[Federation] Plugin ${pluginId} not found`);
      return null;
    }

    // Check if already loaded
    const existingModule = plugin.modules.get(moduleName);
    if (existingModule?.state === 'loaded' && existingModule.component) {
      return existingModule.component;
    }

    // Create module entry
    const moduleEntry: RemoteModule = {
      name: moduleName,
      component: null,
      state: 'loading',
    };
    plugin.modules.set(moduleName, moduleEntry);

    try {
      // Container name is typically the plugin namespace
      const containerName = plugin.namespace.replace(/-/g, '_');
      const remoteModule = await getRemoteModule(containerName, `./${moduleName}`);

      moduleEntry.component = remoteModule.default;
      moduleEntry.state = 'loaded';
      plugin.modules.set(moduleName, moduleEntry);

      return remoteModule.default;
    } catch (error) {
      console.error(`[Federation] Failed to load module ${moduleName} from ${pluginId}`, error);
      moduleEntry.state = 'error';
      moduleEntry.error = error instanceof Error ? error.message : String(error);
      plugin.modules.set(moduleName, moduleEntry);
      return null;
    }
  },

  getSlotContributions: (slotId: SlotId) => {
    return get().slots.get(slotId) || [];
  },

  refreshPlugins: async () => {
    // This would typically fetch enabled plugins from the backend
    // and load any new ones / unload disabled ones
    set({ isInitialized: true });
  },

  setRuntimeProfile: (runtimeProfile: RuntimeProfile) => {
    set((state) => {
      if (state.runtimeProfile === runtimeProfile) {
        return state;
      }
      return { runtimeProfile };
    });
  },

  setError: (error: string | null) => {
    set({ error });
  },
}));

// ========== Selectors ==========

export const selectPlugin = (pluginId: string) => (state: FederationStore) =>
  state.plugins.get(pluginId);

export const selectAllPlugins = (state: FederationStore) =>
  Array.from(state.plugins.values());

export const selectLoadedPlugins = (state: FederationStore) =>
  Array.from(state.plugins.values()).filter((p) => p.state === 'loaded');

export const selectSlotContributions = (slotId: SlotId) => (state: FederationStore) =>
  state.slots.get(slotId) || [];

export const selectIsPluginLoaded = (pluginId: string) => (state: FederationStore) =>
  state.plugins.get(pluginId)?.state === 'loaded';

export const selectPluginError = (pluginId: string) => (state: FederationStore) =>
  state.plugins.get(pluginId)?.error;

// ========== Utilities ==========

/**
 * Initialize the federation system.
 * Call this once when the app starts.
 */
export async function initializeFederation(): Promise<void> {
  const store = useFederationStore.getState();
  if (store.isInitialized) {
    return;
  }

  await store.refreshPlugins();
}
