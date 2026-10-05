/**
 * Temporary probe — dumps the inspector's rendered field testids for a seeded
 * stat-card/detail page. Writes to /tmp/pd-probe-fields.log and always passes.
 */
import { test, expect } from '@playwright/test';
import { uniqueId } from '../helpers';
import { loginViaUI, ensureBusinessSpace } from '../../helpers/auth-fixtures';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

const MODEL_CODE = 'e2et_order';

test('probe: inspector field testids for display blocks', async ({ page, baseURL }) => {
  test.setTimeout(120_000);
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await ensureBusinessSpace(page);

  const uid = uniqueId('probe');
  const resp = await page.request.post('/api/pages', {
    data: {
      name: `Probe ${uid}`,
      pageKey: `pd_probe_${uid}`.replace(/-/g, '_'),
      title: `Probe ${uid}`,
      kind: 'detail',
      modelCode: MODEL_CODE,
      schemaVersion: 3,
      blocks: [
        {
          id: 'detail_root',
          blockType: 'detail',
          title: 'Probe root',
          layout: { span: 12 },
          blocks: [
            { id: 'probe_stat', blockType: 'stat-card', title: 'Orders today', layout: { span: 12 } },
            { id: 'probe_desc', blockType: 'description', title: 'Notes', layout: { span: 12 } },
            { id: 'probe_strip', blockType: 'metric-strip', title: 'KPI', layout: { span: 12 } },
          ],
        },
      ],
      extension: { e2e: true, scenario: 'zz-probe-inspector' },
    },
  });
  expect(resp.ok(), `seed failed: ${resp.status()} ${await resp.text()} [url=${resp.url()}]`).toBeTruthy();
  const body = await resp.json();
  const pid = String(body.data?.pid ?? '');
  expect(pid).toBeTruthy();

  await page.goto(`/unified-designer?pageId=${pid}`, { waitUntil: 'domcontentloaded' });
  await page.getByTestId('unified-designer-workbench').waitFor({ state: 'visible', timeout: 30_000 });
  await page.getByTestId('outline-item-probe_stat').click();
  await page
    .getByTestId('inspector-selected-id')
    .waitFor({ state: 'visible', timeout: 10_000 })
    .catch(() => {});
  await page.waitForTimeout(1500);

  const dump = async (tag: string) => {
    const testids = await page.evaluate(() =>
      Array.from(document.querySelectorAll('[data-testid]'))
        .map((el) => el.getAttribute('data-testid'))
        .filter((id): id is string => id !== null && id.startsWith('inspector-field-')),
    );
    const dsValue = await page
      .getByTestId('inspector-field-dataSource')
      .inputValue()
      .catch(() => '<no-elem>');
    const lines: string[] = [
      `PID=${pid} TAG=${tag} selected=${await page.getByTestId('inspector-selected-id').textContent().catch(() => '?')}`,
      ...new Set(testids),
      `dataSourceValue=${dsValue}`,
    ];
    const fs = await import('fs');
    fs.appendFileSync('/tmp/pd-probe-fields.log', `${lines.join('\n')}\n---\n`);
  };
  await dump('fresh');

  // B1 sequence: fill dataSource + statCard JSON, save, reload, re-select.
  await page.getByTestId('inspector-field-dataSource').fill(`ds_orders_${uid}`);
  await page.getByTestId('inspector-field-statCard').fill(JSON.stringify({ value: 42, unit: 'orders', trend: '+12%', trendDirection: 'up', valueField: 'open_total' }, null, 2));
  await page.evaluate(() => new Promise<void>((r) => requestAnimationFrame(() => requestAnimationFrame(() => r()))));
  await page.getByTestId('inspector-json-field-apply-statCard').click().catch(() => {});
  await page.evaluate(() => new Promise<void>((r) => requestAnimationFrame(() => requestAnimationFrame(() => r()))));
  const saveBtn = page.getByTestId('designer-save');
  if (await saveBtn.isVisible().catch(() => false)) {
    await saveBtn.click();
  } else {
    await page.getByRole('button', { name: /保存|Save/ }).first().click();
  }
  await page
    .getByTestId('designer-dirty-state')
    .filter({ hasText: '已保存' })
    .waitFor({ state: 'visible', timeout: 20_000 })
    .catch(() => {});
  await page.waitForTimeout(1000);
  await dump('after-save');

  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.getByTestId('unified-designer-workbench').waitFor({ state: 'visible', timeout: 30_000 });
  await page.getByTestId('outline-item-probe_desc').click();
  await page.getByTestId('inspector-selected-id').waitFor({ state: 'visible', timeout: 10_000 }).catch(() => {});
  await page.waitForTimeout(2500);
  await dump('after-reload-desc');

  // UDW-055 probe: enter preview and dump all runtime-* testids.
  await page.getByTestId('designer-mode-preview').click().catch(() => {});
  await page.getByTestId('unified-runtime-preview').waitFor({ state: 'visible', timeout: 15000 }).catch(() => {});
  await page.waitForTimeout(2000);
  const runtimeIds = await page.evaluate(() =>
    Array.from(document.querySelectorAll('[data-testid]'))
      .map((el) => el.getAttribute('data-testid'))
      .filter((id) => id?.startsWith('runtime-')),
  );
  const fs5 = await import('fs');
  fs5.appendFileSync('/tmp/pd-probe-fields.log', `RUNTIME_IDS=${[...new Set(runtimeIds)].join(',')}\n`);

  // A1 probe: metric-strip field set after reload.
  await page.getByTestId('outline-item-probe_strip').click();
  await page.waitForTimeout(1500);
  await dump('strip-reload');
  // Persist-path candidates for variant/metrics.
  const stripCandidates = [
    'inspector-field-props.variant',
    'inspector-field-variant',
    'inspector-field-props.metrics',
    'inspector-field-metrics',
  ];
  let stripFilled = '';
  for (const tid of stripCandidates) {
    const f = page.getByTestId(tid);
    if (await f.isVisible({ timeout: 800 }).catch(() => false)) {
      const tag = await f.evaluate((el) => el.tagName);
      if (tag === 'SELECT') {
        await (f as any).selectOption('cards').catch(() => {});
      } else {
        await f.fill('[{"key":"k","label":"K"}]');
      }
      stripFilled = `${tid}(${tag})`;
      break;
    }
  }
  const save4 = page.getByTestId('designer-save');
  if (await save4.isVisible().catch(() => false)) {
    await save4.click();
  } else {
    await page.getByRole('button', { name: /保存|Save/ }).first().click();
  }
  await page.waitForTimeout(2500);
  const persisted3 = await page.request.get(`/api/pages/${pid}`);
  const ptext3 = await persisted3.text();
  const fs4 = await import('fs');
  fs4.appendFileSync('/tmp/pd-probe-fields.log', `STRIP_FILLED=${stripFilled}\nSTRIP_PERSISTED=${ptext3.slice(0, 1500)}\n`);
  await dump('strip-after-edit');
  // B2's real flow guarantees the designer registered the edit (dirty state)
  // before saving — replicate that guarantee here.
  await page
    .getByTestId('designer-dirty-state')
    .filter({ hasText: '未保存' })
    .waitFor({ state: 'visible', timeout: 10_000 })
    .catch(() => {});
  const save3 = page.getByTestId('designer-save');
  if (await save3.isVisible().catch(() => false)) {
    await save3.click();
  } else {
    await page.getByRole('button', { name: /保存|Save/ }).first().click();
  }
  await page.waitForTimeout(2500);
  const persisted2 = await page.request.get(`/api/pages/${pid}`);
  const ptext2 = await persisted2.text();
  const fs3 = await import('fs');
  fs3.appendFileSync('/tmp/pd-probe-fields.log', `STRIP_RESAVED=${stripFilled}\nSTRIP_PERSISTED=${ptext2.slice(0, 1500)}\n`);


});
