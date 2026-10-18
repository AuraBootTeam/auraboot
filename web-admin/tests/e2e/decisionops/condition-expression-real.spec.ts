import { test, expect, type Page, type Response } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { navigateToMenuByClick } from '../helpers';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

const evidence = process.env.AURA_EVIDENCE_DIR;
if (!evidence) throw Error('Bind AURA_EVIDENCE_DIR to the owned runtime evidence round');
test.use({
  storageState: { cookies: [], origins: [] },
  viewport: { width: 1440, height: 1000 },
  locale: 'zh-CN',
});
async function payload(response: Pick<Response, 'status' | 'json'>) {
  const body = await response.json();
  expect(response.status(), JSON.stringify(body)).toBe(200);
  expect(String(body.code)).toBe('0');
  return body.data;
}
async function shot(page: Page, id: string) {
  await mkdir(join(evidence!, 'screenshots'), { recursive: true });
  await page.screenshot({ path: join(evidence!, 'screenshots', id + '.png'), fullPage: true });
}
async function login(page: Page, account = DEFAULT_TEST_ACCOUNT) {
  await page.goto('/login');
  await expect(page.getByTestId('login-page-root')).toHaveAttribute('data-hydrated', 'true');
  await page.locator('#identifier').fill(account.email);
  await page.locator('#password').fill(account.password);
  await page.getByRole('button', { name: '立即登录', exact: true }).click();
  await page.waitForURL((url) => !url.pathname.includes('/login'));
  if (new URL(page.url()).pathname === '/tenant-selection') {
    const spaces = (await (await page.request.get('/api/tenant-selection/my-spaces')).json()).data;
    const business = spaces.filter(
      (space: { spaceType: string }) => space.spaceType === 'business',
    );
    expect(business).toHaveLength(1);
    await page.getByTestId(`space-business-${business[0].tenantId}`).click();
  }
}
const amount = 'record:data.tpm_ac_amount',
  budget = 'record:data.tpm_pj_budget',
  risk = 'record:data.tpm_ac_risk',
  status = 'record:data.tpm_ac_status';
async function field(
  page: Page,
  id: string,
  model: string,
  key: string,
  operator: string,
  value?: string,
) {
  await page.getByLabel(`model-${id}`, { exact: true }).selectOption(`record:${model}`);
  await page.getByLabel(`field-${id}`, { exact: true }).selectOption(key);
  await page.getByLabel(`operator-${id}`, { exact: true }).selectOption(operator);
  if (value !== undefined) {
    const control = page.getByLabel(`value-${id}`, { exact: true });
    if (await control.evaluate((element) => element.tagName === 'SELECT'))
      await control.selectOption(value);
    else await control.fill(value);
  }
}
test('E01–E11 metadata → nested DSL → persisted draft → authoritative explained trial', async ({
  page,
  browser,
}) => {
  test.setTimeout(240000);
  await login(page);
  const catalogResponse = page.waitForResponse(
    (response) =>
      response.url().includes('/api/decision/facts/catalog') &&
      response.request().method() === 'GET',
  );
  await navigateToMenuByClick(page, ['条件片段库']);
  const catalog = await payload(await catalogResponse);
  expect(JSON.stringify(catalog)).toContain('tpm_ac_amount');
  expect(JSON.stringify(catalog)).toContain('tpm_pj_budget');
  await page.getByTestId('cfl-open-create').click();
  await page
    .getByTestId('fragment-code')
    .fill('delivery_expression_' + randomUUID().replaceAll('-', '').slice(0, 12));
  const code = await page.getByTestId('fragment-code').inputValue();
  await page.getByTestId('fragment-name').fill('交付异常审核 · 组合条件');
  await page.getByTestId('fragment-scope-type').selectOption('WORKFLOW');
  await page
    .getByTestId('fragment-description')
    .fill('交付金额与项目预算比较，结合风险等级和验收终态排除。');
  await page.getByTestId('cb-add').click();
  await field(page, '0', 'tpm_acceptance', amount, 'GTE', '100000');
  await page.getByTestId('cb-add').click();
  await field(page, '1', 'tpm_project', budget, 'GTE', '100000');
  await expect(page.getByLabel('model-0', { exact: true })).toHaveValue('record:tpm_acceptance');
  await shot(page, 'E01');
  await page.getByLabel('select-0', { exact: true }).check();
  await page.getByLabel('select-1', { exact: true }).check();
  await page.getByTestId('condition-wrap').click();
  await expect(page.getByLabel('group-op-0', { exact: true })).toHaveValue('AND');
  await page.getByRole('button', { name: '撤销', exact: true }).click();
  await expect(page.getByLabel('group-op-0', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '重做', exact: true }).click();
  await expect(page.getByLabel('group-op-0', { exact: true })).toHaveValue('AND');
  await shot(page, 'E04');
  await page.getByTestId('cb-add-group').click();
  await page.getByLabel('group-op-1', { exact: true }).selectOption('OR');
  await field(page, '1-0', 'tpm_acceptance', risk, 'EQ', 'high');
  await page.getByTestId('cb-add-1').click();
  await field(page, '1-1', 'tpm_acceptance', amount, 'GT');
  await page.getByLabel('value-kind-1-1', { exact: true }).selectOption('field');
  await page.getByLabel('right-model-1-1', { exact: true }).selectOption('record:tpm_project');
  await page.getByLabel('right-field-1-1', { exact: true }).selectOption(budget);
  await expect(page.getByLabel('right-field-1-1', { exact: true })).toHaveValue(budget);
  await shot(page, 'E05');
  await page.getByTestId('cb-add-group').click();
  await field(page, '2-0', 'tpm_acceptance', status, 'IN');
  await page.locator('.ce-enum-values summary').click();
  await page.getByLabel('value-2-0-accepted', { exact: true }).check();
  await page.getByLabel('value-2-0-returned', { exact: true }).check();
  await shot(page, 'E02');
  await page.locator('.ce-enum-values summary').click();
  await page.getByLabel('group-not-2', { exact: true }).check();
  await expect(page.getByTestId('cb-not-2')).toBeVisible();
  await shot(page, 'E03');
  const save = page.waitForResponse(
    (response) =>
      response.url().endsWith('/api/decision/condition-fragments') &&
      response.request().method() === 'POST',
  );
  await page.getByTestId('cfl-save-fragment').click();
  const saved = await payload(await save);
  const ast = saved.conditionSpec.root;
  expect(ast.children[1].children[1].right).toMatchObject({
    type: 'path',
    scope: 'record',
    path: 'data.tpm_pj_budget',
  });
  expect(ast.children[2].type).toBe('not');
  expect(ast.children[0].children[0].right.value).toBe(100000);
  await page.reload();
  await page.getByTestId(`cfl-row-${code}`).click();
  await page.getByTestId('cfl-open-edit').click();
  await expect(page.getByLabel('value-0-0', { exact: true })).toHaveValue('100000');
  await expect(page.getByLabel('right-field-1-1', { exact: true })).toHaveValue(budget);
  await shot(page, 'E09');
  const logsBefore = await payload(
    await page.request.get('/api/decision/logs/recent?decisionCode=__test__&page=0&size=1'),
  );
  await page.getByTestId('condition-trial-toggle').click();
  await page.getByLabel(`trial-${amount}`, { exact: true }).fill('128000');
  await page.getByLabel(`trial-${budget}`, { exact: true }).fill('150000');
  await page.getByLabel(`trial-${risk}`, { exact: true }).selectOption('high');
  await page.getByLabel(`trial-${status}`, { exact: true }).selectOption('draft');
  const trial = async (expected: string) => {
    const response = page.waitForResponse(
      (response) =>
        response.url().endsWith('/api/decision/test-run') && response.request().method() === 'POST',
    );
    await page.getByTestId('condition-server-run').click();
    const result = await payload(await response);
    expect(result.conditionTrace.result).toBe(expected);
    expect(result.conditionTrace.nodeResults.root).toBe(expected);
    await expect(page.getByTestId('condition-server-result')).toContainText(
      expected === 'TRUE' ? '条件成立' : expected === 'FALSE' ? '条件不成立' : '无法判断',
    );
    return result;
  };
  const matched = await trial('TRUE');
  expect(matched.conditionTrace.nodeResults['root.1.0']).toBe('TRUE');
  expect(matched.conditionTrace.nodeResults['root.1.1']).toBe('FALSE');
  await shot(page, 'E06');
  await page.getByTestId('cb-not-2').scrollIntoViewIfNeeded();
  await shot(page, 'E06-details');
  await page.getByTestId('condition-server-result').scrollIntoViewIfNeeded();
  await page.getByLabel(`trial-${risk}`, { exact: true }).selectOption('normal');
  await expect(page.getByTestId('condition-server-result')).toContainText('旧结果已失效');
  await shot(page, 'E07');
  await trial('FALSE');
  await page.getByLabel(`trial-${budget}`, { exact: true }).fill('110000');
  const fieldMatched = await trial('TRUE');
  expect(fieldMatched.conditionTrace.steps[3].right).toBe(110000);
  await page.getByLabel(`trial-${status}`, { exact: true }).selectOption('accepted');
  await trial('FALSE');
  await page.getByLabel(`trial-${status}`, { exact: true }).selectOption('draft');
  await page.getByLabel(`trial-${budget}`, { exact: true }).fill('');
  await trial('UNKNOWN');
  await page.getByLabel('delete-group-1', { exact: true }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await shot(page, 'E08');
  await page.getByRole('dialog').getByRole('button', { name: '取消', exact: true }).click();
  await page.getByLabel(`trial-${budget}`, { exact: true }).fill('110000');
  await trial('TRUE');
  const update = page.waitForResponse(
    (response) =>
      response.url().includes(`/condition-fragment-versions/${saved.pid}/draft`) &&
      response.request().method() === 'POST',
  );
  await page.getByTestId('cfl-save-fragment').click();
  expect((await payload(await update)).pid).toBe(saved.pid);
  const logsAfter = await payload(
    await page.request.get('/api/decision/logs/recent?decisionCode=__test__&page=0&size=1'),
  );
  expect(typeof logsBefore.total).toBe('number');
  expect(logsAfter.total).toBe(logsBefore.total);
  const invalid = await payload(
    await page.request.post('/api/decision/test-run', {
      data: {
        kind: 'SIMPLE_CONDITION',
        runtimeAdapter: 'AST_EVALUATOR',
        contentJson: { type: 'group', op: 'AND', children: [] },
        context: {},
        explain: true,
      },
    }),
  );
  expect(invalid.status).toBe('ERROR');
  expect(invalid.errors.length).toBeGreaterThan(0);
  expect(invalid.conditionTrace).toBeNull();
  expect(matched.actionPlans).toEqual([]);
  // Prepare only this test-owned fragment as an immutable version. Publishing UX is outside this editor journey.
  await payload(
    await page.request.post(`/api/decision/condition-fragment-versions/${saved.pid}/validate`),
  );
  await payload(
    await page.request.post(`/api/decision/condition-fragment-versions/${saved.pid}/publish`, {
      data: { impactAcknowledged: true },
    }),
  );
  await page.reload();
  await page.getByTestId(`cfl-row-${code}`).click();
  await expect(page.getByTestId('cfl-open-edit')).toHaveCount(0);
  await page.getByTestId('cfl-open-version').click();
  await page.getByLabel('value-0-0', { exact: true }).fill('120000');
  const nextVersion = page.waitForResponse(
    (response) =>
      response.url().endsWith(`/condition-fragments/${code}/versions`) &&
      response.request().method() === 'POST',
  );
  await page.getByTestId('cfl-save-fragment').click();
  const version2 = await payload(await nextVersion);
  const versions = await payload(
    await page.request.get(`/api/decision/condition-fragments/${code}/versions`),
  );
  expect(
    versions.find((version: { pid: string }) => version.pid === saved.pid).conditionSpec.root,
  ).toEqual(ast);
  expect(version2.conditionSpec.root.children[0].children[0].right.value).toBe(120000);
  await page.reload();
  await page.getByTestId(`cfl-row-${code}`).click();
  await page.getByTestId('cfl-open-edit').click();
  await expect(page.getByLabel('value-0-0', { exact: true })).toHaveValue('120000');
  await shot(page, 'E09-version');
  const anonymous = await browser.newContext({
    baseURL: new URL(page.url()).origin,
    extraHTTPHeaders: {
      Origin: new URL(page.url()).origin,
      Referer: new URL('/', page.url()).href,
    },
  });
  const denied = await anonymous.request.post('/api/decision/test-run', {
    data: {
      kind: 'SIMPLE_CONDITION',
      runtimeAdapter: 'AST_EVALUATOR',
      contentJson: ast,
      context: {},
      explain: true,
    },
  });
  expect([401, 403]).toContain(denied.status());
  await anonymous.close();
  await writeFile(
    join(evidence!, 'condition-browser-receipt.json'),
    JSON.stringify(
      {
        code,
        pid: saved.pid,
        ast,
        matched,
        fieldMatched,
        anonymousStatus: denied.status(),
        logsBefore: logsBefore.total,
        logsAfter: logsAfter.total,
        immutableVersionPid: saved.pid,
        nextDraftPid: version2.pid,
        ui: 'menu-driven authoring and persisted draft trial',
        sideEffectClaim:
          'trial uses stateless test-run with unchanged logs; test-owned fragment is separately published as an immutable-version fixture; no run-and-execute',
      },
      null,
      2,
    ),
  );
});

test('E10 a real reader can inspect nested conditions but cannot edit, publish or evaluate', async ({
  page,
  browser,
}) => {
  await login(page);
  const identity = await payload(await page.request.get('/api/auth/me'));
  const tenantId = String(identity.user.tenantId);
  const origin = new URL(page.url()).origin;
  const key = `condition_reader_${randomUUID().replaceAll('-', '').slice(0, 10)}`;
  // Import the canonical read-menu contract into this retained development tenant.
  const imported = await page.request.post('/api/plugins/import/import-directory-sync', {
    data: {path:resolve(process.cwd(),'../plugins/core-decisionops'),conflictStrategy:'OVERWRITE',deferReferenceValidation:true}
  });
  expect(imported.status()).toBe(200);expect((await imported.json()).success).toBe(true);

  const path = (name: string, dataType: string) => ({
    type: 'path',
    scope: 'record',
    path: `data.${name}`,
    dataType,
  });
  const literal = (value: string, dataType: string) => ({ type: 'literal', value, dataType });
  const ast = {
    type: 'group',
    op: 'AND',
    children: [
      {
        type: 'compare',
        left: path('tpm_ac_amount', 'decimal'),
        operator: 'GT',
        right: path('tpm_pj_budget', 'decimal'),
      },
      {
        type: 'group',
        op: 'OR',
        children: [
          {
            type: 'compare',
            left: path('tpm_ac_risk', 'enum'),
            operator: 'EQ',
            right: literal('high', 'enum'),
          },
          {
            type: 'compare',
            left: path('tpm_ac_status', 'enum'),
            operator: 'EQ',
            right: literal('draft', 'enum'),
          },
        ],
      },
      {
        type: 'not',
        child: {
          type: 'group',
          op: 'AND',
          children: [
            {
              type: 'compare',
              left: path('tpm_ac_status', 'enum'),
              operator: 'EQ',
              right: literal('accepted', 'enum'),
            },
          ],
        },
      },
    ],
  };
  const fragment = await payload(
    await page.request.post('/api/decision/condition-fragments', {
      data: {
        fragmentCode: key,
        fragmentName: '交付异常审核 · 只读复核',
        scopeType: 'WORKFLOW',
        conditionSpec: { root: ast },
        fieldRefs: [
          'record.data.tpm_ac_amount',
          'record.data.tpm_pj_budget',
          'record.data.tpm_ac_risk',
          'record.data.tpm_ac_status',
        ],
      },
    }),
  );
  const tree = await payload(await page.request.get('/api/permissions/tree'));
  const ids = new Map<string, number>();
  const collect = (nodes: Array<{ code: string; id: number; children?: any[] }>) =>
    nodes.forEach((n) => {
      ids.set(n.code, n.id);
      collect(n.children ?? []);
    });
  collect(tree);
  const role = await payload(
    await page.request.post('/api/roles', {
      data: {
        code: key,
        name: '条件规则只读复核',
        type: 'custom',
        status: 'active',
        scopeType: 'tenant',
        defaultDataScopeType: 'all',
      },
    }),
  );
  const grants = [
    'decision.definition.read',
    'model.tpm_acceptance.read',
    'model.tpm_project.read',
  ];
  for (const code of grants) expect(ids.has(code), `registered permission ${code}`).toBe(true);
  await payload(
    await page.request.put(`/api/permissions/matrix/${role.pid}/batch`, {
      data: grants.map((code) => ({ permissionId: ids.get(code), granted: true })),
    }),
  );
  const email = `${key}@e2e.local`,
    password = 'Test2026x';
  await payload(
    await page.request.post('/api/admin/users', {
      data: {
        email,
        displayName: '条件规则只读复核',
        initialPassword: password,
        roleCodes: [key],
        sendInviteEmail: false,
      },
    }),
  );
  const readerContext = await browser.newContext({
    baseURL: origin,
    storageState: { cookies: [], origins: [] },
    locale: 'zh-CN',
    viewport: { width: 1440, height: 1000 },
    extraHTTPHeaders: { Origin: origin, Referer: origin + '/' },
  });
  const reader = await readerContext.newPage();
  try {
    await login(reader, { ...DEFAULT_TEST_ACCOUNT, email, password });
    const me = await payload(await reader.request.get('/api/auth/me'));
    expect(me.user.email).toBe(email);
    expect(String(me.user.tenantId)).toBe(tenantId);
    expect(me.permissions.roles.map((r: { code: string }) => r.code)).toContain(key);
    expect(me.permissions.roles.map((r: { code: string }) => r.code)).not.toContain('tenant_admin');
    const permissions: string[] = me.permissions.permissionCodes;
    for (const code of grants) expect(permissions).toContain(code);
    for (const code of [
      'decision.definition.manage',
      'decision.definition.publish',
      'decision.runtime.evaluate',
      '*',
    ])
      expect(permissions).not.toContain(code);
    await navigateToMenuByClick(reader, ['条件片段库']);
    await reader.getByPlaceholder('搜索片段编码、名称').fill(key);
    await reader.getByTestId(`cfl-row-${key}`).click();
    const view = reader.getByTestId('cfl-readonly-condition');
    await expect(view).toBeVisible();
    await expect(view).toContainText('只读');
    await expect(view.getByLabel('field-0', { exact: true })).toHaveValue(amount);
    await expect(view.getByLabel('right-field-0', { exact: true })).toHaveValue(budget);
    await expect(view.getByLabel('group-op-1', { exact: true })).toHaveValue('OR');
    await expect(view.getByLabel('group-not-2', { exact: true })).toBeChecked();
    const controls = view.locator('fieldset input, fieldset select, fieldset button');
    expect(await controls.count()).toBeGreaterThan(15);
    for (const control of await controls.all()) await expect(control).toBeDisabled();
    for (const id of [
      'cfl-open-create',
      'cfl-open-version',
      'cfl-validate-selected',
      'cfl-publish-selected',
      'cfl-run-evaluate',
    ])
      await expect(reader.getByTestId(id)).toBeDisabled();
    await expect(reader.getByTestId('cfl-open-edit')).toHaveCount(0);
    await expect(reader.getByTestId('cfl-save-fragment')).toHaveCount(0);
    const before = await payload(
      await reader.request.get(`/api/decision/condition-fragments/${key}/versions`),
    );
    const denied: number[] = [];
    for (const [url, data] of [
      [
        `/api/decision/condition-fragment-versions/${fragment.pid}/draft`,
        {
          fragmentName: 'Unauthorized change',
          conditionSpec: { root: { type: 'group', op: 'AND', children: [] } },
        },
      ],
      [
        `/api/decision/condition-fragments/${key}/versions`,
        { fragmentName: 'Unauthorized version', conditionSpec: { root: ast } },
      ],
      [`/api/decision/condition-fragment-versions/${fragment.pid}/validate`, {}],
      [
        `/api/decision/condition-fragment-versions/${fragment.pid}/publish`,
        { impactAcknowledged: true },
      ],
      [
        '/api/decision/test-run',
        {
          kind: 'SIMPLE_CONDITION',
          runtimeAdapter: 'AST_EVALUATOR',
          contentJson: ast,
          context: {},
          explain: true,
        },
      ],
    ] as Array<[string, unknown]>) {
      const response = await reader.request.post(url, { data });
      expect(response.status(), url).toBe(403);
      denied.push(response.status());
    }
    const after = await payload(
      await reader.request.get(`/api/decision/condition-fragments/${key}/versions`),
    );
    expect(after).toEqual(before);
    expect(after).toHaveLength(1);
    expect(after[0].conditionSpec.root).toEqual(ast);
    await reader.reload();
    await expect(reader.getByTestId('cfl-readonly-condition')).toBeVisible();
    await expect(reader.getByTestId('cfl-open-create')).toBeDisabled();
    await reader.getByPlaceholder('搜索片段编码、名称').fill(key);
    await expect(reader.getByTestId('cfl-count')).toHaveText('1');
    await shot(reader, 'E10-readonly');
    await reader.getByLabel('group-not-2', {exact:true}).scrollIntoViewIfNeeded();
    await shot(reader, 'E10-readonly-details');
    await writeFile(
      join(evidence!, 'readonly-receipt.json'),
      JSON.stringify(
        { role: key, tenantId, permissionCodes: permissions, denied, before, after },
        null,
        2,
      ),
    );
  } finally {
    await readerContext.close();
  }
});

test('R01 restores a persisted business table and retains server rules after reload', async ({
  page,
}) => {
  await login(page);
  const code = `condition_table_${randomUUID().replaceAll('-', '').slice(0, 10)}`;
  const name = '交付审批分级 · 保存版本回读';
  await payload(
    await page.request.post('/api/decision/definitions', {
      data: { decisionCode: code, decisionName: name, scopeType: 'WORKFLOW', enabled: true },
    }),
  );
  const table = {
    hitPolicy: 'FIRST',
    inputs: [
      {
        id: 'delivery_amount',
        label: '交付金额（元）',
        expr: { type: 'path', scope: 'record', path: 'data.tpm_ac_amount', dataType: 'decimal' },
        dataType: 'decimal',
      },
    ],
    outputs: [{ id: 'approval_route', label: '审批路径', dataType: 'string' }],
    rules: [
      {
        ruleId: 'high_amount',
        priority: 10,
        when: { delivery_amount: { operator: 'GTE', value: 168000 } },
        then: { approval_route: 'enhanced' },
      },
    ],
    defaultOutput: { approval_route: 'standard' },
  };
  const version = await payload(
    await page.request.post(`/api/decision/definitions/${code}/versions`, {
      data: {
        kind: 'DECISION_TABLE',
        runtimeAdapter: 'PLATFORM_DECISION_TABLE',
        contentJson: table,
      },
    }),
  );
  await navigateToMenuByClick(page, ['策略工作台']);
  await page.getByTestId('strategy-business-decision').selectOption(code);
  await expect(page.getByTestId('strategy-studio')).toContainText(name);
  await expect(page.getByTestId('dt-in-delivery_amount')).toHaveText('交付金额（元）');
  await expect(page.getByLabel('val-0-delivery_amount', { exact: true })).toHaveValue('168000');
  await expect(page.getByTestId('strategy-publish')).toBeDisabled();
  await page
    .getByTestId('strategy-test-inputs')
    .getByLabel('交付金额（元）', { exact: true })
    .fill('188000');
  await page.getByTestId('strategy-run-test').click();
  await expect(page.getByTestId('strategy-test-outputs')).toContainText('enhanced');
  await shot(page, 'R01-restored');
  await page.reload();
  await page.getByTestId('strategy-business-decision').selectOption(code);
  await expect(page.getByLabel('val-0-delivery_amount', { exact: true })).toHaveValue('168000');
  const after = await payload(await page.request.get(`/api/decision/definitions/${code}/versions`));
  expect(after).toHaveLength(1);
  expect(after[0].pid).toBe(version.pid);
  expect(after[0].contentJson).toEqual(table);
  await writeFile(
    join(evidence!, 'table-restore-receipt.json'),
    JSON.stringify({ code, versionPid: version.pid, table, after }, null, 2),
  );
});
