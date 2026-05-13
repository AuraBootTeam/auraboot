import { expect, type APIRequestContext } from '@playwright/test';

export interface DefaultTableViewState {
  createdPid: string;
  previousDefaultPid: string | null;
}

interface SavedViewRecord {
  pid?: string;
}

export async function fetchDefaultSavedView(
  request: APIRequestContext,
  modelCode: string,
  pageKey: string,
): Promise<SavedViewRecord | null> {
  const resp = await request.get(
    `/api/views/default?modelCode=${encodeURIComponent(modelCode)}&pageKey=${encodeURIComponent(pageKey)}`,
  );
  if (resp.status() === 404) {
    return null;
  }
  expect(resp.ok(), `Fetch default SavedView failed: ${resp.status()}`).toBe(true);
  const body = await resp.json().catch(() => null);
  return body?.data ?? null;
}

export async function createDefaultTableView(
  request: APIRequestContext,
  modelCode: string,
  pageKey: string,
  label: string,
): Promise<DefaultTableViewState> {
  const previousDefault = await fetchDefaultSavedView(request, modelCode, pageKey);
  const name = `E2E ${label} Table ${Date.now()} ${Math.random().toString(36).slice(2, 6)}`;
  const resp = await request.post('/api/views', {
    data: {
      name,
      modelCode,
      pageKey,
      scope: 'personal',
      viewType: 'table',
      isDefault: true,
      viewConfig: {},
    },
  });
  const body = await resp.json().catch(async () => resp.text().catch(() => null));
  expect(resp.ok(), `Create default table SavedView failed: ${resp.status()} ${JSON.stringify(body)}`).toBe(true);
  const createdPid = body?.data?.pid;
  expect(createdPid, `Created default table SavedView missing pid: ${JSON.stringify(body)}`).toBeTruthy();
  return {
    createdPid,
    previousDefaultPid: previousDefault?.pid ?? null,
  };
}

export async function restoreDefaultTableView(
  request: APIRequestContext,
  state: DefaultTableViewState | null,
): Promise<void> {
  if (!state) {
    return;
  }
  if (state.previousDefaultPid) {
    await request.post(`/api/views/${state.previousDefaultPid}/set-default`).catch(() => null);
  }
  await request.delete(`/api/views/${state.createdPid}`).catch(() => null);
}
