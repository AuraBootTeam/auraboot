/**
 * Entitlement Feature Gate Tests
 *
 * Tests plan and feature CRUD via admin API.
 * Note: entitlement enforcement is disabled by default (enabled=false),
 * so these tests verify the data management layer works correctly.
 */

import { test, expect } from '../../fixtures';

test.describe('Entitlement Feature Gate Tests', () => {
  test.describe.configure({ mode: 'serial' });

  const uniqueSuffix = `fg-${Date.now()}`;
  const planCode = `plan-${uniqueSuffix}`;
  const featureKey = `e2eto.feat_${uniqueSuffix}`;

  test('admin can create a plan for e2e-test-order plugin', async ({ page }) => {
    const resp = await page.request.post('/api/admin/entitlements/plans', {
      data: {
        pluginId: 'e2e-test-order',
        planCode,
        displayNameEn: 'Feature Gate Plan',
        displayNameZh: '特性门控计划',
        isDefault: false,
        trialDays: 14,
        billingType: 'free',
      },
    });
    expect(resp.ok()).toBeTruthy();
    const data = await resp.json();
    expect(data.data).toBeTruthy();
  });

  test('admin can create a feature for e2e-test-order plugin', async ({ page }) => {
    const resp = await page.request.post('/api/admin/entitlements/features', {
      data: {
        pluginId: 'e2e-test-order',
        featureKey,
        displayNameEn: 'Advanced Reporting',
        displayNameZh: '高级报表',
      },
    });
    expect(resp.ok()).toBeTruthy();
    const data = await resp.json();
    expect(data.data).toBeTruthy();
  });

  test('admin can list plans for e2e-test-order plugin', async ({ page }) => {
    const resp = await page.request.get('/api/admin/entitlements/plans?pluginId=e2e-test-order');
    expect(resp.ok()).toBeTruthy();
    const data = await resp.json();
    expect(data.data.length).toBeGreaterThan(0);
    const plan = data.data.find((p: any) => p.planCode === planCode);
    expect(plan).toBeTruthy();
    expect(plan.displayNameEn).toBe('Feature Gate Plan');
    expect(plan.trialDays).toBe(14);
  });

  test('admin can list features for e2e-test-order plugin', async ({ page }) => {
    const resp = await page.request.get('/api/admin/entitlements/features?pluginId=e2e-test-order');
    expect(resp.ok()).toBeTruthy();
    const data = await resp.json();
    expect(data.data.length).toBeGreaterThan(0);
    const feature = data.data.find((f: any) => f.featureKey === featureKey);
    expect(feature).toBeTruthy();
  });

  test('admin can assign features to a plan', async ({ page }) => {
    const plansResp = await page.request.get('/api/admin/entitlements/plans?pluginId=e2e-test-order');
    const plans = (await plansResp.json()).data;
    const plan = plans.find((p: any) => p.planCode === planCode);
    expect(plan).toBeTruthy();

    const featResp = await page.request.get('/api/admin/entitlements/features?pluginId=e2e-test-order');
    const features = (await featResp.json()).data;
    const feat = features.find((f: any) => f.featureKey === featureKey);
    expect(feat).toBeTruthy();

    const resp = await page.request.put(`/api/admin/entitlements/plan-features/${plan.pid}`, {
      data: [feat.pid],
    });
    expect(resp.ok()).toBeTruthy();
  });

  test('duplicate plan creation is rejected', async ({ page }) => {
    const resp = await page.request.post('/api/admin/entitlements/plans', {
      data: {
        pluginId: 'e2e-test-order',
        planCode,
        displayNameEn: 'Duplicate',
        displayNameZh: '重复',
      },
    });
    expect(resp.ok()).toBeFalsy();
  });

  test('duplicate feature creation is rejected', async ({ page }) => {
    const resp = await page.request.post('/api/admin/entitlements/features', {
      data: {
        pluginId: 'e2e-test-order',
        featureKey,
        displayNameEn: 'Duplicate',
        displayNameZh: '重复',
      },
    });
    expect(resp.ok()).toBeFalsy();
  });
});
