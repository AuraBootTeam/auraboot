import { requireRuntimeUrl } from '../../helpers/environments';
/**
 * Real enterprise report authorization, using the existing project-management data.
 * API calls only provision the report/roles and independently probe authorization.
 * Users authenticate through the actual BFF login and business-space UI. No LLM,
 * intercepted responses, internal test plugin, injected JWT, or default setup.
 */
import { test, expect, type APIResponse } from '@playwright/test';
import { randomUUID, createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { navigateToMenuByClick } from '../helpers';

const readPermission = 'model.tpm_customer.read';
const compact = (value: string) => value.normalize('NFKC').replace(/\s+/g, '');
test.use({ locale: 'zh-CN', viewport: { width: 1440, height: 1000 } });

async function assertSourceDenied(response: APIResponse, exposePermissionCode = true) {
  expect(response.status()).toBe(403);
  const body = await response.json();
  expect(String(body.code)).toBe('403');
  expect(JSON.stringify(body)).not.toContain('csrf_origin_rejected');
  // The export handler returns code=403 without serializing exception.permissionCode.
  // The independent model preview probe below must identify the exact permission.
  if (exposePermissionCode) expect(JSON.stringify(body)).toContain(readPermission);
  return body;
}

test('SC06-PERMISSIONS report artifact grants do not bypass customer source read permission', async ({ page, browser }, info) => {
  test.setTimeout(240_000);
  const origin = new URL(requireRuntimeUrl('base')).origin;
  expect(new URL(origin).hostname).toBe('localhost');
  expect(process.env.PW_ADMIN_STORAGE_STATE, 'reuse the real administrator UI login').toBeTruthy();
  const evidence = process.env.AURA_EVIDENCE_DIR;
  expect(evidence).toBeTruthy();
  await mkdir(evidence!, { recursive: true });
  execFileSync('pdftotext', ['-v'], { stdio: 'pipe' });
  await page.goto('/home');
  await expect(page.getByTestId('sidebar')).toBeVisible();
  const adminResponse = await page.request.get('/api/auth/me');
  expect(adminResponse.status()).toBe(200);
  const tenantId = String((await adminResponse.json()).data.user.tenantId);
  expect(tenantId).toMatch(/^\d+$/);
  const sourceResponse = await page.request.get('/api/dynamic/tpm_customer/list', {
    params: { pageNum: '1', pageSize: '500' },
  });
  expect(sourceResponse.status()).toBe(200);
  const sourceBody = await sourceResponse.json();
  expect(String(sourceBody.code)).toBe('0');
  const customers = sourceBody.data.records as { pid: string; tpm_cu_name: string; tpm_cu_contact?: string | null }[];
  expect(customers.length).toBeGreaterThan(0);
  expect(Number(sourceBody.data.total)).toBe(customers.length);
  const target = customers.find(row => row.tpm_cu_name?.trim() && row.tpm_cu_contact?.trim());
  expect(target, 'the current tenant must contain a real named customer with a contact').toBeTruthy();
  const template = JSON.parse(await readFile(new URL('../showcase/fixtures/customer-analysis.report-template.json', import.meta.url), 'utf8'));
  expect(template.dsl.dataSources).toEqual({ customers: { type: 'model', modelCode: 'tpm_customer' } });
  const key = `customer_permission_${randomUUID().replaceAll('-', '').slice(0, 12)}`;
  const title = `客户报告权限验证_${key}`;
  const created = await page.request.post('/api/report-definitions', {
    data: { code: key, title, profile: 'paged-media', dsl: { ...template.dsl, title } },
  });
  expect(created.status()).toBe(200);
  const createdBody = await created.json();
  expect(String(createdBody.code)).toBe('0');
  const reportPid = createdBody.data.pid;
  expect(reportPid).toBeTruthy();
  const treeResponse = await page.request.get('/api/permissions/tree');
  expect(treeResponse.status()).toBe(200);
  const permissionIds = new Map<string, number>();
  type PermissionNode = { code: string; id: number; children?: PermissionNode[] };
  const collect = (nodes: PermissionNode[]) => nodes.forEach(node => {
    permissionIds.set(node.code, node.id);
    collect(node.children || []);
  });
  collect((await treeResponse.json()).data);
  const receipts: unknown[] = [];
  let llmCalls = 0;
  for (const hasSourceRead of [false, true]) {
    const roleCode = `${key}_${hasSourceRead ? 'reader' : 'exporter'}`;
    const roleResponse = await page.request.post('/api/roles', {
      data: { code: roleCode, name: roleCode, type: 'custom', status: 'active', scopeType: 'tenant', defaultDataScopeType: 'all' },
    });
    expect(roleResponse.status()).toBe(200);
    const rolePid = (await roleResponse.json()).data.pid;
    const grants = ['report.definition.view', 'report.export.execute', ...(hasSourceRead ? [readPermission] : [])];
    const grantResponse = await page.request.put(`/api/permissions/matrix/${rolePid}/batch`, {
      data: grants.map(code => {
        expect(permissionIds.has(code), `published permission ${code}`).toBe(true);
        return { permissionId: permissionIds.get(code), granted: true };
      }),
    });
    expect(grantResponse.status()).toBe(200);
    const email = `${roleCode}@e2e.local`;
    const password = 'Test2026x';
    const userResponse = await page.request.post('/api/admin/users', {
      data: { email, displayName: roleCode.slice(0, 50), initialPassword: password, roleCodes: [roleCode], sendInviteEmail: false },
    });
    expect(userResponse.status()).toBe(200);
    const context = await browser.newContext({ baseURL: origin, storageState: { cookies: [], origins: [] }, locale: 'zh-CN', viewport: { width: 1440, height: 1000 } });
    const actor = await context.newPage();
    actor.on('request', request => {
      if (new URL(request.url()).pathname === '/api/ai/aurabot/chat/stream') llmCalls++;
    });
    const downloads: string[] = [];
    actor.on('download', download => downloads.push(download.suggestedFilename()));
    try {
      await actor.goto('/login');
      await expect(actor.getByTestId('login-page-root')).toHaveAttribute('data-hydrated', 'true');
      await actor.locator('#identifier').fill(email);
      await actor.locator('#password').fill(password);
      await actor.locator('form button[type="submit"]').click();
      await expect(actor).not.toHaveURL(/\/login(?:$|\?)/);
      if (new URL(actor.url()).pathname === '/tenant-selection') {
        await actor.getByTestId(`space-business-${tenantId}`).click();
        await expect(actor).not.toHaveURL(/\/tenant-selection(?:$|\?)/);
      }
      const identityResponse = await actor.request.get('/api/auth/me');
      expect(identityResponse.status()).toBe(200);
      const identity = (await identityResponse.json()).data;
      expect(identity.user.email).toBe(email);
      expect(String(identity.user.tenantId)).toBe(tenantId);
      const roles = identity.permissions.roles.map((role: { code: string }) => role.code);
      expect(roles).toContain(roleCode);
      expect(roles).not.toContain('tenant_admin');
      const effective: string[] = identity.permissions.permissionCodes;
      for (const permission of grants) expect(effective).toContain(permission);
      expect(effective.includes(readPermission)).toBe(hasSourceRead);
      expect(effective).not.toContain('report.definition.manage');
      // Same-tenant reports are shared by artifact permission; createdBy is not
      // an owner-only ACL (ReportDefinitionController.requireOwned checks tenant).
      const definitionResponse = await actor.request.get(`/api/report-definitions/${reportPid}`);
      expect(definitionResponse.status(), 'cross-user artifact read must work before testing source isolation').toBe(200);
      expect((await definitionResponse.json()).data.pid).toBe(reportPid);
      const deniedExports: unknown[] = [];
      if (!hasSourceRead) {
        for (const format of ['markdown', 'html', 'pdf']) {
          const response = await actor.request.post(`/api/reports/export/${format}`, {
            headers: { Origin: origin, Referer: `${origin}/report-designer/${reportPid}` },
            data: { reportPid },
          });
          deniedExports.push({ format, status: response.status(), body: await assertSourceDenied(response, false) });
        }
      }
      await navigateToMenuByClick(actor, ['元数据管理', '报表管理']);
      const queryResponsePromise = actor.waitForResponse(response =>
        new URL(response.url()).pathname === '/api/dynamic/tpm_customer/list' && response.request().method() === 'GET');
      await actor.getByRole('row').filter({ hasText: title }).getByRole('button', { name: /打开|Open/ }).click();
      const queryResponse = await queryResponsePromise;
      await expect(actor.getByTestId('report-reader-toolbar').filter({ visible: true })).toContainText('只读报表');
      const artifacts: unknown[] = [];
      if (!hasSourceRead) {
        expect(queryResponse.status()).toBe(403);
        // The UI intentionally throws on 403 headers before consuming the Fetch
        // body (requireReportResponse). Do not wait for that unread browser body.
        // A separate real request under this same UI-authenticated BFF session
        // verifies the exact source permission, alongside the UI denial below.
        const sourceDenied = await actor.request.get('/api/dynamic/tpm_customer/list', {
          params: { pageNum: '1', pageSize: '500' },
          headers: { Origin: origin, Referer: actor.url() },
        });
        deniedExports.push({ format: 'model-preview', status: sourceDenied.status(), body: await assertSourceDenied(sourceDenied) });
        await expect(actor.getByRole('alert')).toContainText('查询未成功');
        await expect(actor.getByRole('alert')).toContainText('当前账号无权读取');
        await expect(actor.getByRole('alert')).toContainText('当前没有可用结果');
        await expect(actor.getByRole('cell', { name: target!.tpm_cu_name, exact: true })).toHaveCount(0);
        for (const format of ['JSON', 'Excel', 'PDF', 'Markdown', 'HTML'])
          await expect(actor.getByRole('button', { name: `导出 ${format}`, exact: true })).toBeDisabled();
        expect(downloads).toEqual([]);
      } else {
        expect(queryResponse.status()).toBe(200);
        expect((await queryResponse.json()).data.records.map((row: { pid: string }) => row.pid).sort()).toEqual(customers.map(row => row.pid).sort());
        await expect(actor.getByRole('cell', { name: target!.tpm_cu_name, exact: true })).toBeVisible();
        const targetRow = actor.getByRole('row').filter({ has: actor.getByRole('cell', { name: target!.tpm_cu_name, exact: true }) });
        await expect(targetRow).toHaveCount(1);
        await expect(targetRow.getByRole('cell', { name: target!.tpm_cu_contact!, exact: true })).toBeVisible();
        for (const format of ['markdown', 'html', 'pdf'] as const) {
          const label = { markdown: 'Markdown', html: 'HTML', pdf: 'PDF' }[format];
          const extension = { markdown: 'md', html: 'html', pdf: 'pdf' }[format];
          const responsePromise = actor.waitForResponse(response => new URL(response.url()).pathname === `/api/reports/export/${format}` && response.request().method() === 'POST');
          const downloadPromise = actor.waitForEvent('download', { timeout: 120_000 });
          await actor.getByRole('button', { name: `导出 ${label}`, exact: true }).click();
          const response = await responsePromise;
          expect(response.status()).toBe(200);
          expect(response.request().postDataJSON().reportPid).toBe(reportPid);
          const download = await downloadPromise;
          expect(await download.failure()).toBeNull();
          expect(download.suggestedFilename()).toMatch(new RegExp(`\\.${extension}$`));
          const path = join(evidence!, `SC06-source-reader.${extension}`);
          await download.saveAs(path);
          const bytes = await readFile(path);
          let text = bytes.toString('utf8');
          if (format === 'pdf') {
            expect(bytes.subarray(0, 5).toString()).toBe('%PDF-');
            text = execFileSync('pdftotext', ['-layout', path, '-'], { encoding: 'utf8' });
          } else if (format === 'html') {
            expect(text).toMatch(/^\s*<!doctype html>/i);
            const parsed = await actor.evaluate(html => {
              const doc = new DOMParser().parseFromString(html, 'text/html');
              return { text: doc.body.textContent || '', rows: doc.querySelectorAll('tbody tr').length, headers: [...doc.querySelectorAll('th')].map(cell => cell.textContent) };
            }, text);
            expect(parsed.rows).toBe(customers.length);
            expect(parsed.headers).toEqual(expect.arrayContaining(['客户名称', '客户联系人']));
            text = parsed.text;
          } else {
            expect(text).toMatch(/^\|\s*客户名称\s*\|\s*客户联系人\s*\|\s*\r?\n\|\s*:?-+:?\s*\|\s*:?-+:?\s*\|/m);
            text = text.replace(/\\([\\`*_{}\[\]()#+.!|<>-])/g, '$1');
          }
          expect(compact(text)).toContain(compact(target!.tpm_cu_name));
          expect(compact(text)).toContain(compact(target!.tpm_cu_contact!));
          await info.attach(`SC06-permissions-${format}`, { path });
          artifacts.push({ format, path, bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') });
        }
        expect(downloads).toHaveLength(3);
      }
      await actor.screenshot({ path: join(evidence!, `SC06-source-${hasSourceRead ? 'allowed' : 'denied'}.png`), fullPage: true });
      await actor.goto(`/report-designer/${reportPid}`);
      await expect(actor.getByTestId('report-reader-toolbar')).toContainText('只读报表');
      await expect(actor.getByTestId('report-designer-toolbar-btn-save')).toHaveCount(0);
      await expect(actor.getByTestId('block-palette')).toHaveCount(0);
      await expect(actor.getByTestId('block-property-panel')).toHaveCount(0);
      const deniedEdit=await actor.request.put(`/api/report-definitions/${reportPid}`,{data:{title:'Forbidden reader edit'}});
      expect(deniedEdit.status()).toBe(403);
      const retained=await page.request.get(`/api/report-definitions/${reportPid}`);
      expect((await retained.json()).data.title).toBe(title);
      await actor.screenshot({path:join(evidence!,`DS04-readonly-designer-${hasSourceRead?'source':'artifact'}.png`),fullPage:true});
      receipts.push({ tenantId, roleCode, roles, grantedPermissions: grants, effectiveSourceRead: effective.includes(readPermission), reportPid, deniedExports, artifacts, downloads });
    } finally {
      await context.close();
    }
  }
  expect(llmCalls).toBe(0);
  const receiptPath = join(evidence!, 'SC06-report-permissions-receipt.json');
  await writeFile(receiptPath, JSON.stringify({ source: 'manual-template', provesLiveLlm: false, target, receipts }, null, 2));
  await info.attach('SC06-report-permissions-receipt', { path: receiptPath, contentType: 'application/json' });
});
