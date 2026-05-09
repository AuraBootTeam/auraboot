/**
 * E2E Tests: Cross-Field Validation (Gap 3)
 *
 * Prerequisites:
 * - Backend running with CrossFieldRuleEngine integrated
 * - e2e-test-order plugin reimported with rules in model extension
 * - Test data: e2et_order model has 4 cross-field rules
 *
 * Rules under test:
 * 1. delivery-after-order: e2et_delivery_date > e2et_order_date
 * 2. urgent-remark-required: when urgent=true → remark required
 * 3. amount-range: 0 <= amount <= 9999999
 * 4. discount-warning: when discount > 0.5 → remark required (warning)
 */

import { test, expect } from '@playwright/test';
import { DEFAULT_TEST_ACCOUNT } from '../helpers/test-accounts';
import { BACKEND_URL } from '../helpers/environments';

const uniqueId = () => `cfv_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;

// Helper: login and get token
async function getToken(): Promise<string> {
  const resp = await fetch(`${process.env.BACKEND_URL ?? `http://localhost:${process.env.BE_PORT ?? '6443'}`}/api/auth/login`, {
    method: 'post',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      email: DEFAULT_TEST_ACCOUNT.email,
      password: DEFAULT_TEST_ACCOUNT.password,
    }),
  });
  const data = await resp.json();
  return data.data.jwt;
}

// Helper: execute command via API
async function executeCommand(
  token: string,
  commandCode: string,
  payload: Record<string, unknown>,
  targetRecordId?: string,
) {
  const body: Record<string, unknown> = { payload };
  if (targetRecordId) body.targetRecordId = targetRecordId;

  const resp = await fetch(`${process.env.BACKEND_URL ?? `http://localhost:${process.env.BE_PORT ?? '6443'}`}/api/meta/commands/execute/${commandCode}`, {
    method: 'post',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(body),
  });
  return resp.json();
}

test.describe('Cross-Field Validation', () => {
  let token: string;

  test.beforeAll(async () => {
    token = await getToken();
  });

  test('delivery date before order date → validation error', async () => {
    const result = await executeCommand(token, 'e2eto:create_e2et_order', {
      e2et_order_title: `${uniqueId()} DeliveryBeforeOrder`,
      e2et_order_date: '2026-04-01',
      e2et_delivery_date: '2026-03-01', // Before order date
      e2et_order_type: 'normal',
    });

    expect(result.code).not.toBe('0');
    // Validation error detail is in context.error (cross-field rule message)
    const errorDetail = result.context?.error ?? result.message ?? '';
    expect(errorDetail).toContain('Delivery date must be after order date');
  });

  test('delivery date after order date → passes', async () => {
    const result = await executeCommand(token, 'e2eto:create_e2et_order', {
      e2et_order_title: `${uniqueId()} DeliveryAfterOrder`,
      e2et_order_date: '2026-04-01',
      e2et_delivery_date: '2026-05-01', // After order date
      e2et_order_type: 'normal',
    });

    expect(result.code).toBe('0');
  });

  test('urgent order without remark → validation error', async () => {
    const result = await executeCommand(token, 'e2eto:create_e2et_order', {
      e2et_order_title: `${uniqueId()} UrgentNoRemark`,
      e2et_order_date: '2026-04-01',
      e2et_order_urgent: true,
      e2et_order_type: 'normal',
      // No remark provided
    });

    expect(result.code).not.toBe('0');
    // Validation error detail is in context.error (cross-field rule message)
    const errorDetail = result.context?.error ?? result.message ?? '';
    expect(errorDetail).toContain('Remark is required for urgent orders');
  });

  test('urgent order with remark → passes', async () => {
    const result = await executeCommand(token, 'e2eto:create_e2et_order', {
      e2et_order_title: `${uniqueId()} UrgentWithRemark`,
      e2et_order_date: '2026-04-01',
      e2et_order_urgent: true,
      e2et_order_remark: 'Rush delivery needed',
      e2et_order_type: 'normal',
    });

    expect(result.code).toBe('0');
  });

  test('non-urgent order without remark → passes (rule skipped)', async () => {
    const result = await executeCommand(token, 'e2eto:create_e2et_order', {
      e2et_order_title: `${uniqueId()} NonUrgentNoRemark`,
      e2et_order_date: '2026-04-01',
      e2et_order_urgent: false,
      e2et_order_type: 'normal',
    });

    expect(result.code).toBe('0');
  });

  test('null delivery date → rule skipped (null semantics)', async () => {
    const result = await executeCommand(token, 'e2eto:create_e2et_order', {
      e2et_order_title: `${uniqueId()} NullDeliveryDate`,
      e2et_order_date: '2026-04-01',
      e2et_order_type: 'normal',
      // delivery_date not provided → null → rule skipped
    });

    expect(result.code).toBe('0');
  });
});
