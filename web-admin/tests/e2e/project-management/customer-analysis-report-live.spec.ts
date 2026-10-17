/** Real-provider customer analysis. Missing external prerequisites fail; this suite never substitutes a model. */
import { test, expect } from '@playwright/test';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { join } from 'node:path';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

test.use({
  storageState: { cookies: [], origins: [] },
  locale: 'zh-CN',
  viewport: { width: 1440, height: 1000 },
});

interface ProjectRow {
  tpm_pj_name: string;
  tpm_pj_budget: number | string;
}
interface Metric {
  field: string;
  aggregation: string;
  alias?: string;
}

test('SC06 real customer analysis persists a report and exports matching Markdown HTML and PDF', async ({
  page,
}, info) => {
  test.setTimeout(240_000);
  expect(process.env.PW_REQUIRE_LIVE_LLM, 'blocked: explicitly opt in to a real provider').toBe(
    '1',
  );
  const provider = process.env.PW_EXPECT_LIVE_PROVIDER;
  const model = process.env.PW_EXPECT_LIVE_MODEL;
  expect(provider, 'blocked: name the expected real provider').toBeTruthy();
  expect(model, 'blocked: name the expected real model').toBeTruthy();
  expect(provider).not.toMatch(/stub|fake|mock/i);
  expect(model).not.toMatch(/stub|fake|mock/i);
  const origin = new URL(process.env.PLAYWRIGHT_BASE_URL || '');
  expect(['localhost', '127.0.0.1']).toContain(origin.hostname);
  const evidence = process.env.AURA_EVIDENCE_DIR;
  expect(evidence, 'blocked: bind this run to its registered evidence directory').toBeTruthy();
  await mkdir(evidence!, { recursive: true });
  // This is artifact parsing, not a substitute for the browser-driven export action.
  execFileSync('pdftotext', ['-v'], { stdio: 'pipe' });
  const customer = process.env.PW_REPORT_CUSTOMER_NAME || '华辰制造';

  await page.goto('/login');
  await page.locator('input#identifier, input#email').fill(DEFAULT_TEST_ACCOUNT.email);
  await page.locator('input#password').fill(DEFAULT_TEST_ACCOUNT.password);
  await page.getByRole('button', { name: '立即登录', exact: true }).click();
  await page.getByTestId('sidebar').getByRole('link', { name: '项目', exact: true }).click();
  await expect(page).toHaveURL(/\/p\/tpm_project$/);
  const identity = await (await page.request.get('/api/auth/me')).json();
  expect(String(identity.code)).toBe('0');
  expect(String(identity.data.user.tenantId)).toBe(process.env.AURA_ADMIN_TENANT_ID);
  expect(identity.data.user.email).toBe(DEFAULT_TEST_ACCOUNT.email);

  await expect(page.getByRole('table')).toContainText(customer);

  const customerResponse = await page.request.get('/api/dynamic/tpm_customer/list', {
    params: {
      pageNum: '1',
      pageSize: '20',
      filters: JSON.stringify([{ fieldName: 'tpm_cu_name', operator: 'EQ', value: customer }]),
    },
  });
  expect(customerResponse.status()).toBe(200);
  const customerPage = await customerResponse.json();
  expect(String(customerPage.code)).toBe('0');
  expect(customerPage.data.records).toHaveLength(1);
  const customerPid = customerPage.data.records[0].pid;
  expect(typeof customerPid).toBe('string');
  const sourceResponse = await page.request.get('/api/dynamic/tpm_project/list', {
    params: {
      pageNum: '1',
      pageSize: '500',
      filters: JSON.stringify([{ fieldName: 'tpm_pj_customer', operator: 'EQ', value: customer }]),
    },
  });
  expect(sourceResponse.status()).toBe(200);
  const source = await sourceResponse.json();
  expect(String(source.code)).toBe('0');
  const projects = source.data.records as ProjectRow[];
  expect(projects.length, 'blocked: initialize the real project-delivery fixture').toBeGreaterThan(
    0,
  );
  expect(Number(source.data.total)).toBe(projects.length);
  const expected = new Map<string, number>();
  for (const project of projects) {
    const budget = Number(project.tpm_pj_budget);
    expect(Number.isFinite(budget)).toBe(true);
    expected.set(project.tpm_pj_name, (expected.get(project.tpm_pj_name) || 0) + budget);
  }

  await expect(page.getByTestId('ai-panel-toggle')).toBeVisible({ timeout: 15000 });
  await page.getByTestId('ai-panel-toggle').click();
  const panel = page.getByTestId('aurabot-panel');
  await expect(panel).toBeVisible();
  await panel.getByTestId('aurabot-history-trigger').click();
  await panel.getByTestId('aurabot-new-session').click();
  await expect(panel.getByTestId('aurabot-history-dropdown')).toHaveCount(0);
  const question = `请分析${customer}的项目预算：按项目名称列出预算合计，以表格展示，并给出客户交付管理的简短分析说明。必须查询系统内的真实项目数据，仅分析这家客户，不要猜测，不要执行任意 SQL。`;
  const streamPromise = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/ai/aurabot/chat/stream' &&
      response.request().method() === 'POST',
  );
  const input = panel.locator('textarea');
  await expect(input).toBeEnabled();
  await input.fill(question);
  await panel.getByTestId('aurabot-send').click();
  const stream = await streamPromise;
  expect(new URL(stream.url()).origin).toBe(origin.origin);
  expect(stream.status(), 'blocked or failed: real provider stream unavailable').toBe(200);
  const request = stream.request().postDataJSON();
  expect(request.message).toBe(question);
  const streamBody = await stream.text();
  expect(streamBody).not.toContain('@@AURABOOT_STUB');
  let trace: { traceId: string; input: string } | undefined;
  await expect
    .poll(
      async () => {
        const response = await page.request.get('/api/ai/traces', {
          params: { sessionId: request.sessionId, pageSize: '20' },
        });
        expect(response.status()).toBe(200);
        trace = (await response.json()).records.find(
          (entry: { input: string }) => entry.input === question,
        );
        return trace?.traceId;
      },
      { message: 'this completed question must have a persisted trace', timeout: 15_000 },
    )
    .toBeTruthy();
  const detailResponse = await page.request.get(`/api/ai/traces/${trace!.traceId}`);
  expect(detailResponse.status()).toBe(200);
  const detail = await detailResponse.json();
  expect(detail.trace.metadata.provider_code).toBe(provider);
  const generationModels = detail.spans
    .map((span: { model?: string }) => span.model)
    .filter(Boolean);
  expect(generationModels.length).toBeGreaterThan(0);
  expect(generationModels).toContain(model);
  expect(generationModels.join(' ')).not.toMatch(/stub|fake|mock/i);

  const card = panel.getByTestId('chatbi-result-card');
  await expect(card).toHaveCount(1);
  await expect(card).toHaveAttribute('data-row-count', String(expected.size));
  for (const name of expected.keys()) await expect(card).toContainText(name);
  await page.screenshot({
    path: join(evidence!, 'SC06-report-01-real-analysis.png'),
    fullPage: true,
  });
  const savedPromise = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/report-definitions' &&
      response.request().method() === 'POST',
  );
  await card.getByTestId('chatbi-save-report').click();
  const savedResponse = await savedPromise;
  expect(savedResponse.status()).toBe(200);
  const payload = savedResponse.request().postDataJSON();
  const query = payload.dsl.dataSources.analysis.aggregateQuery;
  expect(query.modelCode).toBe('tpm_project');
  expect(query.dimensions).toContain('tpm_pj_name');
  expect(
    query.filters.some(
      (filter: { field: string; operator: string; value: unknown }) =>
        filter.operator === 'eq' &&
        ((filter.field === 'tpm_pj_customer' && filter.value === customer) ||
          (filter.field === 'tpm_pj_customer_id' && filter.value === customerPid)),
    ),
  ).toBe(true);
  const metric = query.metrics.find(
    (entry: Metric) => entry.field === 'tpm_pj_budget' && entry.aggregation === 'sum',
  ) as Metric;
  expect(
    metric,
    'the report must preserve a real budget aggregate, not an AI-authored static table',
  ).toBeTruthy();
  const budgetColumn = metric.alias || `${metric.field}_${metric.aggregation}`;
  expect(
    payload.dsl.body.some(
      (block: { blockType: string; content?: string }) =>
        block.blockType === 'rich-text' && Boolean(block.content?.trim()),
    ),
  ).toBe(true);
  const saved = (await savedResponse.json()).data;
  expect(typeof saved.pid).toBe('string');
  await card.getByTestId('chatbi-saved-report').click();
  await expect(page).toHaveURL(new RegExp(`/report-designer/${saved.pid}$`));
  await page.reload();
  const toolbar = page.getByTestId('report-designer-toolbar');
  await expect(toolbar).toBeVisible();
  await page.waitForFunction(() => {
    const toolbar = document.querySelector('[data-testid="report-designer-toolbar"]');
    const button =
      toolbar &&
      Array.from(toolbar.querySelectorAll('button')).find(
        (item) => item.textContent?.trim() === '预览',
      );
    return button && Object.keys(button).some((key) => key.startsWith('__reactProps$'));
  });
  const previewPromise = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/reports/query/aggregate' &&
      response.request().method() === 'POST',
  );
  await toolbar.getByRole('button', { name: '预览', exact: true }).click();
  const previewResponse = await previewPromise;
  expect(previewResponse.status()).toBe(200);
  expect(previewResponse.request().postDataJSON()).toEqual(query);
  for (const name of expected.keys()) await expect(page.getByRole('main')).toContainText(name);
  await page.screenshot({
    path: join(evidence!, 'SC06-report-02-persisted-report.png'),
    fullPage: true,
  });

  const receipts: { format: string; filename: string; bytes: number; sha256: string }[] = [];
  for (const format of ['json', 'markdown', 'html', 'pdf'] as const) {
    const label = { json: 'JSON', markdown: 'Markdown', html: 'HTML', pdf: 'PDF' }[format];
    const extension = { json: 'json', markdown: 'md', html: 'html', pdf: 'pdf' }[format];
    const responsePromise = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === `/api/reports/export/${format}` &&
        response.request().method() === 'POST',
    );
    const downloadPromise = page.waitForEvent('download');
    await toolbar.getByRole('button', { name: `导出 ${label}`, exact: true }).click();
    const response = await responsePromise;
    expect(response.status()).toBe(200);
    expect(response.request().postDataJSON().reportPid).toBe(saved.pid);
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toMatch(new RegExp(`\\.${extension}$`));
    const path = join(evidence!, `SC06-customer-analysis.${extension}`);
    await download.saveAs(path);
    const bytes = await readFile(path);
    expect(bytes.length).toBeGreaterThan(50);
    if (format === 'json') {
      const artifact = JSON.parse(bytes.toString('utf8'));
      expect(artifact.reportDsl.dataSources.analysis.aggregateQuery).toEqual(query);
      const rows = artifact.dataSets.analysis as Record<string, unknown>[];
      expect(rows).toHaveLength(expected.size);
      for (const row of rows)
        expect(Number(row[budgetColumn])).toBe(expected.get(String(row.tpm_pj_name)));
    } else {
      let text: string;
      if (format === 'html') {
        const parsed = await page.evaluate((html) => {
          // DOMParser creates an inert document: downloaded HTML is never executed.
          const document = new DOMParser().parseFromString(html, 'text/html');
          return {
            hasTable: Boolean(document.querySelector('table')),
            hasScript: Boolean(document.querySelector('script')),
            text: document.body.textContent || '',
          };
        }, bytes.toString('utf8'));
        expect(parsed.hasTable).toBe(true);
        expect(parsed.hasScript).toBe(false);
        text = parsed.text;
      } else if (format === 'pdf') {
        expect(bytes.subarray(0, 5).toString()).toBe('%PDF-');
        text = execFileSync('pdftotext', ['-layout', path, '-'], { encoding: 'utf8' });
      } else {
        text = bytes.toString('utf8');
        expect(text).toMatch(/^# /);
      }
      expect(text).toContain(customer);
      for (const [name, budget] of expected) {
        expect(text.replace(/\s/g, '')).toContain(name.replace(/\s/g, ''));
        expect(text.replace(/[,\s]/g, '')).toContain(String(budget));
      }
    }
    receipts.push({
      format,
      filename: download.suggestedFilename(),
      bytes: bytes.length,
      sha256: createHash('sha256').update(bytes).digest('hex'),
    });
    await page.screenshot({
      path: join(evidence!, `SC06-report-export-${format}.png`),
      fullPage: true,
    });
  }
  await writeFile(
    join(evidence!, 'SC06-customer-analysis-live-receipt.json'),
    JSON.stringify(
      {
        provider,
        model,
        generationModels,
        traceId: trace!.traceId,
        reportPid: saved.pid,
        customer,
        query,
        expectedRows: [...expected].map(([name, budget]) => ({ name, budget })),
        receipts,
      },
      null,
      2,
    ),
  );
  await info.attach('real-provider-report-receipt', {
    path: join(evidence!, 'SC06-customer-analysis-live-receipt.json'),
    contentType: 'application/json',
  });
});
