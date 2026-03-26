/**
 * DynamicFormPage - generic page object for schema-driven form pages.
 * Works with any model by accepting the page path.
 *
 * Uses data-testid selectors for stability (Phase 3 upgrade).
 *
 * @since 4.0.0
 */

import { type Page, type Locator, expect } from '@playwright/test';
import { BasePage } from './BasePage';

export class DynamicFormPage extends BasePage {
  private path: string;

  constructor(page: Page, path: string) {
    super(page);
    this.path = path;
  }

  async goto(): Promise<void> {
    await this.page.goto(this.path);
    await this.waitForLoad();
  }

  // --- Form fields ---

  /** Get a form field container by field name (data-testid) */
  fieldContainer(name: string): Locator {
    return this.page.locator(`[data-testid="form-field-${name}"]`);
  }

  /** Get a form field input by name attribute */
  field(name: string): Locator {
    return this.page.locator(
      `[data-testid="form-field-${name}"] input, [data-testid="form-field-${name}"] select, [data-testid="form-field-${name}"] [role="combobox"], [data-testid="form-field-${name}"] textarea, [name="${name}"], [data-field="${name}"] input, [data-field="${name}"] select, [data-field="${name}"] [role="combobox"], [data-field="${name}"] textarea`
    );
  }

  /** Get a form label */
  label(text: string): Locator {
    return this.page.locator(`label:has-text("${text}")`);
  }

  /** Fill a text input field */
  async fillField(name: string, value: string): Promise<void> {
    const input = this.field(name);
    await input.fill(value);
  }

  /** Select an option in a select field (supports native select and Radix combobox).
   *  Accepts either the raw enum value (e.g. "bulk") or the displayed label (e.g. "批量").
   *  Matches by data-value attribute first (locale-independent), then falls back to text match.
   */
  async selectField(name: string, value: string): Promise<void> {
    const container = this.fieldContainer(name);
    const nativeSelect = container.locator('select');
    if (await nativeSelect.count() > 0) {
      await nativeSelect.selectOption(value);
    } else {
      const trigger = container.locator('[role="combobox"]');
      await trigger.click();
      // Try matching by data-value attribute first (raw enum value, locale-independent)
      const byValue = this.page.locator(`[role="option"][data-value="${value}"]`);
      const byText = this.page.locator(`[role="option"]:has-text("${value}")`);
      const option = byValue.or(byText).first();
      await option.click();
    }
  }

  /** Toggle a checkbox/switch field */
  async toggleField(name: string): Promise<void> {
    const input = this.page.locator(
      `[data-testid="form-field-${name}"] input[type="checkbox"], [data-testid="form-field-${name}"] button[role="switch"], [name="${name}"][type="checkbox"], [data-field="${name}"] input[type="checkbox"], [data-field="${name}"] button[role="switch"]`
    );
    await input.click();
  }

  /** Set a date field */
  async setDate(name: string, date: string): Promise<void> {
    const input = this.field(name);
    await input.fill(date);
  }

  /** Fill a textarea field */
  async fillTextarea(name: string, value: string): Promise<void> {
    const textarea = this.page.locator(
      `[data-testid="form-field-${name}"] textarea, textarea[name="${name}"], [data-field="${name}"] textarea`
    );
    await textarea.fill(value);
  }

  /** Fill all form fields with provided data */
  async fillForm(data: Record<string, any>): Promise<void> {
    for (const [field, value] of Object.entries(data)) {
      const input = this.field(field);
      if (!(await input.isVisible())) continue;

      const tagName = await input.evaluate(el => el.tagName.toLowerCase());
      const inputType = await input.getAttribute('type');
      const role = await input.getAttribute('role');

      if (role === 'combobox') {
        // Radix Select — click trigger then pick option (match by data-value or text)
        await input.click();
        const strVal = String(value);
        const byValue = this.page.locator(`[role="option"][data-value="${strVal}"]`);
        const byText = this.page.locator(`[role="option"]:has-text("${strVal}")`);
        await byValue.or(byText).first().click();
      } else if (tagName === 'select') {
        await input.selectOption(String(value));
      } else if (tagName === 'textarea') {
        await input.fill(String(value));
      } else if (inputType === 'checkbox') {
        if (value) await input.check();
        else await input.uncheck();
      } else if (inputType === 'date' || inputType === 'datetime-local') {
        await input.fill(String(value));
      } else if (inputType === 'number') {
        await input.fill(String(value));
      } else {
        await input.fill(String(value));
      }
    }
  }

  // --- Validation ---

  /** Get all validation error messages */
  get validationErrors(): Locator {
    return this.page.locator('.field-error, [data-testid="field-error"], .text-red-500');
  }

  /** Get validation error for a specific field */
  fieldError(name: string): Locator {
    return this.page.locator(`[data-testid="form-field-${name}"] .field-error, [data-testid="form-field-${name}"] .text-red-500, [data-field="${name}"] .field-error, [data-field="${name}"] .text-red-500`);
  }

  /** Assert a field has a validation error */
  async expectFieldError(name: string, message?: string): Promise<void> {
    const error = this.fieldError(name);
    await expect(error).toBeVisible();
    if (message) {
      await expect(error).toContainText(message);
    }
  }

  /** Assert no validation errors */
  async expectNoErrors(): Promise<void> {
    await expect(this.validationErrors).toHaveCount(0);
  }

  // --- Actions ---

  /** Get the submit/save button (primary form button) */
  get submitButton(): Locator {
    return this.page.locator('[data-testid^="form-btn-"]').first();
  }

  /** Get a form button by code */
  formButton(code: string): Locator {
    return this.page.locator(`[data-testid="form-btn-${code}"]`);
  }

  /** Get the cancel button */
  get cancelButton(): Locator {
    return this.page.locator(
      '[data-testid="form-btn-cancel"], button:has-text("取消"), button:has-text("Cancel")'
    );
  }

  /** Submit the form */
  async submit(): Promise<void> {
    await this.submitButton.click();
  }

  /** Cancel the form */
  async cancel(): Promise<void> {
    await this.cancelButton.click();
  }

  /** Submit and wait for navigation */
  async submitAndWait(): Promise<void> {
    await this.submitButton.click();
    await this.waitForLoad();
  }

  /** Go back to list page */
  async goBack(): Promise<void> {
    await this.page.locator('[data-testid="form-back-link"]').click();
  }

  // --- SubTable ---

  /** Click the add row button in subtable */
  async addSubTableRow(): Promise<void> {
    await this.page.locator('[data-testid="subtable-add-row"]').click();
  }

  /** Remove a subtable row by index */
  async removeSubTableRow(index: number): Promise<void> {
    await this.page.locator(`[data-testid="subtable-remove-${index}"]`).click();
  }

  /** Get a subtable row by index */
  subTableRow(index: number): Locator {
    return this.page.locator(`[data-testid="subtable-row-${index}"]`);
  }

  // --- Form state ---

  /** Check if form is in edit mode (has pre-filled values) */
  async isEditMode(): Promise<boolean> {
    const firstField = this.page.locator('[data-testid^="form-field-"] input').first();
    if (!(await firstField.isVisible())) return false;
    const value = await firstField.inputValue();
    return value.length > 0;
  }

  /** Get all current form values */
  async getFormValues(): Promise<Record<string, string>> {
    const values: Record<string, string> = {};
    const inputs = this.page.locator('[name], [data-field] input, [data-field] select, [data-field] textarea');
    const count = await inputs.count();

    for (let i = 0; i < count; i++) {
      const input = inputs.nth(i);
      const name = await input.getAttribute('name') ?? await input.getAttribute('data-field') ?? `field_${i}`;
      values[name] = await input.inputValue();
    }

    return values;
  }
}
