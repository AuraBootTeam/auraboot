import { test, expect, type APIResponse, type Page } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { loginViaUI } from '../../helpers/wd-fixtures';
import { ensureSidebarExpanded, uniqueId, waitForDynamicPageLoad } from '../helpers';

type ApiEnvelope<T> = {
  code?: number | string;
  success?: boolean;
  message?: string;
  msg?: string;
  data?: T;
};

type JsonResponseLike = Pick<APIResponse, 'json' | 'text' | 'ok' | 'status' | 'url'>;

type DmnExportResult = {
  valid?: boolean;
  dmnXml?: string;
};

test.use({ storageState: { cookies: [], origins: [] } });
test.setTimeout(90_000);

function isApiSuccess<T>(body: ApiEnvelope<T> | null | undefined): body is ApiEnvelope<T> {
  if (!body) return false;
  if (body.success === false) return false;
  const code = body.code;
  return code === undefined || code === null || String(code) === '0';
}

async function readApi<T>(response: JsonResponseLike): Promise<T> {
  const body = (await response.json().catch(async () => ({
    message: await response.text().catch(() => ''),
  }))) as ApiEnvelope<T>;
  expect(response.ok(), `HTTP ${response.status()} ${response.url()}: ${JSON.stringify(body)}`).toBe(
    true,
  );
  expect(isApiSuccess(body), `API failed: ${JSON.stringify(body)}`).toBe(true);
  return body.data as T;
}

async function openDecisionTableWorkbenchFromSidebar(page: Page): Promise<void> {
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  const parent = nav
    .getByRole('button', { name: /决策中心|DecisionOps/i })
    .or(nav.getByRole('link', { name: /决策中心|DecisionOps/i }))
    .first();
  const link = nav
    .locator('a[href="/p/decisionops_tables"]')
    .or(nav.getByRole('link', { name: /决策表|Decision Tables/i }))
    .first();
  if (!(await link.isVisible({ timeout: 1000 }).catch(() => false))) {
    await parent.click();
  }
  await expect(link).toBeVisible({ timeout: 10_000 });
  await link.click();
  await expect(page).toHaveURL(/\/p\/decisionops_tables(?:$|\?)/, { timeout: 15_000 });
  await waitForDynamicPageLoad(page);
  await expect(page.getByTestId('decision-table-workbench-block')).toBeVisible({ timeout: 15_000 });
}

test('Decision table DMN export downloads XML with stable filename and content @golden', async ({
  page,
}, testInfo) => {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page).not.toHaveURL(/\/login(?:$|\?)/);

  const decisionCode = `codex_dmn_export_${uniqueId().replace(/[^a-zA-Z0-9_]/g, '_')}`;
  await openDecisionTableWorkbenchFromSidebar(page);
  await page.getByTestId('dtw-decision-code').fill(decisionCode);
  await page.getByLabel('decision-table-name').fill(`Codex DMN Export ${decisionCode}`);

  const exportResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/decision/tables/export-dmn'),
  );
  const downloadPromise = page.waitForEvent('download');
  await page.getByTestId('dt-export-dmn').click();
  const [download, apiResponse] = await Promise.all([downloadPromise, exportResponse]);
  const exportResult = await readApi<DmnExportResult>(apiResponse);
  expect(exportResult.valid).toBe(true);
  expect(exportResult.dmnXml?.includes('<decisionTable')).toBe(true);

  const suggested = download.suggestedFilename();
  expect(suggested).toBe(`${decisionCode}.dmn.xml`);
  const savedPath = testInfo.outputPath(suggested);
  await download.saveAs(savedPath);
  const xml = await readFile(savedPath, 'utf8');
  expect(xml.length > 100).toBe(true);
  expect(xml.includes('<definitions')).toBe(true);
  expect(xml.includes('<decisionTable')).toBe(true);
  expect(xml.includes(decisionCode)).toBe(true);
  await expect(page.getByTestId('dt-dmn-status')).toContainText('DMN XML 已导出');
  await expect(page.getByTestId('dt-dmn-xml')).toHaveValue(/<decisionTable/);

  await page.screenshot({
    path: testInfo.outputPath('dmn-export-artifact.png'),
    fullPage: true,
  });
});
