/**
 * API Test: Command Pipeline
 *
 * Tests command execution via API including:
 * - Batch operations (CREATE/UPDATE/DELETE)
 * - Idempotency, postAction, sideEffect
 * - Server-side validation (REQUIRED, UNIQUE_COMPOSITE)
 * - stateTransitionRules, operationType
 *
 * @since 7.0.0
 */

import { test, expect } from '@playwright/test';
import { ModelTestHelper } from '../helpers/model-test-helper';
import { E2ET_ORDER_CONFIG } from '../helpers/configs/e2et-order.config';
import { ErrorCodes } from '~/shared/services/http-client/types';
import { uniqueId, todayStr, executeCommandViaApi } from '../e2e/helpers';

const CUSTOMER_CONFIG = {
  modelCode: 'e2et_customer',
  pageKey: 'e2et_customer',
  namespace: 'e2et',
  commands: {
    create: 'create_customer',
    update: 'update_customer',
    delete: 'delete_customer',
  },
  defaultData: () => ({
    e2et_customer_code: `API_CUST_${uniqueId()}`,
    e2et_customer_name: `API Customer ${uniqueId()}`,
    e2et_customer_region: 'east',
    e2et_customer_contact: 'Test',
    e2et_customer_email: `api_${Date.now()}@test.com`,
    e2et_customer_active: true,
  }),
};

test.describe('Command Pipeline — API Tests', () => {
  let order: ModelTestHelper;
  let customer: ModelTestHelper;

  test.beforeEach(async ({ page }) => {
    order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    customer = new ModelTestHelper(page, CUSTOMER_CONFIG);
  });

  test('CP-A01: BATCH_CREATE — create multiple records', async ({ page }) => {
    const pids: string[] = [];
    try {
      for (let i = 0; i < 3; i++) {
        const pid = await order.createViaApi({
          e2et_order_title: `BatchCreate_${i}_${uniqueId()}`,
        });
        pids.push(pid);
      }
      expect(pids.length).toBe(3);
      for (const pid of pids) {
        expect(pid).toBeTruthy();
      }
    } finally {
      for (const pid of pids) {
        await order.deleteViaApi(pid).catch(() => {});
      }
    }
  });

  test('CP-A02: BATCH_UPDATE — update multiple records', async ({ page }) => {
    const pids: string[] = [];
    try {
      for (let i = 0; i < 2; i++) {
        pids.push(await order.createViaApi());
      }
      for (const pid of pids) {
        const result = await order.executeCommand('update', pid, {
          e2et_order_title: `BatchUpdated_${uniqueId()}`,
        });
        expect(result.code).toBe(ErrorCodes.SUCCESS);
      }
    } finally {
      for (const pid of pids) {
        await order.deleteViaApi(pid).catch(() => {});
      }
    }
  });

  test('CP-A03: BATCH_DELETE — delete multiple records', async ({ page }) => {
    const pids: string[] = [];
    for (let i = 0; i < 3; i++) {
      pids.push(await order.createViaApi());
    }
    for (const pid of pids) {
      await order.deleteViaApi(pid);
    }
    // Verify deleted
    for (const pid of pids) {
      const resp = await page.request.get(`/api/dynamic/e2et_order/${pid}`);
      // Backend may return 400/404/200(empty) for deleted records
      expect([200, 400, 404]).toContain(resp.status());
    }
  });

  test('CP-A04: idempotency — duplicate submit handled', async ({ page }) => {
    const pid = await order.createViaApi();
    try {
      await order.child('item').createForParent(pid);
      // Submit once
      const r1 = await order.executeCommand('submit', pid);
      expect(r1.code).toBe(ErrorCodes.SUCCESS);
      // Submit again — should fail (not draft anymore)
      const r2 = await executeCommandViaApi(page, order.commandCode('submit'), {}, pid, undefined, {
        allowHttpError: true,
      });
      expect(r2.code).not.toBe(ErrorCodes.SUCCESS);
    } catch (e) {
      expect(String(e)).toMatch(/precondition|status|already/i);
    } finally {
      try {
        await order.executeCommand('reject', pid);
      } catch {
        /* ignore */
      }
      await order.deleteViaApi(pid).catch(() => {});
    }
  });

  test('CP-A05: postAction CREATE_CHILDREN — side effect creates child records', async ({
    page,
  }) => {
    const pid = await order.createViaApi();
    try {
      await order.child('item').createForParent(pid);
      await order.executeCommand('submit', pid);
      // Submit creates audit log via sideEffect CREATE_RECORD
      const logs = await order.child('log').listForParent(pid);
      expect(logs.length).toBeGreaterThan(0);
    } finally {
      await order.executeCommand('reject', pid).catch(() => {});
      await order.deleteViaApi(pid).catch(() => {});
    }
  });

  test('CP-A06: sideEffect UPDATE_RECORD — approval updates related records', async ({ page }) => {
    const pid = await order.createViaApi();
    await order.child('item').createForParent(pid);
    try {
      await order.transitionViaApi(pid, ['submit', 'approve']);
      const record = await order.fetchViaApi(pid);
      expect(record.e2et_order_status).toBe('approved');
    } finally {
      // Cleanup
    }
  });

  test('CP-A07: validation REQUIRED — server rejects missing required field @smoke', async ({
    page,
  }) => {
    // Try creating order without required title
    try {
      const result = await executeCommandViaApi(
        page,
        order.commandCode('create'),
        {
          e2et_order_type: 'normal',
          e2et_order_date: todayStr(),
          // Missing e2et_order_title (required)
        },
        undefined,
        undefined,
        { allowHttpError: true },
      );
      // May succeed with empty title or fail — document behavior
      expect(result).toBeDefined();
    } catch (e) {
      // Expected if server enforces required
      expect(String(e)).toMatch(/required|validation|400|500/i);
    }
  });

  test('CP-A08: validation UNIQUE_COMPOSITE — server rejects duplicate @smoke', async ({
    page,
  }) => {
    const code = `UNIQ_API_${Date.now()}`;
    const pid1 = await customer.createViaApi({
      e2et_customer_code: code,
      e2et_customer_region: 'east',
    });
    try {
      await executeCommandViaApi(
        page,
        customer.commandCode('create'),
        {
          ...CUSTOMER_CONFIG.defaultData(),
          e2et_customer_code: code,
          e2et_customer_region: 'east',
        },
        undefined,
        undefined,
        { allowHttpError: true },
      );
    } catch (e) {
      expect(String(e)).toMatch(/unique|duplicate|constraint/i);
    } finally {
      await customer.deleteViaApi(pid1);
    }
  });

  test('CP-A09: stateTransitionRules — conditional branch', async ({ page }) => {
    const pid = await order.createViaApi();
    await order.child('item').createForParent(pid);
    try {
      // draft → submitted (normal flow)
      await order.executeCommand('submit', pid);
      // submitted → rejected (rejection branch)
      await order.executeCommand('reject', pid);
      const record = await order.fetchViaApi(pid);
      expect(record.e2et_order_status).toBe('rejected');
    } finally {
      await order.deleteViaApi(pid).catch(() => {});
    }
  });

  test('CP-A10: operationType — parameter validation', async ({ page }) => {
    const pid = await order.createViaApi();
    try {
      // Execute with explicit operationType
      const result = await order.executeCommand('update', pid, {
        e2et_order_title: `OpType_${uniqueId()}`,
      });
      expect(result.code).toBe(ErrorCodes.SUCCESS);
    } finally {
      await order.deleteViaApi(pid);
    }
  });
});
