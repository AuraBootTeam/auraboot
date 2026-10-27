import { pdfBodyWithoutRunningBands } from '../../helpers/pdf-running-text';
import { navigateToMenuByClick } from '../helpers';
import { liveShowcaseLogin } from '../../helpers/live-showcase-login';
import { requireRuntimeUrl } from '../../helpers/environments';
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
  const origin = new URL(requireRuntimeUrl('base'));
  expect(['localhost', '127.0.0.1']).toContain(origin.hostname);
  const evidence = process.env.AURA_EVIDENCE_DIR;
  expect(evidence, 'blocked: bind this run to its registered evidence directory').toBeTruthy();
  await mkdir(evidence!, { recursive: true });
  // This is artifact parsing, not a substitute for the browser-driven export action.
  execFileSync('pdftotext', ['-v'], { stdio: 'pipe' });
  const showcaseFixture = process.env.PW_REPORT_SHOWCASE_FIXTURE === '1';
  const customer =
    process.env.PW_REPORT_CUSTOMER_NAME ||
    (showcaseFixture ? '景川精密制造' : '华辰制造');

  page.setDefaultTimeout(20000);
  await liveShowcaseLogin(page);
  await navigateToMenuByClick(page, ['项目交付', '项目']);
  await expect(page).toHaveURL(/\/p\/tpm_project$/);
  const identity = await (await page.request.get('/api/auth/me')).json();
  expect(String(identity.code)).toBe('0');
  expect(String(identity.data.user.tenantId)).toBe(process.env.AURA_ADMIN_TENANT_ID);
  expect(identity.data.user.email).toBe(DEFAULT_TEST_ACCOUNT.email);

  // Explicit setup through governed commands; report actions remain browser driven.
  // A separate customer prevents repeated draft tests from polluting this report's scope.
  if (showcaseFixture) {
    const list = async (modelCode: string, field: string, value: string) => {
      const response = await page.request.get(`/api/dynamic/${modelCode}/list`, {
        params: {
          pageNum: '1',
          pageSize: '50',
          filters: JSON.stringify([{ fieldName: field, operator: 'EQ', value }]),
        },
      });
      expect(response.status()).toBe(200);
      const result = await response.json();
      expect(String(result.code)).toBe('0');
      return result.data.records;
    };
    const create = async (code: string, payload: Record<string, unknown>) => {
      const response = await page.request.post(`/api/meta/commands/execute/${code}`, {
        data: { operationType: 'create', payload },
      });
      expect(response.status()).toBe(200);
      const result = await response.json();
      expect(String(result.code)).toBe('0');
      expect(result.data.phaseReached).toBe('completed');
    };
    let customers = await list('tpm_customer', 'tpm_cu_name', customer);
    if (customers.length === 0) {
      await create('tpm:create_customer', {
        tpm_cu_name: customer,
        tpm_cu_contact: '林晓（虚构业务案例）',
      });
      customers = await list('tpm_customer', 'tpm_cu_name', customer);
    }
    expect(customers).toHaveLength(1);
    const fixtureProjects = [
      { name: '供应链协同平台', budget: 128000, code: 'JC-2026-001' },
      { name: '服务交付二期', budget: 286000, code: 'JC-2026-002' },
      { name: '供应商对账升级', budget: 132000.5, code: 'JC-2026-003' },
    ];
    for (const project of fixtureProjects) {
      const existing = await list('tpm_project', 'tpm_pj_code', project.code);
      if (existing.length === 0)
        await create('tpm:create_project', {
          tpm_pj_code: project.code,
          tpm_pj_name: project.name,
          tpm_pj_customer_id: customers[0].pid,
          tpm_pj_owner_user: identity.data.user.pid,
          tpm_pj_budget: project.budget,
          tpm_pj_description: '客户经营分析的独立虚构案例；不代表实际客户或收入。',
        });
      const records = await list('tpm_project', 'tpm_pj_code', project.code);
      expect(records).toHaveLength(1);
      expect(records[0]).toMatchObject({
        tpm_pj_name: project.name,
        tpm_pj_customer_id: customers[0].pid,
      });
      expect(Number(records[0].tpm_pj_budget)).toBe(project.budget);
    }
    await writeFile(
      join(evidence!, 'R1-fixture.json'),
      JSON.stringify(
        {
          customer,
          projects: fixtureProjects,
          setup: 'governed commands; existing records verified, never overwritten',
        },
        null,
        2,
      ),
    );
  }

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
      filters: JSON.stringify([
        { fieldName: 'tpm_pj_customer_id', operator: 'EQ', value: customerPid },
      ]),
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
  await expect(panel.getByTestId('aurabot-context-label')).toContainText('项目');
  await expect(panel.getByTestId('aurabot-context-label')).not.toContainText('tpm_project');
  const historyBounds = await panel.getByTestId('aurabot-history-trigger').boundingBox();
  expect(historyBounds?.y).toBeGreaterThanOrEqual(56);
  await panel.getByTestId('aurabot-history-trigger').click();
  await panel.getByTestId('aurabot-new-session').click();
  await expect(panel.getByTestId('aurabot-history-dropdown')).toHaveCount(0);
  const question = `${customer}名下每个项目的预算是多少？请生成可以点击“存为报表”并刷新数据的预算分析表，不要只回复文字或静态表格。先重新核对客户记录，按所属客户精确关联查询，再按项目分组对项目预算求和，每个项目一行。分析表之外，用不超过300字的简洁纯文本列预算合计并给两条仅基于预算的管理建议，预算不是收入；不要推测进度、日期和收入。正文只用业务名称，不要展示数据库字段名、模型编码、查询语句和任何内部标识。金额使用千分位和两位小数。第一行写客户全称，只依据本轮查询结果，不引用历史会话或历史项目数量。`;
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
  expect(
    detail.spans
      .filter((span: any) => span.type === 'generation')
      .every((span: any) => span.inputTokens > 0 && span.outputTokens > 0),
  ).toBe(true);
  expect(typeof detail.trace.output).toBe('string');
  expect(detail.trace.output.length).toBeGreaterThan(80);
  const firstGeneration = detail.spans.find((span: any) => span.type === 'generation');
  expect(
    firstGeneration.toolDefinitions.map((tool: any) => tool.name),
    'reference resolution must survive policy filtering into the real provider catalog',
  ).toContain('platform_resolve_reference');
  const calls = detail.spans.flatMap((span: any) => span.toolCalls || []);
  // Exact ownership may be established by the dedicated resolver or a governed customer query.
  // Do not force a specific tool name when the same authoritative lookup is actually performed.
  const referenceLookup = calls.some(
    (call: any) =>
      call.name === 'platform_resolve_reference' &&
      call.input?.modelCode === 'tpm_project' &&
      call.input?.fieldCode === 'tpm_pj_customer_id' &&
      call.input?.displayValue === customer,
  );
  const customerLookup = calls.some(
    (call: any) =>
      call.input?.action === 'query' &&
      call.input?.modelCode === 'tpm_customer' &&
      call.input?.dimensions?.includes('pid') &&
      call.input?.dimensions?.includes('tpm_cu_name') &&
      call.input?.filters?.some(
        (f: any) =>
          f.field === 'tpm_cu_name' && ['eq', 'like'].includes(f.operator) && f.value === customer,
      ),
  );
  expect(
    referenceLookup || customerLookup,
    'this turn must actually look up the customer record; a name prefix or historical guess is insufficient',
  ).toBe(true);
  // The human reviewer keeps the live narrative but removes a duplicate Markdown table.
  // The governed report table above remains the authoritative, refreshable source of figures.
  let adoptedNarrative = detail.trace.output
    .split('\n')
    .filter((line: string) => !line.includes('|'))
    .join('\n')
    .trim();
  // Explicit human terminology review, preserving the original provider trace.
  const businessTerms: Record<string, string> = {
    tpm_pj_customer_id: '所属客户',
    tpm_pj_name: '项目名称',
    tpm_pj_budget: '项目预算',
    tpm_cu_name: '客户名称',
    tpm_customer: '客户',
    tpm_project: '项目',
  };
  for (const [code, label] of Object.entries(businessTerms))
    adoptedNarrative = adoptedNarrative.replaceAll(code, label);
  expect(adoptedNarrative).not.toMatch(/\btpm_[a-z_]+\b/);
  expect(adoptedNarrative.length).toBeGreaterThan(80);
  expect(adoptedNarrative).not.toContain('|');
  // Human review uses independently queried facts rather than certifying the raw model's qualitative claims.
  const budgets = [...expected.entries()].sort((a, b) => b[1] - a[1]);
  const totalBudget = budgets.reduce((sum, [, amount]) => sum + amount, 0);
  expect(totalBudget).toBeGreaterThan(0);
  const money = (value: number) =>
    value.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const approvedNarrative =
    `${customer}\n按所属客户关联核对 ${budgets.length} 个项目：\n` +
    budgets
      .map(([name, amount], index) => `${index + 1}. ${name}：${money(amount)} 元`)
      .join('\n') +
    `\n预算合计：${money(totalBudget)} 元。\n人工确认的管理建议：\n1. ${budgets[0][0]}占预算合计 ${((budgets[0][1] / totalBudget) * 100).toFixed(1)}%，建议单独跟踪其预算使用与变更审批。\n2. 统一项目预算复核口径，按交付里程碑核对实际支出；预算不等于收入。`;
  await writeFile(
    join(evidence!, 'SC06-human-review.json'),
    JSON.stringify(
      {
        rawModelNarrative: detail.trace.output,
        terminologyReviewedDraft: adoptedNarrative,
        approvedNarrative,
        mode: 'explicit human review and factual rewrite',
        note: 'Approved narrative is edited from independent source facts; it is not a verbatim or automatically certified model answer.',
      },
      null,
      2,
    ),
  );
  const reviewedAnalysis =
    'AI分析（人工复核并修订后写入；数据见上表，建议不代替审批结论）\n' +
    approvedNarrative +
    '\n人工审阅补充：盈利判断还需核对实际收入与实际成本，不能仅凭预算与合同额的差额下结论。';
  // Full names and exact amounts are asserted below in persisted query rows and every export.
  // A concise narrative may legitimately omit a repeated customer prefix.
  expect(detail.trace.output).toContain(customer);

  // The model may perform auxiliary reads. Select a result by its actual scoped query,
  // never by DOM order or a requirement that a real LLM call exactly one tool.
  const candidates = new Map<string, any>();
  for (const line of streamBody.split('\n')) {
    if (!line.startsWith('data:')) continue;
    const event = JSON.parse(line.slice(5));
    const result = event.data?.data || event.result?.data;
    if (
      result?.analysisId &&
      result.modelCode === 'tpm_project' &&
      result.dataSource?.dimensions?.includes('tpm_pj_name') &&
      result.dataSource?.metrics?.some(
        (m: any) => m.field === 'tpm_pj_budget' && m.aggregation === 'sum',
      ) &&
      result.dataSource?.filters?.some(
        (f: any) =>
          f.field === 'tpm_pj_customer_id' && f.operator === 'eq' && f.value === customerPid,
      )
    ) {
      candidates.set(result.analysisId, result);
    }
  }
  expect(
    candidates.size,
    'AI must resolve the real customer relationship and produce a scoped budget query',
  ).toBeGreaterThan(0);
  const selected = [...candidates.values()].at(-1)!;
  expect(selected.records).toHaveLength(expected.size);
  const card = panel.locator(
    `[data-testid="chatbi-result-card"][data-analysis-id="${selected.analysisId}"]`,
  );
  await expect(card).toHaveCount(1);
  await expect(card).toHaveAttribute('data-row-count', String(expected.size));
  await expect(card).toBeVisible();
  await expect.poll(() => card.innerText()).not.toMatch(/\btpm_[a-z_]+\b/);
  if (referenceLookup) {
    const resolution = panel.getByTestId('reference-resolution-result');
    await expect(resolution).toContainText(customer);
    await expect(resolution.getByTestId('reference-resolution-details')).not.toHaveAttribute('open');
    await expect.poll(() => resolution.innerText()).not.toMatch(/storedValue|targetModel|modelCode/);
  }
  await card.scrollIntoViewIfNeeded();
  await expect(card).toBeInViewport({ratio: 1});
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
        filter.field === 'tpm_pj_customer_id' &&
        filter.value === customerPid,
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
  if (await panel.isVisible()) await page.getByTestId('ai-panel-toggle').click();
  await page.reload();
  const toolbar = page.getByTestId('report-designer-toolbar');
  await expect(toolbar).toBeVisible();
  await page
    .getByRole('textbox', { name: '报表标题', exact: true })
    .fill(`${customer} · 项目预算与交付建议`);
  if (showcaseFixture) {
    await toolbar.getByRole('button', { name: '设置', exact: true }).click();
    const settings = page.getByRole('heading', { name: '页面设置', exact: true }).locator('../..');
    await settings.getByRole('spinbutton').nth(0).fill('14');
    await settings.getByRole('spinbutton').nth(2).fill('14');
    await settings.getByRole('button', { name: '应用', exact: true }).click();
  }
  await page.getByTestId('block-palette').getByText('页眉', { exact: true }).click();
  await page.getByTestId('block-palette').getByText('页脚', { exact: true }).click();
  await page.getByTestId('drag-handle-analysis').locator('..').click();
  const columns = payload.dsl.body.find((block: any) => block.id === 'analysis').columns;
  const reviewedLabels: Record<string, string> = {
    tpm_pj_name: '项目名称',
    tpm_pj_status: '项目状态',
    tpm_pj_budget: '项目预算（元）',
    tpm_pj_risk_summary: '交付风险',
    [budgetColumn]: '项目预算（元）',
  };
  for (const entry of query.metrics as Metric[]) {
    if (entry.aggregation === 'count') {
      reviewedLabels[entry.alias || `${entry.field}_${entry.aggregation}`] = '项目记录数';
    }
  }
  const tableProperties = page
    .getByTestId('block-property-panel')
    .getByTestId('schema-config-field-columns');
  for (let index = 0; index < columns.length; index++) {
    const column = columns[index];
    await expect(
      tableProperties.getByRole('textbox', { name: '字段名称*', exact: true }).nth(index),
    ).toHaveValue(column.field);
    if (reviewedLabels[column.field])
      await tableProperties
        .getByRole('textbox', { name: '显示名称', exact: true })
        .nth(index)
        .fill(reviewedLabels[column.field]);
    if (column.field === budgetColumn) {
      await tableProperties.getByRole('combobox', { name: '格式', exact: true }).nth(index).click();
      await page.getByRole('option', { name: '金额', exact: true }).click();
      await tableProperties
        .getByRole('combobox', { name: '对齐方式', exact: true })
        .nth(index)
        .click();
      await page.getByRole('option', { name: '右对齐', exact: true }).click();
      await tableProperties
        .getByRole('spinbutton', { name: '列宽', exact: true })
        .nth(index)
        .fill('150');
    }
    if (column.field === 'tpm_pj_name')
      await tableProperties
        .getByRole('spinbutton', { name: '列宽', exact: true })
        .nth(index)
        .fill('340');
  }
  {
    // Review the requested name/budget presentation for every live response,
    // including extra identity dimensions, without changing the governed query.
    for (let index = columns.length - 1; index >= 0; index--) {
      if (!['tpm_pj_name', budgetColumn].includes(columns[index].field)) {
        await tableProperties
          .getByRole('button', { name: 'remove', exact: true })
          .nth(index)
          .click();
      }
    }
    const retained = columns.filter((column: any) =>
      ['tpm_pj_name', budgetColumn].includes(column.field),
    );
    expect(retained).toHaveLength(2);
    if (retained[0].field !== 'tpm_pj_name')
      await tableProperties.getByRole('button', { name: '上移', exact: true }).nth(1).click();
  }
  await page.screenshot({ path: join(evidence!, 'SC06-report-properties.png'), fullPage: true });
  await page.getByTestId('drag-handle-analysis-summary').locator('..').click();
  await page
    .getByTestId('block-property-panel')
    .getByPlaceholder('输入文本内容…')
    .fill(reviewedAnalysis);
  const reviewedSave = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === `/api/report-definitions/${saved.pid}` &&
      r.request().method() === 'PUT',
  );
  await page.getByTestId('report-designer-toolbar-btn-save').click();
  expect((await reviewedSave).status()).toBe(200);
  await page.reload();
  const reviewed = await (await page.request.get(`/api/report-definitions/${saved.pid}`)).json();
  expect(reviewed.data.dsl.body.find((block: any) => block.id === 'analysis-summary').content).toBe(
    reviewedAnalysis,
  );
  expect(reviewed.data.title).toBe(`${customer} · 项目预算与交付建议`);
  expect(
      reviewed.data.dsl.body
        .find((block: any) => block.id === 'analysis')
        .columns.map((column: any) => column.field),
    ).toEqual(['tpm_pj_name', budgetColumn]);
  expect(reviewed.data.dsl.dataSources.analysis.aggregateQuery).toEqual(query);
  for (const column of reviewed.data.dsl.body.find((block: any) => block.id === 'analysis')
    .columns) {
    if (reviewedLabels[column.field]) expect(column.label).toBe(reviewedLabels[column.field]);
  }
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
  for (const budget of expected.values())
    await expect(page.getByRole('main')).toContainText(
      `¥${budget.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`,
    );
  await expect(page.getByRole('main')).not.toContainText('tpm_');
  expect(
    reviewed.data.dsl.body
      .find((block: any) => block.id === 'analysis')
      .columns.find((column: any) => column.field === budgetColumn),
  ).toMatchObject({ format: 'currency', align: 'right', width: 150 });
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
        expect(text).toContain('Generated by AuraBoot Platform');
        // Verify exact running bands at each page boundary before comparing the
        // complete narrative; never discard arbitrary body lines or amounts.
        text = pdfBodyWithoutRunningBands(text, `${customer} · 项目预算与交付建议`);
      } else {
        text = bytes.toString('utf8');
        expect(text).toMatch(/^# /);
        // Rich-text paragraphs are literal text; decode CommonMark punctuation escapes for comparison.
        text = text.replace(/\\([\\`*_{}\[\]()#+\-.!|>])/g, '$1');
      }
      expect(text).toContain(customer);
      expect(text.replace(/\s/g, '')).toContain(reviewedAnalysis.replace(/\s/g, ''));
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
  if (showcaseFixture) {
    const longReview =
      reviewedAnalysis +
      '\n' +
      Array.from(
        { length: 20 },
        (_, index) =>
          `复盘检查${String(index + 1).padStart(2, '0')}：请负责人核对项目预算调整记录、审批依据与实际支出凭证。本条为人工审核清单，不是已完成事项。`,
      ).join('\n');
    const saveNarrative = async (content: string) => {
      await toolbar.getByRole('button', { name: '编辑', exact: true }).click();
      await page.getByTestId('drag-handle-analysis-summary').locator('..').click();
      await page
        .getByTestId('block-property-panel')
        .getByPlaceholder('输入文本内容…')
        .fill(content);
      const response = page.waitForResponse(
        (r) =>
          new URL(r.url()).pathname === `/api/report-definitions/${saved.pid}` &&
          r.request().method() === 'PUT',
      );
      await page.getByTestId('report-designer-toolbar-btn-save').click();
      expect((await response).status()).toBe(200);
      await toolbar.getByRole('button', { name: '预览', exact: true }).click();
    };
    try {
      await saveNarrative(longReview);
      const downloadPromise = page.waitForEvent('download');
      await toolbar.getByRole('button', { name: '导出 PDF', exact: true }).click();
      const download = await downloadPromise;
      const path = join(evidence!, 'SC06-pagination-boundary.pdf');
      await download.saveAs(path);
      const text = execFileSync('pdftotext', ['-layout', path, '-'], { encoding: 'utf8' });
      const pages = text.split('\f').filter((value) => value.trim());
      expect(pages.length).toBeGreaterThan(1);
      expect(pages.length).toBeLessThanOrEqual(4);
      for (const value of pages)
        expect(value.replace('Generated by AuraBoot Platform', '').trim().length).toBeGreaterThan(
          80,
        );
      for (let index = 1; index <= 20; index++)
        expect(text).toContain(`复盘检查${String(index).padStart(2, '0')}`);
      const body = pdfBodyWithoutRunningBands(text, `${customer} · 项目预算与交付建议`).replace(
        /\s/g,
        '',
      );
      expect(body).toContain(longReview.replace(/\s/g, ''));
      await writeFile(
        join(evidence!, 'SC06-pagination-receipt.json'),
        JSON.stringify(
          {
            pages: pages.length,
            reviewItems: 20,
            fullNarrativeMatched: true,
            blankPages: 0,
            fixture: 'explicit human review checklist; not generated AI findings',
          },
          null,
          2,
        ),
      );
    } finally {
      await saveNarrative(reviewedAnalysis);
      await page.reload();
      const final = await (await page.request.get(`/api/report-definitions/${saved.pid}`)).json();
      expect(
        final.data.dsl.body.find((block: any) => block.id === 'analysis-summary').content,
      ).toBe(reviewedAnalysis);
    }
  }
  await writeFile(
    join(evidence!, 'SC06-customer-analysis-live-receipt.json'),
    JSON.stringify(
      {
        provider,
        model,
        generationModels,
        rawAnalysis: detail.trace.output,
        adoptedNarrative,
        reviewAction:
          'Reviewed business terms and Schema properties; original provider trace and governed fact table retained',
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
