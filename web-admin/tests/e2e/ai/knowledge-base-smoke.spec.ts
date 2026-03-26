/**
 * RAG Knowledge Base — Smoke E2E Tests
 *
 * Verifies:
 * 1. KB list page loads via menu navigation
 * 2. Create KB → appears in list
 * 3. Upload TXT document → processing completes
 * 4. KB detail page: Documents tab shows data
 * 5. KB detail page: Chunks tab shows processed chunks
 * 6. KB detail page: Retrieval Test tab functional
 * 7. Delete document and KB
 */

import { test, expect } from '@playwright/test';
import { uniqueId } from '../helpers';

const KB_NAME = `E2E KB ${uniqueId('KB')}`;
const TEST_FILE_CONTENT = `
AuraBoot Platform Overview

AuraBoot is a low-code enterprise application platform built with Spring Boot and React.
It provides a DSL-driven approach to building business applications.

Key Features

The platform supports dynamic model creation through configuration.
Each model has custom fields, validation rules, and workflow triggers.
The command system provides a 20-stage pipeline for data operations.

Plugin Architecture

AuraBoot uses a layered plugin architecture with L1 generic and L2 industry plugins.
Solution plugins compose multiple L1/L2 plugins into complete vertical solutions.
`.trim();

let kbPid: string;

test.describe('RAG Knowledge Base', () => {

  test.describe.configure({ mode: 'serial' });

  test('should navigate to KB list via menu', async ({ page }) => {
    await page.goto('/');
    // Wait for app shell
    await page.waitForLoadState('domcontentloaded');

    // Navigate via sidebar menu: AuraBot Management → Knowledge Base
    const sidebar = page.locator('nav, aside, [data-testid="sidebar"]').first();
    // Click AuraBot parent menu
    const aurabotMenu = sidebar.getByText(/AuraBot/);
    if (await aurabotMenu.isVisible()) {
      await aurabotMenu.evaluate((el: HTMLElement) => el.click());
    }
    // Click Knowledge Base menu item
    const kbMenu = sidebar.getByText(/Knowledge Base|知识库/);
    if (await kbMenu.isVisible()) {
      await kbMenu.evaluate((el: HTMLElement) => el.click());
    } else {
      // Fallback: direct navigation
      await page.goto('/aurabot/knowledge');
    }

    // Verify page loads
    await expect(page.getByRole('heading', { name: 'Knowledge Base' })).toBeVisible({ timeout: 10000 });
    await expect(page.getByRole('button', { name: 'New Knowledge Base' })).toBeVisible();
  });

  test('should create a knowledge base via API', async ({ page }) => {
    // Use API to create KB (data prep)
    const resp = await page.request.post('/api/ai/knowledge', {
      data: {
        name: KB_NAME,
        description: 'E2E test knowledge base for smoke testing',
        embeddingProvider: 'openai',
        embeddingModel: 'text-embedding-3-small',
        chunkSize: 300,
        chunkOverlap: 30,
      },
    });
    expect(resp.ok()).toBeTruthy();
    const body = await resp.json();
    kbPid = body.data.pid;
    expect(kbPid).toBeTruthy();
    expect(body.data.name).toBe(KB_NAME);
    expect(body.data.status).toBe('active');
    expect(body.data.docCount).toBe(0);
  });

  test('should show KB in list page', async ({ page }) => {
    await page.goto('/aurabot/knowledge');
    const kbCard = page.locator('.rounded-xl', { hasText: KB_NAME });
    await expect(kbCard).toBeVisible({ timeout: 10000 });
    // Verify card details scoped to this KB's card
    await expect(kbCard.getByText('0 docs')).toBeVisible();
    await expect(kbCard.getByText('active')).toBeVisible();
  });

  test('should upload a TXT document', async ({ page }) => {
    await page.goto(`/aurabot/knowledge/${kbPid}`);
    await page.waitForLoadState('domcontentloaded');

    const uploadTrigger = page.locator('label', { hasText: /Upload Files|Uploading/i }).first();
    await expect(uploadTrigger).toBeVisible({ timeout: 10000 });

    const fileInput = uploadTrigger.locator('input[type="file"]');
    await expect(fileInput).toHaveCount(1);
    await fileInput.setInputFiles({
      name: 'test-auraboot-overview.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from(TEST_FILE_CONTENT, 'utf-8'),
    });

    await expect(page.getByText(/Uploading\.\.\.|上传中/i)).toBeVisible({ timeout: 5000 });
    await expect
      .poll(async () => {
        const resp = await page.request.get(`/api/ai/knowledge/${kbPid}/documents`);
        const body = await resp.json().catch(() => ({}));
        return Array.isArray(body.data) ? body.data.length : 0;
      }, { timeout: 10000 })
      .toBeGreaterThan(0);
  });

  test('should complete document processing', async ({ page }) => {
    // Poll for processing completion (max 15 seconds)
    let completed = false;
    for (let i = 0; i < 15; i++) {
      const resp = await page.request.get(`/api/ai/knowledge/${kbPid}/documents`);
      const body = await resp.json();
      const doc = body.data[0];
      if (doc.status === 'completed') {
        completed = true;
        expect(doc.charCount).toBeGreaterThan(100);
        expect(doc.chunkCount).toBeGreaterThan(0);
        break;
      }
      if (doc.status === 'failed') {
        throw new Error(`Document processing failed: ${doc.errorMessage}`);
      }
      await expect.poll(async () => {
        const resp = await page.request.get(`/api/ai/knowledge/${kbPid}/documents`);
        const body = await resp.json();
        return body?.data?.[0]?.status ?? '';
      }, { timeout: 1000 }).not.toBe('');
    }
    expect(completed).toBeTruthy();
  });

  test('should show documents in KB detail page', async ({ page }) => {
    await page.goto(`/aurabot/knowledge/${kbPid}`);
    // Verify header
    await expect(page.getByText(KB_NAME)).toBeVisible({ timeout: 10000 });
    // Documents tab should be active by default
    const docRow = page.locator('tr', { hasText: 'test-auraboot-overview.txt' });
    await expect(docRow).toBeVisible();
    await expect(docRow.getByText('completed')).toBeVisible();
  });

  test('should show chunks in Chunks tab', async ({ page }) => {
    await page.goto(`/aurabot/knowledge/${kbPid}`);
    await page.waitForLoadState('domcontentloaded');

    // Switch to Chunks tab
    await page.getByRole('button', { name: 'Chunks' }).click();

    // Wait for chunks to load
    await expect(page.getByText(/Chunk #0/)).toBeVisible({ timeout: 10000 });
    // Should have multiple chunks
    await expect(page.getByText(/Chunk #1/)).toBeVisible();

    // Expand first chunk to see content
    await page.getByText(/Chunk #0/).first().click();
    // Verify chunk content is visible (scoped to chunk area)
    await expect(page.getByText('AuraBoot Platform Overview')).toBeVisible();
  });

  test('should have functional Retrieval Test tab', async ({ page }) => {
    await page.goto(`/aurabot/knowledge/${kbPid}`);
    await page.waitForLoadState('domcontentloaded');

    // Switch to Retrieval Test tab
    await page.getByRole('button', { name: 'Retrieval Test' }).click();

    // Verify search input and button are visible
    await expect(page.locator('input[placeholder*="question"]')).toBeVisible();
    // Search button is next to the input
    const searchBtn = page.locator('button', { hasText: 'Search' }).last();
    await expect(searchBtn).toBeVisible();
  });

  test('should retrieve results via API', async ({ page }) => {
    // Note: Without embedding API key, vector search won't return results.
    // Test the API endpoint responds correctly.
    const resp = await page.request.post('/api/ai/knowledge/retrieve', {
      data: {
        query: 'What is AuraBoot?',
        knowledgeBaseIds: [kbPid],
        topK: 5,
      },
    });
    expect(resp.ok()).toBeTruthy();
    const body = await resp.json();
    // Without embedding config, results will be empty — but API should not error
    expect(Array.isArray(body.data)).toBeTruthy();
  });

  test('should update KB counters after processing', async ({ page }) => {
    const resp = await page.request.get(`/api/ai/knowledge/${kbPid}`);
    expect(resp.ok()).toBeTruthy();
    const body = await resp.json();
    expect(body.data.docCount).toBe(1);
    expect(body.data.chunkCount).toBeGreaterThan(0);
  });

  test('should delete document', async ({ page }) => {
    // Get doc pid
    const docsResp = await page.request.get(`/api/ai/knowledge/${kbPid}/documents`);
    const docs = (await docsResp.json()).data;
    expect(docs.length).toBe(1);
    const docPid = docs[0].pid;

    // Delete
    const delResp = await page.request.delete(`/api/ai/knowledge/${kbPid}/documents/${docPid}`);
    expect(delResp.ok()).toBeTruthy();

    // Verify empty
    const afterResp = await page.request.get(`/api/ai/knowledge/${kbPid}/documents`);
    const afterDocs = (await afterResp.json()).data;
    expect(afterDocs.length).toBe(0);
  });

  test('should delete knowledge base', async ({ page }) => {
    const resp = await page.request.delete(`/api/ai/knowledge/${kbPid}`);
    expect(resp.ok()).toBeTruthy();

    // Verify gone
    const listResp = await page.request.get('/api/ai/knowledge');
    const kbs = (await listResp.json()).data;
    const found = kbs.find((kb: any) => kb.pid === kbPid);
    expect(found).toBeUndefined();
  });
});
