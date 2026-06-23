import fs from 'node:fs';
import path from 'node:path';
import { test, expect, type Page } from '@playwright/test';
import { BACKEND_URL } from '../../helpers/environments';
import { openSavedViewManagePanel, uniqueId, waitForDynamicPageLoad } from '../helpers';
import { navigateToOrderViaSidebar } from './helpers';

const ORDER_MODEL = 'e2et_order';
const ORDER_PAGE_KEY = 'e2et_order_list';
const SHOTS = 'test-results/saved-view-follow-up-golden';

async function apiData<T>(page: Page, method: 'get' | 'post' | 'put', url: string, data?: unknown): Promise<T> {
  const resp = method === 'get'
    ? await page.request.get(url)
    : method === 'post'
      ? await page.request.post(url, { data })
      : await page.request.put(url, { data });
  const text = await resp.text();
  expect(resp.ok(), `${method.toUpperCase()} ${url} failed: ${resp.status()} ${text}`).toBe(true);
  const body = text ? JSON.parse(text) : {};
  const successCodes = new Set(['0', 'SUCCESS', 'OK']);
  if (body?.code != null && !successCodes.has(String(body.code))) {
    throw new Error(`${method.toUpperCase()} ${url} returned ${body.code}: ${body.desc ?? body.message ?? text}`);
  }
  return (body?.data ?? body) as T;
}

async function apiText(page: Page, method: 'get' | 'post', url: string, data?: unknown): Promise<string> {
  const resp = method === 'get'
    ? await page.request.get(url)
    : await page.request.post(url, { data });
  const text = await resp.text();
  expect(resp.ok(), `${method.toUpperCase()} ${url} failed: ${resp.status()} ${text}`).toBe(true);
  return text;
}

function backendBaseUrl(): string {
  return BACKEND_URL;
}

function adminStorageStatePath(): string {
  if (process.env.PW_ADMIN_STORAGE_STATE) return path.resolve(process.env.PW_ADMIN_STORAGE_STATE);
  if (process.env.PW_STORAGE_DIR) return path.resolve(process.env.PW_STORAGE_DIR, 'admin.json');
  return path.resolve('tests/storage/admin.json');
}

function adminJwtToken(): string {
  const raw = fs.readFileSync(adminStorageStatePath(), 'utf-8');
  const storage = JSON.parse(raw) as { cookies?: Array<{ name: string; value: string }> };
  const sessionCookie = storage.cookies?.find((cookie) => cookie.name === '__session');
  expect(sessionCookie?.value, 'admin storageState must include __session cookie').toBeTruthy();
  const encodedSession = decodeURIComponent(sessionCookie!.value).split('.')[0];
  const session = JSON.parse(Buffer.from(encodedSession, 'base64url').toString('utf-8')) as {
    jwtToken?: string;
  };
  expect(session.jwtToken, 'admin session cookie must include jwtToken').toBeTruthy();
  return session.jwtToken!;
}

async function backendApiText(
  page: Page,
  method: 'get' | 'post',
  url: string,
  data?: unknown,
): Promise<string> {
  const targetUrl = `${backendBaseUrl()}${url}`;
  const headers = { Authorization: `Bearer ${adminJwtToken()}` };
  const resp = method === 'get'
    ? await page.request.get(targetUrl, { headers })
    : await page.request.post(targetUrl, { data, headers });
  const text = await resp.text();
  expect(resp.ok(), `${method.toUpperCase()} ${url} failed: ${resp.status()} ${text}`).toBe(true);
  return text;
}

function extractJsonLongText(text: string, fieldName: string): string {
  const match = text.match(new RegExp(`"${fieldName}"\\s*:\\s*(?:"(\\d+)"|(\\d+))`));
  return match?.[1] ?? match?.[2] ?? '';
}

function extractJsonStringText(text: string, fieldName: string): string {
  const match = text.match(new RegExp(`"${fieldName}"\\s*:\\s*"([^"]+)"`));
  return match?.[1] ?? '';
}

async function currentUser(page: Page): Promise<{ id: string; pid: string }> {
  const text = await apiText(page, 'get', '/api/auth/me');
  const id = extractJsonLongText(text, 'id') || extractJsonLongText(text, 'userId');
  const pid = extractJsonStringText(text, 'pid') || extractJsonStringText(text, 'userPid');
  expect(id, 'current user id is required for team fixture setup').toBeTruthy();
  expect(pid, 'current user pid is required for team fixture setup').toBeTruthy();
  return { id, pid };
}

async function provisionUser(page: Page): Promise<{ userId: string; userPid: string; email: string }> {
  const suffix = uniqueId('sv_collab').replace(/_/g, '-');
  const email = `${suffix}@e2e.local`;
  // Provision through the backend directly. The BFF parses and reserializes JSON,
  // which corrupts snowflake Long ids before the team-member setup API can use them.
  const text = await backendApiText(page, 'post', '/api/admin/users', {
    email,
    displayName: `SV Collab ${suffix.slice(-24)}`,
    initialPassword: 'Test2026x',
    roleCodes: ['viewer'],
    sendInviteEmail: false,
  });
  const userId = extractJsonLongText(text, 'userId');
  const userPid = extractJsonStringText(text, 'userPid');
  expect(userId, 'provisioned userId is required for team member fixture setup').toBeTruthy();
  expect(userPid, 'provisioned userPid is required for collaborator ACL').toBeTruthy();
  return { userId, userPid, email };
}

async function createTeam(page: Page): Promise<{ pid: string; name: string }> {
  const code = uniqueId('sv_team');
  const team = await apiData<any>(page, 'post', '/api/org/teams', {
    code,
    name: `SavedView Team ${code}`,
    description: 'SavedView follow-up E2E team fixture',
  });
  expect(team?.pid, 'created team pid is required').toBeTruthy();
  return { pid: String(team.pid), name: String(team.name ?? code) };
}

async function addTeamMember(page: Page, teamPid: string, userId: string): Promise<void> {
  const targetUrl = `${backendBaseUrl()}/api/org/teams/${teamPid}/members`;
  const resp = await page.request.post(targetUrl, {
    data: { userId, role: 'member' },
    headers: { Authorization: `Bearer ${adminJwtToken()}` },
  });
  if (!resp.ok()) {
    const text = await resp.text();
    expect(text, `team member add failed for ${userId}: ${resp.status()} ${text}`).toMatch(
      /already|duplicate|exist|SUCCESS/i,
    );
  }
}

async function createTeamSavedView(
  page: Page,
  teamPid: string,
  name: string,
  viewConfig: Record<string, unknown> = {},
): Promise<string> {
  const view = await apiData<any>(page, 'post', '/api/views', {
    name,
    modelCode: ORDER_MODEL,
    pageKey: ORDER_PAGE_KEY,
    scope: 'team',
    teamId: teamPid,
    viewType: 'table',
    viewConfig,
  });
  expect(view?.pid, 'created SavedView pid is required').toBeTruthy();
  return String(view.pid);
}

async function getSavedView(page: Page, pid: string): Promise<any> {
  return apiData<any>(page, 'get', `/api/views/${pid}`);
}

function toLocalDateString(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function modifiedThisWeekPresetConfig(): Record<string, unknown> {
  const now = new Date();
  const today = toLocalDateString(now);
  const weekAgo = toLocalDateString(new Date(now.getTime() - 7 * 86400000));
  return {
    filters: [
      {
        fieldCode: 'updated_at',
        operator: 'between',
        value: { start: weekAgo, end: `${today}T23:59:59` },
      },
    ],
    meta: {
      managedBy: 'user',
      originPresetKey: 'modified_this_week',
    },
  };
}

async function findPersonalPresetSavedView(page: Page, presetKey: string): Promise<any | null> {
  const views = await apiData<any[]>(
    page,
    'get',
    `/api/views/accessible?modelCode=${ORDER_MODEL}&pageKey=${ORDER_PAGE_KEY}`,
  );
  return views.find(
    (view) =>
      String(view.scope || '').toLowerCase() === 'personal' &&
      view.viewConfig?.meta?.originPresetKey === presetKey,
  ) ?? null;
}

async function ensureModifiedThisWeekPresetCopy(page: Page): Promise<void> {
  const existing = await findPersonalPresetSavedView(page, 'modified_this_week');
  if (existing?.pid) return;

  await apiData(page, 'post', '/api/views', {
    name: `Modified This Week ${uniqueId('preset')}`,
    modelCode: ORDER_MODEL,
    pageKey: ORDER_PAGE_KEY,
    scope: 'personal',
    viewType: 'table',
    viewConfig: modifiedThisWeekPresetConfig(),
  });
}

async function createTeamViewLimit(page: Page, teamPid: string): Promise<void> {
  for (let i = 0; i < 20; i += 1) {
    await createTeamSavedView(page, teamPid, `SV Team Limit ${i + 1} ${uniqueId('quota')}`, {
      columns: [{ fieldCode: 'e2et_order_title', visible: true, order: i }],
    });
  }
}

test.describe('SavedView follow-up golden coverage', () => {
  test('SV-FU-001: team owner manages collaborators and sees audit evidence', async ({ page }) => {
    const admin = await currentUser(page);
    const collaborator = await provisionUser(page);
    const team = await createTeam(page);
    await addTeamMember(page, team.pid, admin.id);
    await addTeamMember(page, team.pid, collaborator.userId);

    const viewPid = await createTeamSavedView(
      page,
      team.pid,
      `SV Share ${uniqueId('view')}`,
      { meta: { collaborators: [] } },
    );

    await navigateToOrderViaSidebar(page);
    const panel = await openSavedViewManagePanel(page);
    await panel.getByTestId(`view-share-${viewPid}`).click();
    const sharePanel = page.getByTestId('saved-view-collaborator-panel');
    await expect(sharePanel).toBeVisible();

    await sharePanel.getByPlaceholder('Search name, email, or paste user pid').fill(collaborator.email);
    await sharePanel.getByRole('button', { name: 'Search' }).click();
    await sharePanel.getByTestId('saved-view-collaborator-user-option').first().click();
    await sharePanel.getByLabel('Collaborator permission').selectOption('save');

    const updateResponse = page.waitForResponse(
      (resp) => resp.request().method() === 'PUT' && resp.url().includes(`/api/views/${viewPid}`),
      { timeout: 10000 },
    );
    await sharePanel.getByRole('button', { name: 'Add collaborator' }).click();
    await expect((await updateResponse).ok()).toBeTruthy();

    const row = sharePanel.getByTestId('saved-view-collaborator-row').filter({
      hasText: collaborator.userPid,
    });
    await expect(row).toContainText('save');
    const updated = await getSavedView(page, viewPid);
    expect(
      updated.viewConfig?.meta?.collaborators?.some(
        (item: any) => item.principalPid === collaborator.userPid && item.permission === 'save',
      ),
    ).toBe(true);
    await page.screenshot({ path: `${SHOTS}/01-collaborator-share-panel.png`, fullPage: true });

    await panel.getByTestId(`view-audit-${viewPid}`).click();
    const auditPanel = page.getByTestId('saved-view-audit-panel');
    await expect(auditPanel).toBeVisible();
    await expect(
      auditPanel.getByTestId('saved-view-audit-event').filter({
        hasText: /Updated saved view collaborators|collaborators/i,
      }).first(),
    ).toBeVisible({ timeout: 10000 });
    await page.screenshot({ path: `${SHOTS}/02-collaborator-audit-panel.png`, fullPage: true });

    const removeResponse = page.waitForResponse(
      (resp) => resp.request().method() === 'PUT' && resp.url().includes(`/api/views/${viewPid}`),
      { timeout: 10000 },
    );
    await row.getByRole('button', { name: 'Remove' }).click();
    await expect((await removeResponse).ok()).toBeTruthy();
    await expect(row).toHaveCount(0);
    const removed = await getSavedView(page, viewPid);
    expect(
      removed.viewConfig?.meta?.collaborators?.some(
        (item: any) => item.principalPid === collaborator.userPid,
      ),
    ).toBe(false);
  });

  test('SV-FU-002: team quota limit is visible before creating another shared view', async ({ page }) => {
    const admin = await currentUser(page);
    const team = await createTeam(page);
    await addTeamMember(page, team.pid, admin.id);
    await createTeamViewLimit(page, team.pid);

    await navigateToOrderViaSidebar(page);
    const panel = await openSavedViewManagePanel(page);
    await panel.getByRole('button', { name: 'New View' }).click();
    await panel.getByLabel('Scope').selectOption('team');
    await panel.getByLabel('Team').selectOption(team.pid);

    const quotaStatus = panel.getByTestId('saved-view-quota-status');
    await expect(quotaStatus).toContainText('Team views: 20/20');
    await expect(panel.getByTestId('saved-view-quota-limit-reached')).toBeVisible();
    await expect(panel.getByRole('button', { name: 'Table' })).toBeDisabled();
    await page.screenshot({ path: `${SHOTS}/03-team-quota-limit.png`, fullPage: true });
  });

  test('SV-FU-003: quick preset saved copy shows saved, edited, and reset states', async ({ page }) => {
    await ensureModifiedThisWeekPresetCopy(page);
    await navigateToOrderViaSidebar(page);
    await expect(page.getByTestId('preset-view-bar')).toBeVisible({ timeout: 30000 });

    const presetChip = page.getByTestId('preset-view-modified_this_week');
    await expect(presetChip).toHaveAttribute('data-preset-saved', 'true', { timeout: 10000 });
    await presetChip.click();
    await page.getByTestId('preset-view-save-as-personal').click();
    await expect(page).toHaveURL(/view=[^&]+/, { timeout: 10000 });

    const viewPid = new URL(page.url()).searchParams.get('view');
    expect(viewPid).toBeTruthy();
    await expect(presetChip).toHaveAttribute('data-preset-saved', 'true');

    const currentView = await getSavedView(page, viewPid!);
    await apiData(page, 'put', `/api/views/${viewPid}`, {
      viewConfig: {
        ...(currentView.viewConfig ?? {}),
        filters: [
          {
            fieldCode: 'e2et_order_title',
            operator: 'eq',
            value: `edited-${uniqueId('preset')}`,
          },
        ],
        meta: {
          ...(currentView.viewConfig?.meta ?? {}),
          managedBy: 'user',
          originPresetKey: 'modified_this_week',
        },
      },
    });

    await page.reload({ waitUntil: 'domcontentloaded' });
    await waitForDynamicPageLoad(page);
    await expect(presetChip).toHaveAttribute('data-preset-edited', 'true');
    await expect(page.getByTestId('preset-view-reset-saved')).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/04-preset-edited-state.png`, fullPage: true });

    const resetResponse = page.waitForResponse(
      (resp) => resp.request().method() === 'PUT' && resp.url().includes(`/api/views/${viewPid}`),
      { timeout: 10000 },
    );
    await page.getByTestId('preset-view-reset-saved').click();
    await expect((await resetResponse).ok()).toBeTruthy();
    await expect(presetChip).toHaveAttribute('data-preset-edited', 'false');
    await expect(page.getByTestId('preset-view-reset-saved')).toHaveCount(0);

    const resetView = await getSavedView(page, viewPid!);
    expect(resetView.viewConfig?.filters?.some((filter: any) => filter.fieldCode === 'updated_at')).toBe(true);
    expect(resetView.viewConfig?.filters?.some((filter: any) => filter.fieldCode === 'e2et_order_title')).toBe(false);
    await page.screenshot({ path: `${SHOTS}/05-preset-reset-state.png`, fullPage: true });
  });
});
