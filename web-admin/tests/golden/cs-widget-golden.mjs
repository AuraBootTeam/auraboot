#!/usr/bin/env node
/**
 * End-to-end golden for the embeddable customer-service widget.
 *
 * Drives the thing a customer actually gets: a plain HTML page on the customer's own origin, with
 * nothing on it but the <script> tag the embed centre hands out. The widget has to load itself,
 * open a session across origins, and stream a real answer — none of which a unit test can show.
 *
 * The page is served from a throwaway origin that is NOT the backend's, because same-origin would
 * quietly hide every CORS and origin-allowlist mistake this feature can make. A second origin that
 * is deliberately not allowlisted proves the guard actually bites.
 *
 * Usage:
 *   node tests/golden/cs-widget-golden.mjs \
 *     --backend http://localhost:6401 --site-key csk_... --shots ./artifacts
 */
import http from 'node:http';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { chromium } from '@playwright/test';

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, arg, i, all) => {
    if (arg.startsWith('--')) acc.push([arg.slice(2), all[i + 1]]);
    return acc;
  }, []),
);

const BACKEND = args.backend ?? 'http://localhost:6401';
const SITE_KEY = args['site-key'];
const SHOTS = args.shots ?? './artifacts/cs-golden';
const ALLOWED_PORT = Number(args['allowed-port'] ?? 5199);
const BLOCKED_PORT = Number(args['blocked-port'] ?? 5197);

if (!SITE_KEY) {
  console.error('--site-key is required');
  process.exit(2);
}

mkdirSync(SHOTS, { recursive: true });

const results = [];
const record = (name, ok, detail) => {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`);
};

/** A customer's website: one script tag, nothing else. */
function customerSite(port) {
  const html = `<!doctype html>
<html><head><meta charset="utf-8"><title>Acme Store</title></head>
<body style="font-family:system-ui;padding:40px">
  <h1>Acme Store</h1>
  <p>Buy the finest widgets.</p>
  <script src="${BACKEND}/api/public/cs/widget.js" data-site-key="${SITE_KEY}" async></script>
</body></html>`;
  const server = http.createServer((_req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(html);
  });
  return new Promise((resolve) => server.listen(port, () => resolve(server)));
}

const shot = async (page, name) => {
  const file = join(SHOTS, `${name}.png`);
  await page.screenshot({ path: file, fullPage: false });
  return file;
};

const allowed = await customerSite(ALLOWED_PORT);
const blocked = await customerSite(BLOCKED_PORT);
const browser = await chromium.launch();
// One context for the visitor, so localStorage persists across pages the way it does for a real
// person coming back to the site.
const visitorContext = await browser.newContext();

try {
  // ---------------------------------------------------------------- happy path
  const page = await visitorContext.newPage();
  const consoleErrors = [];
  page.on('console', (m) => m.type() === 'error' && consoleErrors.push(m.text()));

  await page.goto(`http://localhost:${ALLOWED_PORT}/`);

  // Playwright pierces shadow roots with CSS selectors, which is the only way to see inside the
  // widget at all — everything it renders lives in a closed-off shadow tree by design.
  const launcher = page.locator('[data-testid="cs-launcher"]');
  await launcher.waitFor({ state: 'visible', timeout: 15000 });
  record('widget loads on the customer site from a single script tag', true);
  await shot(page, '01-site-with-launcher');

  await launcher.click();
  const panel = page.locator('[data-testid="cs-panel"]');
  await panel.waitFor({ state: 'visible', timeout: 10000 });

  // The welcome message is the proof the session really opened: it comes from ab_cs_site, so it
  // could only have arrived by resolving the key and passing the origin allowlist.
  const welcome = page.locator('[data-testid="cs-msg-agent"]').first();
  await welcome.waitFor({ state: 'visible', timeout: 15000 });
  const welcomeText = (await welcome.textContent())?.trim() ?? '';
  record(
    'session opens cross-origin and the server-configured welcome renders',
    welcomeText.length > 0,
    JSON.stringify(welcomeText),
  );
  await shot(page, '02-panel-open-welcome');

  // ---------------------------------------------------------------- the AI answers
  await page.locator('[data-testid="cs-input"]').fill('How long is the Acme Widget warranty?');
  await page.locator('[data-testid="cs-send"]').click();
  await shot(page, '03-question-sent');

  const answer = page.locator('[data-testid="cs-msg-agent"]').last();
  // Wait for a SECOND agent bubble carrying text that is not the welcome. An earlier version of
  // this check just asked for "the last agent bubble, longer than 20 chars" — which the welcome
  // message satisfies, so it passed while the AI was answering nothing at all. The assertion has
  // to be able to tell the answer apart from what was already on screen.
  //
  // NOTE the null: waitForFunction's second parameter is the ARG passed to the page function, not
  // the options — putting options there silently leaves the default 30s timeout in place.
  await page.waitForFunction(
    (welcome) => {
      const root = document.querySelector('[data-aura-cs="root"]')?.shadowRoot;
      const bubbles = root?.querySelectorAll('[data-testid="cs-msg-agent"]');
      if (!bubbles || bubbles.length < 2) return false;
      const last = bubbles[bubbles.length - 1].textContent?.trim() ?? '';
      return last.length > 20 && last !== welcome;
    },
    welcomeText,
    { timeout: 120000 },
  );
  const answerText = (await answer.textContent())?.trim() ?? '';
  record(
    'the AI streams a real answer back into the panel',
    answerText.length > 20 && answerText !== welcomeText,
    `${answerText.length} chars: ${JSON.stringify(answerText.slice(0, 90))}`,
  );

  // The answer must come from THIS site's knowledge base. "37 months" is a fact that exists only
  // in the knowledge base bound to this site — a generic model would never invent that number, so
  // seeing it is proof the retrieval was scoped and actually reached the AI.
  record(
    "the answer is grounded in the site's own knowledge base",
    /37\s*months/i.test(answerText),
    answerText.includes('37') ? 'cites the site-specific fact' : 'no site-specific fact in the answer',
  );

  // Models answer in markdown. A bubble that shows literal ** around the emphasised words is the
  // kind of defect that only a screenshot (or this assertion) ever catches.
  const boldRendered = await page.locator('[data-testid="cs-msg-agent"]').last().locator('strong').count();
  record(
    'markdown emphasis is rendered, not shown as literal asterisks',
    !answerText.includes('**') && boldRendered > 0,
    `strong=${boldRendered}`,
  );
  await shot(page, '04-ai-answer');

  // A visitor message and an assistant message must both be on screen — a panel that shows only
  // one side is the classic "it looked like it worked" failure.
  const visitorBubbles = await page.locator('[data-testid="cs-msg-visitor"]').count();
  const agentBubbles = await page.locator('[data-testid="cs-msg-agent"]').count();
  record(
    'both sides of the conversation are rendered',
    visitorBubbles >= 1 && agentBubbles >= 2,
    `visitor=${visitorBubbles} agent=${agentBubbles}`,
  );

  record('no console errors on the customer page', consoleErrors.length === 0,
    consoleErrors.length ? consoleErrors.slice(0, 2).join(' | ') : 'clean');

  // ---------------------------------------------------------------- returning visitor
  // Same browser CONTEXT, not just a new page: browser.newPage() would open a fresh context with
  // its own empty localStorage, and the check would "fail" for reasons that have nothing to do
  // with the product.
  const reloaded = await visitorContext.newPage();
  await reloaded.goto(`http://localhost:${ALLOWED_PORT}/`);
  const storedToken = await reloaded.evaluate(() => window.localStorage.getItem('aura-cs-visitor-token'));
  record('the browser remembers the visitor for next time', Boolean(storedToken),
    storedToken ? `${storedToken.slice(0, 12)}…` : 'no token stored');
  await reloaded.close();

  // ---------------------------------------------------------------- the guard bites
  const rogue = await visitorContext.newPage();
  const rogueErrors = [];
  rogue.on('console', (m) => m.type() === 'error' && rogueErrors.push(m.text()));
  await rogue.goto(`http://localhost:${BLOCKED_PORT}/`);

  await rogue.locator('[data-testid="cs-launcher"]').click();
  // Same key, same widget — but this origin is not on the site's allowlist. The widget must say so
  // rather than opening a working chat.
  const systemMsg = rogue.locator('[data-testid="cs-msg-system"]').first();
  await systemMsg.waitFor({ state: 'visible', timeout: 15000 });
  const refusal = (await systemMsg.textContent())?.trim() ?? '';

  // Two contracts, and both matter. The visitor sees a sentence — a shopper on someone else's site
  // must never be shown an internal reason code. The site owner, who is the one who can fix it,
  // gets the exact code in the console.
  const rawReasonLogged = rogueErrors.some((e) => e.includes('origin_not_allowed'));
  record(
    'the same key on a NON-allowlisted origin is refused',
    rawReasonLogged,
    rawReasonLogged ? 'console carries origin_not_allowed for the integrator' : rogueErrors.join(' | '),
  );
  record(
    'the visitor is shown a sentence, not an internal reason code',
    refusal.length > 0 && !/origin_not_allowed|_/.test(refusal),
    JSON.stringify(refusal),
  );
  await shot(rogue, '05-blocked-origin');
  await rogue.close();
} finally {
  await browser.close();
  allowed.close();
  blocked.close();
}

const failed = results.filter((r) => !r.ok);
writeFileSync(join(SHOTS, 'results.json'), JSON.stringify(results, null, 2));

console.log(`\n${results.length - failed.length}/${results.length} passed — screenshots in ${SHOTS}`);
process.exit(failed.length === 0 ? 0 : 1);
