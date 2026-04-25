/**
 * Mission Control — User Soul Profile E2E (PR-79 Phase 5, mocked)
 *
 * Validates /aurabot/my-profile and /aurabot/soul-profiles end-to-end with
 * intercepted /api/user/soul-profile/** and /api/admin/user-soul-profiles/**
 * so the test is independent of whether the deriver has run.
 *
 * Mirrors ai-memory-promotions.spec.ts conventions (page.route fulfilment,
 * bilingual regex assertions). Corresponds to plan §10 Phase 5 acceptance.
 */

import { test, expect } from '../../fixtures';
import type { Page, Route } from '@playwright/test';
import {
  seedSoulProfileMenus,
  cleanupSoulProfileMenus,
  type SeededSoulProfileMenus,
} from './_real-backend-helpers';

// Phase 10 — menu rows are required so we can navigate via sidebar
// (red-line: no direct page.goto). Seed once per worker.
let seededMenus: SeededSoulProfileMenus;

test.beforeAll(async () => {
  seededMenus = seedSoulProfileMenus();
});

test.afterAll(async () => {
  if (seededMenus) cleanupSoulProfileMenus(seededMenus);
});

// ---------------------------------------------------------------------------
// Canned responses
// ---------------------------------------------------------------------------

function envelope<T>(data: T) {
  return { code: '0', message: 'OK', data };
}

function errorEnvelope(code: string, message: string) {
  return { code, message, data: null };
}

function makeProfile(overrides: Record<string, unknown> = {}) {
  return {
    pid: 'USP0000000000000000001',
    tenant_id: 1,
    user_id: 101,
    version: 3,
    status: 'active',
    profile: {
      schema_version: '1.0',
      persona: {
        text: 'Engineer in e-commerce; tenant admin; pragmatic tone.',
        source_memory_pids: ['M01', 'M07'],
        confidence: 0.82,
        last_derived_at: '2026-04-18T04:00:00Z',
      },
      preferences: {
        communication_style: {
          text: 'concise bullet points; code examples welcome',
          source_memory_pids: ['M02', 'M05'],
          confidence: 0.91,
        },
        domain_vocabulary: {
          text: ['SKU', '月结', 'PO'],
          source_memory_pids: ['M03'],
          confidence: 0.85,
        },
        working_hours: {
          text: '09:00-19:00 Asia/Shanghai',
          source_memory_pids: ['M04'],
          confidence: 0.76,
        },
      },
      habits: {
        recurring_actions: [
          {
            pattern: 'monthly reconciliation',
            frequency: 'monthly',
            source_action_count: 8,
            last_seen: '2026-03-28',
          },
        ],
      },
      expertise: {
        domains: [
          { name: 'inventory management', confidence: 0.88, evidence_count: 23 },
        ],
      },
      boundaries: {
        text: 'never auto-approve commit-level changes',
        source_memory_pids: ['M08'],
        confidence: 0.95,
        user_pinned: false,
      },
      language: 'zh-CN',
      meta: { derivation_window_days: 90 },
    },
    derivation_confidence: 0.82,
    source_memory_pids: ['M01', 'M02', 'M03', 'M04', 'M05', 'M07', 'M08'],
    source_memory_refs: [
      { pid: 'M01', memory_title: 'joined as tenant admin', created_at: '2026-02-01T00:00:00Z' },
      { pid: 'M02', memory_title: 'prefer bullet points', created_at: '2026-03-01T00:00:00Z' },
    ],
    edited_fields: {},
    hidden_at: null,
    created_at: '2026-04-18T04:00:00Z',
    activated_at: '2026-04-18T04:30:00Z',
    superseded_at: null,
    stale_flagged_at: null,
    next_derivation_at: '2026-04-19T04:00:00Z',
    last_manual_derive_at: null,
    ...overrides,
  };
}

interface MockState {
  profile: Record<string, unknown> | null | 'not-found';
  history?: Array<Record<string, unknown>>;
  adminRows?: Array<Record<string, unknown>>;
  adminStats?: Record<string, unknown>;
  derive429?: boolean;
  captured: {
    pin: Array<Record<string, unknown>>;
    hide: Array<Record<string, unknown>>;
    edit: Array<Record<string, unknown>>;
    reset: Array<Record<string, unknown>>;
    forget: Array<Record<string, unknown>>;
    derive: Array<Record<string, unknown>>;
  };
}

async function interceptApi(page: Page, state: MockState) {
  await page.route(/\/api\/user\/soul-profile$/, async (route: Route) => {
    if (state.profile === 'not-found') {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify(errorEnvelope('404', 'Profile not found')),
      });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(envelope(state.profile)),
    });
  });

  await page.route(/\/api\/user\/soul-profile\/history(\?.*)?$/, async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(envelope(state.history ?? [])),
    });
  });

  const registerEditor = async (
    pattern: RegExp,
    key: keyof MockState['captured'],
    successMsg = 'ok',
  ) => {
    await page.route(pattern, async (route: Route) => {
      const body = route.request().postData();
      state.captured[key].push(body ? JSON.parse(body) : {});
      // Update mock profile to reflect the action
      if (state.profile && state.profile !== 'not-found') {
        const p = state.profile as Record<string, unknown>;
        const edited = { ...((p.edited_fields as Record<string, string>) ?? {}) };
        const reqBody = body ? JSON.parse(body) : {};
        const field = reqBody.field as string | undefined;
        if (field) {
          if (key === 'pin') edited[field] = 'locked';
          if (key === 'hide') edited[field] = 'hidden';
          if (key === 'edit') edited[field] = String(Date.now());
          if (key === 'reset') delete edited[field];
        }
        state.profile = { ...p, edited_fields: edited };
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(envelope({ ok: true, op: successMsg })),
      });
    });
  };

  await registerEditor(/\/api\/user\/soul-profile\/pin$/, 'pin');
  await registerEditor(/\/api\/user\/soul-profile\/hide$/, 'hide');
  await registerEditor(/\/api\/user\/soul-profile\/edit$/, 'edit');
  await registerEditor(/\/api\/user\/soul-profile\/reset$/, 'reset');

  await page.route(/\/api\/user\/soul-profile\/forget$/, async (route: Route) => {
    const body = route.request().postData();
    state.captured.forget.push(body ? JSON.parse(body) : {});
    state.profile = 'not-found';
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(envelope({ ok: true })),
    });
  });

  await page.route(/\/api\/user\/soul-profile\/derive-now$/, async (route: Route) => {
    const body = route.request().postData();
    state.captured.derive.push(body ? JSON.parse(body) : {});
    if (state.derive429) {
      await route.fulfill({
        status: 429,
        contentType: 'application/json',
        body: JSON.stringify(errorEnvelope('429', 'Rate limit: once per 24h')),
      });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(envelope({ ok: true })),
    });
  });

  await page.route(/\/api\/admin\/user-soul-profiles$/, async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(envelope(state.adminRows ?? [])),
    });
  });
  await page.route(/\/api\/admin\/user-soul-profiles\/stats$/, async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(envelope(state.adminStats ?? {
        total_users_with_profile: 0,
        coverage_rate: 0,
        stale_count: 0,
        avg_confidence: null,
      })),
    });
  });
}

function makeState(overrides: Partial<MockState> = {}): MockState {
  return {
    profile: makeProfile(),
    history: [],
    adminRows: [],
    adminStats: undefined,
    derive429: false,
    captured: { pin: [], hide: [], edit: [], reset: [], forget: [], derive: [] },
    ...overrides,
  };
}

// Phase 10 — navigate via sidebar click (red-line: no direct page.goto).
async function clickSidebarLeaf(
  page: Page,
  leafPattern: RegExp,
  pageTestId: string,
  href?: string,
): Promise<void> {
  await page.goto('/', { waitUntil: 'commit', timeout: 10000 });
  await page.waitForLoadState('domcontentloaded');

  const nav = page.locator('nav').first();
  const aiCenter = nav.getByRole('button', { name: /AI 中心|AI Center/ });
  await aiCenter.waitFor({ state: 'visible', timeout: 10_000 });
  await aiCenter.evaluate((el: HTMLElement) => el.click());

  const leaf = href
    ? nav.locator(`a[href="${href}"]`).or(nav.getByRole('link', { name: leafPattern }))
    : nav.getByRole('link', { name: leafPattern });
  await leaf.waitFor({ state: 'visible', timeout: 5_000 });
  await leaf.first().evaluate((el: HTMLElement) => el.click());

  await expect(page.locator(`[data-testid="${pageTestId}"]`)).toBeVisible({
    timeout: 10_000,
  });
}

async function openMyProfile(page: Page) {
  await clickSidebarLeaf(page, /我的画像|My Profile/, 'my-profile-page', '/aurabot/my-profile');
}

async function openAdmin(page: Page) {
  await clickSidebarLeaf(
    page,
    /Soul Profiles(?:\s*\(?(?:管理|Admin)\)?)?/,
    'soul-profiles-admin-page',
    '/aurabot/soul-profiles',
  );
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test.describe('Mission Control — User Soul Profile (PR-79)', () => {
  test('USP-01: my-profile renders field cards with confidence bars and source counts', async ({
    page,
  }) => {
    const state = makeState();
    await interceptApi(page, state);
    await openMyProfile(page);

    // Persona
    const persona = page.locator('[data-testid="field-persona"]');
    await expect(persona).toBeVisible();
    await expect(persona.locator('[data-testid="field-title"]')).toContainText(
      /人设|Persona/,
    );
    await expect(persona.locator('[data-testid="field-text"]')).toContainText(
      /Engineer in e-commerce/,
    );
    await expect(persona.locator('[data-testid="confidence-value"]')).toHaveText('82%');
    await expect(persona.locator('[data-testid="source-toggle"]')).toContainText('2');

    // Communication style
    const comm = page.locator('[data-testid="field-preferences.communication_style"]');
    await expect(comm).toBeVisible();
    await expect(comm.locator('[data-testid="confidence-value"]')).toHaveText('91%');
    await expect(comm.locator('[data-testid="source-toggle"]')).toContainText('2');

    // Boundaries
    const boundaries = page.locator('[data-testid="field-boundaries"]');
    await expect(boundaries).toBeVisible();
    await expect(boundaries.locator('[data-testid="field-text"]')).toContainText(
      /never auto-approve/,
    );

    // Footer: version + last derived
    await expect(page.locator('[data-testid="footer-version"]')).toContainText('v3');
    await expect(page.locator('[data-testid="footer-last-derived"]')).toContainText(
      /最后派生|Last derived/,
    );
  });

  test('USP-02: pin button POSTs /pin and shows success toast', async ({ page }) => {
    const state = makeState();
    await interceptApi(page, state);
    await openMyProfile(page);

    await page
      .locator('[data-testid="field-persona"] [data-testid="pin-btn"]')
      .click();

    await expect(page.locator('[role="alert"], [data-testid="toast"]').first()).toContainText(
      /固定|Pinned/,
    );
    expect(state.captured.pin).toHaveLength(1);
    expect(state.captured.pin[0]).toMatchObject({ field: 'persona' });

    // Pinned badge appears after refresh
    await expect(
      page.locator('[data-testid="field-persona"] [data-testid="pinned-badge"]'),
    ).toBeVisible();
  });

  test('USP-03: hide button POSTs /hide and field disappears', async ({ page }) => {
    const state = makeState();
    await interceptApi(page, state);
    await openMyProfile(page);

    await expect(
      page.locator('[data-testid="field-preferences.working_hours"]'),
    ).toBeVisible();

    await page
      .locator(
        '[data-testid="field-preferences.working_hours"] [data-testid="hide-btn"]',
      )
      .click();

    await expect(page.locator('[data-testid="toast"]')).toContainText(/隐藏|Hidden/);
    expect(state.captured.hide).toHaveLength(1);
    expect(state.captured.hide[0]).toMatchObject({
      field: 'preferences.working_hours',
    });

    await expect(
      page.locator('[data-testid="field-preferences.working_hours"]'),
    ).toHaveCount(0);
  });

  test('USP-04: edit modal saves text via POST /edit', async ({ page }) => {
    const state = makeState();
    await interceptApi(page, state);
    await openMyProfile(page);

    await page
      .locator('[data-testid="field-persona"] [data-testid="edit-btn"]')
      .click();

    const modal = page.locator('[data-testid="edit-modal"]');
    await expect(modal).toBeVisible();
    await modal.locator('[data-testid="edit-textarea"]').fill('custom persona text');
    await modal.locator('[data-testid="edit-submit"]').click();

    await expect(page.locator('[data-testid="toast"]')).toContainText(/保存|Saved/);
    expect(state.captured.edit).toHaveLength(1);
    expect(state.captured.edit[0]).toMatchObject({
      field: 'persona',
      text: 'custom persona text',
    });
  });

  test('USP-05: reset button POSTs /reset for a field', async ({ page }) => {
    const state = makeState({
      profile: makeProfile({ edited_fields: { persona: 'locked' } }),
    });
    await interceptApi(page, state);
    await openMyProfile(page);

    await page
      .locator('[data-testid="field-persona"] [data-testid="reset-btn"]')
      .click();

    await expect(page.locator('[data-testid="toast"]')).toContainText(/重置|Reset/);
    expect(state.captured.reset).toHaveLength(1);
    expect(state.captured.reset[0]).toMatchObject({ field: 'persona' });
  });

  test('USP-06: stale banner appears when stale_flagged_at is set', async ({ page }) => {
    const state = makeState({
      profile: makeProfile({ stale_flagged_at: '2026-04-18T00:00:00Z' }),
    });
    await interceptApi(page, state);
    await openMyProfile(page);

    const banner = page.locator('[data-testid="stale-banner"]');
    await expect(banner).toBeVisible();
    await expect(banner).toContainText(/过时|outdated/);
    await expect(banner.locator('[data-testid="stale-rederive-btn"]')).toBeVisible();
  });

  test('USP-07: history tab shows superseded + archived versions', async ({ page }) => {
    const state = makeState({
      history: [
        {
          pid: 'USP0000000000000000002',
          version: 2,
          status: 'superseded',
          derivation_confidence: 0.7,
          activated_at: '2026-04-10T04:00:00Z',
          superseded_at: '2026-04-18T04:00:00Z',
          created_at: '2026-04-10T04:00:00Z',
          persona_text: 'Older persona text describing last month snapshot.',
        },
        {
          pid: 'USP0000000000000000003',
          version: 1,
          status: 'archived',
          derivation_confidence: 0.6,
          activated_at: '2026-03-01T04:00:00Z',
          superseded_at: '2026-04-10T04:00:00Z',
          archived_at: '2026-04-15T04:00:00Z',
          created_at: '2026-03-01T04:00:00Z',
          persona_text: null,
        },
      ],
    });
    await interceptApi(page, state);
    await openMyProfile(page);

    await expect
      .poll(
        async () => {
          await page.locator('[data-testid="tab-history"]').click().catch(() => {});
          const count = await page
            .locator(
              '[data-testid="history-loading"], [data-testid="history-list"], [data-testid="history-empty"]',
            )
            .count();
          return count;
        },
        { timeout: 5000, intervals: [100, 250, 500, 1000] },
      )
      .toBe(1);
    await expect(page.locator('[data-testid="history-list"]')).toBeVisible();

    const superseded = page.locator('[data-testid="history-USP0000000000000000002"]');
    await expect(superseded).toBeVisible();
    await expect(superseded.locator('[data-testid="version-badge"]')).toHaveText('v2');
    await expect(superseded.locator('[data-testid="status-pill"]')).toContainText(
      /已被取代|superseded/i,
    );
    await superseded.locator('[data-testid="history-expand"]').click();
    await expect(superseded.locator('[data-testid="history-diff"]')).toContainText(
      /Older persona text/,
    );

    // Archived shows no content, only placeholder
    const archived = page.locator('[data-testid="history-USP0000000000000000003"]');
    await expect(archived).toBeVisible();
    await expect(archived.locator('[data-testid="archived-placeholder"]')).toContainText(
      /归档|Archived/,
    );
    await expect(archived).not.toContainText(/Older persona text/);
  });

  test('USP-08: forget modal requires typed confirmation, then POSTs /forget', async ({
    page,
  }) => {
    const state = makeState();
    await interceptApi(page, state);
    await openMyProfile(page);

    await page.locator('[data-testid="forget-btn"]').click();
    const modal = page.locator('[data-testid="forget-modal"]');
    await expect(modal).toBeVisible();

    const confirmBtn = modal.locator('[data-testid="forget-confirm"]');
    await expect(confirmBtn).toBeDisabled();

    await modal.locator('[data-testid="forget-input"]').fill('nope');
    await expect(confirmBtn).toBeDisabled();

    await modal.locator('[data-testid="forget-input"]').fill('forget');
    await expect(confirmBtn).toBeEnabled();
    await confirmBtn.click();

    await expect(page.locator('[data-testid="toast"]')).toContainText(
      /遗忘|forgotten|GDPR/i,
    );
    expect(state.captured.forget).toHaveLength(1);

    // After forget, empty-state renders
    await expect(page.locator('[data-testid="profile-empty"]')).toBeVisible();
  });

  test('USP-09: re-derive button disabled when last_manual_derive_at within 24h; 429 shows toast', async ({
    page,
  }) => {
    const recent = new Date(Date.now() - 1 * 3_600_000).toISOString();
    const state = makeState({
      profile: makeProfile({ last_manual_derive_at: recent }),
    });
    await interceptApi(page, state);
    await openMyProfile(page);

    const btn = page.locator('[data-testid="derive-now-btn"]');
    await expect(btn).toBeDisabled();
    await expect(page.locator('[data-testid="derive-cooldown-hint"]')).toContainText(
      /24|24h/,
    );

    // When server returns 429 for a non-cooldown user, UI shows rate-limit toast.
    const state2 = makeState({ derive429: true });
    const page2 = await page.context().newPage();
    await interceptApi(page2, state2);
    await openMyProfile(page2);
    await page2.locator('[data-testid="derive-now-btn"]').click();
    await expect(page2.locator('[data-testid="toast"]')).toContainText(
      /频繁|Too many|rate/i,
    );
    expect(state2.captured.derive).toHaveLength(1);
    await page2.close();
  });

  test('USP-10: admin dashboard shows metadata only — no profile content', async ({
    page,
  }) => {
    const state = makeState({
      adminRows: [
        {
          user_id: 12345,
          user_email: 'alice@example.com',
          user_name: 'Alice',
          version: 3,
          status: 'active',
          activated_at: '2026-04-18T04:30:00Z',
          derivation_confidence: 0.82,
          stale_flagged_at: null,
        },
        {
          user_id: 12346,
          user_email: 'bob@example.com',
          user_name: 'Bob',
          version: 1,
          status: 'active',
          activated_at: '2026-04-17T04:30:00Z',
          derivation_confidence: 0.55,
          stale_flagged_at: '2026-04-18T00:00:00Z',
        },
      ],
      adminStats: {
        total_users_with_profile: 2,
        total_active_users: 5,
        coverage_rate: 0.4,
        stale_count: 1,
        avg_confidence: 0.685,
      },
    });
    await interceptApi(page, state);
    await openAdmin(page);

    // Stats
    await expect(page.locator('[data-testid="stat-total"]')).toContainText('2');
    await expect(page.locator('[data-testid="stat-coverage"]')).toContainText('40%');
    await expect(page.locator('[data-testid="stat-stale"]')).toContainText('1');

    // Rows
    const alice = page.locator('[data-testid="admin-row-12345"]');
    await expect(alice).toBeVisible();
    await expect(alice.locator('[data-testid="cell-version"]')).toHaveText('v3');
    await expect(alice.locator('[data-testid="cell-confidence"]')).toHaveText('82%');

    const bob = page.locator('[data-testid="admin-row-12346"]');
    await expect(bob.locator('[data-testid="stale-badge"]')).toBeVisible();

    // Privacy invariant: profile CONTENT must never appear in DOM.
    // The mocked admin response has no content at all — assert that the
    // persona strings from the "My Profile" fixture don't leak here.
    const bodyText = await page.locator('body').innerText();
    expect(bodyText).not.toContain('Engineer in e-commerce');
    expect(bodyText).not.toContain('concise bullet points');
    expect(bodyText).not.toContain('never auto-approve');

    // And the privacy notice is rendered
    await expect(
      page.locator('[data-testid="admin-metadata-notice"]'),
    ).toContainText(/仅展示元数据|Metadata only/);
  });

  test('USP-11: empty state renders when GET / returns 404', async ({ page }) => {
    const state = makeState({ profile: 'not-found' });
    await interceptApi(page, state);
    await openMyProfile(page);

    await expect(page.locator('[data-testid="profile-empty"]')).toBeVisible();
    await expect(page.locator('[data-testid="profile-empty"]')).toContainText(
      /尚未生成画像|No profile yet/,
    );
    // No field cards should be present
    await expect(page.locator('[data-testid="field-persona"]')).toHaveCount(0);
  });
});
