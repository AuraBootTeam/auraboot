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
          ],
        },
      ],
      extension: { e2e: true, scenario: 'zz-probe-inspector' },
    },
  });
  expect(resp.ok(), `seed failed: ${resp.status()}`).toBeTruthy();
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
        .filter((id) => id?.startsWith('inspector-field-')),
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
  await page.getByTestId('outline-item-probe_stat').click();
  await page.getByTestId('inspector-selected-id').waitFor({ state: 'visible', timeout: 10_000 }).catch(() => {});
  await page.waitForTimeout(2500);
  await dump('after-reload');
});
