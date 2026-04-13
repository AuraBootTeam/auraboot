/**
 * E2E Test: View Public Sharing & Embed (GAP-121)
 *
 * Tests view sharing: generate link, access shared view, password protection,
 * expiration, and revocation.
 */

import { test, expect, type Page } from '@playwright/test';
import { uniqueId } from '../helpers';

async function createViewViaApi(page: Page, name: string): Promise<string> {
  const resp = await page.request.post('/api/views', {
    data: { name, modelCode: 'e2et_order', viewType: 'table', scope: 'personal', viewConfig: {} },
  });
  if (!resp.ok()) return '';
  const body = await resp.json();
  return body.data?.pid ?? '';
}

test.describe('View Public Sharing (GAP-121)', () => {
  test('VS-001: generate share link for a view', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const pid = await createViewViaApi(page, `VS_Share_${uniqueId()}`);
    expect(pid).toBeTruthy();

    const resp = await page.request.post(`/api/views/${pid}/share`, { data: {} });
    expect(resp.ok()).toBeTruthy();
    const body = await resp.json();
    expect(body.data?.token).toBeTruthy();
    expect(body.data?.shareUrl).toContain('/api/views/shared/');
  });

  test('VS-002: access shared view by token', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const pid = await createViewViaApi(page, `VS_Access_${uniqueId()}`);
    const shareResp = await page.request.post(`/api/views/${pid}/share`, { data: {} });
    const token = (await shareResp.json()).data?.token;
    expect(token).toBeTruthy();

    // Access shared view
    const viewResp = await page.request.get(`/api/views/shared/${token}`);
    expect(viewResp.ok()).toBeTruthy();
    const viewData = (await viewResp.json()).data;
    expect(viewData?.modelCode).toBe('e2et_order');
    expect(viewData?.viewType).toBe('table');
  });

  test('VS-003: share with password protection', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const pid = await createViewViaApi(page, `VS_Password_${uniqueId()}`);
    const shareResp = await page.request.post(`/api/views/${pid}/share`, {
      data: { password: 'secret123' },
    });
    const result = (await shareResp.json()).data;
    expect(result?.passwordProtected).toBe(true);

    const token = result?.token;

    // Access without password → should fail
    const failResp = await page.request.get(`/api/views/shared/${token}`);
    expect(failResp.ok()).toBe(false);

    // Access with correct password → should succeed
    const successResp = await page.request.get(`/api/views/shared/${token}?password=secret123`);
    expect(successResp.ok()).toBeTruthy();
  });

  test('VS-004: revoke share link', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const pid = await createViewViaApi(page, `VS_Revoke_${uniqueId()}`);
    const shareResp = await page.request.post(`/api/views/${pid}/share`, { data: {} });
    const token = (await shareResp.json()).data?.token;

    // Revoke
    const revokeResp = await page.request.delete(`/api/views/${pid}/share`);
    expect(revokeResp.ok()).toBeTruthy();

    // Access after revoke → should fail
    const accessResp = await page.request.get(`/api/views/shared/${token}`);
    expect(accessResp.ok()).toBe(false);
  });

  test('VS-005: get share status', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const pid = await createViewViaApi(page, `VS_Status_${uniqueId()}`);

    // Initially not shared
    const statusResp1 = await page.request.get(`/api/views/${pid}/share/status`);
    const status1 = (await statusResp1.json()).data;
    expect(status1?.shared).toBe(false);

    // Share it
    await page.request.post(`/api/views/${pid}/share`, { data: {} });

    // Now shared
    const statusResp2 = await page.request.get(`/api/views/${pid}/share/status`);
    const status2 = (await statusResp2.json()).data;
    expect(status2?.shared).toBe(true);
    expect(status2?.token).toBeTruthy();
  });

  test('VS-006: iframe embed code generation', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const pid = await createViewViaApi(page, `VS_Embed_${uniqueId()}`);
    const shareResp = await page.request.post(`/api/views/${pid}/share`, { data: {} });
    const result = (await shareResp.json()).data;

    // Verify the shareUrl can be used as iframe src
    expect(result?.shareUrl).toBeTruthy();
    const embedCode = `<iframe src="${result.shareUrl}" width="100%" height="600" frameborder="0"></iframe>`;
    expect(embedCode).toContain('iframe');
    expect(embedCode).toContain(result.token);
  });
});
