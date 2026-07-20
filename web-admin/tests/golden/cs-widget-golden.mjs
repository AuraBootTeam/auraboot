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
import { createHmac } from 'node:crypto';
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
// The site's HMAC key. A host site holds this on ITS SERVER and signs its own user ids with it.
// Optional: without it the identified-visitor tier is skipped rather than silently passing.
const IDENTITY_SECRET = args['identity-secret'];

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
  // Wait for the stream to FINISH, not for it to have produced some text. The widget disables the
  // send button for the duration of a turn and re-enables it in onDone/onError, and onDone replaces
  // the bubble with the answer carried by the final frame — so send-enabled is the point at which
  // the bubble holds the whole answer.
  //
  // This used to wait for `last.length > 20`, which is satisfied by the first fragment of the
  // reply. Whether that mattered depended on how the provider chunked its output: a model that
  // emits the answer in one piece passed, and one that opens with a preamble — deepseek begins
  // "Based on the retrieved information," — was read at 35 characters, before the fact the
  // grounding assertion looks for had arrived. The suite then reported an ungrounded answer and a
  // missing markdown emphasis, neither of which was true.
  //
  // Requiring the agent bubble to exist first also removes the race with the click: the bubble is
  // appended after setBusy(true), so once it is there, an enabled send button means the turn ended.
  await page.waitForFunction(
    (welcome) => {
      const root = document.querySelector('[data-aura-cs="root"]')?.shadowRoot;
      const bubbles = root?.querySelectorAll('[data-testid="cs-msg-agent"]');
      if (!bubbles || bubbles.length < 2) return false;
      const send = root?.querySelector('[data-testid="cs-send"]');
      if (!send || send.disabled) return false;
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

  // The answer must come from THIS site's knowledge base. 37 months is a fact that exists only in
  // the knowledge base bound to this site — a generic model would never invent that number, so
  // seeing it is proof the retrieval was scoped and actually reached the AI.
  //
  // Match the number and the noun, not one phrasing of them. The first version of this asked for
  // /37\s*months/ and the model answered "a 37-month warranty" — grounded, correct, and scored as
  // a failure. An assertion about whether a fact arrived must not also be an assertion about the
  // hyphen the model chose to put in it.
  const grounded = /37[\s-]*months?/i.test(answerText);
  record(
    "the answer is grounded in the site's own knowledge base",
    grounded,
    grounded ? 'cites the site-specific fact (37 months)' : 'no site-specific fact in the answer',
  );

  // Models answer in markdown. A bubble that shows literal ** around the emphasised words is the
  // kind of defect that only a screenshot (or this assertion) ever catches.
  //
  // What is asserted is the widget's rendering, not the model's prose style. Requiring strong > 0
  // outright made this a test of whether the model felt like emphasising something: deepseek
  // answered "**37 months**" and passed, qwen answered "The Acme Widget warranty is 37 months from
  // the date of purchase." — complete, correct, no emphasis anywhere — and was scored as a
  // rendering failure. The invariant that actually belongs to the widget is that a visitor never
  // sees raw markers; whether emphasis appears at all is the model's business.
  const boldRendered = await page.locator('[data-testid="cs-msg-agent"]').last().locator('strong').count();
  const leaksMarkers = answerText.includes('**');
  record(
    'markdown emphasis is rendered, not shown as literal asterisks',
    !leaksMarkers,
    leaksMarkers
      ? `literal ** left in the bubble: ${JSON.stringify(answerText.slice(0, 90))}`
      : boldRendered > 0
        ? `strong=${boldRendered}`
        : 'model used no emphasis this turn — nothing to render, no markers leaked',
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

  // ---------------------------------------------------------------- identified visitor (HMAC)
  if (IDENTITY_SECRET) {
    const sign = (externalUserId) =>
      createHmac('sha256', IDENTITY_SECRET).update(externalUserId).digest('hex');

    // A brand-new browser context: no localStorage, nothing this person has ever left behind. The
    // only thing tying them to their history is the identity their own site vouches for.
    const otherDevice = await browser.newContext();
    const devicePage = await otherDevice.newPage();
    await devicePage.goto(`http://localhost:${ALLOWED_PORT}/`);
    await devicePage.waitForFunction(() => Boolean(window.AuraCS));

    const identified = await devicePage.evaluate(
      async ([base, key, externalUserId, userHash]) => {
        const response = await fetch(`${base}/api/public/cs/session`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Site-Key': key },
          body: JSON.stringify({ externalUserId, userHash }),
        });
        return { status: response.status, body: await response.text() };
      },
      [BACKEND, SITE_KEY, 'alice', sign('alice')],
    );
    record(
      'a correctly signed identity is accepted on a device that has never been here',
      identified.status === 200,
      `HTTP ${identified.status}`,
    );

    // The attack: same claim, a signature the browser made up. This is the only thing standing
    // between a stranger and alice's conversation history.
    const forged = await devicePage.evaluate(
      async ([base, key]) => {
        const response = await fetch(`${base}/api/public/cs/session`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Site-Key': key },
          body: JSON.stringify({ externalUserId: 'alice', userHash: 'f'.repeat(64) }),
        });
        return { status: response.status, body: await response.text() };
      },
      [BACKEND, SITE_KEY],
    );
    record(
      'a forged identity signature is refused — nobody can claim to be alice',
      forged.status === 403 && forged.body.includes('identity_hash_invalid'),
      `HTTP ${forged.status}`,
    );

    // And an unsigned claim, which is what a naive integration would send.
    const unsigned = await devicePage.evaluate(
      async ([base, key]) => {
        const response = await fetch(`${base}/api/public/cs/session`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Site-Key': key },
          body: JSON.stringify({ externalUserId: 'alice' }),
        });
        return { status: response.status, body: await response.text() };
      },
      [BACKEND, SITE_KEY],
    );
    record(
      'an unsigned identity claim is refused, not quietly downgraded to anonymous',
      unsigned.status === 403 && unsigned.body.includes('identity_hash_required'),
      `HTTP ${unsigned.status}`,
    );

    await otherDevice.close();
  } else {
    console.log('SKIP  identified-visitor tier (pass --identity-secret to cover it)');
  }
} finally {
  await browser.close();
  allowed.close();
  blocked.close();
}

const failed = results.filter((r) => !r.ok);
writeFileSync(join(SHOTS, 'results.json'), JSON.stringify(results, null, 2));

console.log(`\n${results.length - failed.length}/${results.length} passed — screenshots in ${SHOTS}`);
process.exit(failed.length === 0 ? 0 : 1);
