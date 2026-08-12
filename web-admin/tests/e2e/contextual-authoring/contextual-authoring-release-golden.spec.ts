import { test, expect, type Browser, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { Client as PgClient } from 'pg';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { PG_CONN } from '../../helpers/environments';
import { loginViaUI } from '../../helpers/wd-fixtures';

const SOURCE_PAGE_KEY = 'e2et_record_list';
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
  pagePid?: string;
  ownerUserId?: string | number;
  state: string;
  revision: number;
  riskLevel?: string;
  publishPolicy?: string;
  validationState: string;
  validation?: {
    errorCount: number;
    issues: Array<{ code: string; blockId?: string; propertyPath: string }>;
  } | null;
  impactState: string;
  impact?: {
    failureCode?: string | null;
    dependencies: Array<{
      resourceType: string;
      resourceCode: string;
      resourcePid: string;
      rowVersion: number;
    }>;
  } | null;
  changeSetStatus: string;
  approvalState?: string;
  publishState: string;
  snapshot: Record<string, unknown>;
};

type PatchResult = {
  session: AuthoringSession;
};

type Release = {
  releasePid: string;
  changeSetPid: string;
  changeSetRevision: number;
  previousReleasePid: string | null;
  status: string;
  channelVersion: number;
};

type RuntimeTuple = {
  releasePid: string | null;
  channelVersion: number;
  cacheKey: string;
  density: string | undefined;
};

type GatePage = {
  pid: string;
  pageKey: string;
  route: string;
  title: string;
  recordMarker: string;
  menuId: string | number;
};

type PermissionRecord = { pid: string; code: string };
type RoleRecord = { pid: string; code: string };
type ReviewWorkspace = { session: AuthoringSession };
type CapabilityRegistry = {
  manifests: Array<{ blockType: string; checksum: string }>;
};
type ChangeItem = {
  changeItemPid: string;
  sourceChangeItemPid?: string | null;
  blockId: string;
  propertyPath: string;
  riskLevel: string;
};
type SplitResult = {
  sourceSession: AuthoringSession;
  targetSession: AuthoringSession;
  sourceItems: ChangeItem[];
  targetItems: ChangeItem[];
  lineage: Array<{ changeSetPid: string; revision: number; relation: string }>;
};
type AuditRow = {
  event_type: string;
  result: string;
  reason_code: string | null;
  metadata: Record<string, unknown>;
};

const REVIEWER = {
  roleCode: 'e2e_authoring_release_reviewer',
  roleName: 'Authoring release independent reviewer',
  email: 'e2e-authoring-release-reviewer@test.com',
  permissions: ['page.page.read', 'meta.publish.read', 'meta.publish.update'],
} as const;

const ADMIN_AUTHORING_PERMISSIONS = [
  'meta.designer.read',
  'meta.designer.update',
  'meta.designer.admin',
  'meta.publish.read',
  'meta.publish.update',
  'meta.publish.admin',
] as const;

let gatePage: GatePage;

test.use({ storageState: { cookies: [], origins: [] } });

test.describe('Contextual authoring release PC golden', () => {
  test.describe.configure({ mode: 'serial', timeout: 300_000 });

  test.beforeAll(async ({ browser }) => {
    gatePage = await createGatePageFromPublishedRuntime(browser);
  });

  test.beforeEach(async ({ page }) => {
    await login(page);
  });

  test.afterAll(async ({ browser }) => {
    if (gatePage) await cleanupGatePage(browser, gatePage);
  });

  test('PC-AUTH-017 @critical — failed publish stays retryable and release history rolls back atomically', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });

    const first = await publishDensityRevisionViaUi(page, false);
    expect(first.release.status).toBe('ACTIVE');
    await page.getByTestId('studio-governance-close').click();
    const sourceMenuLink = page.locator('nav').locator(`a[href="${gatePage.route}"]`).first();
    await expect(sourceMenuLink).toBeVisible();
    await sourceMenuLink.click();
    await expect(page).toHaveURL(new RegExp(`${gatePage.route}$`));
    await expect(page.getByRole('main').first().getByText(gatePage.recordMarker)).toBeVisible();

    const second = await publishDensityRevisionViaUi(page, true);
    expect(second.release.status).toBe('ACTIVE');
    expect(second.release.previousReleasePid).toBe(first.release.releasePid);
    expect(second.release.channelVersion).toBe(first.release.channelVersion + 1);

    const releaseHistory = page.getByTestId('authoring-release-history');
    await expect(releaseHistory.getByTestId('authoring-rollback-eligibility')).toContainText(
      '可回滚到 immediate previous Release',
    );
    const refreshResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' &&
        apiPath(response.url()) ===
          `/api/authoring/change-sets/${second.release.changeSetPid}/releases`,
    );
    await releaseHistory.getByTestId('authoring-release-refresh').click();
    expect((await refreshResponse).status()).toBe(200);
    await expect(
      releaseHistory.getByTestId(`authoring-release-${second.release.releasePid}`),
    ).toContainText('当前活动');
    await expect(releaseHistory.getByTestId('authoring-rollback-prepare')).toBeVisible();
    await releaseHistory.getByTestId('authoring-rollback-prepare').click();
    const rollbackConfirmation = releaseHistory.getByTestId('authoring-rollback-confirmation');
    await expect(rollbackConfirmation).toContainText('不会伪称撤销外部副作用');
    await expect(releaseHistory.getByTestId('authoring-rollback-confirm')).toBeDisabled();
    await rollbackConfirmation
      .getByLabel(/回滚原因/)
      .fill('PC 门禁：恢复 immediate previous Release');
    await expect(releaseHistory.getByTestId('authoring-rollback-confirm')).toBeEnabled();
    const rollbackResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        apiPath(response.url()) === `/api/authoring/releases/${second.release.releasePid}/rollback`,
    );
    await releaseHistory.getByTestId('authoring-rollback-confirm').click();
    const rolledBack = await expectApiData<Release>(
      await rollbackResponse,
      'rollback active release',
    );
    expect(rolledBack.releasePid).toBe(first.release.releasePid);
    expect(rolledBack.channelVersion).toBe(second.release.channelVersion + 1);
    await expect(releaseHistory.getByRole('status')).toContainText('活动 Release 已原子切换');
    await expect(
      releaseHistory.getByTestId(`authoring-release-${first.release.releasePid}`),
    ).toContainText('当前活动');
    await expect(
      releaseHistory.getByTestId(`authoring-release-${second.release.releasePid}`),
    ).toContainText('已回滚');
    await expect(releaseHistory.getByTestId('authoring-rollback-prepare')).toHaveCount(0);

    const runtimeAfterRollback = await loadRuntimeTuple(page);
    expect(runtimeAfterRollback.releasePid).toBe(first.release.releasePid);
    expect(runtimeAfterRollback.channelVersion).toBe(rolledBack.channelVersion);
    expect(runtimeAfterRollback.density).toBe(first.density);
    expect(runtimeAfterRollback.cacheKey).toContain(first.release.releasePid);

    await mkdir(SCREENSHOT_DIR, { recursive: true });
    await page.screenshot({
      path: resolve(SCREENSHOT_DIR, 'pc-auth-017-release-history-rollback.png'),
      fullPage: true,
    });

    const releaseAudits = [
      ...(await loadAuditRows(first.release.changeSetPid)),
      ...(await loadAuditRows(second.release.changeSetPid)),
    ];
    expect(releaseAudits.map((row) => row.event_type)).toEqual(
      expect.arrayContaining(['RELEASE_PUBLISHED', 'RELEASE_ROLLED_BACK']),
    );
    const rollbackAudit = releaseAudits.find((row) => row.event_type === 'RELEASE_ROLLED_BACK');
    expect(rollbackAudit?.metadata).toEqual(
      expect.objectContaining({
        activeReleasePid: first.release.releasePid,
        rolledBackReleasePid: second.release.releasePid,
      }),
    );
    expect(JSON.stringify(releaseAudits)).not.toContain(DEFAULT_TEST_ACCOUNT.password);
  });

  test('PC-AUTH-036 @critical — exact validation, L2 freeze, denial and owner withdrawal stay governed', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    const opened = await enterAuthoringFromMenu(page);
    const table = await selectTableInContextualInspector(page, opened);
    const filterEditor = page
      .getByTestId('authoring-property-/props/defaultFilter')
      .locator('textarea');
    const sensitiveInvalid = 'PC-AUTH-036-SENSITIVE-FILTER';
    await expect(filterEditor).toBeVisible();
    await filterEditor.fill(JSON.stringify(sensitiveInvalid));
    const invalidSave = page.waitForResponse(
      (response) =>
        response.request().method() === 'PATCH' &&
        apiPath(response.url()) === `/api/authoring/sessions/${opened.sessionPid}/patches`,
    );
    await page.getByRole('button', { name: '保存', exact: true }).click();
    const invalidSaved = await expectApiData<PatchResult>(await invalidSave, 'save invalid filter');
    expect(invalidSaved.session.riskLevel).toBe('L2');
    expect(invalidSaved.session.publishPolicy).toBe('REQUIRED_REVIEW');
    await expect(page.getByTestId('authoring-risk-summary')).toContainText('L2');
    await expect(page.getByTestId('authoring-risk-summary')).toContainText('强制评审');

    const invalidPrepare = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        apiPath(response.url()) === `/api/authoring/sessions/${opened.sessionPid}/prepare`,
    );
    await page.getByRole('button', { name: '校验与影响分析', exact: true }).click();
    const invalid = await expectApiData<AuthoringSession>(
      await invalidPrepare,
      'validate invalid exact revision',
    );
    expect(invalid.validationState).toBe('INVALID');
    expect(invalid.state).toBe('ACTIVE');
    expect(invalid.validation?.issues).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          code: 'DEFAULT_FILTER_INVALID',
          blockId: String(table.id),
          propertyPath: '/props/defaultFilter',
        }),
      ]),
    );
    const validationNotice = page.getByTestId('authoring-validation-notice');
    await expect(validationNotice).toContainText('草稿已保存');
    await expect(page.getByTestId('authoring-validation-issues')).toContainText(
      'code: DEFAULT_FILTER_INVALID',
    );
    await expect(page.getByRole('button', { name: '提交评审', exact: true })).toHaveCount(0);

    await filterEditor.fill(JSON.stringify({ status: 'OPEN' }));
    const validSave = page.waitForResponse(
      (response) =>
        response.request().method() === 'PATCH' &&
        apiPath(response.url()) === `/api/authoring/sessions/${opened.sessionPid}/patches`,
    );
    await page.getByRole('button', { name: '保存', exact: true }).click();
    const fixed = await expectApiData<PatchResult>(await validSave, 'save structured filter');
    expect(fixed.session.validationState).toBe('UNVALIDATED');
    const validPrepare = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        apiPath(response.url()) === `/api/authoring/sessions/${opened.sessionPid}/prepare`,
    );
    await page.getByRole('button', { name: '校验与影响分析', exact: true }).click();
    const prepared = await expectApiData<AuthoringSession>(
      await validPrepare,
      'validate and analyze L2 exact revision',
    );
    expect(prepared.validationState).toBe('VALID');
    expect(prepared.impactState).toBe('KNOWN');
    expect(prepared.revision).toBe(fixed.session.revision);
    await expect(page.getByRole('button', { name: '提交评审', exact: true })).toBeEnabled();

    const submitResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        apiPath(response.url()) === `/api/authoring/sessions/${opened.sessionPid}/submit`,
    );
    await page.getByRole('button', { name: '提交评审', exact: true }).click();
    expect((await submitResponse).status()).toBe(200);
    const frozen = await loadSession(page, opened.sessionPid, 'load frozen L2 revision');
    expect(frozen.changeSetStatus).toBe('IN_REVIEW');
    expect(frozen.state).toBe('READ_ONLY');
    expect(frozen.revision).toBe(prepared.revision);
    await expect(page.getByTestId('authoring-governance-notice')).toContainText(
      `revision r${prepared.revision} 已冻结`,
    );
    await expect(filterEditor).toBeDisabled();
    await expect(page.getByTestId('authoring-governance-approve')).toHaveCount(0);

    const ownerDenied = await page.request.post(
      `/api/authoring/change-sets/${opened.changeSetPid}/approve`,
      {
        data: {
          expectedRevision: prepared.revision,
          reason: 'PC-AUTH-036-SELF-APPROVAL-SENSITIVE',
        },
      },
    );
    expect(ownerDenied.status(), 'owner must not approve their own revision').toBe(403);
    const afterDenial = await loadSession(page, opened.sessionPid, 'reload after self denial');
    expect(afterDenial.changeSetStatus).toBe('IN_REVIEW');
    expect(afterDenial.approvalState).toBe('PENDING');

    await page.getByTestId('authoring-governance-reason').fill('PC 门禁：撤回后补充异常状态说明');
    const withdrawResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        apiPath(response.url()) === `/api/authoring/sessions/${opened.sessionPid}/review/withdraw`,
    );
    await page.getByTestId('authoring-governance-withdraw').click();
    expect((await withdrawResponse).status()).toBe(200);
    await expect(filterEditor).toBeEnabled();
    const withdrawn = await loadSession(page, opened.sessionPid, 'reload withdrawn revision');
    expect(withdrawn.changeSetStatus).toBe('DRAFT');
    expect(withdrawn.state).toBe('ACTIVE');
    expect(withdrawn.revision).toBe(prepared.revision + 1);
    expect(withdrawn.validationState).toBe('UNVALIDATED');
    expect(withdrawn.approvalState).toBe('STALE');

    const audits = await loadAuditRows(opened.changeSetPid);
    expect(audits.map((row) => `${row.event_type}:${row.result}:${row.reason_code ?? ''}`)).toEqual(
      expect.arrayContaining([
        expect.stringContaining('SESSION_OPENED:ALLOW'),
        expect.stringContaining('PATCH_SAVED:ALLOW'),
        'CHANGE_SET_VALIDATION_FAILED:DENY:REVISION_INVALID',
        'CHANGE_SET_VALIDATED:ALLOW:REVISION_VALID',
        'CHANGE_SET_IMPACT_KNOWN:ALLOW:DEPENDENCIES_RESOLVED',
        'CHANGE_SET_SUBMITTED:ALLOW:REVIEW_REQUIRED',
        'CHANGE_SET_APPROVAL_DENIED:DENY:FOUR_EYES_REQUIRED',
        'CHANGE_SET_REVIEW_WITHDRAWN:ALLOW:OWNER_RESUMED_EDITING',
      ]),
    );
    const auditJson = JSON.stringify(audits);
    expect(auditJson).not.toContain(sensitiveInvalid);
    expect(auditJson).not.toContain('PC-AUTH-036-SELF-APPROVAL-SENSITIVE');
    expect(auditJson).not.toContain(DEFAULT_TEST_ACCOUNT.password);

    await mkdir(SCREENSHOT_DIR, { recursive: true });
    await page.screenshot({
      path: resolve(SCREENSHOT_DIR, 'pc-auth-036-withdrawn-editable.png'),
      fullPage: true,
    });
  });

  test('PC-AUTH-037 @critical — real dependency timeout and drift fail closed with a retryable Studio state', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    const opened = await enterAuthoringFromMenu(page);
    await stageDensityEdit(page, opened);
    const saveResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'PATCH' &&
        apiPath(response.url()) === `/api/authoring/sessions/${opened.sessionPid}/patches`,
    );
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await expectApiData<PatchResult>(await saveResponse, 'save timeout gate revision');
    await enterStudioFromContextual(page);

    const blocker = new PgClient(PG_CONN);
    await blocker.connect();
    try {
      await blocker.query('BEGIN');
      await blocker.query('LOCK TABLE ab_meta_model IN ACCESS EXCLUSIVE MODE');
      const timeoutResponse = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) === `/api/authoring/sessions/${opened.sessionPid}/prepare`,
      );
      await page.getByTestId('studio-prepare-submit').click();
      const timedOut = await expectApiData<AuthoringSession>(
        await timeoutResponse,
        'real dependency analysis timeout',
      );
      expect(timedOut.validationState).toBe('VALID');
      expect(timedOut.impactState).toBe('FAILED');
      expect(timedOut.impact?.failureCode).toBe('ANALYSIS_TIMEOUT');
      await expect(page.getByTestId('authoring-impact-notice')).toContainText(
        '影响分析失败，不能提交评审或发布',
      );
      await expect(page.getByTestId('authoring-impact-notice')).toContainText(
        'code: ANALYSIS_TIMEOUT',
      );
      await expect(page.getByTestId('studio-prepare-submit')).toHaveText('校验与影响分析');
      await blocker.query('ROLLBACK');
    } finally {
      await blocker.query('ROLLBACK').catch(() => {});
      await blocker.end();
    }

    const retryResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        apiPath(response.url()) === `/api/authoring/sessions/${opened.sessionPid}/prepare`,
    );
    await page.getByTestId('studio-prepare-submit').click();
    const prepared = await expectApiData<AuthoringSession>(
      await retryResponse,
      'retry dependency analysis after lock release',
    );
    expect(prepared.validationState).toBe('VALID');
    expect(prepared.impactState).toBe('KNOWN');
    const modelDependency = prepared.impact?.dependencies.find(
      (dependency) => dependency.resourceType === 'MODEL',
    );
    expect(modelDependency?.resourcePid, 'prepared model dependency').toBeTruthy();
    await expect(page.getByTestId('authoring-impact-notice')).toHaveCount(0);
    await expect(page.getByTestId('studio-prepare-submit')).toHaveText('提交评审');

    const restoreDependency = await driftModelBinding(modelDependency!.resourcePid);
    try {
      const staleResponse = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) === `/api/authoring/sessions/${opened.sessionPid}/submit`,
      );
      await page.getByTestId('studio-prepare-submit').click();
      const stale = await staleResponse;
      expect(stale.status(), 'dependency drift must reject exact revision submission').toBe(409);
      await expect(page.getByTestId('authoring-impact-notice')).toContainText(
        '依赖已变化，当前校验与影响结果已失效',
      );
      await expect(page.getByTestId('studio-prepare-submit')).toBeDisabled();
      const authoritative = await loadSession(page, opened.sessionPid, 'load stale session');
      expect(authoritative.validationState).toBe('STALE');
      expect(authoritative.impactState).toBe('STALE');
      expect(authoritative.changeSetStatus).toBe('DRAFT');

      await mkdir(SCREENSHOT_DIR, { recursive: true });
      await page.screenshot({
        path: resolve(SCREENSHOT_DIR, 'pc-auth-037-impact-stale.png'),
        fullPage: true,
      });
    } finally {
      await restoreDependency();
    }

    const audits = await loadAuditRows(opened.changeSetPid);
    expect(audits.map((row) => `${row.event_type}:${row.result}:${row.reason_code ?? ''}`)).toEqual(
      expect.arrayContaining([
        'CHANGE_SET_IMPACT_FAILED:DENY:ANALYSIS_TIMEOUT',
        'CHANGE_SET_IMPACT_KNOWN:ALLOW:DEPENDENCIES_RESOLVED',
      ]),
    );
  });

  test('PC-AUTH-038 @critical — L0 and L3 changes split through Studio with durable lineage', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    const opened = await enterAuthoringFromMenu(page);
    const table = findTableBlock(opened.snapshot);
    expect(table?.id, 'table for L0/L3 split').toBeTruthy();
    await stageDensityEdit(page, opened);
    const lowRiskSave = page.waitForResponse(
      (response) =>
        response.request().method() === 'PATCH' &&
        apiPath(response.url()) === `/api/authoring/sessions/${opened.sessionPid}/patches`,
    );
    await page.getByRole('button', { name: '保存', exact: true }).click();
    const lowRisk = await expectApiData<PatchResult>(await lowRiskSave, 'save L0 density change');
    expect(lowRisk.session.riskLevel).toBe('L0');
    await enterStudioFromContextual(page);
    await expect(page.getByTestId('inspector-selected-id')).toContainText(String(table!.id));
    const modelEditor = page.getByTestId('inspector-field-dataSource.model-manual');
    await expect(modelEditor).toBeVisible();
    const currentModel = String(readObjectPath(table!, '/dataSource/model') ?? 'e2et_record');
    const l3Model = currentModel === 'payments' ? 'e2et_record' : 'payments';
    await modelEditor.fill(l3Model);
    await expect(page.getByTestId('designer-dirty-state')).toContainText('未保存');
    const highRiskSave = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        apiPath(response.url()) === `/api/authoring/sessions/${opened.sessionPid}/studio-batches`,
    );
    await page.getByTestId('designer-save').click();
    const highRisk = await expectApiData<PatchResult>(await highRiskSave, 'save L3 data source');
    expect(highRisk.session.riskLevel).toBe('L3');
    expect(highRisk.session.publishPolicy).toBe('STUDIO_APPROVAL');
    await expect(page.getByTestId('authoring-risk-summary').first()).toContainText('L3');

    await page.getByTestId('studio-governance-open').click();
    const splitPanel = page.getByTestId('authoring-split-panel');
    await expect(splitPanel).toBeVisible();
    await splitPanel.locator('summary').click();
    await expect(splitPanel).toContainText('2 项');
    const l3Item = splitPanel.locator('label', { hasText: 'L3' }).locator('input[type="checkbox"]');
    await expect(l3Item).toHaveCount(1);
    await l3Item.check();
    await page.getByTestId('authoring-split-title').fill('PC L3 数据源独立评审');
    await page
      .getByTestId('authoring-split-reason')
      .fill('PC 门禁：L0 密度与 L3 数据源没有跨分组依赖');
    const splitResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        apiPath(response.url()) === `/api/authoring/sessions/${opened.sessionPid}/split`,
    );
    await page.getByTestId('authoring-split-submit').click();
    const split = await expectApiData<SplitResult>(await splitResponse, 'split L0 and L3 changes');
    expect(split.sourceSession.changeSetPid).toBe(opened.changeSetPid);
    expect(split.sourceSession.riskLevel).toBe('L0');
    expect(split.sourceSession.publishPolicy).toBe('DIRECT_ALLOWED');
    expect(split.targetSession.riskLevel).toBe('L3');
    expect(split.targetSession.publishPolicy).toBe('STUDIO_APPROVAL');
    expect(split.sourceItems).toEqual([
      expect.objectContaining({ propertyPath: '/props/density', riskLevel: 'L0' }),
    ]);
    expect(split.targetItems).toEqual([
      expect.objectContaining({
        sourceChangeItemPid: expect.any(String),
        propertyPath: '/dataSource',
        riskLevel: 'L3',
      }),
    ]);
    expect(split.lineage).toEqual([
      expect.objectContaining({
        changeSetPid: opened.changeSetPid,
        relation: 'SPLIT_FROM',
      }),
    ]);
    await expect(page.getByTestId('authoring-split-success')).toContainText('已创建新的 ChangeSet');
    const targetLink = page.getByTestId('authoring-split-target-link');
    await expect(targetLink).toHaveAttribute(
      'href',
      `/unified-designer?authoringSession=${split.targetSession.sessionPid}`,
    );
    await assertSplitPersistence(split);

    await mkdir(SCREENSHOT_DIR, { recursive: true });
    await page.screenshot({
      path: resolve(SCREENSHOT_DIR, 'pc-auth-038-split-success.png'),
      fullPage: true,
    });
    await targetLink.click();
    await expect(page).toHaveURL(
      new RegExp(`authoringSession=${encodeURIComponent(split.targetSession.sessionPid)}`),
    );
    await expect(page.getByTestId('authoring-risk-summary').first()).toContainText('L3');
  });

  test('PC-AUTH-039 @critical — reviewer rejection and approval reopening each create a new editable revision', async ({
    page,
    browser,
  }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    await ensureReviewer(page);
    const opened = await enterAuthoringFromMenu(page);
    await selectTableInContextualInspector(page, opened);
    const filterEditor = page
      .getByTestId('authoring-property-/props/defaultFilter')
      .locator('textarea');
    await filterEditor.fill(JSON.stringify({ status: 'OPEN', owner: 'CURRENT' }));
    const saveResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'PATCH' &&
        apiPath(response.url()) === `/api/authoring/sessions/${opened.sessionPid}/patches`,
    );
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await expectApiData<PatchResult>(await saveResponse, 'save review lifecycle filter');
    await enterStudioFromContextual(page);
    const firstPrepared = await prepareAndSubmitInStudio(page, opened.sessionPid);
    expect(firstPrepared.riskLevel).toBe('L2');

    const reviewer = await openReviewer(browser);
    try {
      await openReviewWorkspace(reviewer, opened.changeSetPid, firstPrepared.revision);
      await reviewer
        .getByTestId('authoring-governance-reason')
        .fill('PC 门禁：默认筛选会隐藏需要人工复核的订单');
      const rejectResponse = reviewer.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) === `/api/authoring/change-sets/${opened.changeSetPid}/reject`,
      );
      await reviewer.getByTestId('authoring-governance-reject').click();
      expect((await rejectResponse).status()).toBe(200);

      await page.reload({ waitUntil: 'domcontentloaded' });
      await expect(page.getByTestId('unified-designer-workbench')).toBeVisible();
      await expect(page.getByTestId('authoring-governance-notice')).toContainText(
        '评审已驳回 · 已进入可编辑 revision',
      );
      await expect(page.getByTestId('studio-handoff-editable-reason')).toBeVisible();
      await expect(page.getByTestId('studio-submission-notice')).toBeVisible();
      await expect(page.getByTestId('studio-prepare-submit')).toBeEnabled();
      await expect(page.getByTestId('designer-contextual-read-only')).toHaveCount(0);
      const rejected = await loadSession(page, opened.sessionPid, 'load rejected owner revision');
      expect(rejected.changeSetStatus).toBe('REJECTED');
      expect(rejected.state).toBe('ACTIVE');
      expect(rejected.revision).toBe(firstPrepared.revision + 1);
      expect(rejected.approvalState).toBe('REJECTED');

      const secondPrepared = await prepareAndSubmitInStudio(page, opened.sessionPid);
      await openReviewWorkspace(reviewer, opened.changeSetPid, secondPrepared.revision);
      await reviewer
        .getByTestId('authoring-governance-reason')
        .fill('PC 门禁：补充说明后批准当前精确 revision');
      const approveResponse = reviewer.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) === `/api/authoring/change-sets/${opened.changeSetPid}/approve`,
      );
      await reviewer.getByTestId('authoring-governance-approve').click();
      expect((await approveResponse).status()).toBe(200);

      await page.reload({ waitUntil: 'domcontentloaded' });
      await expect(page.getByRole('button', { name: /当前状态：APPROVED/ })).toBeVisible();
      await page.getByTestId('studio-governance-open').click();
      const governance = page.getByTestId('studio-governance-drawer');
      await governance
        .getByTestId('authoring-governance-reason')
        .fill('PC 门禁：批准后发现还需补充页面说明');
      const reopenResponse = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) ===
            `/api/authoring/sessions/${opened.sessionPid}/approved/reopen`,
      );
      await governance.getByTestId('authoring-governance-reopen').click();
      expect((await reopenResponse).status()).toBe(200);
      await expect(page.getByTestId('studio-submission-notice')).toBeVisible();
      await expect(page.getByTestId('studio-prepare-submit')).toBeEnabled();
      await expect(page.getByTestId('studio-handoff-editable-reason')).toBeVisible();
      await expect(page.getByTestId('designer-contextual-read-only')).toHaveCount(0);
      const reopened = await loadSession(page, opened.sessionPid, 'load reopened owner revision');
      expect(reopened.changeSetStatus).toBe('DRAFT');
      expect(reopened.state).toBe('ACTIVE');
      expect(reopened.revision).toBe(secondPrepared.revision + 1);
      expect(reopened.approvalState).toBe('STALE');
      expect(reopened.validationState).toBe('UNVALIDATED');

      const audits = await loadAuditRows(opened.changeSetPid);
      expect(audits.map((row) => row.event_type)).toEqual(
        expect.arrayContaining([
          'CHANGE_SET_REJECTED',
          'CHANGE_SET_APPROVED',
          'CHANGE_SET_APPROVAL_INVALIDATED',
        ]),
      );
      await mkdir(SCREENSHOT_DIR, { recursive: true });
      await page.screenshot({
        path: resolve(SCREENSHOT_DIR, 'pc-auth-039-approved-reopened.png'),
        fullPage: true,
      });
    } finally {
      await reviewer.context().close();
    }
  });

  test('PC-AUTH-028 @critical — governed new page stays private until independent review and atomic publish', async ({
    page,
    browser,
  }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    await ensureReviewer(page);
    const source = await enterAuthoringFromMenu(page);
    await page.getByRole('button', { name: '新页面 / 菜单' }).click();
    const explain = page.getByRole('dialog', { name: '在应用设计中心创建页面' });
    await expect(explain).toContainText('新页面会改变页面树、路由和发布资源');
    await explain.getByRole('button', { name: '继续到应用设计中心' }).click();

    const wizard = page.getByTestId('new-page-workspace-wizard');
    await expect(wizard).toBeVisible();
    const suffix = `${Date.now().toString(36)}_${process.pid}`;
    const pageKey = `authoring_new_gate_${suffix}`;
    const route = `/${pageKey.replaceAll('_', '-')}`;
    const title = `Governed New Page ${suffix}`;
    await wizard.getByLabel('页面标题').fill(title);
    await wizard.getByLabel('页面标识').fill(pageKey);
    await wizard.getByLabel('业务模型').selectOption('e2et_record');
    const parentSelect = wizard.getByLabel('父菜单');
    await expect.poll(() => parentSelect.locator('option').count()).toBeGreaterThan(1);
    await parentSelect.selectOption(
      (await parentSelect.locator('option').nth(1).getAttribute('value'))!,
    );
    await wizard.getByLabel('访问权限').selectOption('page.page.read');

    const createdResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        apiPath(response.url()) ===
          `/api/authoring/sessions/${source.sessionPid}/new-page-workspaces`,
    );
    await wizard.getByRole('button', { name: '创建并进入页面设计' }).click();
    const created = await expectApiData<AuthoringSession>(
      await createdResponse,
      'create governed new-page workspace',
    );
    expect(created.riskLevel).toBe('L3');
    expect(created.publishPolicy).toBe('STUDIO_APPROVAL');
    expect(created.pagePid).toBeTruthy();
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible();

    const runtimeProbe = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    const probePage = await runtimeProbe.newPage();
    await login(probePage);
    try {
      await expect(page.locator('nav').locator(`a[href="${route}"]`)).toHaveCount(0);
      const unpublished = await probePage.request.get(`/api/pages/key/${pageKey}`);
      expect(unpublished.status(), 'new PageSchema must not exist before publish').toBe(404);
      const missingMenu = await expectApiData<unknown>(
        await probePage.request.get(`/api/menu/by-path?path=${encodeURIComponent(route)}`),
        'probe unpublished route menu',
      );
      expect(missingMenu).toBeNull();
      await probePage.goto(route, { waitUntil: 'domcontentloaded' });
      await expect(probePage.getByText(gatePage.recordMarker)).toHaveCount(0);

      await page.getByTestId('resource-tab-blocks').click();
      await expect(page.getByTestId('palette-add-table')).toBeEnabled();
      await page.getByTestId('palette-add-table').click();
      await expect(page.getByTestId('designer-dirty-state')).toContainText('未保存');
      await page.getByTestId('resource-tab-fields').click();
      await expect(page.getByTestId('model-field-e2et_name')).toBeEnabled();
      await page.getByTestId('model-field-e2et_name').click();
      await expect(page.getByTestId('model-field-e2et_name')).toHaveAttribute('data-used', 'true');

      const saveResponse = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) ===
            `/api/authoring/sessions/${created.sessionPid}/studio-batches`,
      );
      await page.getByTestId('designer-save').click();
      const saved = await expectApiData<PatchResult>(await saveResponse, 'save new-page design');
      await expect(page.getByTestId('designer-dirty-state')).toContainText('已保存');

      const prepareResponse = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) === `/api/authoring/sessions/${created.sessionPid}/prepare`,
      );
      await page.getByTestId('studio-prepare-submit').click();
      const prepared = await expectApiData<AuthoringSession>(
        await prepareResponse,
        'prepare new page exact revision',
      );
      expect(prepared.revision).toBe(saved.session.revision);
      await expect(page.getByTestId('studio-prepare-submit')).toHaveText('提交评审');
      const submitResponse = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) === `/api/authoring/sessions/${created.sessionPid}/submit`,
      );
      await page.getByTestId('studio-prepare-submit').click();
      const submitted = await expectApiData<{
        status: string;
        publishState: string;
      }>(await submitResponse, 'submit new page for independent review');
      expect(submitted.status).toBe('IN_REVIEW');

      const reviewer = await openReviewer(browser);
      try {
        const reviewResponse = reviewer.waitForResponse(
          (response) =>
            response.request().method() === 'POST' &&
            apiPath(response.url()) ===
              `/api/authoring/change-sets/${created.changeSetPid}/review-workspaces`,
        );
        await reviewer.goto(
          `/unified-designer?reviewChangeSetId=${encodeURIComponent(created.changeSetPid)}`,
          { waitUntil: 'domcontentloaded' },
        );
        const review = await expectApiData<ReviewWorkspace>(
          await reviewResponse,
          'open bounded new-page review workspace',
        );
        expect(review.session.revision).toBe(prepared.revision);
        await reviewer
          .getByTestId('authoring-governance-reason')
          .fill('PC 门禁：独立审核受治理新页面');
        const approvalResponse = reviewer.waitForResponse(
          (response) =>
            response.request().method() === 'POST' &&
            apiPath(response.url()) ===
              `/api/authoring/change-sets/${created.changeSetPid}/approve`,
        );
        await reviewer.getByTestId('authoring-governance-approve').click();
        await expectApiData(await approvalResponse, 'approve governed new page');
      } finally {
        await reviewer.context().close();
      }

      const published = await expectApiData<Release>(
        await page.request.post(`/api/authoring/change-sets/${created.changeSetPid}/publish`, {
          data: { expectedRevision: prepared.revision },
        }),
        'publish independently approved new page',
      );
      expect(published.status).toBe('ACTIVE');
      expect(published.channelVersion).toBe(1);

      await probePage.goto('/', { waitUntil: 'domcontentloaded' });
      const newMenu = probePage.locator('nav').locator(`a[href="${route}"]`).first();
      await expect(newMenu).toBeVisible({ timeout: 15_000 });
      await newMenu.click();
      await expect(probePage).toHaveURL(new RegExp(`${route}$`));
      const runtime = probePage.getByRole('main').first();
      await expect(runtime.getByRole('heading', { name: title })).toBeVisible();
      await expect(runtime.getByText(gatePage.recordMarker)).toBeVisible();
      const deployedPage = await expectApiData<Record<string, unknown>>(
        await probePage.request.get(`/api/pages/key/${pageKey}`),
        'read deployed new page release identity',
      );
      expect((deployedPage.runtime as Record<string, unknown>).source).toBe('AUTHORING_RELEASE');
      expect((deployedPage.runtime as Record<string, unknown>).channelVersion).toBe(1);

      const noModelReadReviewer = await openReviewer(browser);
      try {
        const denied = await noModelReadReviewer.request.get(
          '/api/dynamic/e2et_record/list?pageNum=1&pageSize=20',
        );
        expect(denied.status(), 'page permission must not imply model read').toBe(403);
        await noModelReadReviewer.goto(route, { waitUntil: 'domcontentloaded' });
        await expect(noModelReadReviewer.getByText(gatePage.recordMarker)).toHaveCount(0);
      } finally {
        await noModelReadReviewer.context().close();
      }

      await mkdir(SCREENSHOT_DIR, { recursive: true });
      await probePage.screenshot({
        path: resolve(SCREENSHOT_DIR, 'pc-auth-028-new-page-published.png'),
        fullPage: true,
      });
    } finally {
      await runtimeProbe.close();
      await cleanupMaterializedNewPage(pageKey);
    }
  });
});

async function createGatePageFromPublishedRuntime(browser: Browser): Promise<GatePage> {
  const recordMarker = await seedReleaseGateRecord();
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();
  try {
    await login(page);
    const source = await expectApiData<Record<string, unknown>>(
      await page.request.get(`/api/pages/key/${SOURCE_PAGE_KEY}`),
      'load published source page',
    );
    const suffix = `${Date.now().toString(36)}_${process.pid}`;
    const menuId = Date.now();
    const pageKey = `authoring_release_ui_gate_${suffix}`;
    const route = `/authoring-release-ui-gate-${suffix.replaceAll('_', '-')}`;
    const title = `Authoring Release UI Gate ${suffix}`;
    const created = await expectApiData<{ pid: string }>(
      await page.request.post('/api/pages', {
        data: {
          pageKey,
          modelCode: source.modelCode,
          name: title,
          title,
          description: 'Persistent browser evidence for governed release and rollback',
          kind: source.kind,
          profile: source.profile,
          layout: source.layout ?? {},
          blocks: source.blocks,
          schemaVersion: source.schemaVersion,
          isTemplate: false,
          sortWeight: 9999,
          semver: '1.0.0',
        },
      }),
      'create release UI gate page',
    );
    await expectApiData(
      await page.request.post(`/api/pages/${created.pid}/publish`),
      'publish release UI gate page',
    );
    const menu = await expectApiData<{ id: string | number }>(
      await page.request.post('/api/menu/create', {
        data: {
          id: menuId,
          pid: `menu_${suffix}`,
          code: pageKey,
          name: title,
          path: route,
          component: 'dynamic-page',
          type: 1,
          permissionCode: 'page.page.read',
          visible: true,
          orderNo: 9999,
          pageKey,
          pagePid: created.pid,
          status: 'active',
          deletedFlag: false,
        },
      }),
      'mount release UI gate page in the menu',
    );
    expect(Number(menu.id), 'safe release UI gate menu id').toBe(menuId);
    return { pid: created.pid, pageKey, route, title, recordMarker, menuId };
  } finally {
    await context.close();
  }
}

async function cleanupGatePage(browser: Browser, target: GatePage): Promise<void> {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();
  try {
    await login(page);
    await expectApiData(
      await page.request.delete(`/api/menu/${encodeURIComponent(String(target.menuId))}`),
      'delete release UI gate menu',
    );
    await expectApiData(
      await page.request.post(`/api/pages/${encodeURIComponent(target.pid)}/unpublish`),
      'unpublish release UI gate page',
    );
    await expectApiData(
      await page.request.delete(`/api/pages/${encodeURIComponent(target.pid)}`),
      'delete release UI gate page',
    );
  } finally {
    await context.close();
    const client = new PgClient(PG_CONN);
    await client.connect();
    try {
      await client.query('DELETE FROM mt_e2et_record WHERE e2et_name = $1', [target.recordMarker]);
      const residue = await client.query<{ active_menus: string; active_pages: string }>(
        `SELECT
           (SELECT COUNT(*)::text FROM ab_menu
             WHERE code = $1 AND deleted_flag = FALSE) AS active_menus,
           (SELECT COUNT(*)::text FROM ab_page_schema
             WHERE page_key = $1 AND deleted_flag = FALSE) AS active_pages`,
        [target.pageKey],
      );
      expect(residue.rows[0]).toEqual({ active_menus: '0', active_pages: '0' });
    } finally {
      await client.end();
    }
  }
}

async function ensureReviewer(adminPage: Page): Promise<void> {
  const roles = await expectApiData<RoleRecord[]>(
    await adminPage.request.get('/api/roles/all'),
    'load roles for release reviewer',
  );
  let role = roles.find((candidate) => candidate.code === REVIEWER.roleCode);
  if (!role) {
    role = await expectApiData<RoleRecord>(
      await adminPage.request.post('/api/roles', {
        data: {
          code: REVIEWER.roleCode,
          name: REVIEWER.roleName,
          description: 'PC release golden persona: independent reviewer without model read',
          type: 'custom',
        },
      }),
      'create release reviewer role',
    );
  }

  const permissions = (
    await Promise.all(
      ['function', 'operation', 'data', 'model'].map(async (resourceType) =>
        expectApiData<PermissionRecord[]>(
          await adminPage.request.get(`/api/permissions/resource-type/${resourceType}`),
          `load ${resourceType} permissions for release reviewer`,
        ),
      ),
    )
  ).flat();
  const byCode = new Map(permissions.map((permission) => [permission.code, permission.pid]));
  const missing = REVIEWER.permissions.filter((code) => !byCode.has(code));
  expect(missing, `missing release reviewer permissions: ${missing.join(', ')}`).toEqual([]);
  await expectApiData<boolean>(
    await adminPage.request.post(`/api/roles/${role.pid}/permissions`, {
      data: REVIEWER.permissions.map((code) => byCode.get(code)!),
    }),
    'assign release reviewer permissions',
  );

  const loginProbe = await adminPage.request.post('/api/auth/login', {
    data: { email: REVIEWER.email, password: DEFAULT_TEST_ACCOUNT.password },
  });
  if (!loginProbe.ok()) {
    await expectApiData(
      await adminPage.request.post('/api/admin/users', {
        data: {
          email: REVIEWER.email,
          displayName: REVIEWER.roleName,
          initialPassword: DEFAULT_TEST_ACCOUNT.password,
          roleCodes: [REVIEWER.roleCode],
          sendInviteEmail: false,
        },
      }),
      'create release reviewer user',
    );
  }
  const members = await expectApiData<Array<{ email?: string; memberPid?: string }>>(
    await adminPage.request.get(
      `/api/org/members/unlinked?keyword=${encodeURIComponent(REVIEWER.email)}`,
    ),
    'resolve release reviewer member',
  );
  const member = members.find((candidate) => candidate.email === REVIEWER.email);
  expect(member?.memberPid, 'release reviewer member pid').toBeTruthy();
  await expectApiData<boolean>(
    await adminPage.request.post('/api/user-roles/assign-by-code', {
      data: { memberPid: member!.memberPid, roleCodes: [REVIEWER.roleCode] },
    }),
    'activate release reviewer role',
  );
}

async function openReviewer(browser: Browser): Promise<Page> {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();
  await loginViaUI(page, REVIEWER.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page.locator('nav')).toBeVisible({ timeout: 15_000 });
  return page;
}

async function enterStudioFromContextual(page: Page): Promise<void> {
  await page.getByRole('button', { name: '高级设置', exact: true }).click();
  const explain = page.getByRole('dialog', { name: '进入应用设计中心' });
  await expect(explain).toContainText('当前 ChangeSet、选择对象、返回位置');
  await explain.getByRole('button', { name: '继续到应用设计中心' }).click();
  await expect(page).toHaveURL(/\/unified-designer\?authoringSession=/);
  await expect(page.getByTestId('unified-designer-workbench')).toBeVisible();
}

async function selectTableInContextualInspector(
  page: Page,
  session: AuthoringSession,
): Promise<Record<string, unknown>> {
  const table = findTableBlock(session.snapshot);
  expect(table?.id, 'table block in contextual authoring gate').toBeTruthy();
  await page.getByTestId('authoring-outline-open').click();
  await page.getByTestId(`authoring-outline-${String(table!.id)}`).click();
  await page.getByRole('button', { name: '关闭页面大纲' }).click();
  await page.getByTestId('authoring-inspector-open').click();
  return table!;
}

async function prepareAndSubmitInStudio(page: Page, sessionPid: string): Promise<AuthoringSession> {
  const prepareResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      apiPath(response.url()) === `/api/authoring/sessions/${sessionPid}/prepare`,
  );
  await page.getByTestId('studio-prepare-submit').click();
  const prepared = await expectApiData<AuthoringSession>(
    await prepareResponse,
    'prepare exact governed Studio revision',
  );
  expect(prepared.validationState).toBe('VALID');
  expect(prepared.impactState).toBe('KNOWN');
  await expect(page.getByTestId('studio-prepare-submit')).toHaveText('提交评审');
  const submitResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      apiPath(response.url()) === `/api/authoring/sessions/${sessionPid}/submit`,
  );
  await page.getByTestId('studio-prepare-submit').click();
  expect((await submitResponse).status()).toBe(200);
  await expect(page.getByRole('button', { name: /当前状态：IN_REVIEW/ })).toBeVisible();
  return prepared;
}

async function openReviewWorkspace(
  page: Page,
  changeSetPid: string,
  expectedRevision: number,
): Promise<ReviewWorkspace> {
  const response = page.waitForResponse(
    (candidate) =>
      candidate.request().method() === 'POST' &&
      apiPath(candidate.url()) === `/api/authoring/change-sets/${changeSetPid}/review-workspaces`,
  );
  await page.goto(`/unified-designer?reviewChangeSetId=${encodeURIComponent(changeSetPid)}`, {
    waitUntil: 'domcontentloaded',
  });
  const workspace = await expectApiData<ReviewWorkspace>(await response, 'open review workspace');
  expect(workspace.session.revision).toBe(expectedRevision);
  await expect(page.getByTestId('unified-designer-workbench')).toBeVisible();
  await expect(page.getByTestId('authoring-governance-approve')).toBeVisible();
  return workspace;
}

async function loadSession(
  page: Page,
  sessionPid: string,
  label: string,
): Promise<AuthoringSession> {
  return expectApiData<AuthoringSession>(
    await page.request.get(`/api/authoring/sessions/${sessionPid}`),
    label,
  );
}

async function driftModelBinding(modelPid: string): Promise<() => Promise<void>> {
  const client = new PgClient(PG_CONN);
  await client.connect();
  try {
    const target = await client.query<{
      pid: string;
      updated_at: Date;
    }>(
      `SELECT b.pid, b.updated_at
         FROM ab_meta_model_field_binding b
         JOIN ab_meta_model m ON m.id = b.model_id AND m.tenant_id = b.tenant_id
        WHERE m.pid = $1 AND b.deleted_flag = FALSE
        ORDER BY b.id
        LIMIT 1`,
      [modelPid],
    );
    expect(target.rows[0]?.pid, 'model dependency binding for drift').toBeTruthy();
    const row = target.rows[0];
    await client.query(
      `UPDATE ab_meta_model_field_binding
          SET updated_at = updated_at + INTERVAL '1 second'
        WHERE pid = $1`,
      [row.pid],
    );
    return async () => {
      try {
        await client.query(
          `UPDATE ab_meta_model_field_binding SET updated_at = $2 WHERE pid = $1`,
          [row.pid, row.updated_at],
        );
      } finally {
        await client.end();
      }
    };
  } catch (error) {
    await client.end();
    throw error;
  }
}

async function assertSplitPersistence(split: SplitResult): Promise<void> {
  const client = new PgClient(PG_CONN);
  await client.connect();
  try {
    const persistence = await client.query<{
      mapping_count: string;
      split_count: string;
      audit_count: string;
      reason: string;
    }>(
      `SELECT
         (SELECT COUNT(*)::text
            FROM ab_authoring_change_item_split item_split
            JOIN ab_authoring_change_item target_item
              ON target_item.id = item_split.target_change_item_id
           WHERE target_item.pid = $1) AS mapping_count,
         (SELECT COUNT(*)::text
            FROM ab_authoring_change_set_split split_row
            JOIN ab_authoring_change_set target_set
              ON target_set.id = split_row.target_change_set_id
           WHERE target_set.pid = $2) AS split_count,
         (SELECT COUNT(*)::text
            FROM ab_authoring_audit_event
           WHERE change_set_pid IN ($2, $3)
             AND event_type IN ('CHANGE_SET_SPLIT_SOURCE', 'CHANGE_SET_SPLIT_TARGET')) AS audit_count,
         (SELECT split_row.reason
            FROM ab_authoring_change_set_split split_row
            JOIN ab_authoring_change_set target_set
              ON target_set.id = split_row.target_change_set_id
           WHERE target_set.pid = $2) AS reason`,
      [
        split.targetItems[0].changeItemPid,
        split.targetSession.changeSetPid,
        split.sourceSession.changeSetPid,
      ],
    );
    expect(persistence.rows[0]).toEqual({
      mapping_count: '1',
      split_count: '1',
      audit_count: '2',
      reason: 'PC 门禁：L0 密度与 L3 数据源没有跨分组依赖',
    });
  } finally {
    await client.end();
  }
}

async function loadAuditRows(changeSetPid: string): Promise<AuditRow[]> {
  const client = new PgClient(PG_CONN);
  await client.connect();
  try {
    const result = await client.query<AuditRow>(
      `SELECT event_type, result, reason_code, metadata
         FROM ab_authoring_audit_event
        WHERE change_set_pid = $1
        ORDER BY created_at, id`,
      [changeSetPid],
    );
    return result.rows;
  } finally {
    await client.end();
  }
}

async function cleanupMaterializedNewPage(pageKey: string): Promise<void> {
  const client = new PgClient(PG_CONN);
  await client.connect();
  try {
    await client.query('BEGIN');
    await client.query(
      `UPDATE ab_menu
          SET deleted_flag = TRUE, updated_at = NOW()
        WHERE code = $1 AND deleted_flag = FALSE`,
      [pageKey],
    );
    await client.query(
      `UPDATE ab_page_schema
          SET deleted_flag = TRUE, updated_at = NOW()
        WHERE page_key = $1 AND deleted_flag = FALSE`,
      [pageKey],
    );
    await client.query('COMMIT');
    const residue = await client.query<{ active_menus: string; active_pages: string }>(
      `SELECT
         (SELECT COUNT(*)::text FROM ab_menu
           WHERE code = $1 AND deleted_flag = FALSE) AS active_menus,
         (SELECT COUNT(*)::text FROM ab_page_schema
           WHERE page_key = $1 AND deleted_flag = FALSE) AS active_pages`,
      [pageKey],
    );
    expect(residue.rows[0]).toEqual({ active_menus: '0', active_pages: '0' });
  } catch (error) {
    await client.query('ROLLBACK').catch(() => {});
    throw error;
  } finally {
    await client.end();
  }
}

async function publishDensityRevisionViaUi(
  page: Page,
  exerciseFailure: boolean,
): Promise<{ release: Release; density: string }> {
  const opened = await enterAuthoringFromMenu(page);
  const { density } = await stageDensityEdit(page, opened);
  const saveResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'PATCH' &&
      apiPath(response.url()) === `/api/authoring/sessions/${opened.sessionPid}/patches`,
  );
  await page.getByRole('button', { name: '保存', exact: true }).click();
  const saved = await expectApiData<PatchResult>(await saveResponse, 'save density revision');
  await expect(page.getByText('0 项未保存')).toBeVisible();

  await page.getByRole('button', { name: '高级设置', exact: true }).click();
  const explain = page.getByRole('dialog', { name: '进入应用设计中心' });
  await expect(explain).toContainText('系统不会猜测影响范围');
  await expect(explain).toContainText('当前 ChangeSet、选择对象、返回位置');
  await expect(explain).toContainText('应用设计中心会重新检查权限');
  await explain.getByRole('button', { name: '继续到应用设计中心' }).click();
  await expect(page).toHaveURL(/\/unified-designer\?authoringSession=/);
  await expect(page.getByTestId('unified-designer-workbench')).toBeVisible();

  const prepareResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      apiPath(response.url()) === `/api/authoring/sessions/${opened.sessionPid}/prepare`,
  );
  await page.getByTestId('studio-prepare-submit').click();
  const prepared = await expectApiData<AuthoringSession>(
    await prepareResponse,
    'validate and analyze exact revision',
  );
  expect(prepared.validationState).toBe('VALID');
  expect(prepared.impactState).toBe('KNOWN');
  await expect(page.getByTestId('studio-prepare-submit')).toHaveText('提交评审');

  const submitResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      apiPath(response.url()) === `/api/authoring/sessions/${opened.sessionPid}/submit`,
  );
  await page.getByTestId('studio-prepare-submit').click();
  const submitted = await expectApiData<{
    status: string;
    publishState: string;
  }>(await submitResponse, 'submit directly publishable revision');
  expect(submitted.status).toBe('APPROVED');
  expect(submitted.publishState).toBe('READY');
  await expect(page.getByRole('button', { name: /当前状态：APPROVED/ })).toBeVisible();

  await page.getByTestId('studio-governance-open').click();
  const drawer = page.getByTestId('studio-governance-drawer');
  await expect(drawer).toBeVisible();
  const publishPath = `/api/authoring/change-sets/${opened.changeSetPid}/publish`;

  if (exerciseFailure) {
    const beforeFailure = await loadRuntimeTuple(page);
    await page.route(`**${publishPath}`, async (route) => {
      const payload = route.request().postDataJSON() as { expectedRevision: number };
      await route.continue({
        headers: { ...route.request().headers(), 'content-type': 'application/json' },
        postData: JSON.stringify({ expectedRevision: payload.expectedRevision - 1 }),
      });
    });
    const failedResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' && apiPath(response.url()) === publishPath,
    );
    await drawer.getByTestId('authoring-governance-publish').click();
    const failed = await failedResponse;
    expect(failed.status()).toBe(409);
    await expect(drawer.getByRole('alert')).toBeVisible();
    await expect(drawer.getByTestId('authoring-governance-publish')).toBeEnabled();
    await page.unroute(`**${publishPath}`);
    const afterFailure = await loadRuntimeTuple(page);
    expect(afterFailure).toEqual(beforeFailure);
  }

  const publishResponse = page.waitForResponse(
    (response) => response.request().method() === 'POST' && apiPath(response.url()) === publishPath,
  );
  await drawer.getByTestId('authoring-governance-publish').click();
  const release = await expectApiData<Release>(await publishResponse, 'publish exact revision');
  expect(release.changeSetRevision).toBe(saved.session.revision);
  await expect(page.getByRole('button', { name: /当前状态：PUBLISHED/ })).toBeVisible();
  await expect(drawer.getByTestId('authoring-governance-publish')).toHaveCount(0);
  await expect(drawer.getByTestId(`authoring-release-${release.releasePid}`)).toContainText(
    '当前活动',
  );
  await expect(
    drawer.getByText(`v${release.channelVersion}`, { exact: true }).first(),
  ).toBeVisible();
  return { release, density };
}

async function enterAuthoringFromMenu(page: Page): Promise<AuthoringSession> {
  await expect(page.locator('nav')).toBeVisible({ timeout: 15_000 });
  const menuLink = page.locator('nav').locator(`a[href="${gatePage.route}"]`).first();
  await expect(menuLink).toBeVisible({ timeout: 15_000 });
  await menuLink.click();
  await expect(page).toHaveURL(new RegExp(`${gatePage.route}$`));
  const runtime = page.getByRole('main').first();
  await expect(runtime.getByRole('heading', { name: gatePage.title })).toBeVisible();
  await expect(runtime.getByText(gatePage.recordMarker)).toBeVisible();

  const entry = page.getByTestId('contextual-authoring-enter');
  await expect(entry).toHaveText('配置此页', { timeout: 15_000 });
  const sessionResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      apiPath(response.url()) === '/api/authoring/sessions',
  );
  await entry.click();
  const session = await expectApiData<AuthoringSession>(
    await sessionResponse,
    'enter release gate authoring from its menu',
  );
  await expect(page.getByTestId('contextual-authoring-surface')).toBeVisible();
  return session;
}

async function seedReleaseGateRecord(): Promise<string> {
  const marker = `AUTHORING-RELEASE-REAL-${Date.now().toString(36)}`;
  const client = new PgClient(PG_CONN);
  await client.connect();
  try {
    const tenants = await client.query<{ tenant_id: string }>(
      `SELECT DISTINCT tenant_id
         FROM ab_meta_model
        WHERE code = 'e2et_record'
          AND is_current = TRUE
          AND deleted_flag = FALSE`,
    );
    expect(tenants.rows.length, `${SOURCE_PAGE_KEY} model tenant`).toBeGreaterThan(0);
    for (const { tenant_id: tenantId } of tenants.rows) {
      await client.query(
        `INSERT INTO mt_e2et_record
           (pid, tenant_id, e2et_name, e2et_status, e2et_count, created_at, updated_at)
         VALUES ($1, $2, $3, 'active', 1, NOW(), NOW())
         ON CONFLICT (pid) DO UPDATE SET
           e2et_name = EXCLUDED.e2et_name,
           e2et_status = EXCLUDED.e2et_status,
           e2et_count = EXCLUDED.e2et_count,
           updated_at = NOW()`,
        [`authrel_${tenantId}`, tenantId, marker],
      );
    }
    return marker;
  } finally {
    await client.end();
  }
}

async function stageDensityEdit(
  page: Page,
  session: AuthoringSession,
): Promise<{ density: string }> {
  const table = findTableBlock(session.snapshot);
  expect(table?.id, 'table block id in release UI gate').toBeTruthy();
  const current = readObjectPath(table!, '/props/density');
  const density = current === 'compact' ? 'comfortable' : 'compact';
  await page.getByTestId('authoring-outline-open').click();
  await page.getByTestId(`authoring-outline-${String(table!.id)}`).click();
  await page.getByRole('button', { name: '关闭页面大纲' }).click();
  await page.getByTestId('authoring-inspector-open').click();
  const editor = page.getByTestId('authoring-property-/props/density').locator('input');
  await expect(editor).toBeVisible();
  await editor.fill(density);
  await expect(page.getByText('1 项未保存')).toBeVisible();
  return { density };
}

async function loadRuntimeTuple(page: Page): Promise<RuntimeTuple> {
  const runtimePage = await expectApiData<Record<string, unknown>>(
    await page.request.get(`/api/pages/key/${gatePage.pageKey}`),
    'load deployed runtime tuple',
  );
  const runtime = (runtimePage.runtime ?? {}) as Record<string, unknown>;
  const table = findTableBlock(runtimePage);
  return {
    releasePid: typeof runtime.releasePid === 'string' ? runtime.releasePid : null,
    channelVersion: Number(runtime.channelVersion ?? 0),
    cacheKey: String(runtime.cacheKey ?? ''),
    density: readObjectPath(table!, '/props/density') as string | undefined,
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

async function login(page: Page): Promise<void> {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  const spaces = await expectApiData<
    Array<{
      tenantId: string | number;
      tenantName?: string;
      spaceType?: string;
    }>
  >(await page.request.get('/api/tenant-selection/my-spaces'), 'load admin tenant spaces');
  const businessSpace =
    spaces.find(
      (space) => space.spaceType === 'business' && space.tenantName === 'AuraBoot Demo',
    ) ?? spaces.find((space) => space.spaceType === 'business');
  expect(businessSpace?.tenantId, 'business tenant for contextual authoring golden').toBeTruthy();

  const currentAuth = await loadAuthSnapshot(page);
  if (String(currentAuth.user.tenantId) !== String(businessSpace!.tenantId)) {
    const switchResponse = await page.request.post('/_action/switch-space', {
      form: {
        tenantId: String(businessSpace!.tenantId),
        redirectTo: '/home',
      },
      maxRedirects: 0,
    });
    expect(
      [302, 303].includes(switchResponse.status()),
      `switch to business tenant: HTTP ${switchResponse.status()} ${await switchResponse.text()}`,
    ).toBe(true);
    const sessionCookie = switchResponse.headers()['set-cookie']?.match(/__session=([^;]+)/)?.[1];
    expect(sessionCookie, 'business tenant switch must refresh __session').toBeTruthy();
    const cookieBase = {
      name: '__session',
      value: sessionCookie!,
      path: '/',
      httpOnly: true,
      sameSite: 'Lax' as const,
    };
    await page.context().addCookies([
      { ...cookieBase, domain: 'localhost' },
      { ...cookieBase, domain: '127.0.0.1' },
    ]);
    await page.goto('/home', { waitUntil: 'domcontentloaded' });
  }

  const auth = await loadAuthSnapshot(page);
  expect(String(auth.user.tenantId)).toBe(String(businessSpace!.tenantId));
  const roleCodes = auth.permissions.roles.map((role) => role.code);
  expect(roleCodes).toContain('tenant_admin');
  expect(auth.permissions.permissionCodes).toEqual(
    expect.arrayContaining([...ADMIN_AUTHORING_PERMISSIONS]),
  );
  await expect(page.locator('nav')).toBeVisible({ timeout: 15_000 });
}

async function loadAuthSnapshot(page: Page): Promise<{
  user: { tenantId?: string | number };
  permissions: {
    roles: Array<{ code: string }>;
    permissionCodes: string[];
  };
}> {
  return expectApiData(
    await page.request.get('/api/auth/me'),
    'load contextual authoring auth snapshot',
  );
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

function apiPath(url: string): string {
  return new URL(url).pathname;
}
