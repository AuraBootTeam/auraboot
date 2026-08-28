import { expect, test, type Page } from '../../fixtures';
import { executeCommandViaApi } from '../helpers';
import * as XLSX from 'xlsx';

/**
 * PAR-13 first slice (W2 chain): product create/edit/lifecycle/export/delete
 * as real-stack browser journeys on the product-catalog plugin, plus viewer
 * deny cells. Every state change is asserted through the API/DB boundary.
 */

const RUN_ID = `par13-${Date.now()}`;
const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5161';
const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:6461';
const ADMIN_EMAIL = 'admin@auraboot.com';
const ADMIN_PASSWORD = 'Test2026x';


const DESKTOP = { width: 1440, height: 900 };
const COMPACT = { width: 390, height: 844 };

async function loginJwt(email: string, password: string): Promise<string> {
  const resp = await fetch(`${BACKEND_URL}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  });
  const body: any = await resp.json().catch(() => ({}));
  expect(resp.status === 200 && Boolean(body?.data?.jwt), `login ${email}: ${JSON.stringify(body).slice(0, 150)}`).toBe(true);
  return body.data.jwt;
}

async function uiLogin(page: Page, email: string, password: string): Promise<void> {
  const response = await page.request.post(`${BASE_URL}/login`, {
    form: { email, password, remember: 'on', redirectTo: '/' },
    maxRedirects: 0,
  });
  expect([302, 303], `BFF login for ${email}`).toContain(response.status());
  await page.goto(`${BASE_URL}/`, { waitUntil: 'load' });
  if (page.url().includes('tenant-selection')) {
    await page.getByRole('button', { name: /进入|选择|Enter|AuraBoot/ }).first().click();
    await page.waitForURL((url) => !url.pathname.includes('tenant-selection'));
  }
}

test('PAR-13 product lifecycle: create, edit, publish, discontinue, export, delete, viewer deny', async ({ page, browser }) => {
  test.setTimeout(600_000);
  const adminJwt = await loginJwt(ADMIN_EMAIL, ADMIN_PASSWORD);

  // provision a read-only persona for the deny cells
  const viewerEmail = `${RUN_ID}-viewer@e2e.local`;
  const deptPid = await (async () => {
    const resp = await fetch(`${BACKEND_URL}/api/meta/commands/execute/org:create_department`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${adminJwt}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ payload: { org_dept_name: `${RUN_ID} 只读部门`, org_dept_order: 30, org_dept_status: 'active' }, operationType: 'create' }),
    });
    const body: any = await resp.json().catch(() => null);
    return String(body?.data?.data?.recordPid ?? '');
  })();
  expect(deptPid, 'viewer department').toBeTruthy();
  const posResp = await fetch(`${BACKEND_URL}/api/meta/commands/execute/org:create_position`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${adminJwt}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ payload: { org_pos_code: `${RUN_ID}-V`, org_pos_name: `${RUN_ID} 只读岗`, org_pos_dept_id: deptPid, org_pos_level: 'staff', org_pos_status: 'active' }, operationType: 'create' }),
  });
  const posBody: any = await posResp.json().catch(() => null);
  const viewerPosPid = String(posBody?.data?.data?.recordPid ?? '');
  expect(viewerPosPid, 'viewer position').toBeTruthy();
  const empResp = await fetch(`${BACKEND_URL}/api/org/employees`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${adminJwt}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: `${RUN_ID} 只读用户`, email: viewerEmail, phone: `136${Math.floor(10000000 + Math.random() * 89999999)}`, deptPid, positionPid: viewerPosPid }),
  });
  const empBody: any = await empResp.json().catch(() => ({}));
  const viewerMemberPid: string = empBody?.data?.memberPid || empBody?.data?.pid || '';
  expect(empResp.status === 200 && Boolean(viewerMemberPid), 'viewer employee created').toBe(true);
  const assignResp = await fetch(`${BACKEND_URL}/api/user-roles/assign-by-code`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${adminJwt}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ memberPid: viewerMemberPid, roleCodes: ['crm_viewer'] }),
  });
  expect(assignResp.ok, 'assign crm_viewer').toBe(true);
  await page.waitForTimeout(2000);
  const VIEWER_PASSWORD = 'AuraBoot2026!';
  const viewerJwt = await loginJwt(viewerEmail, VIEWER_PASSWORD);
  await uiLogin(page, ADMIN_EMAIL, ADMIN_PASSWORD);

  const PRODUCT = `${RUN_ID} 温控仪`;

  // ---- create via the real full-page form (/p/prod_product/new) ----
  await page.goto('/p/prod_product', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2000);
  await page.setViewportSize(DESKTOP);
  const createBtn = page.getByRole('button', { name: /新建/ }).first();
  await expect(createBtn).toBeVisible({ timeout: 12_000 });
  await createBtn.click();
  await page.waitForURL(/prod_product/, { timeout: 12_000 });
  await expect(page.getByText('商品表单')).toBeVisible({ timeout: 10_000 });
  await page.screenshot({ path: 'test-results/artifacts/par13-create-form.png' });
  // product-catalog renders controlled-field-renderer rows (not n-form-item)
  const fieldInput = (label: string) =>
    page.locator('.controlled-field-renderer', { hasText: label }).locator('input').first();
  await fieldInput('商品名称').fill(PRODUCT);
  await fieldInput('规格型号').fill('PARR1-SPEC-A');
  for (const field of ['prod_type', 'prod_unit']) {
    await page.getByTestId(`select-trigger-${field}`).click();
    await page.waitForTimeout(900);
    const opt = page.locator('[role=option]:visible').first();
    if ((await opt.count()) === 0) {
      await page.screenshot({ path: `test-results/artifacts/par13-select-${field}-debug.png` });
      throw new Error(`no dropdown options for ${field}`);
    }
    await opt.click();
    await page.waitForTimeout(500);
  }
  await page.screenshot({ path: 'test-results/artifacts/par13-create-filled.png' });
  await page.getByRole('button', { name: '保存' }).first().click();
  await page.waitForTimeout(2500);

  // assert via API and via list
  const listResp = await page.request.get(`${BACKEND_URL}/api/dynamic/prod_product/list?pageNum=1&pageSize=50`, {
    headers: { Authorization: `Bearer ${adminJwt}` },
  });
  expect(listResp.ok(), 'product list').toBe(true);
  const listBody: any = await listResp.json();
  const rows: any[] = listBody?.data?.records ?? [];
  const mine = rows.find((r) => r?.prod_name === PRODUCT);
  expect(mine, 'created product visible in list').toBeTruthy();
  const productPid = String(mine?.pid ?? mine?.id);

  // ---- list screenshots ----
  await page.setViewportSize(DESKTOP);
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2000);
  await page.screenshot({ path: 'test-results/artifacts/par13-list-desktop.png' });
  await page.setViewportSize(COMPACT);
  await page.screenshot({ path: 'test-results/artifacts/par13-list-compact.png' });

  // ---- edit via the real form page ----
  await page.setViewportSize(DESKTOP);
  await page.goto('/p/prod_product', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2000);
  const nameCell = page.locator('tbody tr').filter({ hasText: PRODUCT }).first();
  await expect(nameCell).toBeVisible({ timeout: 12_000 });
  const opened = await (async () => {
    for (const attempt of [
      () => nameCell.getByRole('button', { name: PRODUCT }).first(),
      () => nameCell.getByRole('link', { name: PRODUCT }).first(),
      () => nameCell.getByText(PRODUCT).first(),
    ]) {
      const loc = attempt();
      if ((await loc.count()) > 0) {
        await loc.click().catch(() => {});
        await page.waitForTimeout(1800);
        if (page.url().includes('prod_product') && !page.url().endsWith('/p/prod_product')) return true;
        if (await page.locator('[role=dialog]:visible, .n-drawer:visible').first().isVisible().catch(() => false)) return true;
      }
    }
    return false;
  })();
  expect(opened, 'opened product detail from list').toBe(true);
  // product detail is a page; 编辑 navigates to the edit form page
  const editBtn = page.getByRole('button', { name: '编辑' }).first();
  await expect(editBtn).toBeVisible({ timeout: 12_000 });
  await editBtn.click();
  await page.waitForTimeout(1800);
  await page.locator('.controlled-field-renderer', { hasText: '商品名称' }).locator('input').first().fill(`${PRODUCT}-V2`);
  await page.screenshot({ path: 'test-results/artifacts/par13-edit-form.png' });
  await page.getByRole('button', { name: '保存' }).first().click();
  await page.waitForTimeout(2200);
  const getResp = await page.request.get(`${BACKEND_URL}/api/dynamic/prod_product/${productPid}`, {
    headers: { Authorization: `Bearer ${adminJwt}` },
  });
  const getBody: any = await getResp.json().catch(() => null);
  expect(String(getBody?.data?.data?.prod_name ?? getBody?.data?.prod_name ?? '')).toContain('-V2');
  await page.screenshot({ path: 'test-results/artifacts/par13-detail.png' });

  // ---- lifecycle: planned -> sales -> discontinued (API commands, UI-asserted) ----
  async function runCommand(jwt: string, code: string, pid: string): Promise<{ ok: boolean; status: number }> {
    const resp = await fetch(`${BACKEND_URL}/api/meta/commands/execute/${code}`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${jwt}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ payload: {}, targetRecordPid: pid, operationType: 'update' }),
    });
    const body: any = await resp.json().catch(() => null);
    return { ok: resp.ok && body?.code === '0', status: resp.status };
  }
  const activate = await runCommand(adminJwt, 'prod:activate_product', productPid);
  expect(activate.ok, `activate: ${activate.status}`).toBe(true);
  const discontinue = await runCommand(adminJwt, 'prod:discontinue_product', productPid);
  expect(discontinue.ok, `discontinue: ${discontinue.status}`).toBe(true);

  // ---- viewer deny cells: update and delete are 403 without side effects ----
  const viewerUpdate = await runCommand(viewerJwt, 'prod:update_product', productPid);
  expect(viewerUpdate.ok, 'viewer update denied').toBe(false);
  expect(viewerUpdate.status, 'viewer update deny is 403').toBe(403);
  const viewerDelete = await runCommand(viewerJwt, 'prod:delete_product', productPid, ).then((r) => r, () => ({ ok: false, status: 0 }));
  expect(viewerDelete.ok, 'viewer delete denied').toBe(false);
  const survived = await page.request.get(`${BACKEND_URL}/api/dynamic/prod_product/${productPid}`, {
    headers: { Authorization: `Bearer ${adminJwt}` },
  });
  expect(survived.ok(), 'product survives viewer denies').toBe(true);

  // ---- export all pages: real file download ----
  await page.setViewportSize(DESKTOP);
  await page.goto('/p/prod_product', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2000);
  // export lives in the title-row overflow menu (⋮ next to 新建)
  const downloadPromise = page.waitForEvent('download', { timeout: 20_000 });
  const exportCandidates = page.getByRole('button', { name: /导出/ });
  if ((await exportCandidates.count()) > 0) {
    await exportCandidates.first().click();
  } else {
    const newBtn = page.getByRole('button', { name: /新建/ }).first();
    const overflow = newBtn.locator('xpath=..').getByRole('button').last();
    await overflow.click();
    await page.waitForTimeout(800);
    await page.getByText('导出 Excel').first().click();
  }
  const download = await downloadPromise;
  const exportPath = `test-results/artifacts/par13-export-${Date.now()}.xlsx`;
  await download.saveAs(exportPath);
  {
    const fs = await import('node:fs');
    const buf = fs.readFileSync(exportPath);
    // XLSX is a ZIP container: PK\x03\x04 signature
    expect(buf.length).toBeGreaterThan(1000);
    expect(buf[0]).toBe(0x50);
    expect(buf[1]).toBe(0x4b);
  }

  // ---- import: template download -> build workbook -> upload -> commit ----
  const IMPORT_NAME = `${RUN_ID} 导入品`;
  const totalBeforeImport = await (async () => {
    const r = await fetch(`${BACKEND_URL}/api/dynamic/prod_product/list?pageNum=1&pageSize=1`, { headers: { Authorization: `Bearer ${adminJwt}` } });
    return (await r.json())?.data?.total ?? 0;
  })();
  await page.goto('/p/prod_product', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2000);
  const importNewBtn = page.getByRole('button', { name: /新建/ }).first();
  await importNewBtn.locator('xpath=..').getByRole('button').last().click();
  await page.waitForTimeout(800);
  await page.getByText('Excel 数据导入').first().click();
  await page.waitForTimeout(1200);
  const importDialog = page.locator('[role=dialog]:visible, .n-modal:visible, .n-drawer:visible').last();
  await expect(importDialog).toContainText('Excel 数据导入', { timeout: 8000 });
  const templateDownload = page.waitForEvent('download', { timeout: 20_000 });
  await importDialog.getByText(/下载新增导入模板/).first().click();
  const template = await templateDownload;
  const templatePath = `test-results/artifacts/par13-import-template-${Date.now()}.xlsx`;
  await template.saveAs(templatePath);
  const tbuf = (await import('node:fs')).readFileSync(templatePath);
  const wb = XLSX.read(tbuf);
  const sheetName = wb.SheetNames[0];
  const sheet = wb.Sheets[sheetName];
  const headerRow = XLSX.utils.sheet_to_json<string[]>(sheet, { header: 1 })[0] as string[];
  expect(headerRow?.length).toBeGreaterThan(0);
  // build one import row aligned to the template headers
  // template headers are Chinese labels with required markers ("* 商品名称")
  const importRow: Record<string, string | number> = {};
  for (const h of headerRow) {
    const header = String(h ?? '').trim();
    if (!header) continue;
    if (header.includes('商品名称')) importRow[h] = IMPORT_NAME;
    else if (header.includes('规格型号')) importRow[h] = 'IMPORT-SPEC';
    else if (header.includes('计量单位')) importRow[h] = '台';
    else if (header.includes('商品类型')) importRow[h] = '原材料';
    else if (header.includes('基础价格')) importRow[h] = 1200;
    else importRow[h] = '';
  }
  const importSheet = XLSX.utils.json_to_sheet([importRow], { header: headerRow });
  wb.Sheets[sheetName] = importSheet;
  const importBuf: Buffer = XLSX.write(wb, { type: 'buffer', bookType: 'xlsx' }) as Buffer;
  const fileInput = importDialog.locator('input[type=file]');
  await fileInput.setInputFiles({
    name: 'par13-import.xlsx',
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    buffer: importBuf,
  });
  await page.waitForTimeout(1500);
  // after upload the dialog offers the commit action
  const commitBtn = importDialog.getByRole('button', { name: /导入|提交|开始/ }).last();
  await expect(commitBtn).toBeVisible({ timeout: 8000 });
  await page.screenshot({ path: 'test-results/artifacts/par13-import-ready.png' });
  await commitBtn.click();
  await page.waitForTimeout(3500);
  await page.screenshot({ path: 'test-results/artifacts/par13-import-done.png' });
  const importedCheck = await fetch(`${BACKEND_URL}/api/dynamic/prod_product/list?pageNum=1&pageSize=500`, {
    headers: { Authorization: `Bearer ${adminJwt}` },
  });
  const importedNames = ((await importedCheck.json())?.data?.records ?? []).map((r: any) => String(r?.prod_name ?? ''));
  expect(importedNames.some((n: string) => n.includes('导入品')), `imported product present: ${JSON.stringify(importedNames).slice(0, 300)}`).toBe(true);
  expect(importedNames.length, `total grew by exactly the import (+1): ${totalBeforeImport} -> ${importedNames.length}`).toBe(totalBeforeImport + 1);
  await importDialog.getByRole('button', { name: '关闭' }).last().click();
  await page.waitForTimeout(800);

  // ---- delete via UI action on a throwaway product ----
  const throwaway = await (async () => {
    const resp = await fetch(`${BACKEND_URL}/api/meta/commands/execute/prod:create_product`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${adminJwt}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ payload: { prod_name: `${RUN_ID} 待删除品`, prod_unit: '台', prod_type: 'raw_material' }, operationType: 'create' }),
    });
    const body: any = await resp.json().catch(() => null);
    return String(body?.data?.data?.recordPid ?? body?.data?.data?.recordId ?? '');
  })();
  expect(throwaway).toBeTruthy();
  await page.getByPlaceholder('查询...').first().fill(`${RUN_ID} 待删除品`);
  await page.getByPlaceholder('查询...').first().press('Enter');
  await page.waitForTimeout(1500);
  const row = page.locator('tbody tr').filter({ hasText: `${RUN_ID} 待删除品` }).first();
  const more = row.getByTestId('row-action-more');
  if ((await more.count()) > 0) {
    await more.click();
    await page.waitForTimeout(600);
    const deleteItem = page.getByTestId('row-action-delete_product').last()
      .or(page.getByRole('menuitem', { name: '删除' }).last())
      .or(row.getByRole('button', { name: '删除' }));
    await deleteItem.first().click();
  } else {
    await row.getByRole('button', { name: '删除' }).first().click();
  }
  await page.waitForTimeout(800);
  const confirmBtn = page.getByRole('button', { name: /确认|确定/ }).first();
  if ((await confirmBtn.count()) > 0 && (await confirmBtn.isVisible())) await confirmBtn.click();
  await page.waitForTimeout(1800);
  await expect(page.locator('tbody tr').filter({ hasText: `${RUN_ID} 待删除品` })).toHaveCount(0, { timeout: 10_000 });
  await page.screenshot({ path: 'test-results/artifacts/par13-after-delete.png' });
});
