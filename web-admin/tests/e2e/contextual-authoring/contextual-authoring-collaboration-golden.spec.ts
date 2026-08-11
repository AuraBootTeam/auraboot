import {
  test,
  expect,
  type Browser,
  type BrowserContext,
  type Page,
  type Request,
} from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { loginViaUI } from '../../helpers/wd-fixtures';

const RUNTIME_ROUTE = '/production-exception-list-v4';
const DEFAULT_PASSWORD = DEFAULT_TEST_ACCOUNT.password;
const SCREENSHOT_DIR = resolve(
  process.env.CONTEXTUAL_AUTHORING_SCREENSHOT_DIR ?? 'test-results/contextual-authoring',
);

const PERSONAS = {
  secondAdmin: {
    roleCode: 'e2e_contextual_authoring_second_admin',
    roleName: 'Contextual authoring second admin',
    email: 'e2e-contextual-authoring-second-admin@test.com',
    permissions: [
      'model.production_exception.read',
      'page.page.read',
      'meta.designer.read',
      'meta.designer.update',
      'meta.designer.admin',
    ],
  },
  reviewer: {
    roleCode: 'e2e_contextual_authoring_reviewer',
    roleName: 'Contextual authoring reviewer',
    email: 'e2e-contextual-authoring-reviewer@test.com',
    permissions: ['page.page.read', 'meta.publish.read', 'meta.publish.update'],
  },
  author: {
    roleCode: 'e2e_contextual_authoring_author',
    roleName: 'Contextual authoring author',
    email: 'e2e-contextual-authoring-author@test.com',
    permissions: [
      'model.production_exception.read',
      'page.page.read',
      'meta.designer.read',
      'meta.designer.update',
    ],
  },
} as const;

type Persona = (typeof PERSONAS)[keyof typeof PERSONAS];

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

type PermissionRecord = {
  pid: string;
  code: string;
};

type RoleRecord = {
  pid: string;
  code: string;
};

type WriterLease = {
  status: 'OWNED' | 'HELD_BY_OTHER' | 'HELD_BY_OTHER_SESSION' | 'EXPIRED';
  revision: number;
  leasedUntil: string;
};

type AuthoringSession = {
  sessionPid: string;
  changeSetPid: string;
  state: string;
  workspaceMode: 'AUTHORING' | 'OBSERVER' | 'REVIEW';
  revision: number;
  riskLevel: string;
  publishPolicy: string;
  validationState: string;
  impactState: string;
  changeSetStatus: string;
  approvalState: string;
  publishState: string;
  snapshot: Record<string, unknown>;
  writerLease?: WriterLease;
};

type CapabilityRegistry = {
  manifests: Array<{ blockType: string; checksum: string }>;
};

type PatchResult = {
  session: AuthoringSession;
};

type ChangeItem = {
  changeItemPid: string;
  blockId: string;
  propertyPath: string;
  operation: string;
};

type ReviewWorkspace = {
  session: AuthoringSession;
  capabilities: CapabilityRegistry;
};

type OpenedActor = {
  context: BrowserContext;
  page: Page;
  close: () => Promise<void>;
};

test.use({ storageState: { cookies: [], origins: [] } });

test.describe('Contextual authoring PC collaboration golden', () => {
  test.describe.configure({ mode: 'serial', timeout: 180_000 });

  test.beforeEach(async ({ page }) => {
    await login(page, DEFAULT_TEST_ACCOUNT.email);
  });

  test('PC-AUTH-005 @critical — second admin observes read-only and takes over the single writer lease', async ({
    page,
    browser,
  }) => {
    await ensurePersona(page, PERSONAS.secondAdmin);
    await page.setViewportSize({ width: 1440, height: 900 });
    const ownerSession = await enterAuthoringFromRuntime(page);
    const tablePatch = await buildTablePatch(page, ownerSession, '/props/density', 'compact');

    const secondAdmin = await openAsPersona(browser, PERSONAS.secondAdmin);
    try {
      await secondAdmin.page.setViewportSize({ width: 1440, height: 900 });
      const observerResponse = secondAdmin.page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) ===
            `/api/authoring/change-sets/${ownerSession.changeSetPid}/sessions`,
      );
      await secondAdmin.page.goto(
        `/unified-designer?changeSetId=${encodeURIComponent(ownerSession.changeSetPid)}`,
        { waitUntil: 'domcontentloaded' },
      );
      const observer = await expectApiData<AuthoringSession>(
        await observerResponse,
        'open second-admin observer workspace',
      );

      expect(observer.workspaceMode).toBe('OBSERVER');
      expect(observer.state).toBe('READ_ONLY');
      expect(observer.writerLease?.status).toBe('HELD_BY_OTHER');
      await expect(secondAdmin.page).toHaveURL(/authoringSession=/);
      await expect(secondAdmin.page.getByTestId('unified-designer-workbench')).toBeVisible();
      await expect(secondAdmin.page.getByTestId('authoring-writer-lease-notice')).toContainText(
        '另一位管理员正在编辑此 ChangeSet',
      );
      await expect(secondAdmin.page.getByTestId('designer-save')).toBeDisabled();

      await secondAdmin.page
        .getByPlaceholder('填写接管原因（必填，将写入审计）')
        .fill('PC 门禁：经值班负责人确认接管');
      const takeoverResponse = secondAdmin.page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) ===
            `/api/authoring/sessions/${observer.sessionPid}/writer-lease/takeover`,
      );
      await secondAdmin.page.getByTestId('authoring-writer-lease-takeover').click();
      const taken = await expectApiData<AuthoringSession>(
        await takeoverResponse,
        'take over writer lease',
      );
      expect(taken.state).toBe('ACTIVE');
      expect(taken.writerLease?.status).toBe('OWNED');
      await expect(secondAdmin.page.getByTestId('authoring-writer-lease-notice')).toHaveCount(0);

      await expect(page.getByTestId('authoring-writer-lease-notice')).toContainText(
        '另一位管理员正在编辑此 ChangeSet',
        { timeout: 20_000 },
      );
      await expect(page.getByTestId('contextual-authoring-surface')).toHaveAttribute(
        'data-read-only',
        'true',
      );

      const rejectedWrite = await page.request.patch(
        `/api/authoring/sessions/${ownerSession.sessionPid}/patches`,
        { data: { expectedRevision: ownerSession.revision, ...tablePatch } },
      );
      expect(rejectedWrite.status(), '失去 writer lease 的原 owner 不能继续写入').toBe(409);
      const rejectedBody = await rejectedWrite.text();
      expect(rejectedBody).toContain('authoring.writer-lease.lost');

      const unchanged = await expectApiData<AuthoringSession>(
        await page.request.get(`/api/authoring/sessions/${ownerSession.sessionPid}`),
        'reload former owner session',
      );
      expect(unchanged.revision).toBe(ownerSession.revision);
      expect(unchanged.writerLease?.status).toBe('HELD_BY_OTHER');
    } finally {
      await secondAdmin.close();
    }
  });

  test('PC-AUTH-012 @critical — two admins resolve Base Mine Latest without last-write-wins', async ({
    page,
    browser,
  }) => {
    await ensurePersona(page, PERSONAS.secondAdmin);
    await page.setViewportSize({ width: 1440, height: 900 });
    const ownerSession = await enterAuthoringFromRuntime(page);
    const table = findTableBlock(ownerSession.snapshot);
    expect(table?.id, 'table block for Base Mine Latest').toBeTruthy();
    const tableId = String(table!.id);
    const baseTitleValue = readObjectPath(table!, '/title');
    const baseTitle = typeof baseTitleValue === 'string' ? baseTitleValue : undefined;
    const baseSpanValue = readObjectPath(table!, '/layout/span');
    const baseSpan = typeof baseSpanValue === 'number' ? baseSpanValue : undefined;
    const mineTitle = baseTitle === '生产异常（Mine）' ? '生产异常（Mine 2）' : '生产异常（Mine）';
    const latestTitle =
      baseTitle === '生产异常（Latest）' ? '生产异常（Latest 2）' : '生产异常（Latest）';
    const mineSpan = distinctSpan(baseSpan ?? -1, []);
    const latestSpan = distinctSpan(baseSpan ?? -1, [mineSpan]);
    const itemsBefore = await loadChangeItems(page, ownerSession.sessionPid);

    const staged = await stageLocalTitleAndSpanEdits(page, tableId, mineTitle, mineSpan);
    await expect(page.getByText('2 项未保存')).toBeVisible();

    const secondAdmin = await openAsPersona(browser, PERSONAS.secondAdmin);
    try {
      await secondAdmin.page.setViewportSize({ width: 1440, height: 900 });
      const observerResponse = secondAdmin.page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) ===
            `/api/authoring/change-sets/${ownerSession.changeSetPid}/sessions`,
      );
      await secondAdmin.page.goto(
        `/unified-designer?changeSetId=${encodeURIComponent(ownerSession.changeSetPid)}`,
        { waitUntil: 'domcontentloaded' },
      );
      const observer = await expectApiData<AuthoringSession>(
        await observerResponse,
        'open conflict observer workspace',
      );
      expect(observer.writerLease?.status).toBe('HELD_BY_OTHER');

      await secondAdmin.page
        .getByPlaceholder('填写接管原因（必填，将写入审计）')
        .fill('PC 门禁：写入 Latest 供三方冲突裁决');
      const takeoverResponse = secondAdmin.page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) ===
            `/api/authoring/sessions/${observer.sessionPid}/writer-lease/takeover`,
      );
      await secondAdmin.page.getByTestId('authoring-writer-lease-takeover').click();
      const taken = await expectApiData<AuthoringSession>(
        await takeoverResponse,
        'second admin takes the writer lease for Latest',
      );
      expect(taken.writerLease?.status).toBe('OWNED');

      await secondAdmin.page.getByTestId(`outline-item-${tableId}`).click();
      await expect(secondAdmin.page.getByTestId('inspector-selected-id')).toContainText(tableId);
      await secondAdmin.page.getByTestId('inspector-field-title').fill(latestTitle);
      await secondAdmin.page.getByTestId('inspector-field-layout.span').fill(String(latestSpan));
      await expect(secondAdmin.page.getByTestId('designer-dirty-state')).toContainText('未保存');

      const latestSaveResponse = secondAdmin.page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) ===
            `/api/authoring/sessions/${observer.sessionPid}/studio-batches`,
      );
      await secondAdmin.page.getByTestId('designer-save').click();
      const latestSaved = await expectApiData<PatchResult>(
        await latestSaveResponse,
        'save the second admin Latest document',
      );
      expect(latestSaved.session.revision).toBeGreaterThan(ownerSession.revision);
      expect(readObjectPath(findTableBlock(latestSaved.session.snapshot)!, '/title')).toBe(
        latestTitle,
      );
      expect(readObjectPath(findTableBlock(latestSaved.session.snapshot)!, '/layout/span')).toBe(
        latestSpan,
      );
      await expect(secondAdmin.page.getByTestId('designer-dirty-state')).toContainText('已保存');

      await expect(page.getByTestId('contextual-authoring-conflict')).toBeVisible({
        timeout: 25_000,
      });
      await expect(page.getByTestId('contextual-authoring-conflict')).toContainText(
        `Base r${ownerSession.revision} / Latest r${latestSaved.session.revision}`,
      );
      await expect(page.getByTestId('contextual-authoring-conflict')).toContainText(
        '已保留 2 项 Mine',
      );
      await expect(staged.titleEditor).toHaveValue(mineTitle);
      await expect(staged.spanEditor).toHaveValue(String(mineSpan));
      await expect(page.getByRole('button', { name: '保存', exact: true })).toBeDisabled();

      await page.getByTestId('contextual-authoring-conflict-studio').click();
      await expect(page).toHaveURL(/unified-designer\?authoringSession=/);
      await expect(page.getByTestId('authoring-conflict-panel')).toBeVisible();
      await expect(page.getByTestId('studio-handoff-read-only-reason')).toContainText(
        'Base / Mine / Latest',
      );

      const titleConflict = conflictCard(page, '标题');
      const spanConflict = conflictCard(page, '布局宽度');
      await expect(titleConflict.getByTestId('authoring-conflict-value-base')).toContainText(
        baseTitle ?? '未设置',
      );
      await expect(titleConflict.getByTestId('authoring-conflict-value-mine')).toContainText(
        mineTitle,
      );
      await expect(titleConflict.getByTestId('authoring-conflict-value-latest')).toContainText(
        latestTitle,
      );
      await expect(spanConflict.getByTestId('authoring-conflict-value-base')).toContainText(
        baseSpan == null ? '未设置' : String(baseSpan),
      );
      await expect(spanConflict.getByTestId('authoring-conflict-value-mine')).toContainText(
        String(mineSpan),
      );
      await expect(spanConflict.getByTestId('authoring-conflict-value-latest')).toContainText(
        String(latestSpan),
      );
      await expect(page.getByText(tableId, { exact: true })).toHaveCount(0);
      await expect(page.getByText('/layout/span', { exact: true })).toHaveCount(0);
      await mkdir(SCREENSHOT_DIR, { recursive: true });
      await page.setViewportSize({ width: 1440, height: 1200 });
      await page.screenshot({
        path: resolve(SCREENSHOT_DIR, 'pc-auth-012-base-mine-latest.png'),
        fullPage: true,
      });

      await page
        .getByPlaceholder('填写接管原因（必填，将写入审计）')
        .fill('PC 门禁：裁决 Base Mine Latest 并恢复保存');
      const ownerTakeoverResponse = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) ===
            `/api/authoring/sessions/${ownerSession.sessionPid}/writer-lease/takeover`,
      );
      await page.getByTestId('authoring-writer-lease-takeover').click();
      const ownerTaken = await expectApiData<AuthoringSession>(
        await ownerTakeoverResponse,
        'original admin retakes writer lease for conflict resolution',
      );
      expect(ownerTaken.writerLease?.status).toBe('OWNED');
      expect(ownerTaken.revision).toBe(latestSaved.session.revision);

      await titleConflict.getByLabel('保留 Mine').click();
      await spanConflict.getByLabel('保留 Latest').click();
      await expect(page.getByTestId('authoring-conflict-apply')).toBeEnabled();
      const resolutionResponse = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) ===
            `/api/authoring/sessions/${ownerSession.sessionPid}/studio-batches`,
      );
      await page.getByTestId('authoring-conflict-apply').click();
      const resolutionHttpResponse = await resolutionResponse;
      const resolutionRequest = resolutionHttpResponse.request().postDataJSON() as {
        expectedRevision: number;
        patches: Array<{ blockId: string; propertyPath: string; value: unknown }>;
      };
      const resolved = await expectApiData<PatchResult>(
        resolutionHttpResponse,
        'persist explicit Mine and Latest decisions',
      );

      expect(resolutionRequest.expectedRevision).toBe(latestSaved.session.revision);
      expect(resolutionRequest.patches).toEqual([
        expect.objectContaining({
          blockId: tableId,
          propertyPath: '/title',
          value: mineTitle,
        }),
      ]);
      expect(resolved.session.revision).toBe(latestSaved.session.revision + 1);
      const resolvedTable = findTableBlock(resolved.session.snapshot);
      expect(readObjectPath(resolvedTable!, '/title')).toBe(mineTitle);
      expect(readObjectPath(resolvedTable!, '/layout/span')).toBe(latestSpan);
      await expect(page.getByTestId('authoring-conflict-panel')).toHaveCount(0);
      await expect(page.getByTestId('studio-handoff-context')).toContainText(
        `修订 r${resolved.session.revision}`,
      );
      await expect(page.getByTestId('designer-dirty-state')).toContainText('已保存');

      const itemsAfter = await loadChangeItems(page, ownerSession.sessionPid);
      expect(itemsAfter).toHaveLength(itemsBefore.length + 3);
      expect(
        itemsAfter
          .slice(-3, -1)
          .map(({ propertyPath }) => propertyPath)
          .sort(),
      ).toEqual(['/layout/span', '/title']);
      expect(itemsAfter.at(-1)?.propertyPath).toBe('/title');
      const canonical = await expectApiData<AuthoringSession>(
        await page.request.get(`/api/authoring/sessions/${ownerSession.sessionPid}`),
        'reload explicitly resolved authoring session',
      );
      expect(canonical.revision).toBe(resolved.session.revision);
      expect(readObjectPath(findTableBlock(canonical.snapshot)!, '/title')).toBe(mineTitle);
      expect(readObjectPath(findTableBlock(canonical.snapshot)!, '/layout/span')).toBe(latestSpan);
      await page.screenshot({
        path: resolve(SCREENSHOT_DIR, 'pc-auth-012-resolved.png'),
        fullPage: true,
      });
    } finally {
      await secondAdmin.close();
    }
  });

  test('PC-AUTH-013 @critical — a second tab is read-only until audited takeover', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    const ownerSession = await enterAuthoringFromRuntime(page);
    const staged = await stageLocalDensityEdit(page, ownerSession);
    await expect(page.getByText('1 项未保存')).toBeVisible();

    const secondTab = await page.context().newPage();
    try {
      await secondTab.setViewportSize({ width: 1440, height: 900 });
      const observerResponse = secondTab.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) ===
            `/api/authoring/change-sets/${ownerSession.changeSetPid}/sessions`,
      );
      await secondTab.goto(
        `/unified-designer?changeSetId=${encodeURIComponent(ownerSession.changeSetPid)}`,
        { waitUntil: 'domcontentloaded' },
      );
      const observer = await expectApiData<AuthoringSession>(
        await observerResponse,
        'open the same ChangeSet in a second tab',
      );
      expect(observer.writerLease?.status).toBe('HELD_BY_OTHER_SESSION');
      await expect(secondTab.getByTestId('authoring-writer-lease-notice')).toContainText(
        '当前账号的另一个会话持有编辑权',
      );
      await expect(secondTab.getByTestId('studio-handoff-read-only-reason')).toContainText(
        'Writer lease 由其他会话持有',
      );
      await expect(secondTab.getByTestId('designer-save')).toBeDisabled();
      await mkdir(SCREENSHOT_DIR, { recursive: true });
      await secondTab.screenshot({
        path: resolve(SCREENSHOT_DIR, 'pc-auth-013-second-tab-readonly.png'),
        fullPage: true,
      });

      await secondTab
        .getByPlaceholder('填写接管原因（必填，将写入审计）')
        .fill('PC 门禁：同账号第二标签页继续编辑');
      const takeoverResponse = secondTab.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) ===
            `/api/authoring/sessions/${observer.sessionPid}/writer-lease/takeover`,
      );
      await secondTab.getByTestId('authoring-writer-lease-takeover').click();
      const taken = await expectApiData<AuthoringSession>(
        await takeoverResponse,
        'take over editing from the second tab',
      );
      expect(taken.writerLease?.status).toBe('OWNED');
      await expect(secondTab.getByTestId('authoring-writer-lease-notice')).toHaveCount(0);
      await expect(secondTab.getByTestId('studio-handoff-editable-reason')).toBeVisible();

      await expect(page.getByTestId('authoring-writer-lease-notice')).toContainText(
        '当前账号的另一个会话持有编辑权',
        { timeout: 20_000 },
      );
      await expect(page.getByTestId('contextual-authoring-surface')).toHaveAttribute(
        'data-read-only',
        'true',
      );
      await expect(page.getByText('1 项未保存')).toBeVisible();
      await expect(staged.editor).toHaveValue(staged.value);
      await expect(page.getByRole('button', { name: '保存', exact: true })).toBeDisabled();
      const originalAfter = await expectApiData<AuthoringSession>(
        await page.request.get(`/api/authoring/sessions/${ownerSession.sessionPid}`),
        'reload the first tab after second-tab takeover',
      );
      expect(originalAfter.state).toBe('READ_ONLY');
      expect(originalAfter.writerLease?.status).toBe('HELD_BY_OTHER_SESSION');
      await page.screenshot({
        path: resolve(SCREENSHOT_DIR, 'pc-auth-013-first-tab-retains-dirty.png'),
        fullPage: true,
      });
    } finally {
      await secondTab.close();
    }
  });

  test('PC-AUTH-014 @critical — foreground heartbeat renews only the writer lease', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    const ownerSession = await enterAuthoringFromRuntime(page);
    expect(ownerSession.writerLease?.status).toBe('OWNED');
    const itemsBefore = await loadChangeItems(page, ownerSession.sessionPid);
    const renewResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        apiPath(response.url()) ===
          `/api/authoring/sessions/${ownerSession.sessionPid}/writer-lease/renew`,
    );

    await page.evaluate(() => {
      const originalNow = Date.now;
      const clock = window as Window & { __authoringOriginalDateNow?: () => number };
      clock.__authoringOriginalDateNow = originalNow;
      Date.now = () => originalNow() + 4 * 60_000;
      window.dispatchEvent(new Event('focus'));
    });
    const renewed = await expectApiData<AuthoringSession>(
      await renewResponse,
      'renew the writer lease after returning to the foreground',
    );
    await page.evaluate(() => {
      const clock = window as Window & { __authoringOriginalDateNow?: () => number };
      if (clock.__authoringOriginalDateNow) Date.now = clock.__authoringOriginalDateNow;
      delete clock.__authoringOriginalDateNow;
    });

    expect(renewed.revision).toBe(ownerSession.revision);
    expect(renewed.writerLease?.status).toBe('OWNED');
    expect(renewed.writerLease?.revision).toBe((ownerSession.writerLease?.revision ?? 0) + 1);
    expect(Date.parse(renewed.writerLease!.leasedUntil)).toBeGreaterThan(
      Date.parse(ownerSession.writerLease!.leasedUntil),
    );
    expect(await loadChangeItems(page, ownerSession.sessionPid)).toEqual(itemsBefore);
    await expect(page.getByText('0 项未保存')).toBeVisible();
  });

  test('PC-AUTH-015 @critical @soak — a naturally expired background writer stays dirty while three tabs arbitrate one takeover', async ({
    page,
  }) => {
    test.slow();
    await page.setViewportSize({ width: 1440, height: 900 });
    const ownerSession = await enterAuthoringFromRuntime(page);
    expect(ownerSession.writerLease?.status).toBe('OWNED');
    const staged = await stageLocalDensityEdit(page, ownerSession);
    await expect(page.getByText('1 项未保存')).toBeVisible();

    const renewRequests: string[] = [];
    const recordRenewRequest = (request: Request) => {
      if (
        request.method() === 'POST' &&
        apiPath(request.url()) ===
          `/api/authoring/sessions/${ownerSession.sessionPid}/writer-lease/renew`
      ) {
        renewRequests.push(request.url());
      }
    };
    page.on('request', recordRenewRequest);

    const secondTab = await page.context().newPage();
    const thirdTab = await page.context().newPage();
    try {
      const openObserver = async (observerPage: Page, label: string) => {
        await observerPage.setViewportSize({ width: 1440, height: 900 });
        const observerResponse = observerPage.waitForResponse(
          (response) =>
            response.request().method() === 'POST' &&
            apiPath(response.url()) ===
              `/api/authoring/change-sets/${ownerSession.changeSetPid}/sessions`,
        );
        await observerPage.goto(
          `/unified-designer?changeSetId=${encodeURIComponent(ownerSession.changeSetPid)}`,
          { waitUntil: 'domcontentloaded' },
        );
        const observer = await expectApiData<AuthoringSession>(await observerResponse, label);
        expect(observer.writerLease?.status).toBe('HELD_BY_OTHER_SESSION');
        await expect(observerPage.getByTestId('authoring-writer-lease-notice')).toContainText(
          '当前账号的另一个会话持有编辑权',
        );
        return observer;
      };

      const secondObserver = await openObserver(secondTab, 'open the second tab observer');
      const thirdObserver = await openObserver(thirdTab, 'open the third tab observer');
      expect(secondObserver.writerLease?.revision).toBe(ownerSession.writerLease?.revision);
      expect(thirdObserver.writerLease?.revision).toBe(ownerSession.writerLease?.revision);

      await thirdTab.bringToFront();
      await setControlledVisibility(page, 'hidden');
      expect(await page.evaluate(() => document.visibilityState)).toBe('hidden');
      const naturalWaitMs = Date.parse(ownerSession.writerLease!.leasedUntil) - Date.now() + 12_000;
      expect(naturalWaitMs).toBeGreaterThan(240_000);
      expect(naturalWaitMs).toBeLessThan(330_000);
      await new Promise<void>((resolveDelay) => setTimeout(resolveDelay, naturalWaitMs));

      expect(renewRequests, 'a hidden tab must not renew its writer lease').toEqual([]);
      page.off('request', recordRenewRequest);
      const expired = await expectApiData<AuthoringSession>(
        await page.request.get(`/api/authoring/sessions/${ownerSession.sessionPid}`),
        'read the naturally expired owner session',
      );
      expect(expired.writerLease?.status).toBe('EXPIRED');
      expect(expired.writerLease?.revision).toBe(ownerSession.writerLease?.revision);
      expect(expired.revision).toBe(ownerSession.revision);
      expect(await loadChangeItems(page, ownerSession.sessionPid)).toEqual([]);

      await setControlledVisibility(page, 'visible');
      await page.bringToFront();
      await expect(page.getByTestId('authoring-writer-lease-notice')).toContainText(
        'Writer lease 已过期',
        { timeout: 20_000 },
      );
      await expect(page.getByTestId('contextual-authoring-surface')).toHaveAttribute(
        'data-read-only',
        'true',
      );
      await expect(page.getByText('1 项未保存')).toBeVisible();
      await expect(staged.editor).toHaveValue(staged.value);
      await expect(page.getByRole('button', { name: '保存', exact: true })).toBeDisabled();

      await secondTab
        .getByPlaceholder('填写接管原因（必填，将写入审计）')
        .fill('PC soak：第二标签基于自然过期租约尝试接管');
      await thirdTab
        .getByPlaceholder('填写接管原因（必填，将写入审计）')
        .fill('PC soak：第三标签基于自然过期租约尝试接管');
      const secondTakeoverResponse = secondTab.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) ===
            `/api/authoring/sessions/${secondObserver.sessionPid}/writer-lease/takeover`,
      );
      const thirdTakeoverResponse = thirdTab.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) ===
            `/api/authoring/sessions/${thirdObserver.sessionPid}/writer-lease/takeover`,
      );
      await Promise.all([
        secondTab.getByTestId('authoring-writer-lease-takeover').click(),
        thirdTab.getByTestId('authoring-writer-lease-takeover').click(),
      ]);
      const takeoverResponses = await Promise.all([secondTakeoverResponse, thirdTakeoverResponse]);
      expect(takeoverResponses.map((response) => response.status()).sort()).toEqual([200, 409]);

      const winnerIndex = takeoverResponses.findIndex((response) => response.status() === 200);
      const winnerPage = winnerIndex === 0 ? secondTab : thirdTab;
      const loserPage = winnerIndex === 0 ? thirdTab : secondTab;
      const winner = await expectApiData<AuthoringSession>(
        takeoverResponses[winnerIndex]!,
        'one of three tabs wins the naturally expired lease',
      );
      expect(winner.writerLease?.status).toBe('OWNED');
      expect(winner.writerLease?.revision).toBe((ownerSession.writerLease?.revision ?? 0) + 1);
      const loserResponse = takeoverResponses[winnerIndex === 0 ? 1 : 0]!;
      const loserBody = await loserResponse.text();
      expect(loserBody).toContain('authoring.writer-lease.conflict');
      expect(loserBody).not.toContain('snapshot');

      await expect(winnerPage.getByTestId('authoring-writer-lease-notice')).toHaveCount(0);
      await expect(loserPage.getByTestId('authoring-writer-lease-notice')).toContainText(
        '当前账号的另一个会话持有编辑权',
        { timeout: 20_000 },
      );
      await expect(loserPage.getByTestId('writer-lease-takeover-feedback')).toContainText(
        '编辑权刚被另一会话取得，已刷新为只读',
      );
      await expect(loserPage.getByText('Business error')).toHaveCount(0);
      await expect(loserPage.getByTestId('designer-save')).toBeDisabled();
      await expect(page.getByTestId('authoring-writer-lease-notice')).toContainText(
        '当前账号的另一个会话持有编辑权',
        { timeout: 20_000 },
      );
      await expect(page.getByText('1 项未保存')).toBeVisible();
      await expect(staged.editor).toHaveValue(staged.value);

      await mkdir(SCREENSHOT_DIR, { recursive: true });
      await winnerPage.screenshot({
        path: resolve(SCREENSHOT_DIR, 'pc-auth-015-three-tab-winner.png'),
        fullPage: true,
      });
      await loserPage.screenshot({
        path: resolve(SCREENSHOT_DIR, 'pc-auth-015-three-tab-loser.png'),
        fullPage: true,
      });
      await page.screenshot({
        path: resolve(SCREENSHOT_DIR, 'pc-auth-015-expired-owner-dirty.png'),
        fullPage: true,
      });
    } finally {
      await setControlledVisibility(page, 'visible').catch(() => {});
      page.off('request', recordRenewRequest);
      await Promise.all([secondTab.close(), thirdTab.close()]);
    }
  });

  test('PC-AUTH-006 @critical — independent reviewer approves the frozen revision but cannot publish', async ({
    page,
    browser,
  }) => {
    await ensurePersona(page, PERSONAS.reviewer);
    const ownerSession = await enterAuthoringFromRuntime(page);
    const l2Patch = await buildTablePatch(page, ownerSession, '/props/defaultFilter', {
      status: 'OPEN',
    });
    const patched = await expectApiData<PatchResult>(
      await page.request.patch(`/api/authoring/sessions/${ownerSession.sessionPid}/patches`, {
        data: { expectedRevision: ownerSession.revision, ...l2Patch },
      }),
      'apply L2 review-required patch',
    );
    expect(patched.session.riskLevel).toBe('L2');
    expect(patched.session.publishPolicy).toBe('REQUIRED_REVIEW');

    const prepared = await expectApiData<AuthoringSession>(
      await page.request.post(`/api/authoring/sessions/${ownerSession.sessionPid}/prepare`, {
        data: { expectedRevision: patched.session.revision },
      }),
      'prepare exact revision',
    );
    expect(prepared.validationState).toBe('VALID');
    expect(prepared.impactState).toBe('KNOWN');
    await expectApiSuccess(
      await page.request.post(`/api/authoring/sessions/${ownerSession.sessionPid}/submit`, {
        data: { expectedRevision: prepared.revision },
      }),
      'submit exact revision for review',
    );

    const reviewer = await openAsPersona(browser, PERSONAS.reviewer);
    try {
      await reviewer.page.setViewportSize({ width: 1440, height: 900 });
      const reviewWorkspaceResponse = reviewer.page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) ===
            `/api/authoring/change-sets/${ownerSession.changeSetPid}/review-workspaces`,
      );
      await reviewer.page.goto(
        `/unified-designer?reviewChangeSetId=${encodeURIComponent(ownerSession.changeSetPid)}`,
        { waitUntil: 'domcontentloaded' },
      );
      const workspace = await expectApiData<ReviewWorkspace>(
        await reviewWorkspaceResponse,
        'open bounded review workspace',
      );

      expect(workspace.session.workspaceMode).toBe('REVIEW');
      expect(workspace.session.state).toBe('READ_ONLY');
      expect(workspace.session.changeSetStatus).toBe('IN_REVIEW');
      expect(workspace.session.revision).toBe(prepared.revision);
      await expect(reviewer.page).toHaveURL(/reviewSession=/);
      await expect(reviewer.page.getByTestId('unified-designer-workbench')).toBeVisible();
      await expect(reviewer.page.getByTestId('designer-save')).toBeDisabled();
      await expect(reviewer.page.getByTestId('authoring-writer-lease-notice')).toHaveCount(0);
      await expect(reviewer.page.getByTestId('authoring-governance-notice')).toContainText(
        `revision r${prepared.revision} 已冻结`,
      );
      await expect(reviewer.page.getByTestId('authoring-governance-approve')).toBeVisible();
      await expect(reviewer.page.getByTestId('authoring-governance-publish')).toHaveCount(0);

      await reviewer.page
        .getByTestId('authoring-governance-reason')
        .fill('PC 门禁：冻结 revision 的独立审核通过');
      const approveResponse = reviewer.page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) ===
            `/api/authoring/change-sets/${ownerSession.changeSetPid}/approve`,
      );
      await reviewer.page.getByTestId('authoring-governance-approve').click();
      await expectApiSuccess(await approveResponse, 'approve exact frozen revision');
      await expect(reviewer.page.getByTestId('authoring-governance-notice')).toContainText(
        `revision r${prepared.revision} 已批准`,
      );
      await expect(reviewer.page.getByTestId('authoring-governance-publish')).toHaveCount(0);

      const forbiddenPublish = await reviewer.page.request.post(
        `/api/authoring/change-sets/${ownerSession.changeSetPid}/publish`,
        { data: { expectedRevision: prepared.revision } },
      );
      expect(forbiddenPublish.status(), 'reviewer 不得越权发布').toBe(403);

      const approved = await expectApiData<AuthoringSession>(
        await page.request.get(`/api/authoring/sessions/${ownerSession.sessionPid}`),
        'reload approved owner session',
      );
      expect(approved.changeSetStatus).toBe('APPROVED');
      expect(approved.approvalState).toBe('APPROVED');
      expect(approved.publishState).toBe('READY');
      expect(approved.revision).toBe(prepared.revision);
    } finally {
      await reviewer.close();
    }
  });

  test('PC-AUTH-007 @critical — revoking designer update turns a live session read-only and blocks the backend', async ({
    page,
    browser,
  }) => {
    const role = await ensurePersona(page, PERSONAS.author);
    const author = await openAsPersona(browser, PERSONAS.author);
    let permissionsRestored = false;
    try {
      await author.page.setViewportSize({ width: 1280, height: 720 });
      const session = await enterAuthoringFromRuntime(author.page);
      const tablePatch = await buildTablePatch(author.page, session, '/props/density', 'compact');
      const { editor: densityEditor, value: stagedDensity } = await stageLocalDensityEdit(
        author.page,
        session,
      );
      await expect(author.page.getByTestId('contextual-authoring-surface')).toHaveAttribute(
        'data-read-only',
        'false',
      );
      await expect(author.page.getByText('1 项未保存')).toBeVisible();

      const retainedPermissions = PERSONAS.author.permissions.filter(
        (permission) => permission !== 'meta.designer.update',
      );
      await assignPermissions(page, role.pid, retainedPermissions);

      const forbiddenWrite = await author.page.request.patch(
        `/api/authoring/sessions/${session.sessionPid}/patches`,
        { data: { expectedRevision: session.revision, ...tablePatch } },
      );
      expect(forbiddenWrite.status(), '后端权限必须在 UI 刷新前独立生效').toBe(403);
      const forbiddenBody = await forbiddenWrite.text();
      expect(forbiddenBody).not.toContain('snapshot');

      await author.page.waitForResponse(
        (response) =>
          response.request().method() === 'GET' &&
          apiPath(response.url()) === `/api/authoring/sessions/${session.sessionPid}`,
        { timeout: 25_000 },
      );
      await expect(author.page.getByTestId('authoring-permission-revoked')).toBeVisible({
        timeout: 65_000,
      });
      await expect(author.page.getByTestId('authoring-permission-revoked')).toContainText(
        '配置权限已收回，当前会话已即时转为只读',
      );
      await expect(author.page.getByTestId('contextual-authoring-surface')).toHaveAttribute(
        'data-read-only',
        'true',
      );
      await expect(author.page.getByText('1 项未保存')).toBeVisible();
      await expect(densityEditor).toBeDisabled();
      await expect(author.page.getByRole('button', { name: '保存', exact: true })).toBeDisabled();

      await assignPermissions(page, role.pid, [...PERSONAS.author.permissions]);
      permissionsRestored = true;
      await expect(author.page.getByTestId('authoring-permission-revoked')).toHaveCount(0, {
        timeout: 65_000,
      });
      await expect(author.page.getByTestId('contextual-authoring-surface')).toHaveAttribute(
        'data-read-only',
        'false',
      );
      await expect(densityEditor).toBeEnabled();
      await expect(densityEditor).toHaveValue(stagedDensity);
      await expect(author.page.getByText('1 项未保存')).toBeVisible();
      await expect(author.page.getByRole('button', { name: '保存', exact: true })).toBeEnabled();
      const unchanged = await expectApiData<AuthoringSession>(
        await author.page.request.get(`/api/authoring/sessions/${session.sessionPid}`),
        'author verifies revision after permission restoration',
      );
      expect(unchanged.revision).toBe(session.revision);
    } finally {
      if (!permissionsRestored) {
        await assignPermissions(page, role.pid, [...PERSONAS.author.permissions]).catch(() => {});
      }
      await author.close();
    }
  });
});

async function login(page: Page, email: string): Promise<void> {
  await loginViaUI(page, email, DEFAULT_PASSWORD);
  await expect(page.locator('nav')).toBeVisible({ timeout: 15_000 });
}

async function openRuntimeFromMenu(page: Page): Promise<void> {
  await expect(page.locator('nav')).toBeVisible({ timeout: 10_000 });
  const link = page.locator('nav').locator(`a[href="${RUNTIME_ROUTE}"]`).first();
  await expect(link).toBeVisible({ timeout: 10_000 });
  await link.click();
  await expect(page).toHaveURL(new RegExp(`${RUNTIME_ROUTE}$`));
  await expect(page.getByRole('main').first().getByText('EXC-V4-REAL-001')).toBeVisible({
    timeout: 15_000,
  });
}

async function enterAuthoringFromRuntime(page: Page): Promise<AuthoringSession> {
  await openRuntimeFromMenu(page);
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

async function expectApiData<T>(response: ReadableHttpResponse, label: string): Promise<T> {
  const body = await expectApiSuccess<T>(response, label);
  return body.data as T;
}

async function expectApiSuccess<T = unknown>(
  response: ReadableHttpResponse,
  label: string,
): Promise<ApiEnvelope<T>> {
  const text = await response.text();
  let body: ApiEnvelope<T>;
  try {
    body = JSON.parse(text) as ApiEnvelope<T>;
  } catch {
    throw new Error(`${label}: non-JSON response HTTP ${response.status()}: ${text}`);
  }
  expect(response.ok(), `${label}: HTTP ${response.status()}: ${text}`).toBe(true);
  expect(String(body.code ?? '0'), `${label}: API envelope ${text}`).toBe('0');
  return body;
}

async function ensurePersona(adminPage: Page, persona: Persona): Promise<RoleRecord> {
  const roles = await expectApiData<RoleRecord[]>(
    await adminPage.request.get('/api/roles/all'),
    `load roles for ${persona.roleCode}`,
  );
  let role = roles.find((candidate) => candidate.code === persona.roleCode);
  if (!role) {
    role = await expectApiData<RoleRecord>(
      await adminPage.request.post('/api/roles', {
        data: {
          code: persona.roleCode,
          name: persona.roleName,
          description: `PC collaboration golden persona: ${persona.roleName}`,
          type: 'custom',
        },
      }),
      `create role ${persona.roleCode}`,
    );
  }
  await assignPermissions(adminPage, role.pid, [...persona.permissions]);

  const loginProbe = await adminPage.request.post('/api/auth/login', {
    data: { email: persona.email, password: DEFAULT_PASSWORD },
  });
  const loginBody = (await loginProbe.json().catch(() => ({}))) as ApiEnvelope<unknown>;
  if (!loginProbe.ok() || String(loginBody.code ?? '1') !== '0') {
    const created = await expectApiData<{ assignedRoles?: string[] }>(
      await adminPage.request.post('/api/admin/users', {
        data: {
          email: persona.email,
          displayName: persona.roleName,
          initialPassword: DEFAULT_PASSWORD,
          roleCodes: [persona.roleCode],
          sendInviteEmail: false,
        },
      }),
      `create user ${persona.email}`,
    );
    expect(created.assignedRoles ?? []).toContain(persona.roleCode);
  }

  const members = await expectApiData<Array<{ email?: string; memberPid?: string }>>(
    await adminPage.request.get(
      `/api/org/members/unlinked?keyword=${encodeURIComponent(persona.email)}`,
    ),
    `resolve member ${persona.email}`,
  );
  const member = members.find((candidate) => candidate.email === persona.email);
  expect(member?.memberPid, `member pid for ${persona.email}`).toBeTruthy();
  await expectApiData<boolean>(
    await adminPage.request.post('/api/user-roles/assign-by-code', {
      data: { memberPid: member!.memberPid, roleCodes: [persona.roleCode] },
    }),
    `activate role ${persona.roleCode}`,
  );
  return role;
}

async function assignPermissions(
  adminPage: Page,
  rolePid: string,
  permissionCodes: string[],
): Promise<void> {
  const permissionPids = await resolvePermissionPids(adminPage, permissionCodes);
  await expectApiData<boolean>(
    await adminPage.request.post(`/api/roles/${rolePid}/permissions`, {
      data: permissionPids,
    }),
    `assign permissions to ${rolePid}`,
  );
}

async function resolvePermissionPids(page: Page, permissionCodes: string[]): Promise<string[]> {
  const resourceTypes = ['function', 'operation', 'data', 'model'];
  const permissions = (
    await Promise.all(
      resourceTypes.map(async (resourceType) =>
        expectApiData<PermissionRecord[]>(
          await page.request.get(`/api/permissions/resource-type/${resourceType}`),
          `load ${resourceType} permissions`,
        ),
      ),
    )
  ).flat();
  const byCode = new Map(permissions.map((permission) => [permission.code, permission.pid]));
  const missing = permissionCodes.filter((code) => !byCode.has(code));
  expect(missing, `missing permissions: ${missing.join(', ')}`).toEqual([]);
  return permissionCodes.map((code) => byCode.get(code)!);
}

async function openAsPersona(browser: Browser, persona: Persona): Promise<OpenedActor> {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();
  await login(page, persona.email);
  return { context, page, close: () => context.close() };
}

async function buildTablePatch(
  page: Page,
  session: AuthoringSession,
  propertyPath: '/props/density' | '/props/defaultFilter',
  value: unknown,
): Promise<{
  blockId: string;
  propertyPath: string;
  operation: 'ADD' | 'REPLACE';
  value: unknown;
  manifestChecksum: string;
}> {
  const table = findTableBlock(session.snapshot);
  expect(table?.id, 'table block id in authoring snapshot').toBeTruthy();
  const capabilities = await expectApiData<CapabilityRegistry>(
    await page.request.get('/api/authoring/capabilities'),
    'load authoring capabilities',
  );
  const tableManifest = capabilities.manifests.find((manifest) => manifest.blockType === 'table');
  expect(tableManifest?.checksum, 'table manifest checksum').toBeTruthy();
  const current = readObjectPath(table!, propertyPath);
  return {
    blockId: String(table!.id),
    propertyPath,
    operation: current === undefined ? 'ADD' : 'REPLACE',
    value,
    manifestChecksum: tableManifest!.checksum,
  };
}

function findTableBlock(value: unknown): Record<string, unknown> | null {
  if (Array.isArray(value)) {
    for (const item of value) {
      const found = findTableBlock(item);
      if (found) return found;
    }
    return null;
  }
  if (!value || typeof value !== 'object') return null;
  const record = value as Record<string, unknown>;
  if (record.type === 'table' || record.blockType === 'table') return record;
  for (const child of Object.values(record)) {
    const found = findTableBlock(child);
    if (found) return found;
  }
  return null;
}

function readObjectPath(root: Record<string, unknown>, path: string): unknown {
  return path
    .split('/')
    .filter(Boolean)
    .reduce<unknown>((value, segment) => {
      if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
      return (value as Record<string, unknown>)[segment];
    }, root);
}

async function stageLocalDensityEdit(page: Page, session: AuthoringSession) {
  const table = findTableBlock(session.snapshot);
  expect(table?.id, 'table block id for local edit').toBeTruthy();
  const value = readObjectPath(table!, '/props/density') === 'compact' ? 'comfortable' : 'compact';
  await page.getByTestId('authoring-outline-open').click();
  await page.getByTestId(`authoring-outline-${String(table!.id)}`).click();
  await page.getByRole('button', { name: '关闭页面大纲' }).click();
  await page.getByTestId('authoring-inspector-open').click();
  const editor = page.getByTestId('authoring-property-/props/density').locator('input');
  await expect(editor).toBeVisible();
  await editor.fill(value);
  return { editor, value };
}

async function stageLocalTitleAndSpanEdits(
  page: Page,
  tableId: string,
  title: string,
  span: number,
) {
  await page.getByTestId('authoring-outline-open').click();
  await page.getByTestId(`authoring-outline-${tableId}`).click();
  await page.getByRole('button', { name: '关闭页面大纲' }).click();
  await page.getByTestId('authoring-inspector-open').click();
  const titleEditor = page.getByTestId('authoring-property-/title').locator('input');
  const spanEditor = page.getByTestId('authoring-property-/layout/span').locator('input');
  await expect(titleEditor).toBeVisible();
  await expect(spanEditor).toBeVisible();
  await titleEditor.fill(title);
  await spanEditor.fill(String(span));
  return { titleEditor, spanEditor };
}

function conflictCard(page: Page, label: string) {
  return page.locator('article[data-testid^="authoring-conflict-"]').filter({ hasText: label });
}

function distinctSpan(base: number, excluded: number[]): number {
  const candidate = [12, 10, 8, 6].find((value) => value !== base && !excluded.includes(value));
  if (candidate == null) throw new Error('unable to choose a distinct layout span');
  return candidate;
}

async function loadChangeItems(page: Page, sessionPid: string): Promise<ChangeItem[]> {
  return expectApiData<ChangeItem[]>(
    await page.request.get(`/api/authoring/sessions/${sessionPid}/change-items`),
    'load authoring ChangeItems',
  );
}

async function setControlledVisibility(
  page: Page,
  visibilityState: DocumentVisibilityState,
): Promise<void> {
  await page.evaluate((nextVisibilityState) => {
    const controlledWindow = window as Window & {
      __authoringTestVisibilityState?: DocumentVisibilityState;
    };
    controlledWindow.__authoringTestVisibilityState = nextVisibilityState;
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => controlledWindow.__authoringTestVisibilityState ?? 'visible',
    });
    document.dispatchEvent(new Event('visibilitychange'));
  }, visibilityState);
}

function apiPath(url: string): string {
  return new URL(url).pathname;
}
