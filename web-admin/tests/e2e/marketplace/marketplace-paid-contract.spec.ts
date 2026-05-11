/**
 * Marketplace Paid Contract Tests
 *
 * Drives the real local-test paid backend. It intentionally avoids direct DB
 * DML and route mocks so payload assertions match production API contracts.
 */

import { test, expect } from '../../fixtures';
import type { APIRequestContext } from '@playwright/test';

type JsonRecord = Record<string, unknown>;

const ENTERPRISE_PLUGIN_ROOT = '/Users/ghj/work/auraboot/auraboot-enterprise/plugins';
const MARKETPLACE_PLUGIN_DIR = `${ENTERPRISE_PLUGIN_ROOT}/marketplace-server`;

function isRecord(value: unknown): value is JsonRecord {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function responseData(body: unknown): JsonRecord {
  const data = isRecord(body) && isRecord(body.data) ? body.data : body;
  expect(isRecord(data), `response data must be an object: ${JSON.stringify(body).slice(0, 600)}`).toBe(true);
  return data as JsonRecord;
}

function expectNoInternalIdKeys(value: unknown, label: string) {
  const allowed = new Set(['providerPaymentId', 'idempotencyKey']);
  const visit = (node: unknown, path: string) => {
    if (Array.isArray(node)) {
      node.forEach((item, index) => visit(item, `${path}[${index}]`));
      return;
    }
    if (!isRecord(node)) return;
    for (const [key, child] of Object.entries(node)) {
      if (!allowed.has(key)) {
        expect(key, `${label} must not expose internal id key at ${path}.${key}`).not.toMatch(/(^id$|Id$|_id$)/);
      }
      visit(child, `${path}.${key}`);
    }
  };
  visit(value, label);
}

async function importMarketplaceServer(request: APIRequestContext) {
  const response = await request.post('/api/plugins/import/import-directory-sync', {
    data: {
      path: MARKETPLACE_PLUGIN_DIR,
      conflictStrategy: 'OVERWRITE',
      autoPublishModels: true,
      autoPublishFields: true,
      autoPublishCommands: true,
      autoPublishPages: true,
    },
    headers: { 'Content-Type': 'application/json' },
    timeout: 600_000,
  });
  const body = await response.json().catch(() => ({}));
  const data = isRecord(body.data) ? body.data : body;
  const success = response.ok() && (data.success === true || body.success === true);
  expect(
    success,
    `marketplace-server import should succeed: HTTP ${response.status()} ${JSON.stringify(body).slice(0, 800)}`,
  ).toBe(true);
}

async function postPaid(request: APIRequestContext, path: string, payload: JsonRecord): Promise<JsonRecord> {
  const response = await request.post(`/api/marketplace/paid${path}`, {
    data: payload,
    headers: { 'Content-Type': 'application/json' },
  });
  const body = await response.json().catch(async () => ({ raw: await response.text().catch(() => '') }));
  expect(
    response.ok(),
    `paid API ${path} should succeed: HTTP ${response.status()} ${JSON.stringify(body).slice(0, 800)}`,
  ).toBe(true);
  const data = responseData(body);
  expectNoInternalIdKeys(data, `paid API ${path}`);
  return data;
}

test.describe('Marketplace Paid Contract', () => {
  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext({ storageState: './tests/storage/admin.json' });
    const page = await context.newPage();
    try {
      await importMarketplaceServer(page.request);
    } finally {
      await page.close();
      await context.close();
    }
  });

  test('checkout, payment, token issue and redeem use pid-only payloads', async ({ page }) => {
    const suffix = Date.now().toString(36);
    const checkout = await postPaid(page.request, '/checkout', {
      pluginPid: `E2E-PLUGIN-${suffix}`,
      pricingPlanPid: `E2E-PLAN-${suffix}`,
      buyerTenantPid: `E2E-TENANT-${suffix}`,
      amount: 19.99,
      currency: 'usd',
      idempotencyKey: `e2e-${suffix}`,
    });

    expect(checkout.purchasePid).toEqual(expect.any(String));
    expect(checkout.pluginPid).toBe(`E2E-PLUGIN-${suffix}`);
    expect(checkout.pricingPlanPid).toBe(`E2E-PLAN-${suffix}`);
    expect(checkout.buyerTenantPid).toBe(`E2E-TENANT-${suffix}`);
    expect(checkout.status).toBe('checkout_started');

    const paid = await postPaid(page.request, '/payment-events/local-test', {
      purchasePid: checkout.purchasePid,
      provider: 'local_test',
      providerPaymentId: `local_test:e2e-payment-${suffix}`,
      eventId: `evt-${suffix}`,
      eventType: 'payment_confirmed',
      idempotencyKey: `payment-${suffix}`,
    });
    expect(paid.purchasePid).toBe(checkout.purchasePid);
    expect(paid.status).toBe('active');
    expect(paid.eventPid).toEqual(expect.any(String));

    const replayed = await postPaid(page.request, '/payment-events/local-test', {
      purchasePid: checkout.purchasePid,
      provider: 'local_test',
      providerPaymentId: `local_test:e2e-payment-${suffix}`,
      eventId: `evt-${suffix}`,
      eventType: 'payment_confirmed',
      idempotencyKey: `payment-${suffix}`,
    });
    expect(replayed.purchasePid).toBe(checkout.purchasePid);
    expect(replayed.status).toBe('active');
    expect(replayed.eventPid).toBe(paid.eventPid);
    expect(replayed.replayed).toBe(true);

    const issued = await postPaid(page.request, '/install-tokens', {
      purchasePid: checkout.purchasePid,
      versionPid: `E2E-VERSION-${suffix}`,
      targetInstanceUrl: `https://tenant-${suffix}.example.test`,
      ttlHours: 24,
    });
    expect(issued.tokenPid).toEqual(expect.any(String));
    expect(issued.token).toEqual(expect.any(String));
    expect(issued.claims).toMatchObject({
      purchasePid: checkout.purchasePid,
      pluginPid: `E2E-PLUGIN-${suffix}`,
      versionPid: `E2E-VERSION-${suffix}`,
      buyerTenantPid: `E2E-TENANT-${suffix}`,
    });
    expectNoInternalIdKeys(issued.claims, 'install token claims');

    const redeemed = await postPaid(page.request, '/install-tokens/redeem', {
      token: issued.token,
      targetInstanceUrl: `https://tenant-${suffix}.example.test`,
    });
    expect(redeemed.tokenPid).toBe(issued.tokenPid);
    expect(redeemed.purchasePid).toBe(checkout.purchasePid);
    expect(redeemed.pluginPid).toBe(`E2E-PLUGIN-${suffix}`);
    expect(redeemed.versionPid).toBe(`E2E-VERSION-${suffix}`);
    expect(redeemed.status).toBe('redeemed');
  });

  test('revoke uses purchase pid and invalidates issued tokens', async ({ page }) => {
    const suffix = `rvk-${Date.now().toString(36)}`;
    const checkout = await postPaid(page.request, '/checkout', {
      pluginPid: `E2E-PLUGIN-${suffix}`,
      pricingPlanPid: `E2E-PLAN-${suffix}`,
      buyerTenantPid: `E2E-TENANT-${suffix}`,
      amount: 29.99,
      currency: 'usd',
      idempotencyKey: `e2e-${suffix}`,
    });

    await postPaid(page.request, '/payment-events/local-test', {
      purchasePid: checkout.purchasePid,
      provider: 'local_test',
      providerPaymentId: `local_test:e2e-payment-${suffix}`,
      eventId: `evt-${suffix}`,
      eventType: 'payment_confirmed',
      idempotencyKey: `payment-${suffix}`,
    });

    await postPaid(page.request, '/install-tokens', {
      purchasePid: checkout.purchasePid,
      versionPid: `E2E-VERSION-${suffix}`,
      targetInstanceUrl: `https://tenant-${suffix}.example.test`,
      ttlHours: 24,
    });

    const revoked = await postPaid(page.request, '/purchases/revoke', {
      purchasePid: checkout.purchasePid,
      reason: 'E2E contract revoke',
    });
    expect(revoked.purchasePid).toBe(checkout.purchasePid);
    expect(revoked.status).toBe('revoked');
    expect(revoked.revokedTokenCount).toBeGreaterThanOrEqual(1);
    expect(revoked.eventPid).toEqual(expect.any(String));
  });

  test('refund requires reason, records event pid, and invalidates issued tokens', async ({ page }) => {
    const suffix = `rfd-${Date.now().toString(36)}`;
    const checkout = await postPaid(page.request, '/checkout', {
      pluginPid: `E2E-PLUGIN-${suffix}`,
      pricingPlanPid: `E2E-PLAN-${suffix}`,
      buyerTenantPid: `E2E-TENANT-${suffix}`,
      amount: 39.99,
      currency: 'usd',
      idempotencyKey: `e2e-${suffix}`,
    });

    await postPaid(page.request, '/payment-events/local-test', {
      purchasePid: checkout.purchasePid,
      provider: 'local_test',
      providerPaymentId: `local_test:e2e-payment-${suffix}`,
      eventId: `evt-${suffix}`,
      eventType: 'payment_confirmed',
      idempotencyKey: `payment-${suffix}`,
    });

    await postPaid(page.request, '/install-tokens', {
      purchasePid: checkout.purchasePid,
      versionPid: `E2E-VERSION-${suffix}`,
      targetInstanceUrl: `https://tenant-${suffix}.example.test`,
      ttlHours: 24,
    });

    const refunded = await postPaid(page.request, '/purchases/refund', {
      purchasePid: checkout.purchasePid,
      reason: 'E2E contract refund',
      providerRefundReference: `re-contract-${suffix}`,
    });
    expect(refunded.purchasePid).toBe(checkout.purchasePid);
    expect(refunded.status).toBe('refunded');
    expect(refunded.revokedTokenCount).toBeGreaterThanOrEqual(1);
    expect(refunded.eventPid).toEqual(expect.any(String));
    expect(refunded.providerRefundReference).toBe(`re-contract-${suffix}`);
  });
});
