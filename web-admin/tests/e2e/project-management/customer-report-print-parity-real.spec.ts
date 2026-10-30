import { test, expect } from '@playwright/test';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { navigateToMenuByClick } from '../helpers';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { requireRuntimeUrl } from '../../helpers/environments';
import { customerBudgetFixture } from '../../helpers/customer-budget-fixture';

test.use({
  locale: 'zh-CN',
  viewport: { width: 1440, height: 1000 },
  storageState: { cookies: [], origins: [] },
});

test('SC06-PRINT canonical designer layout survives real HTML and multi-page PDF exports', async ({
  page,
}, info) => {
  test.setTimeout(240_000);
  const evidence = process.env.AURA_EVIDENCE_DIR!;
  expect(evidence).toBeTruthy();
  await mkdir(evidence, { recursive: true });
  const python = process.env.PW_PDF_PYTHON!;
  expect(
    python,
    'Provide a Python runtime with pdfplumber for independent PDF geometry',
  ).toBeTruthy();
  execFileSync(python, ['-c', 'import pdfplumber']);
  const origin = new URL(requireRuntimeUrl('base')).origin;
  expect(['localhost', '127.0.0.1']).toContain(new URL(origin).hostname);
  await page.goto('/login');
  await expect(page.getByTestId('login-page-root')).toHaveAttribute('data-hydrated', 'true');
  await page.locator('input#identifier, input#email').fill(DEFAULT_TEST_ACCOUNT.email);
  await page.locator('#password').fill(DEFAULT_TEST_ACCOUNT.password);
  await page.getByRole('button', { name: '立即登录', exact: true }).click();
  await page.waitForURL((url) => !url.pathname.includes('/login'));
  const tenant = process.env.AURA_ADMIN_TENANT_ID!;
  if (new URL(page.url()).pathname === '/tenant-selection')
    await page.getByTestId(`space-business-${tenant}`).click();
  await expect(page.getByTestId('sidebar')).toBeVisible();
  const current = (await (await page.request.get('/api/auth/me')).json()).data;
  if (String(current.user.tenantId) !== tenant) {
    await page.getByTestId('user-menu').getByRole('button').first().click();
    await page.getByTestId(`tenant-switch-${tenant}`).click();
    await page.waitForLoadState('load');
  }
  const identity = await (await page.request.get('/api/auth/me')).json();
  expect(String(identity.code)).toBe('0');
  expect(String(identity.data.user.tenantId)).toBe(process.env.AURA_ADMIN_TENANT_ID);
  let llmRequests = 0;
  page.on('request', (request) => {
    if (new URL(request.url()).pathname === '/api/ai/aurabot/chat/stream') llmRequests++;
  });
  const list = async (model: string, field: string, value: string) => {
    const response = await page.request.get(`/api/dynamic/${model}/list`, {
      params: {
        pageNum: '1',
        pageSize: '50',
        filters: JSON.stringify([{ fieldName: field, operator: 'EQ', value }]),
      },
    });
    expect(response.status()).toBe(200);
    const body = await response.json();
    expect(String(body.code)).toBe('0');
    return body.data.records;
  };
  const create = async (code: string, payload: Record<string, unknown>) => {
    const response = await page.request.post(`/api/meta/commands/execute/${code}`, {
      data: { operationType: 'create', payload },
    });
    expect(response.status()).toBe(200);
    const body = await response.json();
    expect(String(body.code)).toBe('0');
    expect(body.data.phaseReached).toBe('completed');
  };
  const customer = customerBudgetFixture.customerName;
  let customers = await list('tpm_customer', 'tpm_cu_name', customer);
  if (!customers.length) {
    await create('tpm:create_customer', {
      tpm_cu_name: customer,
      tpm_cu_contact: '林晓（虚构案例）',
    });
    customers = await list('tpm_customer', 'tpm_cu_name', customer);
  }
  expect(customers).toHaveLength(1);
  const customerPid = customers[0].pid;
  for (const project of customerBudgetFixture.projects) {
    if (!(await list('tpm_project', 'tpm_pj_code', project.code)).length)
      await create('tpm:create_project', {
        tpm_pj_code: project.code,
        tpm_pj_name: project.name,
        tpm_pj_customer_id: customerPid,
        tpm_pj_budget: project.budget,
        tpm_pj_owner_user: identity.data.user.pid,
      });
    const records = await list('tpm_project', 'tpm_pj_code', project.code);
    expect(records).toHaveLength(1);
    expect(records[0]).toMatchObject({
      tpm_pj_name: project.name,
      tpm_pj_customer_id: customerPid,
    });
    expect(Number(records[0].tpm_pj_budget)).toBe(project.budget);
  }
  const template = JSON.parse(
    await readFile(
      new URL('../showcase/fixtures/ai-budget-print.report-template.json', import.meta.url),
      'utf8',
    ),
  );
  template.dsl.dataSources.analysis.aggregateQuery.filters.find(
    (f: any) => f.field === 'tpm_pj_customer_id',
  ).value = customerPid;
  const upload = join(evidence, 'bound-report-template.json');
  await writeFile(upload, JSON.stringify(template));
  await navigateToMenuByClick(page, ['元数据管理', '报表管理']);
  await page.getByRole('button', { name: '新建报表', exact: true }).click();
  await expect(page.getByTestId('report-template-actions')).toBeVisible();
  const chooser = page.waitForEvent('filechooser');
  await page.getByTestId('report-template-import').click();
  await (await chooser).setFiles(upload);
  await expect(page.getByTestId('report-template-imported')).toBeVisible();
  await page.getByTestId('drag-handle-analysis').locator('..').click();
  const fields = page
    .getByTestId('block-property-panel')
    .getByTestId('schema-config-field-columns');
  await fields.getByRole('spinbutton', { name: '列宽', exact: true }).nth(0).fill('340');
  await fields.getByRole('spinbutton', { name: '列宽', exact: true }).nth(1).fill('150');
  await fields.getByRole('combobox', { name: '对齐方式', exact: true }).nth(1).click();
  await page.getByRole('option', { name: '右对齐', exact: true }).click();
  await page.screenshot({ path: join(evidence, 'designer.png'), fullPage: true });
  const savedResponse = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === '/api/report-definitions' && r.request().method() === 'POST',
  );
  await page.getByTestId('report-designer-toolbar-btn-save').click();
  const response = await savedResponse;
  expect(response.status()).toBe(200);
  expect(new URL(response.url()).origin).toBe(origin);
  const saved = (await response.json()).data;
  expect(saved.pid).toBeTruthy();
  await expect(page).toHaveURL(new RegExp(`/report-designer/${saved.pid}$`));
  await page.reload();
  await expect(page.getByRole('textbox', { name: '报表标题', exact: true })).toHaveValue(
    template.dsl.title,
  );
  const persisted = (await (await page.request.get(`/api/report-definitions/${saved.pid}`)).json())
    .data.dsl;
  expect(persisted.body.find((b: any) => b.id === 'analysis').columns).toEqual(
    template.dsl.body.find((b: any) => b.id === 'analysis').columns,
  );
  expect(persisted.header).toEqual(template.dsl.header);
  expect(persisted.footer).toEqual(template.dsl.footer);
  const toolbar = page.getByTestId('report-designer-toolbar');
  const previewQuery = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === '/api/reports/query/aggregate' &&
      r.request().method() === 'POST',
  );
  await toolbar.getByRole('button', { name: '预览', exact: true }).click();
  const previewResponse = await previewQuery;
  expect(previewResponse.status()).toBe(200);
  expect(previewResponse.request().postDataJSON()).toEqual(
    template.dsl.dataSources.analysis.aggregateQuery,
  );
  await expect(page.getByRole('main')).toContainText('¥132,000.50');
  await expect(page.getByRole('main')).toContainText('第 1 页');
  const headers = page.getByRole('main').getByRole('columnheader');
  const dimensions = await headers.evaluateAll((nodes) =>
    nodes.map((n) => ({
      width: n.getBoundingClientRect().width,
      align: getComputedStyle(n).textAlign,
    })),
  );
  expect(dimensions).toHaveLength(2);
  expect(dimensions[1].align).toBe('right');
  const ratio = dimensions[0].width / dimensions[1].width;
  await page.screenshot({ path: join(evidence, 'preview.png'), fullPage: true });
  const download = async (format: 'PDF' | 'HTML', name: string) => {
    const pending = page.waitForEvent('download');
    await toolbar.getByRole('button', { name: `导出 ${format}`, exact: true }).click();
    const file = await pending;
    const target = join(evidence, name);
    await file.saveAs(target);
    return target;
  };
  const short = await download('PDF', 'short.pdf');
  const geometry = JSON.parse(
    execFileSync(
      python,
      [
        fileURLToPath(new URL('../../helpers/assert-report-print-layout.py', import.meta.url)),
        short,
        String(ratio),
      ],
      { encoding: 'utf8' },
    ),
  );
  expect(geometry.pages).toBe(1);
  await writeFile(
    join(evidence, 'geometry.json'),
    JSON.stringify({ designer: dimensions, pdf: geometry }, null, 2),
  );
  const shortText = execFileSync('pdftotext', ['-layout', short, '-'], { encoding: 'utf8' });
  expect(shortText).toMatch(/第\s*1\s*页/);
  const htmlPath = await download('HTML', 'report.html');
  const html = await readFile(htmlPath, 'utf8');
  expect(html).toContain('width:340px');
  expect(html).toContain('text-align:right');
  const artifactPage = await page.context().newPage();
  await artifactPage.setContent(html);
  await expect(artifactPage.getByRole('table')).toBeVisible();
  await artifactPage.screenshot({ path: join(evidence, 'html.png'), fullPage: true });
  await artifactPage.close();
  const original = persisted.body.find((b: any) => b.id === 'analysis-summary').content;
  const checklist = Array.from(
    { length: 30 },
    (_, i) =>
      `复盘检查${String(i + 1).padStart(2, '0')}：核对项目预算调整记录、审批依据和支出凭证。本条为人工检查清单，不代表已经完成。`,
  ).join('\n');
  await toolbar.getByRole('button', { name: '编辑', exact: true }).click();
  await page.getByTestId('drag-handle-analysis-summary').locator('..').click();
  await page
    .getByTestId('block-property-panel')
    .getByPlaceholder('输入文本内容…')
    .fill(original + '\n' + checklist);
  const update = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === `/api/report-definitions/${saved.pid}` &&
      r.request().method() === 'PUT',
  );
  await page.getByTestId('report-designer-toolbar-btn-save').click();
  expect((await update).status()).toBe(200);
  await toolbar.getByRole('button', { name: '预览', exact: true }).click();
  const long = await download('PDF', 'long.pdf');
  const text = execFileSync('pdftotext', ['-layout', long, '-'], { encoding: 'utf8' });
  const pages = text.split('\f').filter((p) => p.trim());
  expect(pages.length).toBeGreaterThan(1);
  for (let i = 0; i < pages.length; i++) {
    expect(pages[i]).toMatch(new RegExp(`第\\s*${i + 1}\\s*页`));
    expect(pages[i].replace(/\s/g, '')).toContain(template.dsl.title.replace(/\s/g, ''));
  }
  for (let i = 1; i <= 30; i++) expect(text).toContain(`复盘检查${String(i).padStart(2, '0')}`);
  await toolbar.getByRole('button', { name: '编辑', exact: true }).click();
  await page.getByTestId('drag-handle-analysis-summary').locator('..').click();
  await page.getByTestId('block-property-panel').getByPlaceholder('输入文本内容…').fill(original);
  const restore = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === `/api/report-definitions/${saved.pid}` &&
      r.request().method() === 'PUT',
  );
  await page.getByTestId('report-designer-toolbar-btn-save').click();
  expect((await restore).status()).toBe(200);
  await toolbar.getByRole('button', { name: '预览', exact: true }).click();
  expect(llmRequests).toBe(0);
  await writeFile(
    join(evidence, 'receipt.json'),
    JSON.stringify(
      {
        reportPid: saved.pid,
        url: `${origin}/report-designer/${saved.pid}`,
        longPages: pages.length,
        checkItems: 30,
        modelCalls: llmRequests,
        source:
          'previously qualified AI narrative; this test only exercises real DSL design/export',
      },
      null,
      2,
    ),
  );
  await info.attach('print-parity-receipt', {
    path: join(evidence, 'receipt.json'),
    contentType: 'application/json',
  });
});
