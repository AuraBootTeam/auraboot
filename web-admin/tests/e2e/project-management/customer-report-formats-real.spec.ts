/**
 * Hand-authored SC05 template + current tenant data. No LLM invocation or substitute.
 * This proves report persistence and real exports, never AI interpretation/integration.
 */
import { test, expect, type Request } from '@playwright/test';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { navigateToMenuByClick } from '../helpers';

// Existing integrated fixture, intentionally shared with SC05; not AI-generated.
const templatePath = fileURLToPath(
  new URL('../showcase/fixtures/customer-analysis.report-template.json', import.meta.url),
);
const compact = (value: string) => value.normalize('NFKC').replace(/\s+/g, '');
test.use({
  locale: 'zh-CN',
  viewport: { width: 1440, height: 1000 },
});

test('SC06-FORMATS manual customer template persists and exports real Chinese data in Markdown HTML and PDF', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  const origin = new URL(process.env.PLAYWRIGHT_BASE_URL || '');
  expect(['localhost', '127.0.0.1']).toContain(origin.hostname);
  const evidence = process.env.AURA_EVIDENCE_DIR;
  expect(evidence, 'bind artifacts to the registered runtime evidence run').toBeTruthy();
  await mkdir(evidence!, { recursive: true });
  execFileSync('pdftotext', ['-v'], { stdio: 'pipe' });
  const templateBytes = await readFile(templatePath);
  const template = JSON.parse(templateBytes.toString('utf8'));
  expect(template.format).toBe('auraboot.report-template');
  expect(template.dsl.dataSources).toEqual({
    customers: { type: 'model', modelCode: 'tpm_customer' },
  });
  let modelRequestCount = 0;
  // Observe only; never intercept/replace a response.
  page.on('request', (request) => {
    if (new URL(request.url()).pathname === '/api/ai/aurabot/chat/stream') modelRequestCount++;
  });
  expect(process.env.PW_ADMIN_STORAGE_STATE, 'reuse the private state created by the real S1 browser login').toBeTruthy();
  await page.goto('/home');
  await expect(page.getByTestId('sidebar')).toBeVisible();

  // Independent read-only oracle. Every mutation below uses the actual UI.
  const source = await page.request.get('/api/dynamic/tpm_customer/list', {
    params: { pageNum: '1', pageSize: '500' },
  });
  expect(source.status()).toBe(200);
  const sourceBody = await source.json();
  expect(String(sourceBody.code)).toBe('0');
  const customers = sourceBody.data.records as {
    pid: string;
    tpm_cu_name: string;
    tpm_cu_contact?: string | null;
  }[];
  expect(
    customers.length,
    'initialize real project-delivery customer fixtures first',
  ).toBeGreaterThan(0);
  expect(Number(sourceBody.data.total), 'fixture must fit the preview/export 500-row limit').toBe(
    customers.length,
  );
  expect(
    customers.every(
      (row) => typeof row.tpm_cu_name === 'string' && row.tpm_cu_name.trim().length > 0,
    ),
  ).toBe(true);
  expect(
    customers.some(
      (row) => typeof row.tpm_cu_contact === 'string' && row.tpm_cu_contact.trim().length > 0,
    ),
    'at least one actual contact is required to prove both columns',
  ).toBe(true);

  await navigateToMenuByClick(page, ['元数据管理', '报表管理']);
  await page.getByRole('button', { name: '新建报表', exact: true }).click();
  await expect(page.getByTestId('report-template-actions')).toBeVisible();
  await page.waitForFunction(() => {
    const button = document.querySelector('[data-testid="report-template-import"]');
    return button && Object.keys(button).some((key) => key.startsWith('__reactProps$'));
  });
  const choose = page.waitForEvent('filechooser');
  await page.getByTestId('report-template-import').click();
  await (await choose).setFiles(templatePath);
  await expect(page.getByTestId('report-template-imported')).toBeVisible();
  const title = `客户报告格式验证_${Date.now().toString(36)}`;
  await page.getByRole('textbox', { name: '报表标题', exact: true }).fill(title);
  const create = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/report-definitions' &&
      response.request().method() === 'POST',
  );
  await page.getByTestId('report-designer-toolbar-btn-save').click();
  const createdResponse = await create;
  expect(createdResponse.status()).toBe(200);
  const persistedDsl = createdResponse.request().postDataJSON().dsl;
  expect(persistedDsl.dataSources).toEqual(template.dsl.dataSources);
  expect(persistedDsl.title).toBe(title);
  const created = await createdResponse.json();
  expect(String(created.code)).toBe('0');
  const reportPid = created.data.pid;
  expect(typeof reportPid).toBe('string');

  // Reopen via the real menu; observe UI GETs to prove identity/query persistence.
  await navigateToMenuByClick(page, ['元数据管理', '报表管理']);
  const definitionPath = `/api/report-definitions/${reportPid}`;
  const reopen = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === definitionPath && response.request().method() === 'GET',
  );
  await page.locator('tr').filter({ hasText: title }).getByText('打开', { exact: true }).click();
  const reopened = await reopen;
  expect(reopened.status()).toBe(200);
  expect(await reopened.finished()).toBeNull();
  const reopenedDefinition = await reopened.json();
  expect(String(reopenedDefinition.code)).toBe('0');
  expect(reopenedDefinition.data.pid).toBe(reportPid);
  expect(reopenedDefinition.data.dsl.dataSources).toEqual(persistedDsl.dataSources);
  await expect(page).toHaveURL(new RegExp(`/report-designer/${reportPid}$`));
  // Complete the initial UI read before navigating away. Response headers alone
  // do not mean Chrome has retained its body across a subsequent full reload.
  await expect(page.getByRole('textbox', { name: '报表标题', exact: true })).toHaveValue(title);
  // Reopen may issue parallel reads. A late response from that old document
  // must not satisfy the refresh assertion after Chrome discards its body.
  let refreshNavigationStarted = false;
  const refreshReads = new Set<Request>();
  const observeRefresh = (request: Request) => {
    if (request.isNavigationRequest() && request.frame() === page.mainFrame()) {
      refreshNavigationStarted = true;
    }
    if (
      refreshNavigationStarted &&
      new URL(request.url()).pathname === definitionPath &&
      request.method() === 'GET'
    )
      refreshReads.add(request);
  };
  page.on('request', observeRefresh);
  const reload = page
    .waitForResponse((response) => refreshReads.has(response.request()))
    .then(async (response) => {
      expect(response.status()).toBe(200);
      expect(await response.finished()).toBeNull();
      return response.json();
    });
  let definition;
  try {
    await page.reload();
    definition = await reload;
  } finally {
    page.off('request', observeRefresh);
  }
  expect(String(definition.code)).toBe('0');
  expect(definition.data.pid).toBe(reportPid);
  expect(definition.data.dsl.title).toBe(title);
  expect(definition.data.dsl.dataSources).toEqual(persistedDsl.dataSources);
  expect(definition.data.dsl.body).toEqual(persistedDsl.body);
  await expect(page.getByRole('textbox', { name: '报表标题', exact: true })).toHaveValue(title);
  await page.waitForFunction(() => {
    const toolbar = document.querySelector('[data-testid="report-designer-toolbar"]');
    const button =
      toolbar &&
      Array.from(toolbar.querySelectorAll('button')).find(
        (item) => item.textContent?.trim() === '预览',
      );
    return button && Object.keys(button).some((key) => key.startsWith('__reactProps$'));
  });
  const toolbar = page.getByTestId('report-designer-toolbar');
  const preview = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/dynamic/tpm_customer/list' &&
      response.request().method() === 'GET',
  );
  await toolbar.getByRole('button', { name: '预览', exact: true }).click();
  const previewResponse = await preview;
  expect(previewResponse.status()).toBe(200);
  const previewBody = await previewResponse.json();
  expect(String(previewBody.code)).toBe('0');
  expect(previewBody.data.records.map((row: { pid: string }) => row.pid).sort()).toEqual(
    customers.map((row) => row.pid).sort(),
  );
  for (const customer of customers) {
    await expect(page.getByRole('main')).toContainText(customer.tpm_cu_name);
    if (customer.tpm_cu_contact?.trim())
      await expect(page.getByRole('main')).toContainText(customer.tpm_cu_contact);
  }
  await page.screenshot({
    path: join(evidence!, 'SC06-formats-01-persisted-preview.png'),
    fullPage: true,
  });

  const artifacts: { format: string; filename: string; bytes: number; sha256: string }[] = [];
  for (const format of ['markdown', 'html', 'pdf'] as const) {
    const label = { markdown: 'Markdown', html: 'HTML', pdf: 'PDF' }[format];
    const extension = { markdown: 'md', html: 'html', pdf: 'pdf' }[format];
    const responsePromise = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === `/api/reports/export/${format}` &&
        response.request().method() === 'POST',
    );
    const downloadPromise = page.waitForEvent('download');
    await toolbar.getByRole('button', { name: `导出 ${label}`, exact: true }).click();
    const response = await responsePromise;
    expect(new URL(response.url()).origin).toBe(origin.origin);
    expect(response.status()).toBe(200);
    expect(response.request().postDataJSON().reportPid).toBe(reportPid);
    const contentType = response.headers()['content-type'];
    expect(contentType).toMatch(
      { markdown: /^text\/markdown\b/i, html: /^text\/html\b/i, pdf: /^application\/pdf\b/i }[
        format
      ],
    );
    expect(response.headers()['content-disposition']).toContain('attachment');
    const download = await downloadPromise;
    expect(await download.failure()).toBeNull();
    expect(download.suggestedFilename()).toMatch(new RegExp(`\\.${extension}$`));
    const artifactPath = join(evidence!, `SC06-manual-customer-report.${extension}`);
    await download.saveAs(artifactPath);
    const bytes = await readFile(artifactPath);
    expect(bytes.length).toBeGreaterThan(50);
    let text: string;
    if (format === 'html') {
      const html = bytes.toString('utf8');
      expect(html).toMatch(/^\s*<!doctype html>/i);
      const parsed = await page.evaluate((source) => {
        // Inert parsing: downloaded HTML is never executed.
        const document = new DOMParser().parseFromString(source, 'text/html');
        return {
          tableCount: document.querySelectorAll('table').length,
          headers: Array.from(document.querySelectorAll('th')).map((cell) => cell.textContent),
          scriptCount: document.querySelectorAll('script').length,
          text: document.body.textContent || '',
        };
      }, html);
      expect(parsed.tableCount).toBeGreaterThan(0);
      expect(parsed.headers).toEqual(expect.arrayContaining(['客户名称', '客户联系人']));
      expect(parsed.scriptCount).toBe(0);
      text = parsed.text;
    } else if (format === 'pdf') {
      expect(bytes.subarray(0, 5).toString()).toBe('%PDF-');
      text = execFileSync('pdftotext', ['-layout', artifactPath, '-'], { encoding: 'utf8' });
    } else {
      text = bytes.toString('utf8');
      expect(text).toMatch(/^# /);
      text = text.replace(/\\([\\`*_{}\[\]()#+.!|<>-])/g, '$1');
    }
    expect(compact(text)).toContain(compact(title));
    expect(text).toContain('客户名称');
    expect(text).toContain('客户联系人');
    for (const customer of customers) {
      expect(compact(text)).toContain(compact(customer.tpm_cu_name));
      if (customer.tpm_cu_contact?.trim())
        expect(compact(text)).toContain(compact(customer.tpm_cu_contact));
    }
    await writeFile(join(evidence!, `SC06-manual-customer-report.${extension}.parsed.txt`), text);
    artifacts.push({
      format,
      filename: download.suggestedFilename(),
      bytes: bytes.length,
      sha256: createHash('sha256').update(bytes).digest('hex'),
    });
    await info.attach(`SC06-manual-${format}`, { path: artifactPath, contentType });
  }
  expect(modelRequestCount, 'manual-template success must not be presented as AI generation').toBe(
    0,
  );
  const receipt = join(evidence!, 'SC06-manual-report-formats-receipt.json');
  await writeFile(
    receipt,
    JSON.stringify(
      {
        source: 'manual-design-template',
        provesLiveLlm: false,
        templateSha256: createHash('sha256').update(templateBytes).digest('hex'),
        reportPid,
        title,
        dataSources: persistedDsl.dataSources,
        customerOracle: customers.map(({ pid, tpm_cu_name, tpm_cu_contact }) => ({
          pid,
          name: tpm_cu_name,
          contact: tpm_cu_contact,
        })),
        artifacts,
      },
      null,
      2,
    ),
  );
  await info.attach('SC06-manual-report-formats-receipt', {
    path: receipt,
    contentType: 'application/json',
  });
});
