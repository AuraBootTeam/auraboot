/**
 * NamedQueryPage - Page Object for the Named Query management pages.
 *
 * Covers three modes:
 *  - List page:   `/meta/named-queries`
 *  - Create form: `/meta/named-queries/new`
 *  - Edit form:   `/meta/named-queries/:pid`
 *
 * @since 4.0.0
 */

import {
  type APIResponse,
  type Page,
  type Locator,
  type Response as PWResponse,
  expect,
} from '@playwright/test';
import { BasePage } from './BasePage';

export type NqTab = 'basic' | 'fields' | 'test' | 'policy' | 'versions';

export class NamedQueryPage extends BasePage {
  constructor(page: Page) {
    super(page);
  }

  // =====================================================================
  // Navigation
  // =====================================================================

  /** Default goto (list page) */
  async goto(): Promise<void> {
    await this.gotoList();
  }

  /** Navigate to the named query list page */
  async gotoList(): Promise<void> {
    await this.page.goto('/meta/named-queries');
    await this.waitForLoad();
    await expect(this.pageTitle).toBeVisible({ timeout: 10000 });
  }

  /** Navigate to the create form page */
  async gotoNew(): Promise<void> {
    await this.page.goto('/meta/named-queries/new');
    await this.waitForLoad();
  }

  /** Navigate to the edit form page */
  async gotoEdit(pid: string): Promise<void> {
    await this.page.goto(`/meta/named-queries/${pid}`);
    await this.waitForLoad();
  }

  /** Navigate to a specific tab on the edit page */
  async gotoEditTab(pid: string, tab: NqTab): Promise<void> {
    await this.page.goto(`/meta/named-queries/${pid}#${tab}`);
    await this.waitForLoad();
  }

  // =====================================================================
  // List page locators
  // =====================================================================

  get pageTitle(): Locator {
    return this.page.locator('[data-testid="page-title"]');
  }

  get createButton(): Locator {
    return this.page.locator('[data-testid="btn-create-query"]');
  }

  get queryTable(): Locator {
    return this.page.locator('[data-testid="query-table"]');
  }

  /** Status filter dropdown */
  get statusFilter(): Locator {
    return this.page.locator('select, [role="combobox"]').filter({ hasText: /全部状态/ });
  }

  // =====================================================================
  // Create form locators
  // =====================================================================

  get codeInput(): Locator {
    return this.page.locator('[data-testid="form-field-code"]');
  }

  get titleInput(): Locator {
    return this.page.locator('[data-testid="form-field-title"]');
  }

  get descriptionInput(): Locator {
    return this.page.locator('[data-testid="form-field-description"]');
  }

  get fromSqlInput(): Locator {
    return this.page.locator('[data-testid="form-field-fromSql"]');
  }

  /** Monaco editor area inside the fromSql field */
  get fromSqlEditor(): Locator {
    return this.page.locator('[data-testid="form-field-fromSql"] .monaco-editor .view-lines');
  }

  get cancelButton(): Locator {
    return this.page.locator('[data-testid="form-btn-cancel"]');
  }

  get submitButton(): Locator {
    return this.page.locator('[data-testid="form-btn-submit"]');
  }

  // =====================================================================
  // Edit form locators
  // =====================================================================

  get tabBasic(): Locator {
    return this.page.locator('[data-testid="tab-basic"]');
  }

  get tabFields(): Locator {
    return this.page.locator('[data-testid="tab-fields"]');
  }

  get tabTest(): Locator {
    return this.page.locator('[data-testid="tab-test"]');
  }

  get tabPolicy(): Locator {
    return this.page.locator('[data-testid="tab-policy"]');
  }

  get tabVersions(): Locator {
    return this.page.locator('[data-testid="tab-versions"]');
  }

  get saveButton(): Locator {
    return this.page.locator('[data-testid="form-btn-save"]');
  }

  get backButton(): Locator {
    return this.page.locator('[data-testid="form-btn-back"]');
  }

  // =====================================================================
  // Policy tab locators
  // =====================================================================

  get policyMaxRows(): Locator {
    return this.page.locator('[data-testid="policy-max-rows"]');
  }

  get policyTimeout(): Locator {
    return this.page.locator('[data-testid="policy-timeout"]');
  }

  get policyRateLimit(): Locator {
    return this.page.locator('[data-testid="policy-rate-limit"]');
  }

  get policyCacheTtl(): Locator {
    return this.page.locator('[data-testid="policy-cache-ttl"]');
  }

  get policyExportMaxRows(): Locator {
    return this.page.locator('[data-testid="policy-export-max-rows"]');
  }

  get policySandboxMaxRows(): Locator {
    return this.page.locator('[data-testid="policy-sandbox-max-rows"]');
  }

  get savePolicyButton(): Locator {
    return this.page.locator('[data-testid="save-policy-btn"]');
  }

  // =====================================================================
  // Actions
  // =====================================================================

  /** Fill the create form fields */
  async fillCreateForm(
    code: string,
    title: string,
    description?: string,
    fromSql?: string,
  ): Promise<void> {
    // Monaco mounts in a client effect, so this also proves the form has hydrated.
    await expect(this.fromSqlInput.locator('.monaco-editor')).toBeVisible({ timeout: 15000 });
    await this.codeInput.click();
    await this.codeInput.fill(code);

    await this.titleInput.click();
    await this.titleInput.fill(title);

    if (description !== undefined) {
      await this.descriptionInput.fill(description);
    }

    if (fromSql !== undefined) {
      await this.fillFromSql(fromSql);
    }
    await expect(this.codeInput).toHaveValue(code);
    await expect(this.titleInput).toHaveValue(title);
  }

  /** Type through the visible editor and observe its controlled value. */
  async fillFromSql(value: string): Promise<void> {
    const field = this.fromSqlInput;
    const editor = field.locator('.monaco-editor');
    await expect(editor).toBeVisible({ timeout: 15000 });
    await editor.click();
    await this.page.keyboard.press('ControlOrMeta+A');
    await this.page.keyboard.insertText(value);
    await expect(field.getByTestId('sql-editor-value')).toHaveValue(value);
  }

  /** Observe the create endpoint itself; validation responses are not creation evidence. */
  async submitCreate(): Promise<PWResponse> {
    const responsePromise = this.page.waitForResponse(
      (res) => new URL(res.url()).pathname === '/api/meta/named-queries' &&
        res.request().method() === 'POST',
      { timeout: 15000 },
    );
    const submitBtn = this.submitButton.or(
      this.page.locator(
        'button[data-testid="form-btn-save"], button:has-text("创建"), button:has-text("Create"), button:has-text("保存"), button:has-text("Save")',
      ).first(),
    );
    await expect(submitBtn).toBeVisible({ timeout: 5000 });
    await submitBtn.click();
    return responsePromise;
  }

  /** Click a tab button on the edit page */
  async clickTab(tab: NqTab): Promise<void> {
    const tabMap: Record<NqTab, Locator> = {
      basic: this.tabBasic,
      fields: this.tabFields,
      test: this.tabTest,
      policy: this.tabPolicy,
      versions: this.tabVersions,
    };
    await tabMap[tab].click();
  }

  /** Click save on the edit page and wait for the PUT response */
  async save(): Promise<void> {
    const savePromise = this.page
      .waitForResponse(
        (res) =>
          res.url().includes('/api/meta/named-queries') &&
          res.request().method().toLowerCase() === 'put',
        { timeout: 10000 },
      )
      .catch(() => null);
    await this.saveButton.click();
    await savePromise;
  }

  /** Save policy and wait for response */
  async savePolicy(): Promise<void> {
    const savePromise = this.page
      .waitForResponse(
        (res) =>
          res.url().includes('/api/meta/named-queries') &&
          res.url().includes('/policy') &&
          res.request().method().toLowerCase() === 'put',
        { timeout: 10000 },
      )
      .catch(() => null);
    await this.savePolicyButton.click();
    await savePromise;
  }

  // =====================================================================
  // Status helpers (API)
  // =====================================================================

  /** Update status via API (JSON body) */
  async updateStatusViaApi(pid: string, status: string): Promise<APIResponse> {
    return this.page.request.put(`/api/meta/named-queries/${pid}/status`, {
      data: { status },
    });
  }

  /** Get status badge text from list page row */
  getRowStatusBadge(rowLocator: Locator): Locator {
    return rowLocator.locator('td').nth(5);
  }
}
