import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { waitForFormReady } from '../helpers';
import {
  cleanupRows,
  createCorrectedBomWorkbook,
  executeCommand,
  openQuoteCreateFormFromList,
  openQuoteDetailFromList,
  seedQuoteForCorrectedBomUpload,
  type CreatedRows,
} from './quote-e2e-helpers';

// Slice 1a/1b + Slice 6 targeted regression for the two frontend fixes landed alongside this
// golden-spec cleanup:
//   - validationMessages.ts: buildRequiredFieldMessage() now resolves LocalizedText/`$i18n:`
//     labels via getLocalizedText() instead of `String(label)`, which used to render
//     "请上传[object Object]" for any required field whose label is a { "zh-CN": ..., "en": ... }
//     object (every DSL-authored field label is this shape).
//   - FormPageContent.tsx: a local `submitting` flag now disables the save/submit button for the
//     duration of the in-flight request (previously only `loading` from useActionHandler did,
//     which this form's save path never touches), guarding against duplicate submits.
//   - ToolbarBlockRenderer.tsx / WorkbenchActionBarBlockRenderer.tsx: render
//     <LoadingOverlay data-testid="loading-overlay"> while a toolbar/workbench command is
//     in flight.

async function selectCustomer(page: Page, accountId: string, accountName: string): Promise<void> {
  const trigger = page.getByTestId('select-trigger-qo_quote_crm_account_id');
  await expect(trigger).toBeVisible({ timeout: 15_000 });
  await trigger.click();

  const option = page.locator(`[role="option"][data-value="${accountId}"]`).first();
  await expect(option, `customer option ${accountId} should be loaded`).toBeVisible({
    timeout: 15_000,
  });
  await option.click();
  await expect(trigger).toContainText(accountName, { timeout: 5_000 });
}

async function selectProject(page: Page, projectId: string, projectName: string): Promise<void> {
  const trigger = page.getByTestId('select-trigger-qo_quote_project_id');
  await expect(trigger).toBeVisible({ timeout: 15_000 });
  await trigger.click();

  const option = page.locator(`[role="option"][data-value="${projectId}"]`).first();
  await expect(option, `project option ${projectId} should be loaded`).toBeVisible({
    timeout: 15_000,
  });
  await option.click();
  await expect(trigger).toContainText(projectName, { timeout: 5_000 });
}

async function uploadSmartUploadFile(
  page: Page,
  fieldTestId: string,
  filePath: string,
  filename: string,
): Promise<void> {
  const field = page.getByTestId(fieldTestId);
  await expect(field).toBeVisible({ timeout: 15_000 });
  const uploadResponsePromise = page.waitForResponse(
    (response) => response.url().includes('/api/file/upload') && response.request().method() === 'POST',
    { timeout: 30_000 },
  );
  const input = field.locator('input[type="file"]').first();
  if ((await input.count()) > 0) {
    await input.setInputFiles(filePath);
  } else {
    const fileChooserPromise = page.waitForEvent('filechooser', { timeout: 10_000 });
    await field.locator('button, [role="button"]').first().click();
    const fileChooser = await fileChooserPromise;
    await fileChooser.setFiles(filePath);
  }
  const uploadResponse = await uploadResponsePromise;
  expect(uploadResponse.ok(), `file upload HTTP ${uploadResponse.status()}`).toBe(true);
  await expect(field).toContainText(filename, { timeout: 10_000 });
}

test.describe('QuoteOps form submit + loading overlay golden', () => {
  test.describe.configure({ timeout: 120_000 });

  test('empty required BOM upload surfaces a readable error, not [object Object]', async ({
    page,
  }) => {
    await openQuoteCreateFormFromList(page);
    await waitForFormReady(page, 20_000);

    await page.getByTestId('form-btn-save').click();

    const bomField = page.getByTestId('form-field-corrected_bom_file');
    const errorLocator = bomField
      .locator('p, [role="alert"], .text-red-600, .text-status-red')
      .filter({ hasText: /BOM资料|请上传|required/i })
      .first();
    await expect(errorLocator).toBeVisible({ timeout: 10_000 });

    const errorText = (await errorLocator.innerText()).trim();
    expect(errorText, `required-field error text: ${errorText}`).not.toContain('[object Object]');
    expect(errorText, `required-field error text: ${errorText}`).toMatch(/BOM资料|请上传/);

    // Empty submit must stay on the create form, not silently navigate away.
    await expect(page).toHaveURL(/\/p\/qo_quote_common\/new/);
  });

  test('save button disables while the create command is in flight', async ({ page }, testInfo) => {
    const suffix = `${Date.now()}${Math.random().toString(16).slice(2, 8)}`;
    const accountName = `E2E FormDisable Customer ${suffix}`;
    const projectName = `E2E FormDisable Project ${suffix}`;
    const workbookPath = createCorrectedBomWorkbook(
      testInfo.outputPath('form-submit-disable-bom.xlsx'),
    );
    const created: CreatedRows = { quoteId: '', quoteCode: '', rows: [] };

    try {
      const accountResult = await executeCommand(
        page,
        'crm:create_account',
        {
          crm_acc_name: accountName,
          crm_acc_industry: 'electronics',
          crm_acc_rating: 'A',
        },
        undefined,
        'create',
      );
      const accountId = String(
        accountResult.recordId ?? accountResult.pid ?? accountResult.id ?? '',
      );
      expect(accountId, 'crm:create_account should return recordId').toBeTruthy();
      created.rows.push({ model: 'crm_account', pid: accountId });

      const projectResult = await executeCommand(
        page,
        'bom:create_project',
        {
          bom_project_name: projectName,
          bom_project_customer_id: accountId,
          bom_project_quality_level: 'industrial',
          bom_pcba_code: `PCBA-${suffix}`,
          bom_project_remark: 'Created by form-submit-disable E2E',
        },
        undefined,
        'create',
      );
      const projectId = String(
        projectResult.recordId ?? projectResult.pid ?? projectResult.projectId ?? '',
      );
      expect(projectId, 'bom:create_project should return recordId').toBeTruthy();
      created.rows.push({ model: 'req_requirement_set_pcba_bom', pid: projectId });

      await openQuoteCreateFormFromList(page);
      await waitForFormReady(page, 20_000);
      await selectCustomer(page, accountId, accountName);
      await selectProject(page, projectId, projectName);
      await uploadSmartUploadFile(
        page,
        'form-field-corrected_bom_file',
        workbookPath,
        'form-submit-disable-bom.xlsx',
      );

      // Force the real create-command round trip to take long enough that the
      // disabled state is observed deterministically instead of racing a fast
      // local round trip (the client-side delay runs before the request is even
      // sent, so the backend still executes for real once route.continue() fires).
      await page.route(
        '**/api/meta/commands/execute/qo_quote_common:create',
        async (route) => {
          await new Promise((resolve) => setTimeout(resolve, 1_500));
          await route.continue();
        },
      );

      const createResponsePromise = page.waitForResponse(
        (response) =>
          response.url().includes('/api/meta/commands/execute/qo_quote_common:create') &&
          response.request().method() === 'POST',
        { timeout: 30_000 },
      );
      const saveButton = page.getByTestId('form-btn-save');
      await saveButton.click();

      // Assert disabled state *while the delayed request is still in flight*.
      await expect(saveButton).toBeDisabled({ timeout: 2_000 });

      const createResponse = await createResponsePromise;
      const createBody = await createResponse.json().catch(() => ({}));
      expect(
        String((createBody as any).code),
        `qo_quote_common:create response: ${JSON.stringify(createBody).slice(0, 800)}`,
      ).toBe('0');
      const quoteData = ((createBody as any).data?.data ?? {}) as Record<string, unknown>;
      const quoteId = String(quoteData.recordId ?? quoteData.quoteId ?? quoteData.pid ?? '');
      expect(quoteId, 'quote create should return quote id').toBeTruthy();
      created.quoteId = quoteId;
      created.rows.push({ model: 'qo_quote_common', pid: quoteId });
    } finally {
      await cleanupRows(page, created);
    }
  });

  test('loading overlay appears while a slow toolbar command is in flight', async ({ page }) => {
    const created = await seedQuoteForCorrectedBomUpload(page);

    try {
      await openQuoteDetailFromList(page, created);
      await page.getByRole('tab', { name: /加工点数|Process/i }).click();
      await expect(page.getByTestId('toolbar-btn-recompute_process_fee')).toBeVisible({
        timeout: 20_000,
      });

      // Delay the outbound request so the overlay is guaranteed to be visible long
      // enough to assert on (see the create-command route above for why this is
      // deterministic rather than racing a fast local round trip).
      await page.route(
        '**/api/meta/commands/execute/qo_quote_common:compute_process_fee',
        async (route) => {
          await new Promise((resolve) => setTimeout(resolve, 1_500));
          await route.continue();
        },
      );

      const commandResponsePromise = page.waitForResponse(
        (response) =>
          response.url().includes('/api/meta/commands/execute/qo_quote_common:compute_process_fee') &&
          response.request().method() === 'POST',
        { timeout: 30_000 },
      );
      await page.getByTestId('toolbar-btn-recompute_process_fee').click();

      await expect(page.getByTestId('loading-overlay')).toBeVisible({ timeout: 5_000 });

      const commandResponse = await commandResponsePromise;
      expect(
        commandResponse.ok(),
        `compute_process_fee HTTP ${commandResponse.status()}`,
      ).toBe(true);

      await expect(page.getByTestId('loading-overlay')).toBeHidden({ timeout: 20_000 });
    } finally {
      await cleanupRows(page, created);
    }
  });
});
