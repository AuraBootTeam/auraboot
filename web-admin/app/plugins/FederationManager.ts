import * as React from 'react';
import * as ReactDOM from 'react-dom';
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
  isRuntimeProfileAllowed,
  isRuntimeProfileEnabled,
  type RuntimeProfile,
} from '@auraboot/runtime-kernel';

// Thin host-typed wrappers over the kernel profile-gating predicates. The
// gating *logic* (default-to-plugin-default vs default-to-all) lives in the
// kernel (@auraboot/runtime-kernel); these only extract the declared profile
// list from the admin PluginManifest / SlotContribution shapes.
export function isPluginEnabledForRuntime(
  manifest: PluginManifest,
  runtimeProfile: RuntimeProfile,
): boolean {
  return isRuntimeProfileEnabled(manifest.clientConfig?.runtimeProfiles, runtimeProfile);
}

export function isSlotEnabledForRuntime(
  slot: SlotContribution,
  runtimeProfile: RuntimeProfile,
): boolean {
  return isRuntimeProfileAllowed(slot.runtimeProfiles, runtimeProfile);
}

type SharedModule = Record<string, unknown>;

interface FederationShareEntry {
  get: () => Promise<() => SharedModule>;
  loaded: boolean;
  from: string;
  shareConfig: {
    singleton: boolean;
    requiredVersion: string;
  };
}

export interface FederationContainer {
  init: (shareScope: FederationShareScope) => Promise<void> | void;
  get: (moduleName: string) => Promise<() => unknown>;
}

export type FederationShareScope = Record<
  string,
  Record<string, FederationShareEntry>
>;

const remoteContainers = new Map<string, Promise<FederationContainer>>();

function sharedEntry(
  version: string,
  module: SharedModule,
): Record<string, FederationShareEntry> {
  return {
    [version]: {
      get: async () => () => module,
      loaded: true,
      from: 'aura-host',
      shareConfig: {
        singleton: true,
        requiredVersion: version,
      },
    },
  };
}

export function createHostFederationShareScope(): FederationShareScope {
  const reactVersion = React.version;
  const reactDomVersion =
    (ReactDOM as SharedModule & { version?: string }).version || reactVersion;
  return {
    react: sharedEntry(reactVersion, React),
    'react-dom': sharedEntry(reactDomVersion, ReactDOM),
  };
}

export async function initializeFederationContainer(
  container: FederationContainer,
): Promise<FederationContainer> {
  await container.init(createHostFederationShareScope());
  return container;
}

/** Import a Vite federation ESM container and initialize it with host React. */
async function loadRemoteEntry(
  remoteEntry: string,
): Promise<FederationContainer> {
  const existing = remoteContainers.get(remoteEntry);
  if (existing) return existing;

  const pending = import(/* @vite-ignore */ remoteEntry).then((candidate) => {
    const container = candidate as Partial<FederationContainer>;
    if (typeof container.init !== 'function' || typeof container.get !== 'function') {
      throw new Error(
        `Remote entry ${remoteEntry} does not export the ESM federation get/init contract`,
      );
    }
    return initializeFederationContainer(container as FederationContainer);
  });
  remoteContainers.set(remoteEntry, pending);
  try {
    return await pending;
  } catch (error) {
    remoteContainers.delete(remoteEntry);
    throw error;
  }
}

async function getRemoteModule(
  remoteEntry: string,
  moduleName: string,
): Promise<{ default: React.ComponentType<unknown> }> {
  const container = await loadRemoteEntry(remoteEntry);
  const factory = await container.get(moduleName);
  return factory() as { default: React.ComponentType<unknown> };
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
      // Import and initialize the ESM federation container.
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

        remoteContainers.delete(plugin.remoteEntry);
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
      const remoteModule = await getRemoteModule(
        plugin.remoteEntry,
        `./${moduleName}`,
      );

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
