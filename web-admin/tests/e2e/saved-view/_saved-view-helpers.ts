/**
 * Shared saved-view test-data helpers.
 *
 * WHY: the backend caps explicit personal saved views per user+model+pageKey
 * (SavedViewServiceImpl#create enforces a default limit of 10, implicit views
 * excluded). Saved-view specs seed fresh views on every run and historically
 * never deleted them, so any long-lived campaign database accumulates past the
 * cap and every subsequent create returns 422 "Saved view limit reached for
 * personal scope: 10" — a failure class that only exists on reused databases.
 *
 * The saved-view specs already serialize file-wide on _saved-view-lock, so
 * sweeping stale views while holding that lock cannot race another saved-view
 * file's seeds. The sweep is scoped to one model+pageKey on purpose: specs
 * outside the saved-view lock (list-ux, kanban-grouping, row-height …) seed
 * their views with an empty pageKey, so a pageKey-scoped sweep never deletes a
 * concurrently-running foreign spec's view.
 */
import type { Page } from '@playwright/test';

const IMPLICIT_VIEW_NAME = /^(Default View|Implicit)/;

async function listPersonalViews(
  page: Page,
  modelCode: string,
  pageKey: string,
): Promise<Array<{ pid?: string; name?: string }>> {
  const resp = await page.request.get('/api/views/personal', {
    params: { modelCode, pageKey },
  });
  if (!resp.ok()) return [];
  const body = (await resp.json().catch(() => null)) as {
    data?: Array<{ pid?: string; name?: string }>;
  } | null;
  return Array.isArray(body?.data) ? body!.data! : [];
}

/**
 * Delete the current user's explicit personal views of `modelCode`+`pageKey`
 * (the pair the backend limit is counted on). Returns how many were deleted.
 * Implicit views ("Default View", backend-generated) are never matched.
 */
export async function sweepStaleSavedViews(
  page: Page,
  modelCode: string,
  pageKey: string,
): Promise<number> {
  const views = await listPersonalViews(page, modelCode, pageKey);
  let deleted = 0;
  for (const view of views) {
    if (!view?.pid || !view?.name || IMPLICIT_VIEW_NAME.test(view.name)) continue;
    const del = await page.request.delete(`/api/views/${view.pid}`);
    if (del.ok()) deleted += 1;
  }
  return deleted;
}

/** Create a personal view; throws with the backend message on failure. */
export async function createPersonalView(
  page: Page,
  payload: {
    name: string;
    modelCode: string;
    pageKey: string;
    viewType?: string;
    scope?: string;
    viewConfig?: Record<string, unknown>;
  },
): Promise<string> {
  const resp = await page.request.post('/api/views', {
    data: { viewType: 'table', scope: 'personal', ...payload },
  });
  if (!resp.ok()) {
    throw new Error(`create view ${payload.name} failed: ${resp.status()} ${await resp.text()}`);
  }
  const body = await resp.json();
  const pid = body?.data?.pid ?? body?.pid ?? '';
  if (!pid) throw new Error(`create view ${payload.name} returned no pid: ${JSON.stringify(body)}`);
  return pid;
}

/** Delete a view, tolerating an already-gone 404 so afterAll cleanup never fails a run. */
export async function deleteViewTolerant(page: Page, pid: string): Promise<void> {
  const resp = await page.request.delete(`/api/views/${pid}`);
  if (!resp.ok() && resp.status() !== 404) {
    throw new Error(`delete view ${pid} failed: ${resp.status()} ${await resp.text()}`);
  }
}
