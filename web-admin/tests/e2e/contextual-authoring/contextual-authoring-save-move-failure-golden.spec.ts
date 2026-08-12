import { test, expect, type Page } from '@playwright/test';
import { resolve } from 'node:path';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { loginViaUI } from '../../helpers/wd-fixtures';

const RUNTIME_ROUTE = '/production-exception-list-v4';
const SCREENSHOT_DIR = resolve(
  process.env.CONTEXTUAL_AUTHORING_SCREENSHOT_DIR ?? 'test-results/contextual-authoring',
);

type ApiEnvelope<T> = {
  code?: number | string;
  data?: T;
  message?: string;
};

type ReadableHttpResponse = {
  ok(): boolean;
  status(): number;
  text(): Promise<string>;
};

type AuthoringSession = {
  sessionPid: string;
  changeSetPid: string;
  pagePid: string;
  revision: number;
  snapshot: Record<string, unknown>;
};

type PatchResult = {
  session: AuthoringSession;
};

type ChangeItem = {
  blockId: string;
  propertyPath: string;
  operation: string;
};

type CapabilityRegistry = {
  manifests: Array<{
    blockType: string;
    checksum: string;
  }>;
};

test.use({ storageState: { cookies: [], origins: [] } });

test.describe('Contextual authoring PC save, move and failure golden', () => {
  test.describe.configure({ mode: 'serial', timeout: 120_000 });

  test.beforeEach(async ({ page }) => {
    await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
    await expect(page.locator('nav')).toBeVisible({ timeout: 15_000 });
    await page.setViewportSize({ width: 1440, height: 900 });
  });

  test('PC-AUTH-008 @critical — inline save creates one isolated ChangeItem without changing runtime', async ({
    page,
  }) => {
    const session = await enterAuthoringFromRuntime(page);
    const runtimeBefore = await readRuntimePage(page, session.pagePid);
    const itemsBefore = await loadChangeItems(page, session.sessionPid);
    const { blockId } = await stageTitleEdit(page, session);

    await expect(page.getByText('1 项未保存')).toBeVisible();
    const patchResponse = page.waitForResponse((response) =>
      response.url().includes('/authoring/'),
    );
    await page.getByRole('button', { name: '保存', exact: true }).click();
    const rawPatchResponse = await patchResponse;
    expect({
      method: rawPatchResponse.request().method(),
      path: apiPath(rawPatchResponse.url()),
    }).toEqual({
      method: 'PATCH',
      path: `/api/authoring/sessions/${session.sessionPid}/patches`,
    });
    const saved = await expectApiData<PatchResult>(rawPatchResponse, 'save inline edit');

    expect(saved.session.revision).toBe(session.revision + 1);
    await expect(page.getByText('0 项未保存')).toBeVisible();
    await expect(page.getByText('1 项草稿变更')).toBeVisible();

    const itemsAfter = await loadChangeItems(page, session.sessionPid);
    expect(itemsAfter).toHaveLength(itemsBefore.length + 1);
    expect(itemsAfter.at(-1)).toMatchObject({
      blockId,
      propertyPath: '/title',
      operation: expect.stringMatching(/ADD|REPLACE/),
    });
    expect(await readRuntimePage(page, session.pagePid)).toEqual(runtimeBefore);
  });

  test('PC-AUTH-009 @critical — transport failure keeps the exact dirty edit and retries from the latest revision', async ({
    page,
  }) => {
    const session = await enterAuthoringFromRuntime(page);
    const itemsBefore = await loadChangeItems(page, session.sessionPid);
    const { editor, value } = await stageDensityEdit(page, session);
    const patchUrl = `**/api/authoring/sessions/${session.sessionPid}/patches`;
    await page.route(patchUrl, (route) => route.abort('failed'));

    const latestRevisionProbe = page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' &&
        apiPath(response.url()) === `/api/authoring/sessions/${session.sessionPid}`,
    );
    await page.getByRole('button', { name: '保存', exact: true }).click();

    const latestAfterFailure = await expectApiData<AuthoringSession>(
      await latestRevisionProbe,
      'refresh latest revision after transport failure',
    );
    expect(latestAfterFailure.revision).toBe(session.revision);
    await expect(page.getByRole('alert')).toContainText('本地未保存变更已保留');
    await expect(page.getByText('1 项未保存')).toBeVisible();
    await expect(editor).toHaveValue(value);
    await expect(page.getByRole('button', { name: '保存', exact: true })).toBeEnabled();
    expect((await reloadSession(page, session.sessionPid)).revision).toBe(session.revision);
    expect(await loadChangeItems(page, session.sessionPid)).toEqual(itemsBefore);

    await page.unroute(patchUrl);
    const retryResponse = page.waitForResponse(
      (response) =>
        response.request().method().toUpperCase() === 'PATCH' &&
        apiPath(response.url()).endsWith('/patches'),
    );
    await page.getByRole('button', { name: '保存', exact: true }).click();
    const retried = await expectApiData<PatchResult>(await retryResponse, 'retry inline edit');

    expect(retried.session.revision).toBe(session.revision + 1);
    await expect(page.getByText('0 项未保存')).toBeVisible();
    await expect(page.getByRole('alert')).toHaveCount(0);
    expect(await loadChangeItems(page, session.sessionPid)).toHaveLength(itemsBefore.length + 1);
  });

  test('PC-AUTH-017 @critical — a lost inline-save response deduplicates the committed edit and continues the remainder', async ({
    page,
  }) => {
    const session = await enterAuthoringFromRuntime(page);
    const runtimeBefore = await readRuntimePage(page, session.pagePid);
    const itemsBefore = await loadChangeItems(page, session.sessionPid);
    const { blockId, value: titleValue } = await stageTitleEdit(page, session);
    const table = findBlock(session.snapshot, (candidate) => String(candidate.id) === blockId);
    expect(table, 'table block for interrupted two-property save').not.toBeNull();
    const densityValue =
      readObjectPath(table!, '/props/density') === 'compact' ? 'comfortable' : 'compact';
    const densityEditor = page.getByTestId('authoring-property-/props/density').locator('input');
    await densityEditor.fill(densityValue);
    await expect(page.getByText('2 项未保存')).toBeVisible();

    const patchPath = `/api/authoring/sessions/${session.sessionPid}/patches`;
    let patchRequests = 0;
    await page.route(`**${patchPath}`, async (route) => {
      patchRequests += 1;
      if (patchRequests === 1) {
        const committedResponse = await route.fetch();
        expect(committedResponse.ok(), 'first inline patch committed before response loss').toBe(
          true,
        );
        await route.abort('failed');
        return;
      }
      await route.continue();
    });

    const remainingPatchResponse = page.waitForResponse(
      (response) =>
        response.request().method().toUpperCase() === 'PATCH' &&
        apiPath(response.url()) === patchPath,
    );
    await page.getByRole('button', { name: '保存', exact: true }).click();
    const saved = await expectApiData<PatchResult>(
      await remainingPatchResponse,
      'save remaining inline edit after authoritative reconciliation',
    );
    await page.unroute(`**${patchPath}`);

    expect(patchRequests).toBe(2);
    expect(saved.session.revision).toBe(session.revision + 2);
    await expect(page.getByText('0 项未保存')).toBeVisible();
    await expect(page.getByTestId('authoring-save-reconciliation-feedback')).toHaveAttribute(
      'data-tone',
      'success',
    );
    await expect(page.getByTestId('authoring-save-reconciliation-feedback')).toContainText(
      '未重复写入',
    );
    await expect(page.getByRole('alert')).toHaveCount(0);
    await expect(page.getByTestId('authoring-property-/title').locator('input')).toHaveValue(
      titleValue,
    );
    await expect(densityEditor).toHaveValue(densityValue);

    const authoritative = await reloadSession(page, session.sessionPid);
    expect(authoritative.revision).toBe(session.revision + 2);
    const authoritativeTable = findBlock(
      authoritative.snapshot,
      (candidate) => String(candidate.id) === blockId,
    );
    expect(readObjectPath(authoritativeTable!, '/title')).toBe(titleValue);
    expect(readObjectPath(authoritativeTable!, '/props/density')).toBe(densityValue);
    const itemsAfter = await loadChangeItems(page, session.sessionPid);
    expect(itemsAfter).toHaveLength(itemsBefore.length + 2);
    expect(itemsAfter.slice(-2).map((item) => item.propertyPath)).toEqual([
      '/title',
      '/props/density',
    ]);
    expect(await readRuntimePage(page, session.pagePid)).toEqual(runtimeBefore);
    await page.screenshot({
      path: resolve(SCREENSHOT_DIR, 'pc-auth-017-inline-save-reconciled.png'),
      fullPage: true,
    });
  });

  test('PC-AUTH-019 @critical — a failed authoritative reload keeps unknown-outcome dirty and later reconciles without replay', async ({
    page,
  }) => {
    const session = await enterAuthoringFromRuntime(page);
    const itemsBefore = await loadChangeItems(page, session.sessionPid);
    const { editor, value } = await stageDensityEdit(page, session);
    const patchPath = `/api/authoring/sessions/${session.sessionPid}/patches`;
    const sessionPath = `/api/authoring/sessions/${session.sessionPid}`;
    let patchRequests = 0;
    let failedAuthoritativeReads = 0;
    await page.route(`**${patchPath}`, async (route) => {
      patchRequests += 1;
      const committedResponse = await route.fetch();
      expect(committedResponse.ok(), 'inline patch committed before both responses were lost').toBe(
        true,
      );
      await route.abort('failed');
    });
    await page.route(`**${sessionPath}`, async (route) => {
      if (route.request().method() === 'GET' && failedAuthoritativeReads === 0) {
        failedAuthoritativeReads += 1;
        await route.abort('failed');
        return;
      }
      await route.continue();
    });

    await page.getByRole('button', { name: '保存', exact: true }).click();

    await expect(page.getByRole('alert')).toContainText(
      '保存结果暂时无法确认；无法读取权威草稿，请联网后重试',
    );
    await expect(page.getByText('1 项未保存')).toBeVisible();
    await expect(editor).toHaveValue(value);
    await expect(page.getByRole('button', { name: '保存', exact: true })).toBeEnabled();
    expect(patchRequests).toBe(1);
    expect(failedAuthoritativeReads).toBe(1);
    const committed = await reloadSession(page, session.sessionPid);
    expect(committed.revision).toBe(session.revision + 1);
    expect(await loadChangeItems(page, session.sessionPid)).toHaveLength(itemsBefore.length + 1);
    await page.screenshot({
      path: resolve(SCREENSHOT_DIR, 'pc-auth-019-authoritative-read-failed.png'),
      fullPage: true,
    });

    await page.unroute(`**${patchPath}`);
    await page.unroute(`**${sessionPath}`);
    const staleRetry = page.waitForResponse(
      (response) =>
        response.request().method().toUpperCase() === 'PATCH' &&
        apiPath(response.url()) === patchPath,
    );
    await page.getByRole('button', { name: '保存', exact: true }).click();
    expect((await staleRetry).status()).toBe(409);

    await expect(page.getByText('0 项未保存')).toBeVisible();
    await expect(page.getByRole('alert')).toHaveCount(0);
    await expect(page.getByTestId('authoring-save-reconciliation-feedback')).toContainText(
      '未重复写入',
    );
    const reconciled = await reloadSession(page, session.sessionPid);
    expect(reconciled.revision).toBe(session.revision + 1);
    expect(await loadChangeItems(page, session.sessionPid)).toHaveLength(itemsBefore.length + 1);
    await page.screenshot({
      path: resolve(SCREENSHOT_DIR, 'pc-auth-019-authoritative-read-reconciled.png'),
      fullPage: true,
    });
  });

  test('PC-AUTH-024 @critical — repeated authoritative GET failures survive an inline page-process restart and reconcile without replay', async ({
    page,
  }) => {
    const session = await enterAuthoringFromRuntime(page);
    const itemsBefore = await loadChangeItems(page, session.sessionPid);
    const { value } = await stageDensityEdit(page, session);
    const patchPath = `/api/authoring/sessions/${session.sessionPid}/patches`;
    const sessionPath = `/api/authoring/sessions/${session.sessionPid}`;
    let patchRequests = 0;
    let failedAuthoritativeReads = 0;
    await page.route(`**${patchPath}`, async (route) => {
      patchRequests += 1;
      const committedResponse = await route.fetch();
      expect(committedResponse.ok(), 'inline patch committed before process restart').toBe(true);
      await route.abort('failed');
    });
    await page.route(`**${sessionPath}`, async (route) => {
      if (route.request().method() === 'GET') {
        failedAuthoritativeReads += 1;
        await route.abort('failed');
        return;
      }
      await route.continue();
    });

    await page.getByRole('button', { name: '保存', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('保存结果暂时无法确认');
    await expect(page.getByText('1 项未保存')).toBeVisible();
    expect(patchRequests).toBe(1);
    expect(failedAuthoritativeReads).toBe(1);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('authoring-local-recovery')).toContainText(
      '发现页面中断前保留的本地变更',
    );
    await expect(page.getByTestId('contextual-authoring-enter')).toHaveCount(0);
    for (let attempt = 0; attempt < 2; attempt += 1) {
      await page.getByTestId('authoring-local-recovery-resume').click();
      await expect.poll(() => failedAuthoritativeReads).toBe(attempt + 2);
      await expect(page.getByTestId('authoring-local-recovery')).toBeVisible();
      await expect(page.getByTestId('authoring-local-recovery-resume')).toBeEnabled();
    }
    await expect(page.getByText(/本地恢复数据仍保留/)).toBeVisible();
    await page.screenshot({
      path: resolve(SCREENSHOT_DIR, 'pc-auth-024-inline-restart-recovery-pending.png'),
      fullPage: true,
    });

    await page.unroute(`**${sessionPath}`);
    await page.getByTestId('authoring-local-recovery-resume').click();

    await expect(page.getByTestId('contextual-authoring-surface')).toBeVisible();
    await expect(page.getByText('0 项未保存')).toBeVisible();
    await expect(page.getByTestId('authoring-save-reconciliation-feedback')).toContainText(
      '已由权威草稿确认保存成功，未重复写入',
    );
    expect(patchRequests).toBe(1);
    const authoritative = await reloadSession(page, session.sessionPid);
    expect(authoritative.revision).toBe(session.revision + 1);
    const table = findBlock(
      authoritative.snapshot,
      (candidate) => readObjectPath(candidate, '/props/density') === value,
    );
    expect(table, 'recovered inline Mine in authoritative draft').not.toBeNull();
    expect(await loadChangeItems(page, session.sessionPid)).toHaveLength(itemsBefore.length + 1);
    await page.unroute(`**${patchPath}`);
    await page.screenshot({
      path: resolve(SCREENSHOT_DIR, 'pc-auth-024-inline-restart-reconciled.png'),
      fullPage: true,
    });
  });

  test('PC-AUTH-020 @critical — authority transfer during an interrupted save confirms no write and turns the dirty owner read-only', async ({
    page,
  }) => {
    const session = await enterAuthoringFromRuntime(page);
    const itemsBefore = await loadChangeItems(page, session.sessionPid);
    const { editor, value } = await stageDensityEdit(page, session);
    const secondTab = await page.context().newPage();
    const patchPath = `/api/authoring/sessions/${session.sessionPid}/patches`;
    let patchRequests = 0;
    try {
      const observerResponse = secondTab.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) === `/api/authoring/change-sets/${session.changeSetPid}/sessions`,
      );
      await secondTab.goto(
        `/unified-designer?changeSetId=${encodeURIComponent(session.changeSetPid)}`,
        { waitUntil: 'domcontentloaded' },
      );
      const observer = await expectApiData<AuthoringSession>(
        await observerResponse,
        'open same-account observer before interrupted save',
      );
      await expect(secondTab.getByTestId('authoring-writer-lease-notice')).toBeVisible();
      await secondTab
        .getByPlaceholder('填写接管原因（必填，将写入审计）')
        .fill('PC 门禁：保存中断窗口接管');

      await page.route(`**${patchPath}`, async (route) => {
        patchRequests += 1;
        const takeoverResponse = secondTab.waitForResponse(
          (response) =>
            response.request().method() === 'POST' &&
            apiPath(response.url()) ===
              `/api/authoring/sessions/${observer.sessionPid}/writer-lease/takeover`,
        );
        await secondTab.getByTestId('authoring-writer-lease-takeover').click();
        expect((await takeoverResponse).status()).toBe(200);
        await route.abort('failed');
      });

      await page.getByRole('button', { name: '保存', exact: true }).click();

      await expect(page.getByTestId('authoring-save-reconciliation-feedback')).toHaveAttribute(
        'data-tone',
        'warning',
      );
      await expect(page.getByTestId('authoring-save-reconciliation-feedback')).toContainText(
        '保存未完成；ChangeSet 已进入 READ_ONLY 状态',
      );
      await expect(page.getByTestId('contextual-authoring-surface')).toHaveAttribute(
        'data-read-only',
        'true',
      );
      await expect(page.getByText('1 项未保存')).toBeVisible();
      await expect(editor).toHaveValue(value);
      await expect(page.getByRole('button', { name: '保存', exact: true })).toBeDisabled();
      await expect(page.getByRole('alert')).toHaveCount(0);
      expect(patchRequests).toBe(1);
      const formerOwner = await reloadSession(page, session.sessionPid);
      expect(formerOwner.revision).toBe(session.revision);
      expect(await loadChangeItems(page, session.sessionPid)).toEqual(itemsBefore);
      await page.screenshot({
        path: resolve(SCREENSHOT_DIR, 'pc-auth-020-authority-moved-dirty-readonly.png'),
        fullPage: true,
      });
    } finally {
      await page.unroute(`**${patchPath}`).catch(() => {});
      await secondTab.close();
    }
  });

  test('PC-AUTH-010 @critical — Studio moves one stable block through the atomic batch', async ({
    page,
  }) => {
    const session = await enterAuthoringFromRuntime(page);
    const itemsBefore = await loadChangeItems(page, session.sessionPid);

    await page.getByTestId('authoring-inspector-open').click();
    await page.getByRole('button', { name: '高级设置' }).click();
    await page
      .getByRole('dialog', { name: '进入应用设计中心' })
      .getByRole('button', { name: '继续到应用设计中心' })
      .click();
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible();

    const list = findBlock(session.snapshot, (candidate) => blockType(candidate) === 'list');
    expect(list?.id, 'list root id for creating a movable sibling').toBeTruthy();
    await page.getByTestId(`outline-item-${String(list!.id)}`).click();
    await page.getByTestId('resource-tab-blocks').click();
    const availableBlock = page
      .locator(
        'button[data-testid="palette-add-filter-bar"]:not([disabled]), button[data-testid="palette-add-action-bar"]:not([disabled])',
      )
      .first();
    await expect(availableBlock).toBeVisible();
    await availableBlock.click();
    await expect(page.getByTestId('designer-dirty-state')).toContainText('未保存');
    const createResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        apiPath(response.url()) === `/api/authoring/sessions/${session.sessionPid}/studio-batches`,
    );
    await page.getByTestId('designer-save').click();
    const created = await expectApiData<PatchResult>(
      await createResponse,
      'save safe sibling block shell',
    );
    expect(created.session.revision).toBeGreaterThan(session.revision);
    await expect(page.getByTestId('designer-dirty-state')).toContainText('已保存');
    const itemsAfterCreate = await loadChangeItems(page, session.sessionPid);
    expect(itemsAfterCreate.length).toBeGreaterThan(itemsBefore.length);

    await page.getByTestId('designer-mode-layout').click();
    const moveButton = page.locator('[data-testid^="block-move-up-"]:not([disabled])').first();
    await expect(moveButton).toBeVisible();
    const testId = await moveButton.getAttribute('data-testid');
    const blockId = testId!.slice('block-move-up-'.length);
    const beforeOrder = siblingOrder(created.session.snapshot, blockId);
    expect(beforeOrder.index).toBeGreaterThan(0);

    await moveButton.click();
    await expect(page.getByTestId('designer-dirty-state')).toContainText('未保存');
    const batchResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        apiPath(response.url()) === `/api/authoring/sessions/${session.sessionPid}/studio-batches`,
    );
    await page.getByTestId('designer-save').click();
    const saved = await expectApiData<PatchResult>(await batchResponse, 'save Studio move');

    await expect(page.getByTestId('designer-dirty-state')).toContainText('已保存');
    expect(saved.session.revision).toBe(created.session.revision + 1);
    const afterOrder = siblingOrder(saved.session.snapshot, blockId);
    expect(afterOrder.ids).toEqual([
      ...beforeOrder.ids.slice(0, beforeOrder.index - 1),
      blockId,
      beforeOrder.ids[beforeOrder.index - 1],
      ...beforeOrder.ids.slice(beforeOrder.index + 1),
    ]);
    expect(countStableId(saved.session.snapshot, blockId)).toBe(1);

    const itemsAfter = await loadChangeItems(page, session.sessionPid);
    expect(itemsAfter).toHaveLength(itemsAfterCreate.length + 1);
    expect(itemsAfter.at(-1)).toMatchObject({
      blockId,
      operation: 'MOVE',
    });
  });

  test('PC-AUTH-018 @critical — a lost atomic Studio response reconciles the complete document without replay', async ({
    page,
  }) => {
    const session = await enterAuthoringFromRuntime(page);
    await page.getByTestId('authoring-inspector-open').click();
    await page.getByRole('button', { name: '高级设置' }).click();
    await page
      .getByRole('dialog', { name: '进入应用设计中心' })
      .getByRole('button', { name: '继续到应用设计中心' })
      .click();
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible();

    const list = findBlock(session.snapshot, (candidate) => blockType(candidate) === 'list');
    expect(list?.id, 'list root id for Studio response-loss move').toBeTruthy();
    await page.getByTestId(`outline-item-${String(list!.id)}`).click();
    await page.getByTestId('resource-tab-blocks').click();
    const availableBlock = page
      .locator(
        'button[data-testid="palette-add-filter-bar"]:not([disabled]), button[data-testid="palette-add-action-bar"]:not([disabled])',
      )
      .first();
    await expect(availableBlock).toBeVisible();
    await availableBlock.click();
    const batchPath = `/api/authoring/sessions/${session.sessionPid}/studio-batches`;
    const createResponse = page.waitForResponse(
      (response) => response.request().method() === 'POST' && apiPath(response.url()) === batchPath,
    );
    await page.getByTestId('designer-save').click();
    const created = await expectApiData<PatchResult>(
      await createResponse,
      'create Studio response-loss fixture',
    );
    const itemsAfterCreate = await loadChangeItems(page, session.sessionPid);

    await page.getByTestId('designer-mode-layout').click();
    const moveButton = page.locator('[data-testid^="block-move-up-"]:not([disabled])').first();
    await expect(moveButton).toBeVisible();
    const testId = await moveButton.getAttribute('data-testid');
    const blockId = testId!.slice('block-move-up-'.length);
    const beforeOrder = siblingOrder(created.session.snapshot, blockId);
    await moveButton.click();
    await expect(page.getByTestId('designer-dirty-state')).toContainText('未保存');

    let batchRequests = 0;
    await page.route(`**${batchPath}`, async (route) => {
      batchRequests += 1;
      const committedResponse = await route.fetch();
      expect(committedResponse.ok(), 'Studio batch committed before response loss').toBe(true);
      await route.abort('failed');
    });
    await page.getByTestId('designer-save').click();

    await expect(page.getByTestId('designer-dirty-state')).toContainText('已保存');
    await expect(page.getByTestId('studio-save-reconciliation-feedback')).toContainText(
      '未重复写入',
    );
    await expect(page.getByTestId('designer-save-error')).toHaveCount(0);
    await expect(page.getByTestId('authoring-conflict-panel')).toHaveCount(0);
    await page.unroute(`**${batchPath}`);
    expect(batchRequests).toBe(1);

    const authoritative = await reloadSession(page, session.sessionPid);
    expect(authoritative.revision).toBe(created.session.revision + 1);
    const afterOrder = siblingOrder(authoritative.snapshot, blockId);
    expect(afterOrder.ids).toEqual([
      ...beforeOrder.ids.slice(0, beforeOrder.index - 1),
      blockId,
      beforeOrder.ids[beforeOrder.index - 1],
      ...beforeOrder.ids.slice(beforeOrder.index + 1),
    ]);
    expect(countStableId(authoritative.snapshot, blockId)).toBe(1);
    const itemsAfter = await loadChangeItems(page, session.sessionPid);
    expect(itemsAfter).toHaveLength(itemsAfterCreate.length + 1);
    expect(itemsAfter.at(-1)).toMatchObject({ blockId, operation: 'MOVE' });
    await page.screenshot({
      path: resolve(SCREENSHOT_DIR, 'pc-auth-018-studio-save-reconciled.png'),
      fullPage: true,
    });
  });

  test('PC-AUTH-021 @critical — a failed Studio authority reload keeps the atomic document dirty and later reconciles without replay', async ({
    page,
  }) => {
    const session = await enterAuthoringFromRuntime(page);
    await page.getByTestId('authoring-inspector-open').click();
    await page.getByRole('button', { name: '高级设置' }).click();
    await page
      .getByRole('dialog', { name: '进入应用设计中心' })
      .getByRole('button', { name: '继续到应用设计中心' })
      .click();
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible();

    const list = findBlock(session.snapshot, (candidate) => blockType(candidate) === 'list');
    expect(list?.id, 'list root id for Studio authority-reload failure').toBeTruthy();
    await page.getByTestId(`outline-item-${String(list!.id)}`).click();
    await page.getByTestId('resource-tab-blocks').click();
    const availableBlock = page
      .locator(
        'button[data-testid="palette-add-filter-bar"]:not([disabled]), button[data-testid="palette-add-action-bar"]:not([disabled])',
      )
      .first();
    await expect(availableBlock).toBeVisible();
    await availableBlock.click();
    const batchPath = `/api/authoring/sessions/${session.sessionPid}/studio-batches`;
    const sessionPath = `/api/authoring/sessions/${session.sessionPid}`;
    const createResponse = page.waitForResponse(
      (response) => response.request().method() === 'POST' && apiPath(response.url()) === batchPath,
    );
    await page.getByTestId('designer-save').click();
    const created = await expectApiData<PatchResult>(
      await createResponse,
      'create Studio authority-reload fixture',
    );
    const itemsAfterCreate = await loadChangeItems(page, session.sessionPid);

    await page.getByTestId('designer-mode-layout').click();
    const moveButton = page.locator('[data-testid^="block-move-up-"]:not([disabled])').first();
    await expect(moveButton).toBeVisible();
    const testId = await moveButton.getAttribute('data-testid');
    const blockId = testId!.slice('block-move-up-'.length);
    const beforeOrder = siblingOrder(created.session.snapshot, blockId);
    await moveButton.click();
    await expect(page.getByTestId('designer-dirty-state')).toContainText('未保存');

    let failNextAuthorityRead = false;
    let committedBatches = 0;
    await page.route(`**${sessionPath}`, async (route) => {
      if (
        failNextAuthorityRead &&
        route.request().method() === 'GET' &&
        apiPath(route.request().url()) === sessionPath
      ) {
        failNextAuthorityRead = false;
        await route.abort('failed');
        return;
      }
      await route.continue();
    });
    await page.route(`**${batchPath}`, async (route) => {
      const committedResponse = await route.fetch();
      expect(committedResponse.ok(), 'Studio batch committed before dual response loss').toBe(true);
      committedBatches += 1;
      failNextAuthorityRead = true;
      await route.abort('failed');
    });

    await page.getByTestId('designer-save').click();

    await expect(page.getByTestId('designer-dirty-state')).toContainText('保存失败');
    await expect(page.getByTestId('designer-save-error')).toContainText(
      '保存结果暂时无法确认；无法读取权威草稿，请联网后重试',
    );
    await expect(page.getByTestId('designer-save')).toBeEnabled();
    await expect(page.getByTestId('authoring-conflict-panel')).toHaveCount(0);
    expect(committedBatches).toBe(1);
    const committedUnknown = await reloadSession(page, session.sessionPid);
    expect(committedUnknown.revision).toBe(created.session.revision + 1);
    expect(await loadChangeItems(page, session.sessionPid)).toHaveLength(
      itemsAfterCreate.length + 1,
    );
    await page.screenshot({
      path: resolve(SCREENSHOT_DIR, 'pc-auth-021-studio-authority-read-failed.png'),
      fullPage: true,
    });

    await page.unroute(`**${batchPath}`);
    await page.unroute(`**${sessionPath}`);
    const staleRetry = page.waitForResponse(
      (response) => response.request().method() === 'POST' && apiPath(response.url()) === batchPath,
    );
    await page.getByTestId('designer-save').click();
    expect((await staleRetry).status()).toBe(409);

    await expect(page.getByTestId('designer-dirty-state')).toContainText('已保存');
    await expect(page.getByTestId('studio-save-reconciliation-feedback')).toContainText(
      '未重复写入',
    );
    await expect(page.getByTestId('designer-save-error')).toHaveCount(0);
    await expect(page.getByTestId('authoring-conflict-panel')).toHaveCount(0);
    const authoritative = await reloadSession(page, session.sessionPid);
    expect(authoritative.revision).toBe(created.session.revision + 1);
    const afterOrder = siblingOrder(authoritative.snapshot, blockId);
    expect(afterOrder.ids).toEqual([
      ...beforeOrder.ids.slice(0, beforeOrder.index - 1),
      blockId,
      beforeOrder.ids[beforeOrder.index - 1],
      ...beforeOrder.ids.slice(beforeOrder.index + 1),
    ]);
    expect(countStableId(authoritative.snapshot, blockId)).toBe(1);
    expect(await loadChangeItems(page, session.sessionPid)).toHaveLength(
      itemsAfterCreate.length + 1,
    );
    await page.screenshot({
      path: resolve(SCREENSHOT_DIR, 'pc-auth-021-studio-authority-read-reconciled.png'),
      fullPage: true,
    });
  });

  test('PC-AUTH-025 @critical — Studio Mine survives repeated authoritative GET failures and a page-process restart', async ({
    page,
  }) => {
    const session = await enterAuthoringFromRuntime(page);
    await page.getByTestId('authoring-inspector-open').click();
    await page.getByRole('button', { name: '高级设置' }).click();
    await page
      .getByRole('dialog', { name: '进入应用设计中心' })
      .getByRole('button', { name: '继续到应用设计中心' })
      .click();
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible();

    const list = findBlock(session.snapshot, (candidate) => blockType(candidate) === 'list');
    expect(list?.id, 'list root id for Studio restart recovery').toBeTruthy();
    await page.getByTestId(`outline-item-${String(list!.id)}`).click();
    await page.getByTestId('resource-tab-blocks').click();
    const availableBlock = page
      .locator(
        'button[data-testid="palette-add-filter-bar"]:not([disabled]), button[data-testid="palette-add-action-bar"]:not([disabled])',
      )
      .first();
    await expect(availableBlock).toBeVisible();
    await availableBlock.click();
    const batchPath = `/api/authoring/sessions/${session.sessionPid}/studio-batches`;
    const sessionPath = `/api/authoring/sessions/${session.sessionPid}`;
    const createResponse = page.waitForResponse(
      (response) => response.request().method() === 'POST' && apiPath(response.url()) === batchPath,
    );
    await page.getByTestId('designer-save').click();
    const created = await expectApiData<PatchResult>(
      await createResponse,
      'create Studio restart-recovery fixture',
    );
    const itemsAfterCreate = await loadChangeItems(page, session.sessionPid);

    await page.getByTestId('designer-mode-layout').click();
    const moveButton = page.locator('[data-testid^="block-move-up-"]:not([disabled])').first();
    await expect(moveButton).toBeVisible();
    const testId = await moveButton.getAttribute('data-testid');
    const blockId = testId!.slice('block-move-up-'.length);
    const beforeOrder = siblingOrder(created.session.snapshot, blockId);
    await moveButton.click();
    await expect(page.getByTestId('designer-dirty-state')).toContainText('未保存');

    let committedBatches = 0;
    let failedAuthoritativeReads = 0;
    await page.route(`**${batchPath}`, async (route) => {
      const committedResponse = await route.fetch();
      expect(committedResponse.ok(), 'Studio batch committed before process restart').toBe(true);
      committedBatches += 1;
      await route.abort('failed');
    });
    await page.route(`**${sessionPath}`, async (route) => {
      if (route.request().method() === 'GET') {
        failedAuthoritativeReads += 1;
        await route.abort('failed');
        return;
      }
      await route.continue();
    });

    await page.getByTestId('designer-save').click();
    await expect(page.getByTestId('designer-save-error')).toContainText('保存结果暂时无法确认');
    expect(committedBatches).toBe(1);
    expect(failedAuthoritativeReads).toBe(1);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByText('权威草稿暂不可读，本地 Studio 文档仍保留')).toBeVisible();
    const failedReadsAfterRestart = failedAuthoritativeReads;
    for (let attempt = 0; attempt < 2; attempt += 1) {
      const readsBeforeRetry = failedAuthoritativeReads;
      await page.getByTestId('studio-local-recovery-retry').click();
      await expect.poll(() => failedAuthoritativeReads).toBeGreaterThan(readsBeforeRetry);
      await expect(page.getByTestId('studio-local-recovery-retry')).toBeVisible();
    }
    expect(failedAuthoritativeReads).toBeGreaterThanOrEqual(failedReadsAfterRestart + 2);
    await page.screenshot({
      path: resolve(SCREENSHOT_DIR, 'pc-auth-025-studio-restart-recovery-pending.png'),
      fullPage: true,
    });

    await page.unroute(`**${sessionPath}`);
    await page.getByTestId('studio-local-recovery-retry').click();

    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible();
    await expect(page.getByTestId('designer-dirty-state')).toContainText('已保存');
    await expect(page.getByTestId('studio-save-reconciliation-feedback')).toContainText(
      '已由权威草稿确认完成',
    );
    expect(committedBatches).toBe(1);
    const authoritative = await reloadSession(page, session.sessionPid);
    expect(authoritative.revision).toBe(created.session.revision + 1);
    const afterOrder = siblingOrder(authoritative.snapshot, blockId);
    expect(afterOrder.ids).toEqual([
      ...beforeOrder.ids.slice(0, beforeOrder.index - 1),
      blockId,
      beforeOrder.ids[beforeOrder.index - 1],
      ...beforeOrder.ids.slice(beforeOrder.index + 1),
    ]);
    expect(countStableId(authoritative.snapshot, blockId)).toBe(1);
    expect(await loadChangeItems(page, session.sessionPid)).toHaveLength(
      itemsAfterCreate.length + 1,
    );
    await page.unroute(`**${batchPath}`);
    await page.screenshot({
      path: resolve(SCREENSHOT_DIR, 'pc-auth-025-studio-restart-reconciled.png'),
      fullPage: true,
    });
  });

  test('PC-AUTH-026 @critical — an inline recovery third value survives the Studio handoff and explicit resolution', async ({
    page,
  }) => {
    const session = await enterAuthoringFromRuntime(page);
    const itemsBefore = await loadChangeItems(page, session.sessionPid);
    const table = findBlock(session.snapshot, (candidate) => blockType(candidate) === 'table');
    expect(table?.id, 'table block for inline recovery third value').toBeTruthy();
    const baseTitleValue = readObjectPath(table!, '/title');
    const baseTitle = typeof baseTitleValue === 'string' ? baseTitleValue : undefined;
    const { editor, value: mineTitle } = await stageTitleEdit(page, session);
    const latestTitle = distinctText(baseTitle, mineTitle, '生产异常（恢复 Latest）');
    const latestPatch = await buildTablePatch(page, session, '/title', latestTitle);
    const latestSaved = await expectApiData<PatchResult>(
      await page.request.patch(`/api/authoring/sessions/${session.sessionPid}/patches`, {
        data: { expectedRevision: session.revision, ...latestPatch },
      }),
      'write authoritative third value before inline recovery',
    );
    expect(latestSaved.session.revision).toBe(session.revision + 1);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('authoring-local-recovery')).toBeVisible();
    await page.getByTestId('authoring-local-recovery-resume').click();

    await expect(page.getByTestId('contextual-authoring-conflict')).toContainText(
      'Base / Mine / Latest',
    );
    await expect(page.getByTestId('contextual-authoring-conflict')).toContainText(
      `Base r${session.revision} / Latest r${latestSaved.session.revision}`,
    );
    await expect(editor).toHaveValue(mineTitle);
    await expect(page.getByRole('button', { name: '保存', exact: true })).toBeDisabled();
    await page.screenshot({
      path: resolve(SCREENSHOT_DIR, 'pc-auth-026-inline-restart-third-value.png'),
      fullPage: true,
    });

    await page.getByTestId('contextual-authoring-conflict-studio').click();
    await expect(page.getByTestId('authoring-conflict-panel')).toBeVisible();
    await expect(page.getByTestId('designer-dirty-state')).toContainText('未保存');

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('authoring-conflict-panel')).toBeVisible();
    await expect(page.getByTestId('designer-dirty-state')).toContainText('未保存');
    const titleConflict = conflictCard(page, '标题');
    await expect(titleConflict.getByTestId('authoring-conflict-value-base')).toContainText(
      baseTitle ?? '未设置',
    );
    await expect(titleConflict.getByTestId('authoring-conflict-value-mine')).toContainText(
      mineTitle,
    );
    await expect(titleConflict.getByTestId('authoring-conflict-value-latest')).toContainText(
      latestTitle,
    );

    await titleConflict.getByLabel('保留 Mine').click();
    const resolutionResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        apiPath(response.url()) === `/api/authoring/sessions/${session.sessionPid}/studio-batches`,
    );
    await page.getByTestId('authoring-conflict-apply').click();
    const resolved = await expectApiData<PatchResult>(
      await resolutionResponse,
      'resolve inline restart third value in Studio',
    );
    expect(resolved.session.revision).toBe(latestSaved.session.revision + 1);
    expect(
      readObjectPath(
        findBlock(resolved.session.snapshot, (candidate) => candidate.id === table!.id)!,
        '/title',
      ),
    ).toBe(mineTitle);
    expect(await loadChangeItems(page, session.sessionPid)).toHaveLength(itemsBefore.length + 2);
    await expect(page.getByTestId('authoring-conflict-panel')).toHaveCount(0);
    await expect(page.getByTestId('designer-dirty-state')).toContainText('已保存');

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('authoring-conflict-panel')).toHaveCount(0);
    await expect(page.getByTestId('designer-dirty-state')).toContainText('已保存');
    await page.screenshot({
      path: resolve(SCREENSHOT_DIR, 'pc-auth-026-inline-restart-third-value-resolved.png'),
      fullPage: true,
    });
  });

  test('PC-AUTH-027 @critical — a native Studio recovery third value reloads into Base Mine Latest and resolves once', async ({
    page,
  }) => {
    const session = await enterAuthoringFromRuntime(page);
    const itemsBefore = await loadChangeItems(page, session.sessionPid);
    await continueToStudio(page);

    const table = findBlock(session.snapshot, (candidate) => blockType(candidate) === 'table');
    expect(table?.id, 'table block for native Studio recovery third value').toBeTruthy();
    const tableId = String(table!.id);
    const baseTitleValue = readObjectPath(table!, '/title');
    const baseTitle = typeof baseTitleValue === 'string' ? baseTitleValue : undefined;
    const mineTitle = distinctText(baseTitle, undefined, '生产异常（Studio Mine）');
    const latestTitle = distinctText(baseTitle, mineTitle, '生产异常（Studio Latest）');
    await page.getByTestId(`outline-item-${tableId}`).click();
    await page.getByTestId('inspector-field-title').fill(mineTitle);
    await expect(page.getByTestId('designer-dirty-state')).toContainText('未保存');

    const latestPatch = await buildTablePatch(page, session, '/title', latestTitle);
    const latestSaved = await expectApiData<PatchResult>(
      await page.request.patch(`/api/authoring/sessions/${session.sessionPid}/patches`, {
        data: { expectedRevision: session.revision, ...latestPatch },
      }),
      'write authoritative third value before native Studio reload',
    );
    expect(latestSaved.session.revision).toBe(session.revision + 1);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('authoring-conflict-panel')).toBeVisible();
    await expect(page.getByTestId('designer-dirty-state')).toContainText('未保存');
    const titleConflict = conflictCard(page, '标题');
    await expect(titleConflict.getByTestId('authoring-conflict-value-base')).toContainText(
      baseTitle ?? '未设置',
    );
    await expect(titleConflict.getByTestId('authoring-conflict-value-mine')).toContainText(
      mineTitle,
    );
    await expect(titleConflict.getByTestId('authoring-conflict-value-latest')).toContainText(
      latestTitle,
    );
    await page.screenshot({
      path: resolve(SCREENSHOT_DIR, 'pc-auth-027-studio-restart-third-value.png'),
      fullPage: true,
    });

    await titleConflict.getByLabel('保留 Mine').click();
    const resolutionResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        apiPath(response.url()) === `/api/authoring/sessions/${session.sessionPid}/studio-batches`,
    );
    await page.getByTestId('authoring-conflict-apply').click();
    const resolved = await expectApiData<PatchResult>(
      await resolutionResponse,
      'resolve native Studio restart third value',
    );
    expect(resolved.session.revision).toBe(latestSaved.session.revision + 1);
    expect(
      readObjectPath(
        findBlock(resolved.session.snapshot, (candidate) => candidate.id === tableId)!,
        '/title',
      ),
    ).toBe(mineTitle);
    expect(await loadChangeItems(page, session.sessionPid)).toHaveLength(itemsBefore.length + 2);
    await expect(page.getByTestId('authoring-conflict-panel')).toHaveCount(0);
    await expect(page.getByTestId('designer-dirty-state')).toContainText('已保存');

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('authoring-conflict-panel')).toHaveCount(0);
    await expect(page.getByTestId('designer-dirty-state')).toContainText('已保存');
    await page.screenshot({
      path: resolve(SCREENSHOT_DIR, 'pc-auth-027-studio-restart-third-value-resolved.png'),
      fullPage: true,
    });
  });
});

async function enterAuthoringFromRuntime(page: Page): Promise<AuthoringSession> {
  const link = page.locator('nav').locator(`a[href="${RUNTIME_ROUTE}"]`).first();
  await expect(link).toBeVisible({ timeout: 10_000 });
  await link.click();
  try {
    await page.waitForURL(new RegExp(`${RUNTIME_ROUTE}$`), { timeout: 3_000 });
  } catch {
    // Login may finish a delayed redirect after the first SPA click; navigate once after it settles.
    await page.goto(RUNTIME_ROUTE, { waitUntil: 'domcontentloaded' });
  }
  await expect(page).toHaveURL(new RegExp(`${RUNTIME_ROUTE}$`));
  await expect(page.getByRole('main').first().getByText('EXC-V4-REAL-001')).toBeVisible({
    timeout: 15_000,
  });

  const sessionResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      apiPath(response.url()) === '/api/authoring/sessions',
  );
  await page.getByRole('main').first().getByRole('button', { name: '配置此页' }).click();
  const session = await expectApiData<AuthoringSession>(
    await sessionResponse,
    'enter contextual authoring',
  );
  await expect(page.getByTestId('contextual-authoring-surface')).toBeVisible();
  return session;
}

async function continueToStudio(page: Page): Promise<void> {
  await page.getByTestId('authoring-inspector-open').click();
  await page.getByRole('button', { name: '高级设置' }).click();
  await page
    .getByRole('dialog', { name: '进入应用设计中心' })
    .getByRole('button', { name: '继续到应用设计中心' })
    .click();
  await expect(page.getByTestId('unified-designer-workbench')).toBeVisible();
}

async function buildTablePatch(
  page: Page,
  session: AuthoringSession,
  propertyPath: '/title',
  value: unknown,
) {
  const table = findBlock(session.snapshot, (candidate) => blockType(candidate) === 'table');
  expect(table?.id, 'table block for direct authoritative patch').toBeTruthy();
  const capabilities = await expectApiData<CapabilityRegistry>(
    await page.request.get('/api/authoring/capabilities'),
    'load authoring capabilities for direct patch',
  );
  const tableManifest = capabilities.manifests.find((manifest) => manifest.blockType === 'table');
  expect(tableManifest?.checksum, 'table manifest checksum for direct patch').toBeTruthy();
  return {
    blockId: String(table!.id),
    propertyPath,
    operation: readObjectPath(table!, propertyPath) === undefined ? 'ADD' : 'REPLACE',
    value,
    manifestChecksum: tableManifest!.checksum,
  };
}

async function stageDensityEdit(page: Page, session: AuthoringSession) {
  const table = findBlock(session.snapshot, (candidate) => blockType(candidate) === 'table');
  expect(table?.id, 'table block id for density edit').toBeTruthy();
  const value = readObjectPath(table!, '/props/density') === 'compact' ? 'comfortable' : 'compact';
  await openTableInspector(page, String(table!.id));
  const editor = page.getByTestId('authoring-property-/props/density').locator('input');
  await expect(editor).toBeVisible();
  await editor.fill(value);
  return { blockId: String(table!.id), editor, value };
}

async function stageTitleEdit(page: Page, session: AuthoringSession) {
  const table = findBlock(session.snapshot, (candidate) => blockType(candidate) === 'table');
  expect(table?.id, 'table block id for title edit').toBeTruthy();
  const value =
    readObjectPath(table!, '/title') === '生产异常（PC 验收）'
      ? '生产异常清单（PC 验收）'
      : '生产异常（PC 验收）';
  await openTableInspector(page, String(table!.id));
  const editor = page.getByTestId('authoring-property-/title').locator('input');
  await expect(editor).toBeVisible();
  await editor.fill(value);
  return { blockId: String(table!.id), editor, value };
}

async function openTableInspector(page: Page, tableId: string): Promise<void> {
  await page.getByTestId('authoring-outline-open').click();
  await page.getByTestId(`authoring-outline-${tableId}`).click();
  await page.getByRole('button', { name: '关闭页面大纲' }).click();
  await page.getByTestId('authoring-inspector-open').click();
}

function conflictCard(page: Page, label: string) {
  return page.locator('article[data-testid^="authoring-conflict-"]').filter({ hasText: label });
}

function distinctText(base: string | undefined, excluded: string | undefined, preferred: string) {
  if (preferred !== base && preferred !== excluded) return preferred;
  const alternate = `${preferred} 2`;
  if (alternate !== base && alternate !== excluded) return alternate;
  return `${preferred} 3`;
}

async function loadChangeItems(page: Page, sessionPid: string): Promise<ChangeItem[]> {
  return expectApiData<ChangeItem[]>(
    await page.request.get(`/api/authoring/sessions/${sessionPid}/change-items`),
    'load ChangeItems',
  );
}

async function reloadSession(page: Page, sessionPid: string): Promise<AuthoringSession> {
  return expectApiData<AuthoringSession>(
    await page.request.get(`/api/authoring/sessions/${sessionPid}`),
    'reload authoring session',
  );
}

async function readRuntimePage(page: Page, pagePid: string): Promise<unknown> {
  const response = await page.request.get(`/api/pages/runtime/${encodeURIComponent(pagePid)}`);
  return expectApiData<unknown>(response, 'load runtime PageSchema');
}

async function expectApiData<T>(response: ReadableHttpResponse, label: string): Promise<T> {
  const text = await response.text();
  let body: ApiEnvelope<T>;
  try {
    body = JSON.parse(text) as ApiEnvelope<T>;
  } catch {
    throw new Error(`${label}: non-JSON response HTTP ${response.status()}: ${text}`);
  }
  expect(response.ok(), `${label}: HTTP ${response.status()}: ${text}`).toBe(true);
  expect(String(body.code ?? '0'), `${label}: API envelope ${text}`).toBe('0');
  return body.data as T;
}

function siblingOrder(root: unknown, blockId: string): { ids: string[]; index: number } {
  const siblings = findSiblingArray(root, blockId);
  expect(siblings, `sibling array for ${blockId}`).not.toBeNull();
  const ids = siblings!.map((candidate) => String(candidate.id));
  return { ids, index: ids.indexOf(blockId) };
}

function findSiblingArray(value: unknown, blockId: string): Array<Record<string, unknown>> | null {
  if (Array.isArray(value)) {
    const records = value.filter(isRecord);
    if (records.some((candidate) => String(candidate.id) === blockId)) return records;
    for (const item of value) {
      const found = findSiblingArray(item, blockId);
      if (found) return found;
    }
    return null;
  }
  if (!isRecord(value)) return null;
  for (const child of Object.values(value)) {
    const found = findSiblingArray(child, blockId);
    if (found) return found;
  }
  return null;
}

function findBlock(
  value: unknown,
  predicate: (candidate: Record<string, unknown>) => boolean,
): Record<string, unknown> | null {
  if (Array.isArray(value)) {
    for (const item of value) {
      const found = findBlock(item, predicate);
      if (found) return found;
    }
    return null;
  }
  if (!isRecord(value)) return null;
  if (predicate(value)) return value;
  for (const child of Object.values(value)) {
    const found = findBlock(child, predicate);
    if (found) return found;
  }
  return null;
}

function countStableId(value: unknown, blockId: string): number {
  if (Array.isArray(value)) {
    return value.reduce((count, child) => count + countStableId(child, blockId), 0);
  }
  if (!isRecord(value)) return 0;
  return (
    (String(value.id) === blockId ? 1 : 0) +
    Object.values(value).reduce<number>((count, child) => count + countStableId(child, blockId), 0)
  );
}

function blockType(value: Record<string, unknown>): unknown {
  return value.blockType ?? value.type;
}

function readObjectPath(root: Record<string, unknown>, path: string): unknown {
  return path
    .split('/')
    .filter(Boolean)
    .reduce<unknown>((value, segment) => {
      if (!isRecord(value)) return undefined;
      return value[segment];
    }, root);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value));
}

function apiPath(url: string): string {
  return new URL(url).pathname;
}
