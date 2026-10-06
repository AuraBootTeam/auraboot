// Real-stack browser workflow acceptance. No API or response mocks.
// An executed result and original screenshot review are required for acceptance.
import { test, expect, request as requestFactory, type Page, type APIResponse, type APIRequestContext } from '@playwright/test';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { loginViaUI } from '../../helpers/auth-fixtures';
import { openAsRole, makeRoleUser, ensureRoleUser, fetchRoleSnapshot } from '../rbac/rbac-helpers';
import { ensureSidebarExpanded } from '../helpers';

type Resource = { pid: string; i18nKey: string; value: string; source: string; status: string; rejectReason: string | null; reviewedAt: string | null; reviewedBy?: string | null; refType?: string | null; refId?: string | null };
async function accepted<T>(response: Pick<APIResponse, 'ok' | 'status' | 'url' | 'json'>): Promise<T> {
  expect(response.ok(), `HTTP ${response.status()} from ${response.url()}`).toBe(true);
  const envelope = await response.json();
  expect(envelope.code, JSON.stringify(envelope)).toBe('0');
  return envelope.data as T;
}
async function read(page: Page, pid: string) {
  return accepted<Resource | null>(await page.request.get(`/api/admin/i18n/resources/${pid}`));
}
async function packValue(page: Page, key: string) {
  const pack = await accepted<Record<string, string>>(await page.request.get('/api/i18n/zh-CN'));
  return pack[key];
}
async function openSidebarPage(page: Page, path: string) {
  await ensureSidebarExpanded(page);
  // Reachability is part of the acceptance: do not bypass the sidebar by goto.
  const entry = page.locator(`nav a[href="${path}"]`);
  await expect(entry).toHaveCount(1);
  // SidebarSubmenu mounts children even when their parent is collapsed.
  // Open the actual ancestor buttons, outermost first, without forcing a click.
  const parents = entry.locator('xpath=ancestor::div[./button]/button');
  for (let i = 0; i < await parents.count(); i++) {
    const parent = parents.nth(i);
    if ((await parent.locator('xpath=../div').getAttribute('class'))?.includes('max-h-0')) {
      await expect(parent).toBeVisible();
      await parent.click();
    }
  }
  await expect(entry).toBeVisible();
  await entry.click();
  await expect(page).toHaveURL(url => url.pathname === path);
}
async function openResources(page: Page) {
  await openSidebarPage(page, '/i18n-resources');
  await expect(page.getByTestId('i18n-resources-page')).toBeVisible();
}
async function filter(page: Page, prefix: string) {
  await page.getByLabel('lang', { exact: true }).selectOption('zh-CN');
  await page.getByPlaceholder(/^(key 前缀，如 menu\.|key prefix, e\.g\. menu\.)$/).fill(prefix);
  // Deterministically wait for the filtered list response; asserting the table
  // before it lands races the async fetch and reads the unfiltered page.
  const list = page.waitForResponse(r => r.url().includes('/api/admin/i18n/resources?') && r.request().method() === 'GET');
  await page.getByRole('button', { name: /^(查询|Search)$/ }).click();
  await list;
}
function row(page: Page, key: string) {
  return page.getByTestId('i18n-resources-table').getByRole('row').filter({ has: page.getByRole('cell', { name: key, exact: true }) });
}
const statusLabel = { draft: /^(草稿|Draft)$/, review: /^(待审核|Pending Review)$/, approved: /^(已批准|Approved)$/ };
async function persisted(page: Page, pid: string, status: keyof typeof statusLabel) {
  // Poll read-only persistence; there are no mutation retries.
  await expect.poll(async () => (await read(page, pid))?.status).toBe(status);
  const resource = await read(page, pid);
  expect(resource).not.toBeNull();
  await expect(row(page, resource!.i18nKey).getByText(statusLabel[status])).toBeVisible();
  return resource!;
}

test.describe('i18n admin real workflow', () => {
  test.describe.configure({ timeout: 120_000, retries: 0 });

  test('AI placeholder drafts match persisted counts and can be reviewed through the settings UI', async ({ browser }, info) => {
    expect(process.env.AGENT_LLM_STUB_MODE, 'this case requires explicit no-paid-provider mode').toBe('true');
    const { context, page } = await openAsRole(browser, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
    const key = `000000.e2e.ios-handover.${Date.now()}.${info.workerIndex}.ai`;
    const sourceValue = 'Source placeholder requiring human translation';
    try {
      await accepted(await page.request.post('/api/admin/i18n/resources', { data: { key, lang: 'zh-CN', value: sourceValue } }));
      const existingKey = `${key}.existing`;
      await accepted(await page.request.post('/api/admin/i18n/resources', { data: { key: existingKey, lang: 'zh-CN', value: 'Existing source' } }));
      const existing = await accepted<Resource>(await page.request.post('/api/admin/i18n/resources', { data: {
        key: existingKey, lang: 'ja-JP', value: 'Previously approved target wording',
      } }));
      const existingBefore = await read(page, existing.pid);
      expect(await accepted(await page.request.get('/api/admin/i18n/resources/by-key', { params: { key, lang: 'ja-JP' } }))).toBeNull();
      const coverage = await accepted<{ missingKeys: Array<{ key: string }> }>(await page.request.get('/api/admin/i18n/coverage'));
      expect(coverage.missingKeys.map(entry => entry.key), 'fixture must be inside the ordered missing-key sample before generation').toContain(key);
      const total = async () => Number((await accepted<{ total: number }>(await page.request.get('/api/admin/i18n/resources', {
        params: { lang: 'ja-JP', pageNum: 1, pageSize: 1 },
      }))).total);
      const before = await total();
      await openSidebarPage(page, '/settings/i18n-workflow');
      await expect(page.getByRole('heading', { name: 'Translation Workflow', exact: true })).toBeVisible();
      await page.getByRole('button', { name: 'AI Generate Drafts', exact: true }).click();
      const modal = page.getByRole('heading', { name: 'AI Generate Drafts', exact: true }).locator('xpath=ancestor::div[contains(@class,"max-w-md")]');
      await expect(modal).toBeVisible();
      await expect(modal.locator('select')).toHaveValue('ja-JP');
      await modal.getByRole('spinbutton').fill('200');
      const generation = page.waitForResponse(response => response.url().endsWith('/api/admin/i18n/ai-translate') && response.request().method() === 'POST');
      await modal.getByRole('button', { name: 'Generate Drafts', exact: true }).click();
      const response = await generation;
      expect(response.request().postDataJSON()).toEqual({ targetLocale: 'ja-JP', sourceLocale: 'zh-CN', maxKeys: 200 });
      const result = await accepted<{ generated: number; skipped: number; errors: number; llmUsed: boolean }>(response);
      expect(result.llmUsed).toBe(false);
      expect(result.generated).toBeGreaterThan(0);
      expect(result.generated).toBeLessThanOrEqual(200);
      expect(result.errors).toBe(0);
      expect(result.skipped).toBe(0);
      expect(await total() - before).toBe(result.generated);
      const draft = await accepted<Resource>(await page.request.get('/api/admin/i18n/resources/by-key', { params: { key, lang: 'ja-JP' } }));
      expect(draft).toMatchObject({ i18nKey: key, lang: 'ja-JP', value: sourceValue, status: 'draft', source: 'ai' });
      expect(await read(page, existing.pid)).toEqual(existingBefore);
      await expect(modal.getByText(`+${result.generated} generated`, { exact: true })).toBeVisible();
      await expect(modal.getByText('Fallback: used source locale values as placeholder drafts', { exact: true })).toBeVisible();
      await info.attach('AI-placeholder-summary-original', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });
      await info.attach('AI-placeholder-persistence', { body: Buffer.from(JSON.stringify({ before, after: before + result.generated, result, draftPid: draft.pid, unchangedTargetPid: existing.pid }, null, 2)), contentType: 'application/json' });
      await modal.getByRole('button', { name: 'Close', exact: true }).click();
      const status = page.locator('select').filter({ has: page.locator('option[value="draft"]') });
      await status.selectOption('draft');
      await page.getByPlaceholder('Search by key or value…', { exact: true }).fill(key);
      await page.getByRole('button', { name: 'Search', exact: true }).click();
      const workflowRow = page.locator('table').getByRole('row').filter({ has: page.getByRole('cell', { name: key, exact: true }) });
      await expect(workflowRow).toHaveCount(1);
      await workflowRow.getByRole('button', { name: 'Submit for Review', exact: true }).click();
      await expect.poll(async () => (await read(page, draft.pid))?.status).toBe('review');
      await status.selectOption('review');
      await expect(workflowRow).toHaveCount(1);
      const packBefore = await accepted<Record<string, string>>(await page.request.get('/api/i18n/ja-JP'));
      expect(packBefore[key]).toBeUndefined();
      await workflowRow.getByRole('button', { name: 'Approve', exact: true }).click();
      await expect.poll(async () => (await read(page, draft.pid))?.status).toBe('approved');
      const packAfter = await accepted<Record<string, string>>(await page.request.get('/api/i18n/ja-JP'));
      expect(packAfter[key]).toBe(sourceValue);
      await status.selectOption('approved');
      await expect(workflowRow.getByText(sourceValue, { exact: true })).toBeVisible();
      await info.attach('AI-placeholder-approved-original', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });
    } finally { await context.close(); }
  });

  test('same-role tenant administrators cannot read or mutate each other translation resources', async ({ browser }, info) => {
    const backend = process.env.BACKEND_URL;
    expect(backend, 'an explicit real backend URL is required').toBeTruthy();
    const admin = await openAsRole(browser, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
    const clients: APIRequestContext[] = [];
    const prefix = `e2e.ios-handover.${Date.now()}.${info.workerIndex}.tenant.`;
    const identities: Array<{ tenantId: string; userId: string; roleCodes: string[] }> = [];
    const fixtures: Resource[] = [];
    const outcomes: Array<{ tenantId: string; foreignPid: string; method: string; code: string }> = [];
    try {
      for (const label of ['a', 'b']) {
        const tenant = await accepted<{ tenantId: string; jwt: string }>(await admin.page.request.post('/api/tenant-selection/process', {
          data: { action: 'create', tenantName: `${prefix}${label}`.replaceAll('.', '-'), displayName: `Translation isolation ${label}` },
          timeout: 60_000,
        }));
        expect(tenant.jwt).toBeTruthy();
        const client = await requestFactory.newContext({ baseURL: backend, extraHTTPHeaders: { Authorization: `Bearer ${tenant.jwt}` } });
        clients.push(client);
        const identity = await accepted<{ user: { id: string; tenantId: string }; permissions: { roles: Array<{ code: string }>; permissionCodes: string[] } }>(await client.get('/api/auth/me'));
        const roleCodes = identity.permissions.roles.map(role => role.code).sort();
        expect(roleCodes).toContain('tenant_admin');
        expect(roleCodes).not.toContain('platform_admin');
        expect(identity.permissions.permissionCodes).toContain('system_management');
        expect(String(identity.user.tenantId)).toBe(String(tenant.tenantId));
        identities.push({ tenantId: String(tenant.tenantId), userId: String(identity.user.id), roleCodes });
        const resource = await accepted<Resource>(await client.post('/api/admin/i18n/resources', { data: {
          key: `${prefix}shared`, lang: 'zh-CN', value: `Tenant ${label} private wording`,
        } }));
        await accepted(await client.put(`/api/admin/i18n/resources/${resource.pid}/status`, { data: { status: 'draft' } }));
        fixtures.push(await accepted<Resource>(await client.post(`/api/admin/i18n/resources/${resource.pid}/submit-review`)));
      }
      expect(identities[0].tenantId).not.toBe(identities[1].tenantId);
      expect(identities[0].userId).toBe(identities[1].userId);
      expect(identities[0].roleCodes).toEqual(identities[1].roleCodes);
      expect(fixtures[0].pid).not.toBe(fixtures[1].pid);
      for (let i = 0; i < clients.length; i++) {
        const client = clients[i];
        const foreign = fixtures[1 - i];
        const owner = clients[1 - i];
        const before = await accepted<Resource>(await owner.get(`/api/admin/i18n/resources/${foreign.pid}`));
        expect(await accepted(await client.get(`/api/admin/i18n/resources/${foreign.pid}`))).toBeNull();
        const listed = await accepted<{ records: Resource[]; total: number }>(await client.get('/api/admin/i18n/resources', {
          params: { lang: 'zh-CN', keyPrefix: prefix, pageNum: 1, pageSize: 20 },
        }));
        expect(Number(listed.total)).toBe(1);
        expect(listed.records.map(resource => resource.pid)).toEqual([fixtures[i].pid]);
        const mutations = [
          { method: 'PUT', suffix: '', data: { value: 'Cross-tenant overwrite' } },
          { method: 'PUT', suffix: '/status', data: { status: 'draft' } },
          { method: 'POST', suffix: '/submit-review' },
          { method: 'POST', suffix: '/approve' },
          { method: 'POST', suffix: '/reject', data: { reason: 'Cross-tenant rejection' } },
        ];
        for (const mutation of mutations) {
          const response = await client.fetch(`/api/admin/i18n/resources/${foreign.pid}${mutation.suffix}`, { method: mutation.method, data: mutation.data });
          const envelope = await response.json();
          expect(envelope.code, `${mutation.method} foreign resource${mutation.suffix}`).not.toBe('0');
          expect(await accepted(await owner.get(`/api/admin/i18n/resources/${foreign.pid}`))).toEqual(before);
          outcomes.push({ tenantId: identities[i].tenantId, foreignPid: foreign.pid, method: `${mutation.method}${mutation.suffix}`, code: envelope.code });
        }
        // Missing-PID DELETE is intentionally idempotent; it must not delete the foreign row.
        await accepted(await client.delete(`/api/admin/i18n/resources/${foreign.pid}`));
        expect(await accepted(await owner.get(`/api/admin/i18n/resources/${foreign.pid}`))).toEqual(before);
      }
      await info.attach('same-role-cross-tenant-isolation', { body: Buffer.from(JSON.stringify({ identities, fixtures: fixtures.map(resource => ({ pid: resource.pid, key: resource.i18nKey })), outcomes }, null, 2)), contentType: 'application/json' });
    } finally {
      for (const client of clients) await client.dispose();
      await admin.context.close();
    }
  });

  test('prefix and keyword filters preserve all 21 records across both pages', async ({ browser }, info) => {
    const { context, page } = await openAsRole(browser, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
    const prefix = `e2e.ios-handover.${Date.now()}.${info.workerIndex}.pages.`;
    const resources: Resource[] = [];
    try {
      for (let i = 0; i < 21; i++) {
        resources.push(await accepted<Resource>(await page.request.post('/api/admin/i18n/resources', {
          data: { key: `${prefix}${String(i).padStart(2, '0')}`, lang: 'zh-CN', value: i === 20 ? 'Unique last-page wording' : `Page wording ${i}` },
        })));
      }
      const english = await accepted<Resource>(await page.request.post('/api/admin/i18n/resources', {
        data: { key: `${prefix}english`, lang: 'en-US', value: 'English-only filter fixture' },
      }));
      await openResources(page);
      await filter(page, prefix);
      const rows = page.getByTestId('i18n-resources-table').locator('tbody tr');
      await expect(rows).toHaveCount(20);
      await expect(page.getByText('1 / 2', { exact: true })).toBeVisible();
      const observed = new Set(await rows.locator('td:first-child').allTextContents());
      await page.getByRole('button', { name: /^(下一页|Next)$/ }).click();
      await expect(page.getByText('2 / 2', { exact: true })).toBeVisible();
      await expect(rows).toHaveCount(1);
      for (const key of await rows.locator('td:first-child').allTextContents()) {
        expect(observed.has(key), 'pagination must not duplicate a first-page key').toBe(false);
        observed.add(key);
      }
      expect([...observed].sort()).toEqual(resources.map(r => r.i18nKey).sort());
      await page.getByPlaceholder(/^(关键字|keyword)$/).fill('Unique last-page wording');
      await page.getByRole('button', { name: /^(查询|Search)$/ }).click();
      await expect(page.getByText('1 / 1', { exact: true })).toBeVisible();
      await expect(rows).toHaveCount(1);
      await expect(row(page, `${prefix}20`)).toBeVisible();
      await expect(page.getByRole('button', { name: /^(下一页|Next)$/ })).toBeDisabled();
      await info.attach('filtered-pagination-original', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });
      await page.getByPlaceholder(/^(关键字|keyword)$/).fill('');
      await page.getByLabel('lang', { exact: true }).selectOption('en-US');
      await page.getByRole('button', { name: /^(查询|Search)$/ }).click();
      await expect(rows).toHaveCount(1);
      await expect(row(page, english.i18nKey)).toBeVisible();
      await expect(page.getByText('1 / 1', { exact: true })).toBeVisible();
    } finally { await context.close(); }
  });

  test('authenticated baseline member is denied every admin CRUD and review endpoint', async ({ browser }, info) => {
    const admin = await openAsRole(browser, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
    const suffix = `${Date.now()}-${info.workerIndex}`;
    const user = makeRoleUser(`ios-i18n-member-${suffix}`, ['tenant_member']);
    let member: Awaited<ReturnType<typeof openAsRole>> | undefined;
    try {
      await ensureRoleUser(admin.page, user);
      // A fresh runtime seeds no menu for the member's L1 read codes, which renders no
      // sidebar at all (LeftSidebar returns null on an empty menu tree) and would make the
      // negative-navigation assertions below vacuous. Provision one real read-only entry
      // through the admin menu API so the member sidebar is a genuinely filtered nonempty
      // tree; admin-tier entries must still stay hidden.
      const memberMenuPath = '/meta/models';
      // ab_menu.pid is NOT NULL UNIQUE with no generator on the create path, so the
      // fixture supplies its own 26-character identifier; the create envelope echoes it.
      const memberMenuPid = `m${Date.now()}${info.workerIndex}membermenu`.padEnd(26, '0').slice(0, 26);
      const memberMenu = await accepted<{ pid: string }>(await admin.page.request.post('/api/menu/create', { data: {
        pid: memberMenuPid,
        code: `e2e.ios-handover.${suffix}.member-models`,
        name: 'Member Baseline Models',
        path: memberMenuPath,
        type: 1,
        permissionCode: 'meta.model.read',
        visible: true,
        orderNo: 900,
      } }));
      expect(memberMenu.pid).toBeTruthy();
      const resource = await accepted<Resource>(await admin.page.request.post('/api/admin/i18n/resources', { data: {
        key: `e2e.ios-handover.${suffix}.permission`, lang: 'zh-CN', value: 'Permission fixture',
      } }));
      const persistedBeforeDenials = await read(admin.page, resource.pid);
      expect(persistedBeforeDenials).toMatchObject({ pid: resource.pid, value: 'Permission fixture', status: 'approved' });
      expect(await packValue(admin.page, resource.i18nKey)).toBe('Permission fixture');
      member = await openAsRole(browser, user.email, user.password);
      const snapshot = await fetchRoleSnapshot(member.page);
      expect(snapshot.roleCodes).toEqual(['tenant_member']);
      expect(snapshot.permissionCodes).not.toContain('system_management');
      // Negative navigation requires a loaded, nonempty authenticated sidebar;
      // missing admin links in an empty/error shell must not pass acceptance.
      await ensureSidebarExpanded(member.page);
      await expect(member.page.getByTestId('sidebar')).toBeVisible();
      await expect.poll(() => member!.page.locator('nav a[href]').count()).toBeGreaterThan(0);
      const loadedSnapshot = await fetchRoleSnapshot(member.page);
      expect(loadedSnapshot.roleCodes).toEqual(['tenant_member']);
      expect(loadedSnapshot.menuPaths.length).toBeGreaterThan(0);
      // The provisioned L1 entry proves the member sidebar is a positively filtered
      // menu tree, not an accidental empty shell that merely lacks admin links.
      expect(loadedSnapshot.menuPaths, `member menu must resolve ${memberMenuPath}`).toContain(memberMenuPath);
      await expect(member.page.locator(`nav a[href="${memberMenuPath}"]`)).toHaveCount(1);
      for (const path of ['/i18n-resources', '/settings/i18n-workflow']) {
        expect(loadedSnapshot.menuPaths, `member menu must exclude ${path}`).not.toContain(path);
        await expect(member.page.locator(`nav a[href="${path}"]`)).toHaveCount(0);
      }
      await info.attach('authenticated-member-navigation-original', {
        body: await member.page.screenshot({ fullPage: true }), contentType: 'image/png',
      });
      await info.attach('authenticated-member-menu-snapshot', {
        body: Buffer.from(JSON.stringify(loadedSnapshot, null, 2)), contentType: 'application/json',
      });
      const requests = [
        { method: 'GET', path: '/api/admin/i18n/resources' },
        { method: 'GET', path: `/api/admin/i18n/resources/${resource.pid}` },
        { method: 'POST', path: '/api/admin/i18n/resources', data: { key: `${resource.i18nKey}.forbidden`, lang: 'zh-CN', value: 'Forbidden create' } },
        { method: 'PUT', path: `/api/admin/i18n/resources/${resource.pid}`, data: { value: 'Forbidden update' } },
        { method: 'DELETE', path: `/api/admin/i18n/resources/${resource.pid}` },
        { method: 'POST', path: `/api/admin/i18n/resources/${resource.pid}/submit-review` },
        { method: 'POST', path: `/api/admin/i18n/resources/${resource.pid}/approve` },
        { method: 'POST', path: `/api/admin/i18n/resources/${resource.pid}/reject`, data: { reason: 'Forbidden reject' } },
        { method: 'PUT', path: `/api/admin/i18n/resources/${resource.pid}/status`, data: { status: 'draft' } },
        { method: 'POST', path: '/api/admin/i18n/ai-translate', data: { targetLocale: 'en-US', sourceLocale: 'zh-CN', maxKeys: 1 } },
      ];
      const denied = [];
      for (const request of requests) {
        const response = await member.page.request.fetch(request.path, { method: request.method, data: request.data });
        // The canonical coarse admin-role guard precedes permission evaluation.
        // Its denial is HTTP 200 with the exact business envelope, not HTTP 403.
        expect(response.status(), `${request.method} ${request.path}`).toBe(200);
        const envelope = await response.json();
        expect(envelope.code).toBe('409');
        expect(envelope.message).toBe('admin role required');
        denied.push({ method: request.method, path: request.path, httpStatus: response.status(), code: envelope.code });
      }
      expect(await read(admin.page, resource.pid)).toEqual(persistedBeforeDenials);
      expect(await packValue(admin.page, resource.i18nKey)).toBe('Permission fixture');
      expect(await packValue(admin.page, `${resource.i18nKey}.forbidden`)).toBeUndefined();
      expect(await accepted<Resource | null>(await admin.page.request.get('/api/admin/i18n/resources/by-key', {
        params: { key: `${resource.i18nKey}.forbidden`, lang: 'zh-CN' },
      }))).toBeNull();
      await info.attach('authenticated-member-denials', { body: Buffer.from(JSON.stringify(denied, null, 2)), contentType: 'application/json' });
    } finally { await member?.context.close(); await admin.context.close(); }
  });

  test('create, edit, submit, reject with reason, approve and delete through the admin UI', async ({ browser }, info) => {
    const { context, page } = await openAsRole(browser, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
    const key = `e2e.ios-handover.${Date.now()}.${info.workerIndex}.journey`;
    try {
      const reviewer = await accepted<{ user: { id: string } }>(await page.request.get('/api/auth/me'));
      await openResources(page);
      await filter(page, key);
      await expect(row(page, key)).toHaveCount(0);
      // Warm the actual pack cache before mutation; a fresh uncached read alone
      // would not prove that the mutation invalidates a previously loaded pack.
      expect(await packValue(page, key)).toBeUndefined();
      await page.getByPlaceholder('key', { exact: true }).fill(key);
      await page.getByLabel('new lang', { exact: true }).selectOption('zh-CN');
      await page.getByPlaceholder(/^(文案|value)$/).fill('Original handover translation');
      const creation = page.waitForResponse(r => r.url().endsWith('/api/admin/i18n/resources') && r.request().method() === 'POST');
      await page.getByRole('button', { name: /^(新增|Create)$/ }).click();
      const created = await accepted<Resource>(await creation);
      expect(created.i18nKey).toBe(key);
      expect(created.value).toBe('Original handover translation');
      // Current product contract: a privileged manual create is approved.
      await persisted(page, created.pid, 'approved');
      expect(await packValue(page, key)).toBe('Original handover translation');
      await expect(page.getByPlaceholder('key', { exact: true })).toHaveValue('');
      await openSidebarPage(page, '/settings/i18n-workflow');
      await openResources(page);
      await filter(page, key);
      await expect(row(page, key).getByText('Original handover translation', { exact: true })).toBeVisible();

      // Supported admin fixture operation establishes the draft under review.
      await accepted(await page.request.put(`/api/admin/i18n/resources/${created.pid}/status`, { data: { status: 'draft' } }));
      await filter(page, key);
      const draftBeforeEdit = await persisted(page, created.pid, 'draft');
      expect(await packValue(page, key)).toBeUndefined();
      await row(page, key).getByLabel(`edit-${key}`, { exact: true }).click();
      await row(page, key).getByRole('textbox').fill('Revised handover translation');
      await row(page, key).getByRole('button', { name: /^(保存|Save)$/ }).click();
      const editedDraft = await persisted(page, created.pid, 'draft');
      expect(editedDraft.value).toBe('Revised handover translation');
      expect([editedDraft.source, editedDraft.refType, editedDraft.refId]).toEqual([draftBeforeEdit.source, draftBeforeEdit.refType, draftBeforeEdit.refId]);
      expect(await packValue(page, key)).toBeUndefined();
      await expect(row(page, key).getByLabel(`approve-${key}`, { exact: true })).toHaveCount(0);
      await row(page, key).getByLabel(`submit-review-${key}`, { exact: true }).click();
      await persisted(page, created.pid, 'review');
      expect(await packValue(page, key)).toBeUndefined();
      await row(page, key).getByLabel(`reject-${key}`, { exact: true }).click();
      const dialog = page.getByRole('dialog');
      const reason = dialog.getByLabel(/^(驳回原因（必填）|Rejection reason \(required\))$/);
      const reject = dialog.getByRole('button', { name: /^(驳回|Reject)$/ });
      await expect(reject).toBeDisabled();
      await reason.fill('   ');
      await expect(reject).toBeDisabled();
      expect((await read(page, created.pid))?.status).toBe('review');
      const beforeEmptyReject = await read(page, created.pid);
      const emptyReject = await page.request.post(`/api/admin/i18n/resources/${created.pid}/reject`, { data: { reason: '   ' } });
      expect(emptyReject.status()).toBe(400);
      expect((await emptyReject.json()).code).toBe('35000');
      expect(await read(page, created.pid)).toEqual(beforeEmptyReject);
      await reason.fill('Please clarify this wording');
      await reject.click();
      await expect(dialog).toHaveCount(0);
      const rejected = await persisted(page, created.pid, 'draft');
      expect(rejected.rejectReason).toBe('Please clarify this wording');
      expect(Number.isFinite(Date.parse(rejected.reviewedAt!))).toBe(true);
      await info.attach('rejected-original', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });
      await openSidebarPage(page, '/settings/i18n-workflow');
      // The workflow table filters by locale (page default ja-JP); the fixture resource
      // was created as zh-CN, so a real reviewer selects the matching locale first.
      await page.locator('select').filter({ has: page.locator('option[value="zh-CN"]') }).selectOption('zh-CN');
      await page.locator('select').filter({ has: page.locator('option[value="draft"]') }).selectOption('draft');
      await page.getByPlaceholder('Search by key or value…', { exact: true }).fill(key);
      await page.getByRole('button', { name: 'Search', exact: true }).click();
      const rejectedWorkflowRow = page.locator('table').getByRole('row').filter({ has: page.getByRole('cell', { name: key, exact: true }) });
      await expect(rejectedWorkflowRow.getByText('Please clarify this wording')).toBeVisible();
      await info.attach('workflow-rejection-feedback-original', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });
      await openResources(page);
      await filter(page, key);

      await row(page, key).getByLabel(`submit-review-${key}`, { exact: true }).click();
      await persisted(page, created.pid, 'review');
      await row(page, key).getByLabel(`approve-${key}`, { exact: true }).click();
      const approved = await persisted(page, created.pid, 'approved');
      expect(approved.rejectReason).toBeNull();
      expect(String(approved.reviewedBy)).toBe(String(reviewer.user.id));
      expect(Number.isFinite(Date.parse(approved.reviewedAt!))).toBe(true);
      expect(await packValue(page, key)).toBe('Revised handover translation');
      await info.attach('approved-original', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });
      await expect(row(page, key).getByLabel(`submit-review-${key}`, { exact: true })).toHaveCount(0);
      const illegalSubmit = await page.request.post(`/api/admin/i18n/resources/${created.pid}/submit-review`);
      expect(illegalSubmit.status()).toBe(400);
      expect((await illegalSubmit.json()).code).toBe('35000');
      expect(await read(page, created.pid)).toEqual(approved);
      await row(page, key).getByLabel(`edit-${key}`, { exact: true }).click();
      await row(page, key).getByRole('textbox').fill('Final approved handover translation');
      await row(page, key).getByRole('button', { name: /^(保存|Save)$/ }).click();
      const editedApproved = await persisted(page, created.pid, 'approved');
      expect(editedApproved.value).toBe('Final approved handover translation');
      expect([editedApproved.source, editedApproved.refType, editedApproved.refId]).toEqual([approved.source, approved.refType, approved.refId]);
      expect(await packValue(page, key)).toBe('Final approved handover translation');

      await row(page, key).getByLabel(`delete-${key}`, { exact: true }).click();
      await page.getByRole('dialog').getByRole('button', { name: /^(取消|Cancel)$/ }).click();
      expect((await read(page, created.pid))?.pid).toBe(created.pid);
      await row(page, key).getByLabel(`delete-${key}`, { exact: true }).click();
      await page.getByRole('dialog').getByRole('button', { name: /^(确认|Confirm)$/ }).click();
      await expect.poll(() => read(page, created.pid)).toBeNull();
      await expect(row(page, key)).toHaveCount(0);
      expect(await packValue(page, key)).toBeUndefined();
    } finally { await context.close(); }
  });

  test('real stale-review rejection preserves reason and dialog on a business failure', async ({ browser }, info) => {
    const { context, page } = await openAsRole(browser, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
    const key = `e2e.ios-handover.${Date.now()}.${info.workerIndex}.stale`;
    try {
      const resource = await accepted<Resource>(await page.request.post('/api/admin/i18n/resources', { data: { key, lang: 'zh-CN', value: 'Concurrent review fixture' } }));
      await accepted(await page.request.put(`/api/admin/i18n/resources/${resource.pid}/status`, { data: { status: 'draft' } }));
      await accepted(await page.request.post(`/api/admin/i18n/resources/${resource.pid}/submit-review`));
      await openResources(page);
      await filter(page, key);
      await persisted(page, resource.pid, 'review');
      await row(page, key).getByLabel(`reject-${key}`, { exact: true }).click();
      const dialog = page.getByRole('dialog');
      const reason = dialog.getByLabel(/^(驳回原因（必填）|Rejection reason \(required\))$/);
      await reason.fill('Preserve this reason after the concurrent approval');
      // Actual concurrent state transition. No route interception or fake envelope.
      await accepted(await page.request.post(`/api/admin/i18n/resources/${resource.pid}/approve`));
      const approvedBeforeFailure = await read(page, resource.pid);
      expect(approvedBeforeFailure?.status).toBe('approved');
      const failure = page.waitForResponse(r => r.url().endsWith(`/resources/${resource.pid}/reject`) && r.request().method() === 'POST');
      await dialog.getByRole('button', { name: /^(驳回|Reject)$/ }).click();
      const response = await failure;
      const body = await response.json();
      expect(response.status()).toBe(400);
      expect(body.code, JSON.stringify(body)).toBe('35000');
      expect(typeof body.message).toBe('string');
      expect(body.message.trim().length).toBeGreaterThan(0);
      await expect(dialog).toBeVisible();
      await expect(reason).toHaveValue('Preserve this reason after the concurrent approval');
      await expect(dialog.getByRole('alert')).toHaveText(body.message);
      expect(await read(page, resource.pid)).toEqual(approvedBeforeFailure);
      await info.attach('business-failure-original', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });

      // A real second-tab login changes the shared httpOnly session while the
      // first tab retains its already-open admin UI. No response interception.
      await dialog.getByRole('button', { name: /^(取消|Cancel)$/ }).click();
      const deniedKey = `${key}.session-change`;
      const deniedResource = await accepted<Resource>(await page.request.post('/api/admin/i18n/resources', {
        data: { key: deniedKey, lang: 'zh-CN', value: 'Session change review fixture' },
      }));
      await accepted(await page.request.put(`/api/admin/i18n/resources/${deniedResource.pid}/status`, { data: { status: 'draft' } }));
      await accepted(await page.request.post(`/api/admin/i18n/resources/${deniedResource.pid}/submit-review`));
      const member = makeRoleUser(`ios-i18n-session-change-${Date.now()}-${info.workerIndex}`, ['tenant_member']);
      await ensureRoleUser(page, member);
      await filter(page, deniedKey);
      const reviewBeforeDenial = await persisted(page, deniedResource.pid, 'review');
      const packBeforeDenial = await packValue(page, deniedKey);
      await row(page, deniedKey).getByLabel(`reject-${deniedKey}`, { exact: true }).click();
      const preservedReason = 'Preserve this reason after the session changes in another tab';
      await reason.fill(preservedReason);
      const sessionPage = await context.newPage();
      await loginViaUI(sessionPage, member.email, member.password);
      const memberIdentity = await accepted<{ user: { email: string } }>(await sessionPage.request.get('/api/auth/me'));
      expect(memberIdentity.user.email).toBe(member.email);
      const memberSnapshot = await fetchRoleSnapshot(sessionPage);
      expect(memberSnapshot.roleCodes).toEqual(['tenant_member']);
      expect(memberSnapshot.permissionCodes).not.toContain('system_management');
      await expect(dialog).toBeVisible();
      await expect(reason).toHaveValue(preservedReason);
      const deniedResponse = page.waitForResponse(r => r.url().endsWith(`/resources/${deniedResource.pid}/reject`) && r.request().method() === 'POST');
      await dialog.getByRole('button', { name: /^(驳回|Reject)$/ }).click();
      const denied = await deniedResponse;
      const deniedBody = await denied.json();
      expect(denied.status()).toBe(200);
      expect(deniedBody.code, JSON.stringify(deniedBody)).toBe('409');
      expect(deniedBody.message).toBe('admin role required');
      await expect(dialog).toBeVisible();
      await expect(reason).toHaveValue(preservedReason);
      await expect(dialog.getByRole('alert')).toHaveText(deniedBody.message);
      const observer = await openAsRole(browser, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
      try {
        expect(await read(observer.page, deniedResource.pid)).toEqual(reviewBeforeDenial);
        expect(await packValue(observer.page, deniedKey)).toBe(packBeforeDenial);
      } finally { await observer.context.close(); }
      await info.attach('http200-business-failure-original', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });
      await info.attach('http200-business-failure-session', {
        body: Buffer.from(JSON.stringify({ member: memberIdentity.user.email, roleSnapshot: memberSnapshot, status: denied.status(), code: deniedBody.code, message: deniedBody.message }, null, 2)),
        contentType: 'application/json',
      });
      // Retain the uniquely named fixture and artifacts for failure/owner review.
    } finally { await context.close(); }
  });
});
