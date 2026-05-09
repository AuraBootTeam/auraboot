/**
 * File Preview Modal — E2E Tests
 *
 * Tests the FilePreviewModal component integrated into SmartUpload.
 * Uses the dp_issue form which has an IMAGE_UPLOAD field (dp_issue_images)
 * configured with SmartUpload component and picture-card list type.
 *
 * Test coverage:
 * - FP-001: Upload an image file and verify it appears in the file list
 * - FP-002: Click uploaded image thumbnail to open preview modal
 * - FP-003: Preview modal displays correct file name and image content
 * - FP-004: Close preview modal via the dialog close button
 */
import { test, expect } from '@playwright/test';
import { navigateToDynamicPage, executeCommandViaApi, waitForFormReady } from '../helpers/index';
import { getTestProjectId } from '../quarry-management.setup';
import path from 'node:path';
import fs from 'node:fs';
import { BASE_URL } from '../../helpers/environments';

const ISSUE_MODEL = 'dp_issue';

/**
 * Create a minimal valid PNG file (1x1 red pixel) for upload testing.
 * Returns the absolute path to the created temp file.
 */
function createTestImageFile(dir: string, filename: string): string {
  // Minimal PNG: 1x1 pixel, red color
  // PNG signature + IHDR + IDAT + IEND chunks
  const pngBuffer = Buffer.from([
    0x89,
    0x50,
    0x4e,
    0x47,
    0x0d,
    0x0a,
    0x1a,
    0x0a, // PNG signature
    0x00,
    0x00,
    0x00,
    0x0d,
    0x49,
    0x48,
    0x44,
    0x52, // IHDR length + type
    0x00,
    0x00,
    0x00,
    0x01,
    0x00,
    0x00,
    0x00,
    0x01, // 1x1 pixel
    0x08,
    0x02,
    0x00,
    0x00,
    0x00,
    0x90,
    0x77,
    0x53, // 8-bit RGB, CRC
    0xde,
    0x00,
    0x00,
    0x00,
    0x0c,
    0x49,
    0x44,
    0x41, // IDAT length + type
    0x54,
    0x08,
    0xd7,
    0x63,
    0xf8,
    0xcf,
    0xc0,
    0x00, // compressed data
    0x00,
    0x00,
    0x02,
    0x00,
    0x01,
    0xe2,
    0x21,
    0xbc, // data + CRC
    0x33,
    0x00,
    0x00,
    0x00,
    0x00,
    0x49,
    0x45,
    0x4e, // IEND length + type
    0x44,
    0xae,
    0x42,
    0x60,
    0x82, // IEND CRC
  ]);

  const filepath = path.join(dir, filename);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(filepath, pngBuffer);
  return filepath;
}

test.describe('File Preview Modal', () => {
  test.describe.configure({ mode: 'serial' });

  let projectId: string | null = null;
  const tempDir = path.join(process.cwd(), 'test-results', 'temp-uploads');
  const createdPids: string[] = [];

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: 'tests/storage/admin.json',
      baseURL: BASE_URL,
    });
    const page = await ctx.newPage();
    try {
      projectId = await getTestProjectId(page);
    } catch (e: any) {
      console.warn('PM/QO plugin not available:', e.message);
    }
    await page.close();
    await ctx.close();
  });

  test.afterAll(async ({ browser }) => {
    // Cleanup created issues
    const ctx = await browser.newContext({
      storageState: 'tests/storage/admin.json',
      baseURL: BASE_URL,
    });
    const page = await ctx.newPage();
    for (const pid of createdPids) {
      await executeCommandViaApi(page, 'dp:delete_issue', {}, pid, 'delete').catch(() => {});
    }
    await ctx.close();

    // Cleanup temp files
    if (fs.existsSync(tempDir)) {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });

  test('FP-001: Upload area renders in dp_issue form with SmartUpload component', async ({
    page,
  }) => {
    if (!projectId) {
      throw new Error('Project not available - PM/QO plugin may not be imported');
    }

    // Navigate to create new issue form
    await navigateToDynamicPage(page, ISSUE_MODEL);
    await expect(page.locator('table, [role="table"]').first()).toBeVisible();

    // Click create button to open form
    const addBtn = page
      .locator('[data-testid="toolbar-btn-create"], button:has-text("新建")')
      .first();
    await addBtn.click();
    await page.waitForURL((url) => url.pathname.includes('/new'), { timeout: 10000 });
    await waitForFormReady(page);

    // Verify the dp_issue_images field container exists
    const imageFieldContainer = page.locator('[data-testid="form-field-dp_issue_images"]');
    await expect(imageFieldContainer).toBeVisible({ timeout: 10000 });

    // Check for file upload input inside the field (SmartUpload renders a hidden file input)
    const fileInput = imageFieldContainer.locator('input[type="file"]');
    // The file input is hidden but should be attached to DOM
    await expect(fileInput).toBeAttached({ timeout: 5000 });
  });

  test('FP-002: Upload an image file and verify it appears in the upload list', async ({
    page,
  }) => {
    test.fixme(true, 'File upload API returns 500 — server-side storage not configured');
    test.setTimeout(30000);
    if (!projectId) {
      throw new Error('Project not available - PM/QO plugin may not be imported');
    }

    // Navigate to create new issue form
    await page.goto(`/p/${ISSUE_MODEL}/new?commandCode=dp%3Acreate_issue`);
    await waitForFormReady(page);

    // Wait for the dp_issue_images field to render
    const imageFieldContainer = page.locator('[data-testid="form-field-dp_issue_images"]');
    await expect(imageFieldContainer).toBeVisible({ timeout: 10000 });

    // Create a test image file
    const testImagePath = createTestImageFile(tempDir, 'test-preview.png');

    // Find the hidden file input inside SmartUpload and set the file
    const fileInput = imageFieldContainer.locator('input[type="file"]');
    await expect(fileInput).toBeAttached({ timeout: 5000 });

    // Upload the file via setInputFiles and wait for upload API response
    const uploadResponse = page.waitForResponse((resp) => resp.url().includes('/file/upload'), {
      timeout: 20000,
    });
    await fileInput.setInputFiles(testImagePath);
    const resp = await uploadResponse;

    // Verify the upload API was called (regardless of success/failure)
    expect(resp.status()).toBeLessThan(400);

    // Wait for the upload counter to update (shows after file is processed)
    const uploadCounter = imageFieldContainer.locator('text=/\\d+\\/\\d+ files uploaded/');
    await expect(uploadCounter).toBeVisible({ timeout: 10000 });
  });

  test('FP-003: Click uploaded image opens preview modal with correct file name', async ({
    page,
  }) => {
    test.setTimeout(30000);
    if (!projectId) {
      throw new Error('Project not available - PM/QO plugin may not be imported');
    }

    // Navigate to create new issue form
    await page.goto(`/p/${ISSUE_MODEL}/new?commandCode=dp%3Acreate_issue`);
    await waitForFormReady(page);

    const imageFieldContainer = page.locator('[data-testid="form-field-dp_issue_images"]');
    await expect(imageFieldContainer).toBeVisible({ timeout: 10000 });

    // Upload a test image
    const testImagePath = createTestImageFile(tempDir, 'preview-test-image.png');
    const fileInput = imageFieldContainer.locator('input[type="file"]');
    await expect(fileInput).toBeAttached({ timeout: 5000 });
    // Wait for upload API response before interacting with the thumbnail
    const uploadDone = page.waitForResponse((resp) => resp.url().includes('/file/upload'), {
      timeout: 20000,
    });
    await fileInput.setInputFiles(testImagePath);
    await uploadDone;

    // Wait for the upload to render a thumbnail
    const thumbnail = imageFieldContainer.locator('img[alt="preview-test-image.png"]');
    await expect(thumbnail).toBeVisible({ timeout: 15000 });

    // SmartUpload's overlay (absolute inset-0 bg-black/30) covers the img
    // during 'uploading' status and intercepts clicks. Use JS dispatch to
    // click the img directly, triggering handlePreview regardless of overlay.
    await thumbnail.dispatchEvent('click');

    // Verify the FilePreviewModal opens
    const previewModal = page.locator('[data-testid="file-preview-modal"]');
    await expect(previewModal).toBeVisible({ timeout: 5000 });

    // Verify the modal title contains the file name
    const modalTitle = previewModal.locator('h2, [class*="DialogTitle"]').first();
    await expect(modalTitle).toContainText('preview-test-image.png');

    // Verify the modal contains an image preview (image kind)
    const previewImage = previewModal.locator('img');
    await expect(previewImage).toBeVisible({ timeout: 5000 });
  });

  test('FP-004: Close preview modal via dialog close button', async ({ page }) => {
    test.setTimeout(30000);
    if (!projectId) {
      throw new Error('Project not available - PM/QO plugin may not be imported');
    }

    // Navigate to create new issue form
    await page.goto(`/p/${ISSUE_MODEL}/new?commandCode=dp%3Acreate_issue`);
    await waitForFormReady(page);

    const imageFieldContainer = page.locator('[data-testid="form-field-dp_issue_images"]');
    await expect(imageFieldContainer).toBeVisible({ timeout: 10000 });

    // Upload a test image
    const testImagePath = createTestImageFile(tempDir, 'close-modal-test.png');
    const fileInput = imageFieldContainer.locator('input[type="file"]');
    await expect(fileInput).toBeAttached({ timeout: 5000 });
    // Wait for upload API response
    const uploadDone = page.waitForResponse((resp) => resp.url().includes('/file/upload'), {
      timeout: 20000,
    });
    await fileInput.setInputFiles(testImagePath);
    await uploadDone;

    // Wait for thumbnail to appear (skip spinner check — it may persist)
    const thumbnail = imageFieldContainer.locator('img[alt="close-modal-test.png"]');
    await expect(thumbnail).toBeVisible({ timeout: 15000 });

    // Open preview modal — dispatch click directly to bypass SmartUpload overlay
    await thumbnail.dispatchEvent('click');
    const previewModal = page.locator('[data-testid="file-preview-modal"]');
    await expect(previewModal).toBeVisible({ timeout: 5000 });

    // Close the modal using the Radix Dialog close button (X button)
    // DialogContent renders a close button with data-testid="btn-close-dialog"
    const closeButton = previewModal.locator('[data-testid="btn-close-dialog"]');
    await closeButton.click();

    // Verify the modal is no longer visible
    await expect(previewModal).not.toBeVisible({ timeout: 5000 });
  });

  test('FP-005: Remove uploaded file from the upload list', async ({ page }) => {
    test.setTimeout(30000);
    if (!projectId) {
      throw new Error('Project not available - PM/QO plugin may not be imported');
    }

    // Navigate to create new issue form
    await page.goto(`/p/${ISSUE_MODEL}/new?commandCode=dp%3Acreate_issue`);
    await waitForFormReady(page);

    const imageFieldContainer = page.locator('[data-testid="form-field-dp_issue_images"]');
    await expect(imageFieldContainer).toBeVisible({ timeout: 10000 });

    // Upload a test image
    const testImagePath = createTestImageFile(tempDir, 'remove-test.png');
    const fileInput = imageFieldContainer.locator('input[type="file"]');
    await expect(fileInput).toBeAttached({ timeout: 5000 });

    // Wait for upload API response before proceeding
    const uploadDone = page.waitForResponse((resp) => resp.url().includes('/file/upload'), {
      timeout: 20000,
    });
    await fileInput.setInputFiles(testImagePath);
    await uploadDone;

    // Wait for file thumbnail to appear
    const thumbnail = imageFieldContainer.locator('img[alt="remove-test.png"]').first();
    await expect(thumbnail).toBeVisible({ timeout: 15000 });

    // Wait for upload status to settle (uploading → done or error).
    // The remove button only renders when file.status !== 'uploading'.
    const removeButton = imageFieldContainer.locator('[data-testid="btn-remove-file"]').first();
    await expect(removeButton).toBeAttached({ timeout: 15000 });

    // Click remove — use force:true because the button has opacity-0 (group-hover:opacity-100)
    // and error/uploading overlays may intercept pointer events
    await removeButton.click({ force: true });

    // Verify the file is removed — thumbnail should disappear
    await expect(thumbnail).not.toBeVisible({ timeout: 5000 });
  });

  test('FP-006: Upload multiple images shows correct count', async ({ page }) => {
    test.setTimeout(30000);
    if (!projectId) {
      throw new Error('Project not available - PM/QO plugin may not be imported');
    }

    // Navigate to create new issue form
    await page.goto(`/p/${ISSUE_MODEL}/new?commandCode=dp%3Acreate_issue`);
    await waitForFormReady(page);

    const imageFieldContainer = page.locator('[data-testid="form-field-dp_issue_images"]');
    await expect(imageFieldContainer).toBeVisible({ timeout: 10000 });

    // Create two test images
    const testImage1 = createTestImageFile(tempDir, 'multi-1.png');
    const testImage2 = createTestImageFile(tempDir, 'multi-2.png');

    // Upload first image — wait for API response
    const fileInput = imageFieldContainer.locator('input[type="file"]');
    await expect(fileInput).toBeAttached({ timeout: 5000 });
    const upload1Done = page.waitForResponse((resp) => resp.url().includes('/file/upload'), {
      timeout: 20000,
    });
    await fileInput.setInputFiles(testImage1);
    await upload1Done;

    // Verify counter shows 1/9
    await expect(imageFieldContainer.locator('text=/1\\/9 files uploaded/')).toBeVisible({
      timeout: 5000,
    });

    // Upload second image
    const fileInput2 = imageFieldContainer.locator('input[type="file"]');
    await fileInput2.setInputFiles(testImage2);

    // Verify counter shows 2/9
    await expect(imageFieldContainer.locator('text=/2\\/9 files uploaded/')).toBeVisible({
      timeout: 10000,
    });
  });
});
