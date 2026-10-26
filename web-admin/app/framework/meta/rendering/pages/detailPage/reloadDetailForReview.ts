import type { DataSourceManager } from '~/framework/meta/runtime/data-pipeline/DataSourceManager';

/** Reuse the strict master loader and the same DMS; never turn a soft resolve into success. */
export async function reloadDetailForReview(
  loadRecordStrict: () => Promise<void>,
  manager: Pick<DataSourceManager, 'has' | 'getState' | 'reload'>,
  sourceIds: string[],
): Promise<void> {
  await loadRecordStrict();
  if (sourceIds.some((id) => !manager.has(id)))
    throw new Error('Detail read source is unavailable');
  const before = new Map(sourceIds.map((id) => [id, manager.getState(id)?.lastFetch]));
  if (sourceIds.length > 0) await manager.reload(sourceIds);
  for (const id of sourceIds) {
    const state = manager.getState(id);
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
