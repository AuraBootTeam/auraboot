import { read as xlsxRead, utils as XLSXUtils, write as xlsxWrite } from 'xlsx';
import { expect, type Page } from '../../fixtures';

export const ADMIN_EMAIL = 'admin@auraboot.com';
export const PASSWORD = 'Test2026x';
export const LEAD = 'crm_lead_common';
export const CONTACT = 'crm_contact_common';

export function workbook(rows: Array<Record<string, unknown>>, template: Buffer): Buffer {
  const book = xlsxRead(template, { type: 'buffer' });
  const sheet = book.Sheets[book.SheetNames[0]];
  const headerRows = XLSXUtils.sheet_to_json<unknown[]>(sheet, { header: 1, defval: '' });
  const headers = (headerRows[0] || []).map(String);
  const actualHeader = new Map(headers.map((header) => [header.replace(/^\*\s+/, ''), header]));
  const filledRows = rows.map((row) =>
    Object.fromEntries(
      Object.entries(row).map(([key, value]) => [actualHeader.get(key) || key, value]),
    ),
  );
  XLSXUtils.sheet_add_json(sheet, filledRows, {
    header: headers,
    origin: 'A2',
    skipHeader: true,
  });
  return xlsxWrite(book, { type: 'buffer', bookType: 'xlsx' }) as Buffer;
}

export async function openModelFromMenu(page: Page, model: string): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  const link = nav.locator(`a[href="/p/${model}"]`).first();
  if (!(await link.isVisible().catch(() => false))) {
    await nav
      .getByRole('button', { name: /客户关系管理|crm/i })
      .first()
      .click();
  }
  if (!(await link.isVisible().catch(() => false))) {
    await nav
      .getByRole('button', { name: /业务档案|business records/i })
      .first()
      .click();
  }
  await expect(link).toBeVisible({ timeout: 10_000 });
  await link.click();
  await expect(page).toHaveURL(new RegExp(`/p/${model}(?:\\?.*)?$`), { timeout: 15_000 });
  await expect(page.getByTestId('toolbar-more-menu')).toBeVisible({ timeout: 15_000 });
}

export async function openImport(page: Page): Promise<void> {
  await page.getByTestId('toolbar-more-menu').click();
  await page.getByTestId('more-menu-import').click();
  await expect(page.getByTestId('excel-import-dialog')).toBeVisible();
}

export async function downloadTemplate(page: Page, model: string): Promise<Buffer> {
  const responsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      response.url().includes(`/api/meta/excel/template/${model}?`),
  );
  await page.getByTestId('import-download-template').click();
  const response = await responsePromise;
  expect(response.ok(), await response.text()).toBeTruthy();
  return Buffer.from(await response.body());
}

export async function uploadWorkbook(
  page: Page,
  name: string,
  rows: Array<Record<string, unknown>>,
  template: Buffer,
  timeout = 60_000,
): Promise<void> {
  const chooserPromise = page.waitForEvent('filechooser');
  await page.getByTestId('import-browse-file').click();
  const chooser = await chooserPromise;
  await chooser.setFiles({
    name,
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    buffer: workbook(rows, template),
  });
  await expect(page.getByTestId('import-validation-summary')).toBeVisible({ timeout });
}

export async function submitAndCaptureTask(page: Page, model: string): Promise<string> {
  const responsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/meta/excel/import/${model}?`),
  );
  await page.getByTestId('import-submit').click();
  const response = await responsePromise;
  expect(response.ok(), await response.text()).toBeTruthy();
  const body = await response.json();
  const taskId = String(body?.data?.taskId ?? '');
  expect(taskId).toMatch(/^[0-9A-HJKMNP-TV-Z]{26}$/);
  return taskId;
}
