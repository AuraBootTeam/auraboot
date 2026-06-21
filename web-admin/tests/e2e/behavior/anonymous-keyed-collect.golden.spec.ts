/**
 * Anonymous Keyed Collect — End-to-End Golden (SP4)
 *
 * Proves the full ANONYMOUS telemetry loop a customer's PUBLISHED low-code app drives:
 *   public @auraboot/track SDK (site_key, no login) → POST /api/collect/keyed
 *     → server resolves tenant from the key → ab_behavior_event (user_id NULL, anon_id set)
 *     → that tenant's UV/PV dashboard counts the anonymous visitor.
 *
 * ┌────────────────────────────────────────────────────────────────────────────────┐
 * │ AK-00  Import live path: global UNIQUE(site_key) index exists (SP2 IT could not  │
 * │        run createFieldIndex against a registered model — this is the first real  │
 * │        import→SiteKeyIndexInitializer→createFieldIndex coverage).                │
 * │ AK-01  CORS: cross-origin preflight on /api/collect/keyed is allowed (public,    │
 * │        X-Site-Key, no credentials) — a customer-domain app can actually call it. │
 * │ AK-02  Real browser: published-app page loads the built IIFE SDK, inits with a   │
 * │        real abk_ key, fires pageview+click, flushes cross-origin → events land   │
 * │        under the key's tenant with user_id NULL + a persistent anon_id (cookie). │
 * │ AK-03  Multi-key/tenant isolation + unknown→403 + disabled→403 (API, exact).     │
 * │ AK-04  Dashboard counts the anonymous visitor in UV (real browser, admin).       │
 * └────────────────────────────────────────────────────────────────────────────────┘
 *
 * Prereqs: host-first stack UP with core-site-key imported (index converged), Playwright
 * env points at it. Run:
 *   cd web-admin && eval "$(../scripts/oss-golden-stack.sh env <name>)" \
 *     && PW_SKIP_WEBSERVER=1 npx playwright test -c playwright.config.ts --project chromium \
 *        tests/e2e/behavior/anonymous-keyed-collect.golden.spec.ts
 */

import { test, expect } from '../../fixtures';
import { execSync } from 'node:child_process';
import path from 'node:path';
import { PSQL_BASE, PG_ENV, BACKEND_URL } from '../../helpers/environments';

const DIST = path.resolve(process.cwd(), 'packages/track/dist/aura-track.global.js');
const KEYED_URL = `${BACKEND_URL}/api/collect/keyed`;

function psql(sql: string): string {
  return execSync(`${PSQL_BASE} -P pager=off -t -A -c "${sql.replace(/"/g, '\\"')}"`, {
    encoding: 'utf-8',
    env: { ...PG_ENV, PGPASSWORD: process.env.PGPASSWORD ?? 'auraboot' },
    timeout: 10_000,
  }).trim();
}

function adminAuth(): { jwt: string; tenantId: string } {
  const out = execSync(
    `curl -sf -X POST ${BACKEND_URL}/api/auth/login -H 'Content-Type: application/json' ` +
      `-d '{"email":"admin@auraboot.com","password":"Test2026x"}'`,
    { encoding: 'utf-8', timeout: 10_000 },
  );
  const token = JSON.parse(out)?.data?.jwt as string;
  if (!token) throw new Error(`Admin login failed: ${out}`);
  const raw = Buffer.from(
    token.split('.')[1] + '='.repeat((4 - (token.split('.')[1].length % 4)) % 4),
    'base64',
  ).toString('utf-8');
  const m = raw.match(/"tenantId"\s*:\s*(\d+)/);
  if (!m) throw new Error(`No tenantId in JWT: ${raw}`);
  return { jwt: token, tenantId: m[1] };
}

/** Create a real site key via the platform command; returns the server-generated abk_ key. */
function createSiteKey(jwt: string, name: string): string {
  execSync(
    `curl -sf -X POST ${BACKEND_URL}/api/meta/commands/execute/behavior_site_key:create ` +
      `-H 'Authorization: Bearer ${jwt}' -H 'Content-Type: application/json' ` +
      `-d '${JSON.stringify({ payload: { name } })}'`,
    { encoding: 'utf-8', timeout: 10_000 },
  );
  const key = psql(`SELECT site_key FROM mt_behavior_site_key WHERE name='${name.replace(/'/g, "''")}'`);
  if (!/^abk_[0-9A-Za-z]{20,}$/.test(key)) throw new Error(`No abk_ key generated for ${name}: ${key}`);
  return key;
}

/** POST a keyed batch as a published app would (curl → explicit X-Site-Key + Origin). */
function keyedPost(
  siteKey: string,
  events: object[],
  origin = 'https://customer-app.example.com',
): { status: number; body: string } {
  const out = execSync(
    `curl -s -o /tmp/keyed-body.txt -w '%{http_code}' -X POST ${KEYED_URL} ` +
      `-H 'Content-Type: application/json' -H 'X-Site-Key: ${siteKey}' -H 'Origin: ${origin}' ` +
      `-d '${JSON.stringify({ events }).replace(/'/g, "'\\''")}'`,
    { encoding: 'utf-8', timeout: 10_000 },
  );
  return { status: parseInt(out, 10), body: execSync('cat /tmp/keyed-body.txt', { encoding: 'utf-8' }) };
}

let evtSeq = 0;
function evt(name: string, anonId: string, extra: Record<string, unknown> = {}): object {
  // event_id is VARCHAR(40); the real SDK emits a 26-char ULID. Keep synthetic ids short + unique.
  const eventId = `ak${(evtSeq++).toString(36)}${Math.random().toString(36).slice(2, 12)}`;
  return {
    eventId,
    schemaVersion: '1',
    eventName: name,
    eventCategory: name === 'page_view' ? 'navigation' : 'ui_interaction',
    source: 'web',
    occurredAt: new Date().toISOString(),
    clientSessionId: `sess-${anonId}`,
    anonId,
    ...extra,
  };
}

test.describe.serial('Anonymous Keyed Collect — End-to-End Golden (SP4)', () => {
  test.setTimeout(120_000);
  // AK-04 renders the tenant-admin dashboard; AK-02's published-app sim ignores this auth
  // (it drives the keyed endpoint by X-Site-Key, not by session).
  test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

  let tenantId: string;
  let keyA: string;

  test.beforeAll(() => {
    // Build the embeddable IIFE bundle the published-app fixture loads.
    execSync('npm run build', { cwd: path.resolve(process.cwd(), 'packages/track'), stdio: 'pipe' });

    // ab_behavior_event is created by Flyway migration V20260620000200; the host-first golden
    // stack applies the (older) database/schema.sql baseline that predates it, so self-provision
    // it idempotently here (CREATE TABLE/INDEX IF NOT EXISTS — same approach as SP2's KeyedCollectIT).
    const migration = path.resolve(
      process.cwd(),
      '../platform/src/main/resources/db/migration/core/V20260620000200__behavior_event_store.sql',
    );
    execSync(`${PSQL_BASE} -P pager=off -q -f '${migration}'`, {
      env: { ...PG_ENV, PGPASSWORD: process.env.PGPASSWORD ?? 'auraboot' },
      timeout: 15_000,
    });

    const auth = adminAuth();
    tenantId = auth.tenantId;
    keyA = createSiteKey(auth.jwt, `SP4 Landing A ${Date.now()}`);
    // Clean this tenant's events so assertions are exact.
    psql(`DELETE FROM ab_behavior_event WHERE tenant_id=${tenantId}`);
  });

  // ─── AK-00: import live path — global unique index exists ────────────────────
  test('AK-00 import live path: global UNIQUE(site_key) index converged', () => {
    const row = psql(
      `SELECT i.relname||'|'||idx.indisunique||'|'||array_to_string(array_agg(a.attname ORDER BY a.attnum),',') ` +
        `FROM pg_index idx JOIN pg_class i ON i.oid=idx.indexrelid JOIN pg_class t ON t.oid=idx.indrelid ` +
        `JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=ANY(idx.indkey) ` +
        `WHERE t.relname='mt_behavior_site_key' AND i.relname='uk_mt_behavior_site_key_site_key' ` +
        `GROUP BY i.relname, idx.indisunique`,
    );
    expect(row, 'index uk_mt_behavior_site_key_site_key is global UNIQUE on the single site_key column')
      .toBe('uk_mt_behavior_site_key_site_key|true|site_key');
  });

  // ─── AK-01: CORS preflight is allowed for the public keyed endpoint ───────────
  test('AK-01 CORS: cross-origin preflight on /api/collect/keyed allows POST + X-Site-Key', () => {
    const out = execSync(
      `curl -s -D - -o /dev/null -X OPTIONS ${KEYED_URL} ` +
        `-H 'Origin: https://customer-app.example.com' ` +
        `-H 'Access-Control-Request-Method: POST' ` +
        `-H 'Access-Control-Request-Headers: x-site-key,content-type'`,
      { encoding: 'utf-8', timeout: 10_000 },
    );
    const status = parseInt(out.match(/^HTTP\/[\d.]+ (\d+)/m)?.[1] ?? '0', 10);
    expect(status, `preflight status (headers:\n${out})`).toBeLessThan(400);
    expect(out, 'allows the requesting origin').toMatch(/access-control-allow-origin/i);
    expect(out.toLowerCase(), 'allows POST').toContain('post');
    expect(out.toLowerCase(), 'allows the X-Site-Key header').toContain('x-site-key');
  });

  // ─── AK-02: real browser — published-app SDK → keyed → tenant, user null, anon ─
  test('AK-02 published-app SDK loads, posts cross-origin, lands under tenant with anon_id', async ({ page }) => {
    const before = parseInt(psql(`SELECT COUNT(*) FROM ab_behavior_event WHERE tenant_id=${tenantId}`), 10);

    // Land on a real origin (the Vite app), then replace the document with a published-app page.
    await page.goto('/login', { waitUntil: 'domcontentloaded' });
    await page.setContent(
      `<!doctype html><html><head><title>Customer Published App</title></head>` +
        `<body><h1>Welcome</h1>` +
        `<button data-aura-element-id="cta_signup" data-aura-app-id="pub1">Sign up</button>` +
        `</body></html>`,
    );
    // Embed the built SDK bundle exactly as a published app would via <script>.
    await page.addScriptTag({ path: DIST });

    const cookie = await page.evaluate(
      async ({ siteKey, collectUrl }) => {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const AuraTrack = (window as any).AuraTrack;
        const tracker = AuraTrack.init({ siteKey, collectUrl, auto: false });
        tracker.pageview('/landing');
        tracker.trackClick(document.querySelector('[data-aura-element-id="cta_signup"]')!);
        await tracker.flush();
        return document.cookie;
      },
      { siteKey: keyA, collectUrl: KEYED_URL },
    );

    // anon_id is persisted client-side (cookie) for cross-visit stability.
    expect(cookie, 'public SDK persists an anon id cookie').toContain('_aura_anon=');

    await page.screenshot({ path: 'test-results/ak-02-published-app.png' });

    // Poll the DB for the flushed events (keepalive POST completes shortly after flush()).
    await expect
      .poll(
        () => parseInt(psql(`SELECT COUNT(*) FROM ab_behavior_event WHERE tenant_id=${tenantId}`), 10),
        { timeout: 15_000, message: 'anonymous events land under the key tenant' },
      )
      .toBeGreaterThanOrEqual(before + 2);

    const pv = parseInt(
      psql(`SELECT COUNT(*) FROM ab_behavior_event WHERE tenant_id=${tenantId} AND event_name='page_view'`),
      10,
    );
    const click = parseInt(
      psql(`SELECT COUNT(*) FROM ab_behavior_event WHERE tenant_id=${tenantId} AND event_name='element_click'`),
      10,
    );
    expect(pv, 'page_view landed').toBeGreaterThanOrEqual(1);
    expect(click, 'element_click landed').toBeGreaterThanOrEqual(1);

    // Anonymous identity contract: user_id NULL, anon_id non-null on every row.
    const bad = parseInt(
      psql(
        `SELECT COUNT(*) FROM ab_behavior_event WHERE tenant_id=${tenantId} AND (user_id IS NOT NULL OR anon_id IS NULL)`,
      ),
      10,
    );
    expect(bad, 'every anonymous row has user_id NULL and a non-null anon_id').toBe(0);

    // All events from one visitor share one anon_id (the persisted cookie value).
    const distinctAnon = parseInt(
      psql(`SELECT COUNT(DISTINCT anon_id) FROM ab_behavior_event WHERE tenant_id=${tenantId}`),
      10,
    );
    expect(distinctAnon, 'one visitor → one anon_id').toBe(1);
  });

  // ─── AK-03: multi-key/tenant isolation + unknown + disabled ──────────────────
  test('AK-03 isolation: keys route to their own tenant; unknown→403; disabled→403', () => {
    const auth = adminAuth();
    // Second key, re-homed to a distinct (synthetic) tenant to prove cross-tenant routing.
    const keyB = createSiteKey(auth.jwt, `SP4 Tenant B ${Date.now()}`);
    const tenantB = (BigInt(tenantId) + 777_000n).toString();
    psql(`UPDATE mt_behavior_site_key SET tenant_id=${tenantB} WHERE site_key='${keyB}'`);
    psql(`DELETE FROM ab_behavior_event WHERE tenant_id IN (${tenantId}, ${tenantB})`);

    // Key A → tenant A only.
    const aAnon = 'anon-A-iso';
    expect(keyedPost(keyA, [evt('page_view', aAnon)]).status).toBe(200);
    // Key B → tenant B only.
    const bAnon = 'anon-B-iso';
    expect(keyedPost(keyB, [evt('page_view', bAnon)]).status).toBe(200);

    const aRows = psql(`SELECT count(*) FROM ab_behavior_event WHERE tenant_id=${tenantId} AND anon_id='${aAnon}'`);
    const aLeak = psql(`SELECT count(*) FROM ab_behavior_event WHERE tenant_id=${tenantId} AND anon_id='${bAnon}'`);
    const bRows = psql(`SELECT count(*) FROM ab_behavior_event WHERE tenant_id=${tenantB} AND anon_id='${bAnon}'`);
    const bLeak = psql(`SELECT count(*) FROM ab_behavior_event WHERE tenant_id=${tenantB} AND anon_id='${aAnon}'`);
    expect(aRows, 'A event under tenant A').toBe('1');
    expect(bRows, 'B event under tenant B').toBe('1');
    expect(aLeak, 'B did not leak into tenant A').toBe('0');
    expect(bLeak, 'A did not leak into tenant B').toBe('0');

    // Unknown key → 403, nothing written.
    const beforeUnknown = psql(`SELECT count(*) FROM ab_behavior_event`);
    const unknown = keyedPost('abk_does_not_exist_zzzzzzzzzzzz', [evt('page_view', 'anon-x')]);
    expect(unknown.status, `unknown key rejected (body: ${unknown.body})`).toBe(403);
    expect(psql(`SELECT count(*) FROM ab_behavior_event`), 'unknown key wrote nothing').toBe(beforeUnknown);

    // Disable key A → collection stops (403), count unchanged.
    const pidA = psql(`SELECT pid FROM mt_behavior_site_key WHERE site_key='${keyA}'`);
    execSync(
      `curl -sf -X POST ${BACKEND_URL}/api/meta/commands/execute/behavior_site_key:disable ` +
        `-H 'Authorization: Bearer ${auth.jwt}' -H 'Content-Type: application/json' ` +
        `-d '${JSON.stringify({ targetRecordId: pidA })}'`,
      { encoding: 'utf-8', timeout: 10_000 },
    );
    const beforeDisabled = psql(`SELECT count(*) FROM ab_behavior_event WHERE tenant_id=${tenantId}`);
    const disabled = keyedPost(keyA, [evt('page_view', 'anon-after-disable')]);
    expect(disabled.status, `disabled key rejected (body: ${disabled.body})`).toBe(403);
    expect(
      psql(`SELECT count(*) FROM ab_behavior_event WHERE tenant_id=${tenantId}`),
      'disabled key collected nothing',
    ).toBe(beforeDisabled);
  });

  // ─── AK-04: dashboard counts the anonymous visitor in UV ─────────────────────
  test('AK-04 dashboard: anonymous visitors counted in UV (COUNT DISTINCT anon_id)', async ({ page }) => {
    // Seed two DISTINCT anonymous visitors for the admin tenant via the keyed endpoint.
    // (key A was disabled in AK-03; make a fresh active key for this tenant.)
    const auth = adminAuth();
    const keyC = createSiteKey(auth.jwt, `SP4 Dashboard ${Date.now()}`);
    psql(`DELETE FROM ab_behavior_event WHERE tenant_id=${tenantId}`);
    expect(keyedPost(keyC, [evt('page_view', 'anon-dash-1')]).status).toBe(200);
    expect(keyedPost(keyC, [evt('page_view', 'anon-dash-2')]).status).toBe(200);

    const uvDb = parseInt(
      psql(`SELECT count(DISTINCT COALESCE(CAST(user_id AS text), anon_id)) FROM ab_behavior_event WHERE tenant_id=${tenantId}`),
      10,
    );
    expect(uvDb, 'DB UV = 2 distinct anonymous visitors').toBeGreaterThanOrEqual(2);

    // Render the dashboard as the tenant admin; assert the UV KPI reflects the anon visitors.
    await page.goto('/p/c/behavior_analytics', { waitUntil: 'domcontentloaded' });
    await page.locator('[data-aura-element-id="kpi_uv"]').waitFor({ state: 'visible', timeout: 15_000 });
    await page.waitForLoadState('networkidle').catch(() => null);
    await page.screenshot({ path: 'test-results/ak-04-dashboard-uv.png' });

    const uvCard = page.locator('[data-aura-element-id="kpi_uv"]');
    await expect(uvCard, 'UV card not waiting').not.toContainText('Waiting for first record');
    await expect
      .poll(
        async () => {
          const t = (await uvCard.innerText().catch(() => '')) || '';
          const m = t.match(/\b(\d+)\b/);
          return m ? parseInt(m[1], 10) : -1;
        },
        { timeout: 15_000, message: 'UV KPI card counts the anonymous visitors' },
      )
      .toBeGreaterThanOrEqual(2);
  });
});
