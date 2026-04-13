/**
 * Document Editor E2E Tests
 *
 * Tests DOC-E01 ~ DOC-E09: Document editor page rendering and interactions.
 * - Page navigation and title
 * - Tiptap editor area
 * - Toolbar buttons (Bold, Italic, H1, H2, etc.)
 * - Text editing and formatting
 * - Title input editing
 *
 * Uses storageState for authentication.
 * Connects to real database and API (no mocks).
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';

/**
 * Navigate to the Document Editor page.
 * Returns true if the page loaded successfully.
 */
async function navigateToDocumentEditor(page: import('@playwright/test').Page): Promise<boolean> {
  await page.goto('/documents', { waitUntil: 'domcontentloaded' });

  // Wait for page title, login redirect, or ProseMirror editor
  const headingLocator = page.locator('h1:has-text("Documents")');
  const editorLocator = page.locator('.ProseMirror');
  const loginLocator = page.locator('text=请先登录, text=欢迎登录');
  const errorHeading = page.locator('h1:has-text("Oops!")');

  const result = await Promise.race([
    headingLocator.waitFor({ timeout: 10000 }).then(() => 'content' as const),
    editorLocator.waitFor({ timeout: 10000 }).then(() => 'content' as const),
    loginLocator
      .first()
      .waitFor({ timeout: 10000 })
      .then(() => 'login' as const),
    errorHeading.waitFor({ timeout: 10000 }).then(() => 'error' as const),
  ]).catch(() => 'timeout' as const);

  return result === 'content';
}

test.describe('Document Editor - Page Rendering', () => {
  /**
   * DOC-E01: Document page loads
   * Verify that /documents is accessible and renders the page title.
   */
  test('DOC-E01: Page loads with title', async ({ page }) => {
    const loaded = await navigateToDocumentEditor(page);

    expect(loaded).toBe(true);

    // Verify page heading
    await expect(page.locator('h1:has-text("Documents")')).toBeVisible();
  });

  /**
   * DOC-E02: Tiptap editor area renders
   * Verify the ProseMirror editor content area is present.
   */
  test('DOC-E02: Tiptap editor area renders', async ({ page }) => {
    const loaded = await navigateToDocumentEditor(page);

    expect(loaded).toBe(true);

    // The Tiptap editor renders a .ProseMirror contenteditable div
    const editor = page.locator('.ProseMirror');
    await expect(editor).toBeVisible({ timeout: 8000 });

    // Editor should be contenteditable
    const isEditable = await editor.getAttribute('contenteditable');
    expect(isEditable).toBe('true');
  });

  /**
   * DOC-E03: Title input exists and is editable
   * Verify the document title input field renders with the default title.
   */
  test('DOC-E03: Title input renders and is editable', async ({ page }) => {
    const loaded = await navigateToDocumentEditor(page);

    expect(loaded).toBe(true);

    // Find the title input with placeholder "Untitled Document"
    const titleInput = page.locator('input[placeholder="Untitled Document"]');
    await expect(titleInput).toBeVisible({ timeout: 5000 });

    // Default value should be "Untitled Document"
    const currentValue = await titleInput.inputValue();
    expect(currentValue).toBeTruthy();

    // Clear and type a new title
    await titleInput.clear();
    await titleInput.fill('My Test Document');
    await expect(titleInput).toHaveValue('My Test Document');
  });
});

test.describe('Document Editor - Toolbar', () => {
  /**
   * DOC-E04: Toolbar buttons exist
   * Verify that formatting toolbar buttons are rendered.
   */
  test('DOC-E04: Toolbar buttons are visible', async ({ page }) => {
    const loaded = await navigateToDocumentEditor(page);

    expect(loaded).toBe(true);

    // Wait for editor to initialize
    await page.locator('.ProseMirror').waitFor({ timeout: 8000 });

    // Verify toolbar buttons by their title attributes
    await expect(page.locator('button[title="Bold (Ctrl+B)"]')).toBeVisible({ timeout: 5000 });
    await expect(page.locator('button[title="Italic (Ctrl+I)"]')).toBeVisible();
    await expect(page.locator('button[title="Strikethrough"]')).toBeVisible();
    await expect(page.locator('button[title="Inline Code"]')).toBeVisible();
  });

  /**
   * DOC-E05: Heading buttons exist
   * Verify that H1, H2, H3 heading buttons are rendered.
   */
  test('DOC-E05: Heading buttons are visible', async ({ page }) => {
    const loaded = await navigateToDocumentEditor(page);

    expect(loaded).toBe(true);

    await page.locator('.ProseMirror').waitFor({ timeout: 8000 });

    await expect(page.locator('button[title="Heading 1"]')).toBeVisible({ timeout: 5000 });
    await expect(page.locator('button[title="Heading 2"]')).toBeVisible();
    await expect(page.locator('button[title="Heading 3"]')).toBeVisible();
  });

  /**
   * DOC-E06: List and block buttons exist
   * Verify that list and block formatting buttons are rendered.
   */
  test('DOC-E06: List and block buttons are visible', async ({ page }) => {
    const loaded = await navigateToDocumentEditor(page);

    expect(loaded).toBe(true);

    await page.locator('.ProseMirror').waitFor({ timeout: 8000 });

    await expect(page.locator('button[title="Bullet List"]')).toBeVisible({ timeout: 5000 });
    await expect(page.locator('button[title="Numbered List"]')).toBeVisible();
    await expect(page.locator('button[title="Blockquote"]')).toBeVisible();
    await expect(page.locator('button[title="Code Block"]')).toBeVisible();
    await expect(page.locator('button[title="Horizontal Rule"]')).toBeVisible();
    await expect(page.locator('button[title="Add Link"]')).toBeVisible();
  });

  /**
   * DOC-E07: Undo/Redo buttons exist
   * Verify that undo and redo buttons are rendered.
   */
  test('DOC-E07: Undo and Redo buttons are visible', async ({ page }) => {
    const loaded = await navigateToDocumentEditor(page);

    expect(loaded).toBe(true);

    await page.locator('.ProseMirror').waitFor({ timeout: 8000 });

    await expect(page.locator('button[title="Undo (Ctrl+Z)"]')).toBeVisible({ timeout: 5000 });
    await expect(page.locator('button[title="Redo (Ctrl+Shift+Z)"]')).toBeVisible();
  });
});

test.describe('Document Editor - Editing', () => {
  /**
   * DOC-E08: Type text in the editor
   * Verify that text can be typed into the Tiptap editor area.
   */
  test('DOC-E08: Type text in editor', async ({ page }) => {
    const loaded = await navigateToDocumentEditor(page);

    expect(loaded).toBe(true);

    const editor = page.locator('.ProseMirror');
    await expect(editor).toBeVisible({ timeout: 8000 });

    // Click into the editor and type
    await editor.click();
    await page.keyboard.type('Hello, this is a test document.');

    // Verify text appeared
    await expect(editor).toContainText('Hello, this is a test document.');
  });

  /**
   * DOC-E09: Apply bold formatting
   * Verify that clicking Bold then typing creates bold text.
   */
  test('DOC-E09: Apply bold formatting', async ({ page }) => {
    const loaded = await navigateToDocumentEditor(page);

    expect(loaded).toBe(true);

    const editor = page.locator('.ProseMirror');
    await expect(editor).toBeVisible({ timeout: 8000 });

    await editor.click();
    const boldButton = page.locator('button[title="Bold (Ctrl+B)"]');
    await boldButton.click();
    await editor.click();
    await page.keyboard.type('Bold text here');
    await expect(editor).toContainText('Bold text here');

    const strongTag = editor.locator('strong');
    const hasStrong = await strongTag
      .first()
      .isVisible({ timeout: 3000 })
      .catch(() => false);
    expect(hasStrong).toBe(true);
  });

  /**
   * DOC-E10: Apply heading formatting
   * Verify that clicking H1 converts the current paragraph to a heading.
   */
  test('DOC-E10: Apply H1 heading', async ({ page }) => {
    const loaded = await navigateToDocumentEditor(page);

    expect(loaded).toBe(true);

    const editor = page.locator('.ProseMirror');
    await expect(editor).toBeVisible({ timeout: 8000 });

    // Click into the editor and type a heading
    await editor.click();
    await page.keyboard.type('My Document Title');

    // Select all text in the editor
    await page.keyboard.press('Control+a');

    // Click H1 button
    const h1Button = page.locator('button[title="Heading 1"]');
    await h1Button.click();

    // Verify an h1 element exists in the editor
    const h1Element = editor.locator('h1');
    const hasH1 = await h1Element.isVisible({ timeout: 3000 }).catch(() => false);
    expect(hasH1).toBe(true);

    if (hasH1) {
      await expect(h1Element).toContainText('My Document Title');
    }
  });
});

test.describe('Document Editor - Footer', () => {
  /**
   * DOC-E11: Character count and edit mode indicator
   * Verify the footer shows character count and editing status.
   */
  test('DOC-E11: Footer shows character count and editing status', async ({ page }) => {
    const loaded = await navigateToDocumentEditor(page);

    expect(loaded).toBe(true);

    const editor = page.locator('.ProseMirror');
    await expect(editor).toBeVisible({ timeout: 8000 });

    // Footer should show "characters" count
    const footer = page.locator('text=characters');
    await expect(footer).toBeVisible({ timeout: 5000 });

    // Footer should show "Editing" mode
    await expect(page.getByText('Editing')).toBeVisible();
  });
});

test.describe('Document Editor - Boundary Tests', () => {
  /**
   * DOC-E12: Page renders without error overlays
   * Verify the page is stable and shows no JavaScript errors.
   */
  test('DOC-E12: Page stable without errors', async ({ page }) => {
    const loaded = await navigateToDocumentEditor(page);

    expect(loaded).toBe(true);

    // Verify no error overlays
    const errorOverlay = page.locator(
      '#webpack-dev-server-client-overlay, ' + '[data-testid="error-overlay"], ' + '.error-overlay',
    );
    const hasError = await errorOverlay.isVisible({ timeout: 1000 }).catch(() => false);
    expect(hasError).toBe(false);

    // Page title should still be visible
    await expect(page.locator('h1:has-text("Documents")')).toBeVisible();

    // Editor should still be functional
    const editor = page.locator('.ProseMirror');
    await expect(editor).toBeVisible();
  });
});
