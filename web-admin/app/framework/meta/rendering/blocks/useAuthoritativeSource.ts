import { useEffect, useMemo, useState, useSyncExternalStore } from 'react';
import type { AuthoritativeSourceConfig } from '../../schemas/source-state';
import type { SchemaRuntime } from '../../runtime/schema-runtime';
import type { DataSourceState } from '../../runtime/data-pipeline/DataSourceManager';
import { readSourceIdentity } from './sourceStateModel';

export type SourceReadPhase = 'pending' | 'unconfigured' | 'loading' | 'ready' | 'error';
export type SourceReadReason = 'configuration' | 'denied' | 'unavailable' | 'not-fresh' | 'invalid';
interface ReadState {
  key: string;
  phase: SourceReadPhase;
  reason?: SourceReadReason;
  data?: unknown;
  readAt?: number;
}
const runtimeIds = new WeakMap<SchemaRuntime, number>();
let nextRuntimeId = 0;
function runtimeId(runtime: SchemaRuntime): number {
  if (!runtimeIds.has(runtime)) runtimeIds.set(runtime, ++nextRuntimeId);
  return runtimeIds.get(runtime)!;
}
function denied(error: unknown): boolean {
  if (!error || typeof error !== 'object') return false;
  const item = error as { status?: unknown; statusCode?: unknown; code?: unknown };
  return [item.status, item.statusCode, item.code].some((value) =>
    ['401', '403'].includes(String(value)),
  );
}

/** One subscription to the owning DMS; no polling, cache, or transport outside it. */
export function useAuthoritativeSource(
  runtime: SchemaRuntime,
  source: string | undefined,
  rawBinding: AuthoritativeSourceConfig,
  refreshInterval = 0,
) {
  const store = runtime.getStateManager().getStore(runtime.getScopeId());
  useSyncExternalStore(store.subscribe, store.getState, store.getState);
  const manager = runtime.getDataSourceManager();
  let serialized: string;
  try {
    serialized = JSON.stringify(rawBinding) ?? 'null';
  } catch {
    serialized = 'null';
  }
  const binding = useMemo(() => JSON.parse(serialized) as AuthoritativeSourceConfig, [serialized]);
  const config = source ? manager.getConfig(source) : undefined;
  let selection: ReturnType<typeof readSourceIdentity> = null;
  let configurationError = false;
  try {
    selection = readSourceIdentity(binding, runtime.getContext());
    if (
      refreshInterval !== 0 &&
      (!Number.isFinite(refreshInterval) || refreshInterval < 1000 || refreshInterval > 86400000)
    )
      configurationError = true;
    if (
      config &&
      (config.type !== 'api' || !['raw', 'commandObject'].includes(config.adaptor ?? ''))
    )
      configurationError = true;
  } catch {
    configurationError = true;
  }
  const identityKey = selection?.key;
  let sourceConfigKey = '';
  try {
    sourceConfigKey = JSON.stringify(config) ?? '';
  } catch {
    configurationError = true;
  }
  const key = JSON.stringify([
    runtimeId(runtime),
    source,
    identityKey,
    serialized,
    sourceConfigKey,
  ]);
  const [state, setState] = useState<ReadState>({ key: '', phase: 'pending' });
  const [refresh, setRefresh] = useState(0);

  useEffect(() => {
    if (!source || !config || !identityKey || configurationError) return;
    let active = true;
    let pending = false;
    let baseline = manager.getState(source);
    const current = () => {
      if (!active || !manager.getConfig(source)) return false;
      try {
        return readSourceIdentity(binding, runtime.getContext())?.key === identityKey;
      } catch {
        return false;
      }
    };
    const publish = (value: Omit<ReadState, 'key'>) => {
      if (current()) setState({ key, ...value });
    };
    const settle = (snapshot: DataSourceState | undefined) => {
      if (!current() || !snapshot) return;
      if (!manager.dependenciesReady(config)) {
        publish({ phase: 'pending' });
        return;
      }
      if (snapshot.loading) {
        baseline = snapshot;
        pending = true;
        publish({ phase: 'loading' });
      } else if (snapshot.error) {
        pending = false;
        publish({ phase: 'error', reason: denied(snapshot.error) ? 'denied' : 'unavailable' });
      } else if (pending) {
        pending = false;
        const fresh =
          Number.isFinite(snapshot.lastFetch) &&
          (snapshot.lastFetch !== baseline?.lastFetch || snapshot.data !== baseline?.data);
        if (!fresh) publish({ phase: 'error', reason: 'not-fresh' });
        else publish({ phase: 'ready', data: snapshot.data, readAt: snapshot.lastFetch! });
      }
    };
    const unsubscribe = manager.subscribe(source, settle);
    const load = async () => {
      if (!current()) return;
      if (!manager.dependenciesReady(config)) {
        publish({ phase: 'pending' });
        return;
      }
      baseline = manager.getState(source);
      pending = true;
      publish({ phase: 'loading' });
      try {
        if (baseline?.loading || baseline?.data === null || baseline?.data === undefined)
          await manager.fetch(source);
        else await manager.reload(source);
        settle(manager.getState(source));
      } catch (error) {
        pending = false;
        publish({ phase: 'error', reason: denied(error) ? 'denied' : 'unavailable' });
      }
    };
    void load();
    const timer =
      refreshInterval > 0
        ? window.setInterval(() => {
            void load();
          }, refreshInterval)
        : undefined;
    return () => {
      active = false;
      unsubscribe();
      if (timer !== undefined) window.clearInterval(timer);
    };
  }, [
    runtime,
    manager,
    source,
    config,
    identityKey,
    binding,
    key,
    configurationError,
    refreshInterval,
    refresh,
  ]);

  const visible: ReadState = configurationError
    ? { key, phase: 'error', reason: 'configuration' }
    : !source || !config
      ? { key, phase: 'unconfigured' }
      : !selection
        ? { key, phase: 'pending' }
        : state.key === key
          ? state
          : { key, phase: 'loading' };
  return {
    ...visible,
    binding,
    identity: selection,
    retry: () => setRefresh((value) => value + 1),
  };
}
