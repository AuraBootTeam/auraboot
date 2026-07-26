import { Client } from 'pg';
import { test, expect } from '../../fixtures';

test.use({ storageState: 'tests/storage/admin.json' });
test.describe.configure({ timeout: 120000 });

test('chatbi console: offline catalog fallback answers a governed metric question', async ({ page }) => {
  const errors: string[] = [];
  page.on('console', m => { if (m.type()==='error' && !m.text().includes('Optimize Dep')) errors.push(m.text()); });

  // The stack imports plugins/test-fixtures through the real CLI/import
  // pipeline. Prove resourceDirs.semantic reached the governed catalog before
  // exercising the zero-provider ChatBI path that consumes that catalog.
  const metaResponse = await page.request.get('/api/semantic/meta');
  expect(metaResponse.ok()).toBe(true);
  const metaEnvelope = await metaResponse.json() as {
    code?: string | number;
    data?: { models?: Array<{ pid?: string; code?: string }> };
  };
  expect(String(metaEnvelope.code)).toBe('0');
  const importedModel = metaEnvelope.data?.models?.find(model => model.code === 'e2e_orders');
  expect(importedModel?.pid).toBeTruthy();

  const db = new Client({
    host: process.env.PGHOST ?? '127.0.0.1',
    port: Number(process.env.PGPORT ?? 5432),
    user: process.env.PGUSER ?? 'auraboot',
    password: process.env.PGPASSWORD ?? 'auraboot',
    database: process.env.PGDATABASE ?? 'aura_boot',
  });
  await db.connect();
  const fixturePid = 'chatbi_semantic_golden_order';
  const question = `按订单状态统计订单数 ${Date.now()}`;
  try {
    const tenant = await db.query<{ tenant_id: string }>(
      'SELECT tenant_id FROM ab_semantic_model WHERE pid = $1',
      [importedModel!.pid],
    );
    expect(tenant.rows).toHaveLength(1);
    await db.query('DELETE FROM mt_e2et_order WHERE pid = $1', [fixturePid]);
    await db.query(
      `INSERT INTO mt_e2et_order
         (id, pid, tenant_id, e2et_order_title, e2et_order_status,
          e2et_order_amount, e2et_order_date)
       VALUES ($1, $2, $3, $4, $5, $6, CURRENT_DATE)`,
      [Date.now(), fixturePid, tenant.rows[0].tenant_id,
        'ChatBI semantic golden order', 'DRAFT', 88.50],
    );

    await page.goto('/semantic/ask', { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(2500); // hydration settle
    await expect(page.getByTestId('chatbi-page')).toBeVisible({ timeout: 10000 });
    // Capability banner explains both the zero-provider fallback and its boundary.
    const banner = page.getByTestId('chatbi-llm-banner');
    await expect(banner).toBeVisible();
    await expect(banner).toContainText(/语义目录|catalog/i);
    console.log('BANNER:', (await banner.innerText()).slice(0,80));

    // create a conversation
    await page.getByTestId('chatbi-new-conversation').click();
    await page.waitForTimeout(800);
    const convCount = await page.locator('[data-testid^="chatbi-conversation-"]').count();
    console.log('CONVERSATIONS:', convCount);

    // No LLM provider is configured in the golden stack. The exact catalog metric
    // and dimension labels must still execute against the imported dynamic model.
    await page.getByTestId('chatbi-input').fill(question);
    await page.getByTestId('chatbi-send').click();
    const answer = page.locator('[data-testid^="chatbi-answer-"]').first();
    await expect(answer).toBeVisible({ timeout: 60000 });
    await expect(answer).toContainText('keyword-catalog');
    await expect(answer).toContainText(/1\s*行/);
    await expect(answer).toContainText('DRAFT');

    const persisted = await db.query<{
      status: string;
      semantic_model_pid: string;
      tokens_json: unknown;
      semantic_request_json: { metrics?: string[] };
    }>(
      `SELECT status, semantic_model_pid, tokens_json, semantic_request_json
         FROM chatbi_answer
        WHERE nl_query = $1
        ORDER BY created_at DESC
        LIMIT 1`,
      [question],
    );
    expect(persisted.rows).toHaveLength(1);
    expect(persisted.rows[0].status).toBe('SUCCESS');
    expect(persisted.rows[0].semantic_model_pid).toBe(importedModel!.pid);
    expect(Array.isArray(persisted.rows[0].tokens_json)).toBe(true);
    expect(persisted.rows[0].semantic_request_json.metrics)
      .toContain('e2e_orders.order_count');

    const kind = await answer.getAttribute('data-testid');
    console.log('ANSWER_STATE:', kind);
    await page.screenshot({ path: process.env.SHOT + '/chatbi-01.png', fullPage: true });
    console.log('CONSOLE_ERRORS:', JSON.stringify(errors.slice(0,5)));
    expect(convCount).toBeGreaterThan(0);
    expect(errors).toEqual([]);
  } finally {
    await db.query('DELETE FROM mt_e2et_order WHERE pid = $1', [fixturePid]);
    await db.end();
  }
});
