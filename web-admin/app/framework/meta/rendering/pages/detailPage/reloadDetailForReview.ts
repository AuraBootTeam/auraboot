import type { DataSourceManager } from '~/framework/meta/runtime/data-pipeline/DataSourceManager';

type ReviewManager = Pick<DataSourceManager, 'has' | 'getState' | 'reload' | 'subscribe'>;

function assertActive(signal?: AbortSignal): void {
  if (signal?.aborted) throw new Error('Detail review was cancelled');
}

async function waitForCurrentRead(manager: ReviewManager, id: string, signal?: AbortSignal) {
  assertActive(signal);
  if (!manager.getState(id)?.loading) return manager.getState(id);
  return new Promise<ReturnType<DataSourceManager['getState']>>((resolve, reject) => {
    let unsubscribe = () => {};
    const cleanup = () => {
      unsubscribe();
      signal?.removeEventListener('abort', abort);
    };
    const abort = () => {
      cleanup();
      reject(new Error('Detail review was cancelled'));
    };
    const inspect = () => {
      if (signal?.aborted) {
        abort();
        return;
      }
      const state = manager.getState(id);
      if (!state?.loading) {
        cleanup();
        resolve(state);
      }
    };
    unsubscribe = manager.subscribe(id, inspect);
    signal?.addEventListener('abort', abort, { once: true });
    inspect();
  });
}

/** Reuse the strict master loader and the same DMS; never turn a soft resolve into success. */
export async function reloadDetailForReview(
  loadRecordStrict: () => Promise<void>,
  manager: ReviewManager,
  sourceIds: string[],
  signal?: AbortSignal,
): Promise<void> {
  assertActive(signal);
  await loadRecordStrict();
  assertActive(signal);
  if (sourceIds.some((id) => !manager.has(id)))
    throw new Error('Detail read source is unavailable');
  const before = new Map(sourceIds.map((id) => [id, manager.getState(id)?.lastFetch]));
  if (sourceIds.length > 0) await manager.reload(sourceIds);
  for (const id of sourceIds) {
    const state = await waitForCurrentRead(manager, id, signal);
    if (
      !state ||
      state.loading ||
      state.error ||
      state.lastFetch == null ||
      state.lastFetch === before.get(id)
    ) {
      throw state?.error ?? new Error('Detail read did not confirm a new successful result');
    }
  }
}
