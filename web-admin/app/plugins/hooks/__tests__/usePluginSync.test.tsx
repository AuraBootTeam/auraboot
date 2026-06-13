/**
 * Unit tests for usePluginSync, usePluginStatus, and useLoadedPlugins hooks.
 *
 * Mocks:
 *   - global fetch (for pluginApi)
 *   - ~/plugins/FederationManager (useFederationStore)
 *
 * Notes on Zustand store isolation: the usePluginSyncStore is module-scoped.
 * Because it is internal to the module, we reset by re-setting via syncNow/etc,
 * and test each behavior through the public interface.
 */

import { renderHook, act, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// ---- Mock FederationManager BEFORE imports ----
const mockLoadPlugin = vi.fn();
const mockUnloadPlugin = vi.fn();
// We use a mutable object so the selector closure always reads the latest value.
const federationState = {
  plugins: new Map<string, { state: string; error?: string }>(),
  loadPlugin: (...args: unknown[]) => mockLoadPlugin(...args),
  unloadPlugin: (...args: unknown[]) => mockUnloadPlugin(...args),
};

vi.mock('~/plugins/FederationManager', () => ({
  useFederationStore: (selector: (s: typeof federationState) => unknown) =>
    selector(federationState),
}));

import {
  usePluginSync,
  usePluginStatus,
  useLoadedPlugins,
} from '../usePluginSync';

// Helper to build a PluginInfo-like response object
function makePlugin(
  pluginId: string,
  opts: {
    status?: 'enabled' | 'disabled';
    hasFrontend?: boolean;
    frontendRemoteUrl?: string;
    frontendStatus?: string;
  } = {},
) {
  return {
    pid: `pid-${pluginId}`,
    pluginId,
    namespace: 'test',
    version: '1.0.0',
    displayName: pluginId,
    status: opts.status ?? 'enabled',
    hasFrontend: opts.hasFrontend ?? false,
    frontendRemoteUrl: opts.frontendRemoteUrl,
    frontendStatus: opts.frontendStatus,
  };
}

function mockFetchSuccess(plugins: ReturnType<typeof makePlugin>[]) {
  global.fetch = vi.fn().mockResolvedValue({
    ok: true,
    json: async () => ({ plugins }),
  } as unknown as Response);
}

function mockFetchFailure(statusText = 'Internal Server Error') {
  global.fetch = vi.fn().mockResolvedValue({
    ok: false,
    status: 500,
    statusText,
  } as unknown as Response);
}

beforeEach(() => {
  vi.clearAllMocks();
  federationState.plugins.clear();
  federationState.loadPlugin = (...args: unknown[]) => mockLoadPlugin(...args);
  federationState.unloadPlugin = (...args: unknown[]) => mockUnloadPlugin(...args);
});

afterEach(() => {
  vi.restoreAllMocks();
});

// ---------------------------------------------------------------------------
// usePluginSync
// ---------------------------------------------------------------------------
describe('usePluginSync', () => {
  it('returns expected shape', () => {
    global.fetch = vi.fn();
    const { result } = renderHook(() => usePluginSync({ autoSync: false }));
    expect(typeof result.current.isReady).toBe('boolean');
    expect(typeof result.current.isSyncing).toBe('boolean');
    expect(typeof result.current.syncNow).toBe('function');
    expect(typeof result.current.isPluginEnabled).toBe('function');
    expect(Array.isArray(result.current.enabledPlugins)).toBe(true);
  });

  it('does not sync on mount when autoSync=false', async () => {
    global.fetch = vi.fn();
    renderHook(() => usePluginSync({ autoSync: false, syncInterval: 0 }));
    await act(async () => { await Promise.resolve(); });
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it('syncNow triggers a fetch', async () => {
    const plugins = [makePlugin('plugin-a')];
    mockFetchSuccess(plugins);

    const onSync = vi.fn();
    const { result } = renderHook(() =>
      usePluginSync({ autoSync: false, syncInterval: 0, onSync }),
    );

    await act(async () => {
      await result.current.syncNow();
    });

    expect(global.fetch).toHaveBeenCalled();
    expect(onSync).toHaveBeenCalledWith(plugins);
  });

  it('syncs plugins on mount when autoSync=true', async () => {
    const plugins = [makePlugin('auto-plugin')];
    mockFetchSuccess(plugins);

    const onSync = vi.fn();
    renderHook(() => usePluginSync({ autoSync: true, syncInterval: 0, onSync }));

    await waitFor(() => {
      expect(onSync).toHaveBeenCalledWith(plugins);
    });
  });

  it('sets error on fetch failure', async () => {
    mockFetchFailure('Internal Server Error');
    const onError = vi.fn();

    const { result } = renderHook(() =>
      usePluginSync({ autoSync: false, syncInterval: 0, onError }),
    );

    await act(async () => {
      await result.current.syncNow();
    });

    expect(onError).toHaveBeenCalled();
    const errorArg = onError.mock.calls[0][0] as Error;
    expect(errorArg).toBeInstanceOf(Error);
    expect(errorArg.message).toContain('Failed to fetch plugins');
  });

  it('sets syncError in store on failure', async () => {
    mockFetchFailure();

    const { result } = renderHook(() =>
      usePluginSync({ autoSync: false, syncInterval: 0 }),
    );

    await act(async () => {
      await result.current.syncNow();
    });

    expect(result.current.error).toBeTruthy();
  });

  it('calls loadPlugin for new enabled plugins with frontend remote URL', async () => {
    const plugin = makePlugin('new-plugin', {
      hasFrontend: true,
      frontendRemoteUrl: 'http://cdn/remoteEntry.js',
    });
    mockFetchSuccess([plugin]);
    mockLoadPlugin.mockResolvedValue(undefined);

    const { result } = renderHook(() =>
      usePluginSync({ autoSync: false, syncInterval: 0 }),
    );

    await act(async () => {
      await result.current.syncNow();
    });

    expect(mockLoadPlugin).toHaveBeenCalledTimes(1);
    const calledWith = mockLoadPlugin.mock.calls[0][0];
    expect(calledWith.clientConfig.remoteEntry).toBe('http://cdn/remoteEntry.js');
  });

  it('does not call loadPlugin for plugins already loaded in store', async () => {
    federationState.plugins.set('existing-plugin', { state: 'loaded' });
    const plugin = makePlugin('existing-plugin', {
      hasFrontend: true,
      frontendRemoteUrl: 'http://cdn/remote.js',
    });
    mockFetchSuccess([plugin]);

    const { result } = renderHook(() =>
      usePluginSync({ autoSync: false, syncInterval: 0 }),
    );

    await act(async () => {
      await result.current.syncNow();
    });

    expect(mockLoadPlugin).not.toHaveBeenCalled();
  });

  it('calls unloadPlugin for plugins no longer enabled by backend', async () => {
    federationState.plugins.set('old-plugin', { state: 'loaded' });
    // Backend returns no plugins → old-plugin should be unloaded
    mockFetchSuccess([]);

    const { result } = renderHook(() =>
      usePluginSync({ autoSync: false, syncInterval: 0 }),
    );

    await act(async () => {
      await result.current.syncNow();
    });

    expect(mockUnloadPlugin).toHaveBeenCalledWith('old-plugin');
  });

  it('does not fire concurrent syncs (syncInProgressRef guard)', async () => {
    let resolveFirst!: () => void;
    const firstFetch = new Promise<typeof globalThis.fetch>((resolve) => {
      resolveFirst = () =>
        resolve({
          ok: true,
          json: async () => ({ plugins: [] }),
        } as unknown as typeof globalThis.fetch);
    });

    global.fetch = vi.fn().mockReturnValue(firstFetch) as unknown as typeof fetch;

    const { result } = renderHook(() =>
      usePluginSync({ autoSync: false, syncInterval: 0 }),
    );

    // Start two concurrent syncs — second should be no-op due to guard
    let p1Done = false;
    let p2Done = false;
    act(() => {
      result.current.syncNow().then(() => { p1Done = true; });
      result.current.syncNow().then(() => { p2Done = true; });
    });

    resolveFirst();
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
      await Promise.resolve();
    });

    // Only one fetch call despite two syncNow calls
    expect(global.fetch).toHaveBeenCalledTimes(1);
  });

  it('isPluginEnabled returns true for an enabled plugin after sync', async () => {
    const plugins = [makePlugin('my-plugin')];
    mockFetchSuccess(plugins);

    const { result } = renderHook(() =>
      usePluginSync({ autoSync: false, syncInterval: 0 }),
    );

    await act(async () => {
      await result.current.syncNow();
    });

    expect(result.current.isPluginEnabled('my-plugin')).toBe(true);
  });

  it('isPluginEnabled returns false for an unknown plugin', async () => {
    mockFetchSuccess([]);

    const { result } = renderHook(() =>
      usePluginSync({ autoSync: false, syncInterval: 0 }),
    );

    await act(async () => {
      await result.current.syncNow();
    });

    expect(result.current.isPluginEnabled('no-such-plugin')).toBe(false);
  });

  it('isReady becomes true after first sync', async () => {
    mockFetchSuccess([]);

    const { result } = renderHook(() =>
      usePluginSync({ autoSync: false, syncInterval: 0 }),
    );

    // Before sync
    // NOTE: Zustand store is module-scoped; isReady may already be true from
    // a previous test. We simply confirm the field exists and is a boolean.
    expect(typeof result.current.isReady).toBe('boolean');

    await act(async () => {
      await result.current.syncNow();
    });

    expect(result.current.isReady).toBe(true);
  });

  it('sets up periodic sync using fake timers', async () => {
    vi.useFakeTimers();
    let callCount = 0;
    global.fetch = vi.fn().mockImplementation(async () => {
      callCount++;
      return {
        ok: true,
        json: async () => ({ plugins: [] }),
      } as unknown as Response;
    });

    renderHook(() =>
      usePluginSync({ autoSync: true, syncInterval: 5000 }),
    );

    // Flush auto-sync microtasks
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();

    const after_mount = callCount;
    expect(after_mount).toBeGreaterThanOrEqual(1);

    // Advance clock by one interval
    await vi.advanceTimersByTimeAsync(5000);
    expect(callCount).toBeGreaterThan(after_mount);

    vi.useRealTimers();
  });
});

// ---------------------------------------------------------------------------
// usePluginStatus
// ---------------------------------------------------------------------------
describe('usePluginStatus', () => {
  it('returns unloaded state for unknown plugin', () => {
    const { result } = renderHook(() => usePluginStatus('no-such-plugin'));
    expect(result.current.isLoaded).toBe(false);
    expect(result.current.isLoading).toBe(false);
    expect(result.current.hasError).toBe(false);
    expect(result.current.state).toBe('unloaded');
  });

  it('reports loaded when plugin.state === "loaded"', () => {
    federationState.plugins.set('my-loaded', { state: 'loaded' });
    const { result } = renderHook(() => usePluginStatus('my-loaded'));
    expect(result.current.isLoaded).toBe(true);
    expect(result.current.state).toBe('loaded');
  });

  it('reports loading when plugin.state === "loading"', () => {
    federationState.plugins.set('my-loading', { state: 'loading' });
    const { result } = renderHook(() => usePluginStatus('my-loading'));
    expect(result.current.isLoading).toBe(true);
    expect(result.current.isLoaded).toBe(false);
  });

  it('reports error when plugin.state === "error"', () => {
    federationState.plugins.set('my-errored', { state: 'error', error: 'Load failed' });
    const { result } = renderHook(() => usePluginStatus('my-errored'));
    expect(result.current.hasError).toBe(true);
    expect(result.current.error).toBe('Load failed');
  });
});

// ---------------------------------------------------------------------------
// useLoadedPlugins
// ---------------------------------------------------------------------------
describe('useLoadedPlugins', () => {
  it('returns an array', () => {
    const { result } = renderHook(() => useLoadedPlugins());
    expect(Array.isArray(result.current)).toBe(true);
  });

  it('returns only plugins with state=loaded', () => {
    federationState.plugins.set('p-loaded', { state: 'loaded' });
    federationState.plugins.set('p-loading', { state: 'loading' });
    federationState.plugins.set('p-error', { state: 'error' });

    const { result } = renderHook(() => useLoadedPlugins());
    const states = result.current.map((p: { state: string }) => p.state);
    for (const state of states) {
      expect(state).toBe('loaded');
    }
    expect(states.length).toBeGreaterThanOrEqual(1);
  });
});
