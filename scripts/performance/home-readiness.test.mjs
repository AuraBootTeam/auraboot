import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { installHomeReadiness } from './home-readiness.mjs';
const require = createRequire(new URL('../../web-admin/package.json', import.meta.url));
const { chromium } = require('@playwright/test');
const fixture = `<button data-testid="workbench-open-in-dashboard">Open dashboard</button><div class="react-grid-item">Grid</div><table><tbody><tr><td>暂无待办事项</td></tr></tbody></table><a data-testid="shortcut-row">A real shortcut</a><div data-testid="stat-card-inbox_pending"><div>Pending</div><div>0</div></div><button id="complete" onclick="this.insertAdjacentHTML('afterend', '<div data-testid=stat-card-inbox_urgent><div>Urgent</div><div>0</div></div>')">Complete stats</button>`;

test('a missing widget cannot become ready, and delayed runner observation does not inflate the browser timestamp', async () => {
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage();
    await page.clock.install();
    await page.setContent(fixture);
    await page.evaluate(installHomeReadiness);
    await page.clock.runFor(50);
    assert.equal(await page.evaluate(() => window.__perfHome.ready), undefined);
    await page.locator('#complete').click();
    await page.clock.runFor(50);
    const first = await page.evaluate(() => ({ ...window.__perfHome }));
    assert.ok(first.ready > 0 && first.stats >= first.structure);
    // Deliberate observer delay is the property under test, not a product wait workaround.
    await page.clock.fastForward(5000);
    const late = await page.evaluate(() => ({ marks: window.__perfHome, readAt: performance.now() }));
    assert.equal(late.marks.ready, first.ready);
    assert.ok(late.readAt - late.marks.ready >= 5000);
  } finally { await browser.close(); }
});
