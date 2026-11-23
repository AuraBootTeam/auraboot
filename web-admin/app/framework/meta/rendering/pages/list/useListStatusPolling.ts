import { useEffect, useMemo, useRef, type RefObject } from 'react';
import type { ListLoadDataParams } from './listPageStateUtils';

export interface ListStatusPoll {
  intervalMs: number;
  statusField: string;
  enabledWhenStatuses: string[];
}

export function listStatusPoll(schema: any): ListStatusPoll | undefined {
  const config = schema?.extension?.poll;
  if (config === undefined) return undefined;
  if (
    !config ||
    typeof config !== 'object' ||
    Array.isArray(config) ||
    Object.keys(config).some(
      (key) => !['intervalMs', 'statusField', 'enabledWhenStatuses'].includes(key),
    ) ||
    schema?.dataSource?.type !== 'api' ||
    !schema.dataSource.endpoint ||
    !Number.isInteger(config.intervalMs) ||
    config.intervalMs < 1000 ||
    config.intervalMs > 60000 ||
    typeof config.statusField !== 'string' ||
    !/^[A-Za-z_][A-Za-z0-9_]*$/.test(config.statusField) ||
    !Array.isArray(config.enabledWhenStatuses) ||
    config.enabledWhenStatuses.length === 0 ||
    config.enabledWhenStatuses.length > 20 ||
    config.enabledWhenStatuses.some(
      (status: unknown) => typeof status !== 'string' || !status.trim(),
    ) ||
    new Set(config.enabledWhenStatuses).size !== config.enabledWhenStatuses.length
  ) {
    throw new Error('Invalid list status polling configuration');
  }
  return config;
}

/** Opt-in read-only refresh through the current loader, preserving its submitted query and race guard. */
export function useListStatusPolling({
  schema,
  data,
  dataReady,
  loading,
  error,
  skipListData,
  loadDataRef,
}: {
  schema: any;
  data: Record<string, any>[];
  dataReady: boolean;
  loading: boolean;
  error: string | null;
  skipListData: boolean;
  loadDataRef: RefObject<((params?: ListLoadDataParams) => Promise<void>) | null>;
}) {
  const config = useMemo(() => listStatusPoll(schema), [schema]);
  const current = useRef({ loading, error, loadDataRef });
  current.current = { loading, error, loadDataRef };
  const enabled = Boolean(
    config &&
    dataReady &&
    !skipListData &&
    !error &&
    data.some((row) => config.enabledWhenStatuses.includes(row[config.statusField])),
  );

  useEffect(() => {
    if (!enabled || !config) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout>;
    const schedule = () => {
      timer = setTimeout(tick, config.intervalMs);
    };
    const tick = async () => {
      if (cancelled) return;
      try {
        const state = current.current;
        if (!document.hidden && !state.loading && !state.error) {
          await state.loadDataRef.current?.({ background: true });
        }
      } finally {
        if (!cancelled) schedule();
      }
    };
    schedule();
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [config, enabled]);
}
