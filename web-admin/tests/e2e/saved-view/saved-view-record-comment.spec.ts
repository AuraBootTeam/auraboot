/**
 * E2E Test: Record Comment & Activity History (GAP-123)
 *
 * Tests record-level comments: add, edit, delete, list, and activity history.
 */

import { test, expect } from '@playwright/test';
import { uniqueId } from '../helpers';

test.describe('Record Comment & Activity History (GAP-123)', () => {

  test('RC-001: add comment to a record', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    // First get a record PID
    const listResp = await page.request.get('/api/dynamic/e2et-order/list?pageNum=1&pageSize=1');
    if (!listResp.ok()) { test.skip(true, 'No records'); return; }
    const records = (await listResp.json()).data?.records;
    if (!records?.length) { test.skip(true, 'No records'); return; }

    const recordPid = records[0].pid;
    const comment = `Test comment ${uniqueId()}`;

    const resp = await page.request.post(`/api/records/e2et_order/${recordPid}/comments`, {
      data: { content: comment },
    });
    expect(resp.ok()).toBeTruthy();
    const body = await resp.json();
    expect(body.data?.content).toBe(comment);
    expect(body.data?.id).toBeTruthy();
  });

  test('RC-002: list comments for a record', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const listResp = await page.request.get('/api/dynamic/e2et-order/list?pageNum=1&pageSize=1');
    const records = (await listResp.json()).data?.records;
    if (!records?.length) { test.skip(true, 'No records'); return; }

    const recordPid = records[0].pid;

    // Add a comment first
    await page.request.post(`/api/records/e2et_order/${recordPid}/comments`, {
      data: { content: `Comment for list ${uniqueId()}` },
    });

    // List comments
    const resp = await page.request.get(`/api/records/e2et_order/${recordPid}/comments`);
    expect(resp.ok()).toBeTruthy();
    const comments = (await resp.json()).data;
    expect(Array.isArray(comments)).toBe(true);
    expect(comments.length).toBeGreaterThan(0);
  });

  test('RC-003: edit a comment shows edited flag', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const listResp = await page.request.get('/api/dynamic/e2et-order/list?pageNum=1&pageSize=1');
    const records = (await listResp.json()).data?.records;
    if (!records?.length) { test.skip(true, 'No records'); return; }

    const recordPid = records[0].pid;
    const addResp = await page.request.post(`/api/records/e2et_order/${recordPid}/comments`, {
      data: { content: `Original ${uniqueId()}` },
    });
    const commentId = (await addResp.json()).data?.id;
    expect(commentId).toBeTruthy();

    // Edit
    const editResp = await page.request.put(`/api/records/e2et_order/${recordPid}/comments/${commentId}`, {
      data: { content: 'Edited content' },
    });
    expect(editResp.ok()).toBeTruthy();
    const edited = (await editResp.json()).data;
    expect(edited?.is_edited).toBe(true);
    expect(edited?.content).toBe('Edited content');
  });

  test('RC-004: delete a comment', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const listResp = await page.request.get('/api/dynamic/e2et-order/list?pageNum=1&pageSize=1');
    const records = (await listResp.json()).data?.records;
    if (!records?.length) { test.skip(true, 'No records'); return; }

    const recordPid = records[0].pid;
    const addResp = await page.request.post(`/api/records/e2et_order/${recordPid}/comments`, {
      data: { content: `Delete me ${uniqueId()}` },
    });
    const commentId = (await addResp.json()).data?.id;

    // Delete
    const delResp = await page.request.delete(`/api/records/e2et_order/${recordPid}/comments/${commentId}`);
    expect(delResp.ok()).toBeTruthy();

    // Verify it's gone (soft-deleted)
    const listAfter = await page.request.get(`/api/records/e2et_order/${recordPid}/comments`);
    const remaining = (await listAfter.json()).data;
    const found = (remaining as any[]).find((c: any) => c.id === commentId);
    expect(found).toBeFalsy();
  });

  test('RC-005: activity history API responds', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const listResp = await page.request.get('/api/dynamic/e2et-order/list?pageNum=1&pageSize=1');
    const records = (await listResp.json()).data?.records;
    if (!records?.length) { test.skip(true, 'No records'); return; }

    const recordPid = records[0].pid;
    const resp = await page.request.get(`/api/records/e2et_order/${recordPid}/activity`);
    expect(resp.ok()).toBeTruthy();
    const activity = (await resp.json()).data;
    expect(Array.isArray(activity)).toBe(true);
  });

  test('RC-006: comment with @mentions', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const listResp = await page.request.get('/api/dynamic/e2et-order/list?pageNum=1&pageSize=1');
    const records = (await listResp.json()).data?.records;
    if (!records?.length) { test.skip(true, 'No records'); return; }

    const recordPid = records[0].pid;
    const resp = await page.request.post(`/api/records/e2et_order/${recordPid}/comments`, {
      data: { content: `Hey @admin check this ${uniqueId()}`, mentions: '["admin"]' },
    });
    expect(resp.ok()).toBeTruthy();
    const comment = (await resp.json()).data;
    expect(comment?.mentions).toBeTruthy();
  });
});
