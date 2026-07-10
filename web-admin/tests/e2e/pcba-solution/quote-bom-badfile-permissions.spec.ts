import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';
import { makeQuoteRoleUser, ensureQuoteRoleUser, openQuoteRolePage, type QuoteRoleUser } from './quote-e2e-helpers';

/**
 * Quote/BOM 真机 — 坏文件转换负向 (BOM-04) + 权限管理页 (SYS-04).
 */
const PERMISSIONS = '/enterprise/permissions';
const uid = uniqueId('fin').replace(/_/g, '-');
const users: Record<string, QuoteRoleUser> = {};

async function post(page: Page, code: string, payload: any, op = 'create') {
  const r = await page.request.post(`/api/meta/commands/execute/${code}`, { data: { payload, operationType: op } });
  return { status: r.status(), body: await r.json().catch(() => ({})) };
}
const pid = (b: any) => b?.data?.data?.recordPid || b?.data?.data?.recordId || b?.data?.recordPid || b?.data?.recordId;

async function listConversionTasks(page: Page): Promise<any[]> {
  const list = await page.request.get('/api/dynamic/bom_conversion_task_pcba/list?pageNum=1&pageSize=10&sortField=created_at&sortOrder=desc');
  const lb = await list.json().catch(() => ({} as any));
  const recs = lb?.data?.records || lb?.data?.data?.records || lb?.data || [];
  return Array.isArray(recs) ? recs : [];
}

function findTask(recs: any[], projId: string, fileId: string): any | undefined {
  return recs.find((r: any) => String(r.bom_task_project_id || '') === String(projId) || String(r.bom_task_raw_file_id || '') === String(fileId));
}

test.describe('Quote/BOM bad-file convert + permissions page (BOM-04 / SYS-04) @smoke', () => {
  test.describe.configure({ mode: 'serial', timeout: 150_000 });

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    users['eng'] = makeQuoteRoleUser('bom_engineering', uid, ['bom_engineering']);
    await ensureQuoteRoleUser(page, users['eng']);
    await ctx.close();
  });

  // ── BOM-04: 上传非法/坏文件 → 转换报错(不静默成功)──
  test('BOM-04 bad file conversion fails / surfaces an error (not silent success)', async ({ browser }) => {
    const { context, page } = await openQuoteRolePage(browser, users['eng']);
    try {
      const proj = await post(page, 'bom:create_project', { bom_project_name: `Bad ${uid}`, bom_pcba_code: `BAD-${uid}` });
      const projId = pid(proj.body);
      expect(projId, 'project created').toBeTruthy();
      // upload a clearly-invalid "xlsx" (plain text bytes) — not a real workbook
      const badBuf = Buffer.from('this is not a valid excel workbook ' + uid, 'utf8');
      const up = await page.request.post('/api/file/upload', {
        multipart: { file: { name: 'bad.xlsx', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer: badBuf } },
      });
      const fileId = (await up.json())?.data?.fileId;
      expect(fileId, 'bad file uploaded (upload itself succeeds)').toBeTruthy();
      const conv = await post(page, 'bom:start_conversion', {
        bom_task_project_id: projId, bom_task_source_package: 'badfile_neg', bom_task_raw_file_id: fileId,
      });
      // either start_conversion rejects (4xx) immediately, OR the task ends in a failed/error status —
      // the contract is: a bad file must NOT silently produce a successful conversion with lines.
      let bad = conv.status >= 400;
      if (!bad) {
        await expect.poll(async () => {
          const mine = findTask(await listConversionTasks(page), projId, fileId);
          if (mine) {
            const st = String(mine.bom_task_status || mine.status || '');
            if (/fail|error|reject|invalid/i.test(st)) { bad = true; return true; }
            if (/done|complet|succeed|success|ready|review/i.test(st)) {
              // completed — must have produced NO usable canonical lines for a junk file
              const lc = Number(mine.bom_task_line_count || mine.bom_task_total_lines || 0);
              bad = lc === 0; return true;
            }
          }
          return false;
        }, { timeout: 90_000, intervals: [1_000, 2_000, 5_000] }).toBeTruthy();
      }
      expect(bad, 'BOM-04: junk file does not silently produce a successful conversion with lines').toBeTruthy();
    } finally {
      await context.close();
    }
  });

  // ── SYS-04: 权限管理页 admin 可达 + 渲染角色/能力(diff 保存深度归后端 L2 矩阵 capabilities)──
  test('SYS-04 admin permissions page renders role/capability management', async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      await page.goto(PERMISSIONS, { waitUntil: 'domcontentloaded' });
      let text = '';
      await expect.poll(async () => {
        text = await page.locator('main').innerText().catch(() => '');
        return text.length;
      }, { timeout: 10_000, intervals: [250, 500, 1_000] }).toBeGreaterThan(0);
      // admin reaches it (not bounced to login / not access-denied)
      expect(/登录|login/i.test(page.url()), 'SYS-04: admin not bounced to login').toBeFalsy();
      expect(text.length, 'SYS-04: permissions page renders content').toBeGreaterThan(0);
      // capability/role management surface present (roles, capabilities, or assignment controls)
      const hasSurface = /权限|角色|能力|capabilit|role|permission|分配|授权/i.test(text)
        || (await page.getByRole('combobox').count()) > 0
        || (await page.locator('table tbody tr, [role="row"], [data-testid*="capability"], [data-testid*="role"]').count()) > 0;
      expect(hasSurface, 'SYS-04: role/capability management surface visible to admin').toBeTruthy();
    } finally {
      await ctx.close();
    }
  });

  // ── SYS-04b: 非 admin 不能执行能力分配(真实边界 = API 被拒,UI 仅装饰)──
  test('SYS-04b non-admin is denied the capability-assignment API', async ({ browser }) => {
    const { context, page } = await openQuoteRolePage(browser, users['eng']);
    try {
      // engineer tries to assign capabilities to a role — must be denied at the gate (not 2xx)
      const candidates = [
        { url: '/api/permission/capabilities', method: 'put', body: { roleCode: 'qo_sales', capabilityCodes: ['bom.cap.library_manage'] } },
        { url: '/api/permission/roles/qo_sales/capabilities', method: 'put', body: { capabilityCodes: ['bom.cap.library_manage'] } },
      ];
      const statuses: number[] = [];
      for (const c of candidates) {
        const r = c.method === 'put'
          ? await page.request.put(c.url, { data: c.body })
          : await page.request.post(c.url, { data: c.body });
        statuses.push(r.status());
      }
      // none of the assignment attempts may succeed (2xx) for a non-admin; expect denial (401/403)
      // or admin-guard body (200+code:409) or not-found routing — never a real 2xx success path.
      const anyAllowed = statuses.some((s) => s === 200 || s === 201 || s === 204);
      test.info().annotations.push({ type: 'note', description: `SYS-04b assignment statuses=${statuses.join(',')}` });
      const denied = statuses.every((s) => s === 401 || s === 403 || s === 404 || s === 405 || s >= 400);
      expect(!anyAllowed && denied, `SYS-04b: non-admin capability-assignment denied (statuses=${statuses.join(',')})`).toBeTruthy();
    } finally {
      await context.close();
    }
  });
});
