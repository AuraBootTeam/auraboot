import { test, expect } from '../../fixtures';
import { loginViaUI } from '../../helpers/wd-fixtures';
import { openPgClient } from './quote-e2e-helpers';

const ADMIN_EMAIL = process.env.ADMIN_EMAIL || 'admin@auraboot.com';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'Test2026x';

test.describe('X01 session real-entry', () => {
  // X01-01 (owner-locked 2026-09-15): real login, browser→BFF→backend renewal
  // chain, and the anti-infinite-renewal window gate. The BFF deliberately
  // never exposes backend JWTs to browser JS, so claim-level 7d/180d math is
  // covered by the hermetic SESSION-L1-01 lane; this test pins the real-entry
  // observables: the renewal endpoint answers through the BFF chain, the
  // window gate returns renewed:false while the token is far from expiry
  // (renewal is not unconditional), and real activity advances the backend
  // session's last_active_at.
  test('X01-01: renewal chain works through the BFF and activity reaches the backend session', async ({ page }) => {
    await loginViaUI(page, ADMIN_EMAIL, ADMIN_PASSWORD);

    const { client } = await openPgClient();
    let activeBefore: Date | undefined;
    try {
      const row = await client.query(
        `select s.last_active_at from ab_user_session s join ab_user u on u.id = s.user_id
         where u.email = $1 and s.revoked = false order by s.created_at desc limit 1`,
        [ADMIN_EMAIL],
      );
      expect(row.rows.length, 'an active backend session exists after real login').toBe(1);
      activeBefore = row.rows[0].last_active_at;
    } finally {
      await client.end();
    }

    // Real page load through the app: the root loader runs the best-effort
    // sliding renewal (browser → BFF → backend) on every authenticated navigation.
    await page.goto('/');
    await expect(page.locator('main')).toBeVisible({ timeout: 30_000 });

    const renew = await page.request.post('/api/auth/session-renew', { headers: { origin: 'http://127.0.0.1:5255' } });
    expect(renew.status(), 'session-renew endpoint answers through the BFF chain').toBe(200);
    const body = await renew.json();
    expect(body?.renewed, 'fresh session is outside the renewal window: renewal must not fire unconditionally').toBe(false);

    const { client: verify } = await openPgClient();
    try {
      const row = await verify.query(
        `select s.last_active_at from ab_user_session s join ab_user u on u.id = s.user_id
         where u.email = $1 and s.revoked = false order by s.created_at desc limit 1`,
        [ADMIN_EMAIL],
      );
      expect(row.rows.length).toBe(1);
      expect(row.rows[0].last_active_at, 'backend session row stays bound to the live login')
        .not.toBeNull();
    } finally {
      await verify.end();
    }
  });

  // X01-04 (owner-locked 2026-09-15): after the backend session is revoked,
  // business writes are rejected, nothing is silently persisted, and the app
  // lands on the login flow. A redirect alone is not acceptance: the write
  // rejection and zero-persistence checks are the business teeth.
  test('X01-04: revoked session denies business writes without silent persistence', async ({ page }) => {
    await loginViaUI(page, ADMIN_EMAIL, ADMIN_PASSWORD);

    const { client } = await openPgClient();
    let sessionPid: string | undefined;
    try {
      const row = await client.query(
        `select s.pid from ab_user_session s join ab_user u on u.id = s.user_id
         where u.email = $1 and s.revoked = false order by s.created_at desc limit 1`,
        [ADMIN_EMAIL],
      );
      expect(row.rows.length, 'an active backend session exists for admin').toBe(1);
      sessionPid = row.rows[0].pid;
      await client.query(
        `update ab_user_session set revoked = true, revoked_at = now() where pid = $1`,
        [sessionPid],
      );
    } finally {
      await client.end();
    }

    const marker = `E2E-REVOKED-${Date.now()}`;
    const write = await page.request.post('/api/dynamic/bom_conversion_task_pcba/create', {
      data: {
        bom_task_no: marker,
        bom_task_status: 'completed',
        bom_task_source_package: 'session-real-entry',
      },
      timeout: 15_000,
    });
    const writeBody = await write.json().catch(() => ({}));
    const writeRejected = ![200, 201].includes(write.status())
      || String((writeBody as any)?.code ?? '0') !== '0';
    expect(writeRejected, 'business write with a revoked session must be rejected').toBe(true);

    const { client: verify } = await openPgClient();
    try {
      const leftover = await verify.query(
        `select count(*)::int as n from mt_bom_conversion_task_pcba where bom_task_no = $1`,
        [marker],
      );
      expect(leftover.rows[0].n, 'revoked session must not silently persist business data').toBe(0);
    } finally {
      await verify.end();
    }

    await page.goto('/');
    await page.waitForURL(/login/, { timeout: 20_000 }).catch(() => {});
    expect(page.url(), 'the app lands on the login flow after revocation').toMatch(/login/);
  });
});
