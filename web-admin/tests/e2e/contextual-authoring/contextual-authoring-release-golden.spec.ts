import { test, expect, type Browser, type Locator, type Page } from '@playwright/test';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { Client as PgClient } from 'pg';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { PG_CONN } from '../../helpers/environments';
import { loginViaUI } from '../../helpers/wd-fixtures';

const SOURCE_PAGE_KEY = 'e2et_record_list';
const SCREENSHOT_DIR = resolve(
  process.env.CONTEXTUAL_AUTHORING_SCREENSHOT_DIR ?? 'test-results/contextual-authoring',
);
const PHYSICAL_FIXTURE_PATH = process.env.CONTEXTUAL_AUTHORING_PHYSICAL_FIXTURE_PATH?.trim();

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
  interactionContext?: Record<string, unknown>;
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
  manifests: Array<{
    blockType: string;
    checksum: string;
    properties: Record<
      string,
      { propertyPath: string; allowedOperations: Array<'ADD' | 'REPLACE' | 'REMOVE' | string> }
    >;
  }>;
};
type ChangeItem = {
  changeItemPid: string;
  sourceChangeItemPid?: string | null;
  blockId: string;
  propertyPath: string;
  riskLevel: string;
  operation?: string;
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
type IdentitySimulation = {
  simulationPid: string;
  sourceSessionPid: string;
  targetRole: RoleRecord & { roleName: string };
  status: 'ACTIVE' | 'ENDED' | 'EXPIRED';
  actorIntersectionApplied: boolean;
  businessDataIncluded: boolean;
  readOnly: boolean;
  exportAllowed: boolean;
  businessActionsAllowed: boolean;
  decisions: unknown[];
};
type AiPatchProposal = {
  proposalPid: string;
  baseRevision: number;
  status: 'PROPOSED' | 'APPLIED' | 'REJECTED';
  typedPatchOnly: boolean;
  requiresHumanApproval: boolean;
  aggregateRisk: string;
  aggregateRoute: string;
  publishPolicy: string;
  items: Array<{
    blockId: string;
    propertyPath: string;
    operation: 'ADD' | 'REPLACE' | 'REMOVE';
    value?: unknown;
  }>;
};
type ApplyAiPatchProposalResult = {
  proposal: AiPatchProposal;
  session: AuthoringSession;
};
type AdminPermissionSnapshot = { rolePid: string; permissionPids: string[] };

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
const WP06_ADMIN_PERMISSIONS = ['audit.trail.admin', 'meta.model.update'] as const;

let gatePage: GatePage;
let adminPermissionSnapshot: AdminPermissionSnapshot | null = null;

test.use({ storageState: { cookies: [], origins: [] } });

test.describe('Contextual authoring release PC golden', () => {
  test.describe.configure({ mode: 'serial', timeout: 300_000 });

  test.beforeAll(async ({ browser }) => {
    gatePage = await createGatePageFromPublishedRuntime(browser);
    adminPermissionSnapshot = await ensureTenantAdminPermissions(browser, [
      ...WP06_ADMIN_PERMISSIONS,
    ]);
  });

  test.beforeEach(async ({ page }) => {
    await login(page);
  });

  test.afterAll(async ({ browser }) => {
    try {
      if (gatePage && PHYSICAL_FIXTURE_PATH) {
        await retainPhysicalFixture(browser, gatePage, resolve(PHYSICAL_FIXTURE_PATH));
      } else if (gatePage) {
        await cleanupGatePage(browser, gatePage);
      }
    } finally {
      if (adminPermissionSnapshot) {
        await restoreTenantAdminPermissions(browser, adminPermissionSnapshot);
      }
    }
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
    await sourceMenuLink.focus();
    await expect(sourceMenuLink).toBeFocused();
    await sourceMenuLink.press('Enter');
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
    await saveInlineAuthoring(page);
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

    await page.getByTestId('authoring-inspector-open').click();
    await expect(page.getByRole('dialog', { name: '属性检查器' })).toBeVisible();
    await filterEditor.fill(JSON.stringify({ status: 'OPEN' }));
    const validSave = page.waitForResponse(
      (response) =>
        response.request().method() === 'PATCH' &&
        apiPath(response.url()) === `/api/authoring/sessions/${opened.sessionPid}/patches`,
    );
    await saveInlineAuthoring(page);
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
    await saveInlineAuthoring(page);
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
    await saveInlineAuthoring(page);
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
    await saveInlineAuthoring(page);
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

  test('PC-AUTH-040 @critical — governed Builder completes kind, template, batch, export/import, sanitization and protected semantics', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    const source = await enterAuthoringFromMenu(page);
    await page.getByRole('button', { name: '新页面 / 菜单' }).click();
    const explain = page.getByRole('dialog', { name: '在应用设计中心创建页面' });
    await explain.getByRole('button', { name: '继续到应用设计中心' }).click();

    const wizard = page.getByTestId('new-page-workspace-wizard');
    await expect(wizard).toBeVisible();
    const suffix = `${Date.now().toString(36)}_${process.pid}`;
    const pageKey = `authoring_builder_gate_${suffix}`;
    const title = `Authoring Builder Gate ${suffix}`;
    await wizard.getByLabel('页面标题').fill(title);
    await wizard.getByLabel('页面标识').fill(pageKey);
    await wizard
      .locator('label')
      .filter({ hasText: '页面类型' })
      .getByRole('combobox')
      .selectOption('list');
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
      'create governed Builder workspace',
    );
    try {
      await expect(page.getByTestId('unified-designer-workbench')).toBeVisible();
      await expect(page.getByTestId('designer-contextual-restricted')).toContainText(
        '同一 ChangeSet',
      );
      await expect(page.getByTestId('designer-kind-switch')).toHaveValue('list');
      await expect(page.getByTestId('designer-template-select')).toBeVisible();
      await expect(page.getByTestId('designer-export')).toBeEnabled();
      await expect(page.getByTestId('designer-import')).toBeEnabled();
      const stableRootId = String(
        (created.snapshot.blocks as Array<Record<string, unknown>>)[0].id,
      );

      const unauthorizedImport = {
        schemaVersion: 3,
        id: 'forged-page',
        pageKey: 'forged-page-key',
        kind: 'list',
        blocks: [
          {
            id: 'forged-root',
            blockType: 'list',
            blocks: [{ id: 'forged-divider', blockType: 'divider' }],
          },
        ],
      };
      await page.getByTestId('designer-import-input').setInputFiles({
        name: 'unauthorized.page.json',
        mimeType: 'application/json',
        buffer: Buffer.from(JSON.stringify(unauthorizedImport)),
      });
      await expect(page.getByTestId('designer-dirty-state')).toContainText('导入失败');
      await expect(page.getByTestId(`canvas-block-${stableRootId}`)).toBeVisible();
      await expect(page.getByTestId('canvas-block-forged-divider')).toHaveCount(0);

      await page.getByTestId('designer-kind-switch').selectOption('detail');
      await expect(page.getByTestId(`canvas-block-${stableRootId}`)).toContainText('detail');
      await page.getByTestId('designer-template-select').selectOption('core_detail_summary');
      const summary = page.locator('[data-testid^="canvas-block-core_detail_summary_summary"]');
      const description = page.locator(
        '[data-testid^="canvas-block-core_detail_summary_description"]',
      );
      await expect(summary).toBeVisible();
      await expect(description).toBeVisible();
      const summaryId = (await summary.getAttribute('data-testid'))!.replace('canvas-block-', '');
      const descriptionId = (await description.getAttribute('data-testid'))!.replace(
        'canvas-block-',
        '',
      );

      await summary.click();
      await description.click({ modifiers: ['Shift'] });
      await expect(page.getByTestId('multi-select-count')).toContainText('2');
      await page.getByTestId('multi-select-delete').click();
      await expect(summary).toHaveCount(0);
      await expect(description).toHaveCount(0);
      await page.getByTestId('designer-undo').click();
      await expect(page.getByTestId(`canvas-block-${summaryId}`)).toBeVisible();
      await expect(page.getByTestId(`canvas-block-${descriptionId}`)).toBeVisible();
      await page.getByTestId('designer-redo').click();
      await expect(page.getByTestId(`canvas-block-${summaryId}`)).toHaveCount(0);
      await page.getByTestId('designer-undo').click();

      const firstSaveResponse = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) ===
            `/api/authoring/sessions/${created.sessionPid}/studio-batches`,
      );
      await page.getByTestId('designer-save').click();
      const firstSaved = await expectApiData<PatchResult>(
        await firstSaveResponse,
        'save kind switch and approved template atomically',
      );
      expect(firstSaved.session.snapshot.kind).toBe('detail');
      const firstRoot = (firstSaved.session.snapshot.blocks as Array<Record<string, unknown>>)[0];
      expect(firstRoot.id).toBe(stableRootId);
      expect(firstRoot.blockType).toBe('detail');
      expect(
        (firstRoot.extension as Record<string, unknown> | undefined)?.authoringTemplateLineage,
      ).toBeUndefined();
      const firstChildren = firstRoot.blocks as Array<Record<string, unknown>>;
      expect(firstChildren.map((block) => block.id)).toEqual([summaryId, descriptionId]);
      expect(
        (firstChildren[0].extension as Record<string, unknown>).authoringTemplateLineage,
      ).toEqual({
        templateId: 'core_detail_summary',
        templateVersion: '1',
        sourceBlockId: 'summary',
      });

      const downloadPromise = page.waitForEvent('download');
      await page.getByTestId('designer-export').click();
      const download = await downloadPromise;
      expect(download.suggestedFilename()).toBe(`${pageKey}.page.json`);
      const downloadPath = await download.path();
      expect(downloadPath).toBeTruthy();
      const exported = JSON.parse(await readFile(downloadPath!, 'utf8')) as Record<string, unknown>;
      expect(exported.id).toBe(pageKey);
      expect(exported.pageKey).toBe(pageKey);
      expect(exported.kind).toBe('detail');

      const imported = structuredClone(exported) as Record<string, unknown>;
      imported.id = 'forged-page-id';
      imported.pageKey = 'forged-page-key';
      imported.modelCode = 'forged-model';
      imported.title = 'Forged title';
      const importedRoot = (imported.blocks as Array<Record<string, unknown>>)[0];
      importedRoot.id = 'forged-root-id';
      const importedChildren = importedRoot.blocks as Array<Record<string, unknown>>;
      const protectedActionBarId = `protected-action-bar-${suffix}`;
      const protectedActionId = `protected-action-${suffix}`;
      const importedDescription = importedChildren.find((block) => block.id === descriptionId)!;
      importedDescription.props = {
        content:
          '<b>Builder safe</b><script>window.__builder_xss=1</script><a href="javascript:alert(1)">bad</a>',
      };
      importedDescription.extension = {
        authoringTemplateLineage: {
          templateId: 'forged-template',
          templateVersion: '999',
          sourceBlockId: 'forged-source',
        },
      };
      importedChildren.push({
        id: protectedActionBarId,
        blockType: 'action-bar',
        layout: { span: 12 },
        blocks: [
          {
            id: protectedActionId,
            blockType: 'action',
            actionType: 'command',
            props: {
              command: 'e2et:delete_order',
              label: 'Delete order',
              variant: 'danger',
            },
          },
        ],
      });
      await page.getByTestId('designer-import-input').setInputFiles({
        name: 'roundtrip.page.json',
        mimeType: 'application/json',
        buffer: Buffer.from(JSON.stringify(imported)),
      });
      await expect(page.getByTestId('designer-dirty-state')).toContainText('未保存');

      const secondSaveResponse = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) ===
            `/api/authoring/sessions/${created.sessionPid}/studio-batches`,
      );
      await page.getByTestId('designer-save').click();
      const secondSaved = await expectApiData<PatchResult>(
        await secondSaveResponse,
        'save governed import through the same ChangeSet',
      );
      expect(secondSaved.session.snapshot.pid).toBe(created.snapshot.pid);
      expect(secondSaved.session.snapshot.pageKey).toBe(pageKey);
      expect(secondSaved.session.snapshot.modelCode).toBe('e2et_record');
      expect(secondSaved.session.snapshot.title).toEqual(created.snapshot.title);
      const savedRoot = (secondSaved.session.snapshot.blocks as Array<Record<string, unknown>>)[0];
      expect(savedRoot.id).toBe(stableRootId);
      const savedDescription = (savedRoot.blocks as Array<Record<string, unknown>>).find(
        (block) => block.id === descriptionId,
      )!;
      const savedContent = String((savedDescription.props as Record<string, unknown>).content);
      expect(savedContent).toContain('<b>Builder safe</b>');
      expect(savedContent).not.toMatch(/script|javascript:/i);
      expect(
        (savedDescription.extension as Record<string, unknown>).authoringTemplateLineage,
      ).toEqual({
        templateId: 'core_detail_summary',
        templateVersion: '1',
        sourceBlockId: 'description',
      });
      const savedActionBar = (savedRoot.blocks as Array<Record<string, unknown>>).find(
        (block) => block.id === protectedActionBarId,
      )!;
      const savedAction = (savedActionBar.blocks as Array<Record<string, unknown>>).find(
        (block) => block.id === protectedActionId,
      )!;
      expect(savedAction).toMatchObject({
        blockType: 'action',
        actionType: 'command',
        props: {
          command: 'e2et:delete_order',
          label: 'Delete order',
          variant: 'danger',
        },
      });

      const canonicalDownloadPromise = page.waitForEvent('download');
      await page.getByTestId('designer-export').click();
      const canonicalDownloadPath = await (await canonicalDownloadPromise).path();
      expect(canonicalDownloadPath).toBeTruthy();
      const canonicalImport = JSON.parse(await readFile(canonicalDownloadPath!, 'utf8')) as Record<
        string,
        unknown
      >;

      await page.reload({ waitUntil: 'domcontentloaded' });
      await expect(page.getByTestId('unified-designer-workbench')).toBeVisible();
      await expect(page.getByTestId('designer-kind-switch')).toHaveValue('detail');
      await expect(page.getByTestId(`canvas-block-${stableRootId}`)).toBeVisible();
      const reloaded = await loadSession(page, created.sessionPid, 'reload Builder session');
      expect(reloaded.revision).toBe(secondSaved.session.revision);
      expect(reloaded.snapshot).toEqual(secondSaved.session.snapshot);

      await mkdir(SCREENSHOT_DIR, { recursive: true });
      await page.screenshot({
        path: resolve(SCREENSHOT_DIR, 'pc-auth-040-governed-builder.png'),
        fullPage: true,
      });

      const disguisedActionImport = structuredClone(canonicalImport);
      const disguisedRoot = (disguisedActionImport.blocks as Array<Record<string, unknown>>)[0];
      const disguisedActionBar = (disguisedRoot.blocks as Array<Record<string, unknown>>).find(
        (block) => block.id === protectedActionBarId,
      )!;
      const disguisedAction = (disguisedActionBar.blocks as Array<Record<string, unknown>>).find(
        (block) => block.id === protectedActionId,
      )!;
      disguisedAction.props = {
        ...(disguisedAction.props as Record<string, unknown>),
        label: 'Continue',
        variant: 'primary',
      };
      await page.getByTestId('designer-import-input').setInputFiles({
        name: 'disguised-destructive-action.page.json',
        mimeType: 'application/json',
        buffer: Buffer.from(JSON.stringify(disguisedActionImport)),
      });
      await expect(page.getByTestId('designer-dirty-state')).toContainText('未保存');
      const protectedSemanticResponse = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) ===
            `/api/authoring/sessions/${created.sessionPid}/studio-batches`,
      );
      await page.getByTestId('designer-save').click();
      expect(
        (await protectedSemanticResponse).status(),
        'a destructive command cannot be relabeled or restyled as a benign action',
      ).toBe(422);
      const protectedSemanticError = page.getByTestId('designer-save-error');
      await expect(protectedSemanticError).toContainText('保存被拒绝');
      await expect(protectedSemanticError).toContainText('保留动作真实意图与危险提示');
      await expect(protectedSemanticError).not.toContainText('Business error');
      const rejected = await loadSession(
        page,
        created.sessionPid,
        'reload protected-semantic rejection',
      );
      expect(rejected.revision).toBe(secondSaved.session.revision);
      expect(rejected.snapshot).toEqual(secondSaved.session.snapshot);
      await page.screenshot({
        path: resolve(SCREENSHOT_DIR, 'pc-auth-040-protected-semantic-rejected.png'),
        fullPage: true,
      });

      const sessionPath = `/api/authoring/sessions/${created.sessionPid}`;
      await page.route(`**${sessionPath}`, async (route) => {
        if (route.request().method() === 'GET') {
          await route.abort('failed');
          return;
        }
        await route.continue();
      });
      await page.reload({ waitUntil: 'domcontentloaded' });
      await expect(page.getByText('权威草稿暂不可读，本地 Studio 文档仍保留')).toBeVisible();
      page.once('dialog', async (dialog) => {
        expect(dialog.message()).toContain('放弃页面中断前保留的本地 Studio 文档');
        await dialog.accept();
      });
      await page.getByTestId('studio-local-recovery-discard').click();
      await page.unroute(`**${sessionPath}`);
      await page.reload({ waitUntil: 'domcontentloaded' });
      await expect(page.getByTestId('unified-designer-workbench')).toBeVisible();
      await expect(page.getByTestId('designer-dirty-state')).toContainText('已保存');
      await page.getByTestId('studio-return-source').click();
      await expect(page).toHaveURL(new RegExp(`${gatePage.route}$`));
      await expect(page.getByText(gatePage.recordMarker)).toBeVisible();
    } finally {
      await cleanupMaterializedNewPage(pageKey);
    }
  });

  test('PC-AUTH-041 @critical — governed Builder supports real pointer palettes, reorder, inspector and modes', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    const source = await enterAuthoringFromMenu(page);
    await page.getByRole('button', { name: '新页面 / 菜单' }).click();
    await page
      .getByRole('dialog', { name: '在应用设计中心创建页面' })
      .getByRole('button', { name: '继续到应用设计中心' })
      .click();

    const wizard = page.getByTestId('new-page-workspace-wizard');
    await expect(wizard).toBeVisible();
    const suffix = `${Date.now().toString(36)}_${process.pid}`;
    const pageKey = `authoring_pointer_gate_${suffix}`;
    await wizard.getByLabel('页面标题').fill(`Authoring Pointer Gate ${suffix}`);
    await wizard.getByLabel('页面标识').fill(pageKey);
    await wizard
      .locator('label')
      .filter({ hasText: '页面类型' })
      .getByRole('combobox')
      .selectOption('detail');
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
      'create governed pointer Builder workspace',
    );

    try {
      const workbench = page.getByTestId('unified-designer-workbench');
      await expect(workbench).toBeVisible();
      await expect(workbench).toHaveAttribute('data-mode', 'edit');
      await page.getByTestId('designer-template-select').selectOption('core_detail_summary');

      const rootId = String((created.snapshot.blocks as Array<Record<string, unknown>>)[0].id);
      const summary = page.locator('[data-testid^="canvas-block-core_detail_summary_summary"]');
      const description = page.locator(
        '[data-testid^="canvas-block-core_detail_summary_description"]',
      );
      await expect(summary).toBeVisible();
      await expect(description).toBeVisible();
      const summaryId = (await summary.getAttribute('data-testid'))!.replace('canvas-block-', '');
      const descriptionId = (await description.getAttribute('data-testid'))!.replace(
        'canvas-block-',
        '',
      );

      await page.getByTestId(`outline-item-${rootId}`).click();
      await switchAuthoringResourceTab(page, 'blocks');
      await expect(page.getByTestId('palette-add-detail-section')).toBeEnabled();
      await expect(page.getByTestId('palette-add-form-section')).toHaveCount(0);
      const rootDropPosition = await authoringContainerDropPosition(
        page.getByTestId(`canvas-block-${rootId}`),
      );
      await authoringPointerDragTo(
        page,
        page.getByTestId('palette-add-detail-section'),
        page.getByTestId(`canvas-block-${rootId}`),
        { targetPosition: rootDropPosition },
      );
      const addedSectionId = 'detail_section_new_detail_section';
      await expect(page.getByTestId(`canvas-block-${addedSectionId}`)).toBeVisible();

      await switchAuthoringResourceTab(page, 'outline');
      await page.getByTestId(`outline-item-${summaryId}`).click();
      await switchAuthoringResourceTab(page, 'fields');
      const fieldSearch = page.getByTestId('field-palette-search');
      await expect(page.getByTestId('field-palette-add-field')).toBeDisabled();
      await fieldSearch.fill('e2et_name');
      const modelField = page.getByTestId('model-field-e2et_name');
      await expect(modelField).toBeEnabled();
      const summaryDropPosition = await authoringContainerDropPosition(
        page.getByTestId(`canvas-block-${summaryId}`),
      );
      await authoringPointerDragTo(
        page,
        modelField,
        page.getByTestId(`canvas-block-${summaryId}`),
        { targetPosition: summaryDropPosition },
      );
      const fieldBlockId = 'field_e2et_name';
      await expect(page.getByTestId(`canvas-block-${fieldBlockId}`)).toBeVisible();
      await expect(page.getByTestId('inspector-selected-id')).toContainText(fieldBlockId);
      await expect(page.getByTestId('inspector-field-props.label')).toBeVisible();
      await expect(modelField).toBeDisabled();
      await expect(modelField).toHaveAttribute('data-used', 'true');
      await fieldSearch.fill('no_such_builder_field');
      await expect(page.getByTestId('model-field-e2et_name')).toHaveCount(0);
      await fieldSearch.fill('e2et_name');

      await page.getByTestId(`canvas-block-${summaryId}`).click();
      await page.getByTestId(`canvas-block-${descriptionId}`).click({ modifiers: ['Shift'] });
      await expect(page.getByTestId('multi-select-count')).toContainText('2');
      await page.getByTestId('multi-select-clear').click();
      await expect(page.getByTestId('multi-select-bar')).toHaveCount(0);

      await expect(page.getByTestId('designer-duplicate-block')).toBeEnabled();
      await page.getByTestId('designer-duplicate-block').click();
      const copiedDescriptionId = `${descriptionId}_copy`;
      await expect(page.getByTestId(`canvas-block-${copiedDescriptionId}`)).toBeVisible();
      await expect(page.getByTestId('inspector-selected-id')).toContainText(copiedDescriptionId);
      await page.getByTestId('designer-undo').click();
      await expect(page.getByTestId(`canvas-block-${copiedDescriptionId}`)).toHaveCount(0);
      await page.getByTestId('designer-redo').click();
      await expect(page.getByTestId(`canvas-block-${copiedDescriptionId}`)).toBeVisible();

      await page.getByTestId('designer-mode-layout').click();
      await expect(workbench).toHaveAttribute('data-mode', 'layout');
      await expect(page.getByTestId(`block-drag-handle-${addedSectionId}`)).toBeVisible();
      await page.getByTestId('designer-mode-preview').click();
      await expect(workbench).toHaveAttribute('data-mode', 'preview');
      await expect(page.getByTestId('unified-runtime-preview')).toBeVisible();
      await page.getByTestId('preview-device-select').selectOption('mobile');
      await expect(page.getByTestId('preview-device-select')).toHaveValue('mobile');
      await page.getByTestId('designer-mode-edit').click();
      await expect(workbench).toHaveAttribute('data-mode', 'edit');
      await expect(page.getByTestId('inspector-selected-id')).toContainText(copiedDescriptionId);

      const firstSaveResponse = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) ===
            `/api/authoring/sessions/${created.sessionPid}/studio-batches`,
      );
      await page.getByTestId('designer-save').click();
      const firstSaved = await expectApiData<PatchResult>(
        await firstSaveResponse,
        'save pointer-created block and model field',
      );
      const firstItems = await loadAuthoringChangeItems(page, created.sessionPid);
      const firstRoot = (firstSaved.session.snapshot.blocks as Array<Record<string, unknown>>)[0];
      expect((firstRoot.blocks as Array<Record<string, unknown>>).map((block) => block.id)).toEqual(
        [summaryId, descriptionId, copiedDescriptionId, addedSectionId],
      );
      const copiedDescription = (firstRoot.blocks as Array<Record<string, unknown>>).find(
        (block) => block.id === copiedDescriptionId,
      )!;
      expect(copiedDescription.extension).toMatchObject({
        authoringCopyLineage: { sourceBlockId: descriptionId },
      });
      const firstSummary = (firstRoot.blocks as Array<Record<string, unknown>>).find(
        (block) => block.id === summaryId,
      )!;
      expect(
        (firstSummary.blocks as Array<Record<string, unknown>>).map((block) => block.id),
      ).toEqual([fieldBlockId]);
      await expect(page.getByTestId('designer-save')).toBeDisabled({ timeout: 15_000 });
      await expect(page.getByTestId('designer-save-error')).toHaveCount(0);

      await authoringDragCanvasBlockBefore(page, addedSectionId, summaryId);
      await expectAuthoringBlockBefore(page, addedSectionId, summaryId);
      const saveButton = page.getByTestId('designer-save');
      await expect(saveButton).toBeEnabled();
      const secondSaveRequest = page.waitForRequest(
        (request) =>
          request.method() === 'POST' &&
          apiPath(request.url()) === `/api/authoring/sessions/${created.sessionPid}/studio-batches`,
        { timeout: 15_000 },
      );
      const secondSaveResponse = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) ===
            `/api/authoring/sessions/${created.sessionPid}/studio-batches`,
        { timeout: 15_000 },
      );
      await saveButton.click();
      await expect(page.getByTestId('designer-dirty-state')).toContainText('保存中', {
        timeout: 15_000,
      });
      await secondSaveRequest;
      const secondSaved = await expectApiData<PatchResult>(
        await secondSaveResponse,
        'save pointer reorder as semantic MOVE',
      );
      const secondRoot = (secondSaved.session.snapshot.blocks as Array<Record<string, unknown>>)[0];
      expect(
        (secondRoot.blocks as Array<Record<string, unknown>>).map((block) => block.id),
      ).toEqual([addedSectionId, summaryId, descriptionId, copiedDescriptionId]);
      const secondItems = await loadAuthoringChangeItems(page, created.sessionPid);
      expect(secondItems).toHaveLength(firstItems.length + 1);
      expect(secondItems.at(-1)).toMatchObject({
        blockId: addedSectionId,
        operation: 'MOVE',
      });
      await page.getByTestId('studio-governance-open').click();
      await page.getByTestId('authoring-split-panel').locator('summary').click();
      const moveItem = page
        .getByTestId(`authoring-split-item-${String(secondItems.at(-1)!.changeItemPid)}`)
        .locator('..');
      await moveItem.scrollIntoViewIfNeeded();
      await expect(moveItem).toBeVisible();
      await expect(moveItem).toContainText('MOVE');
      await expect(moveItem).not.toContainText('REMOVE');
      await expect(moveItem).not.toContainText('ADD');

      await mkdir(SCREENSHOT_DIR, { recursive: true });
      await page.screenshot({
        path: resolve(SCREENSHOT_DIR, 'pc-auth-041-governed-builder-palettes.png'),
        fullPage: true,
      });
      await page.getByTestId('studio-governance-close').click();
      await page.getByTestId('studio-return-source').click();
      await expect(page).toHaveURL(new RegExp(`${gatePage.route}$`));
      await expect(page.getByText(gatePage.recordMarker)).toBeVisible();
    } finally {
      await cleanupMaterializedNewPage(pageKey);
    }
  });

  test('PC-AUTH-043 @critical — publish and runtime re-sanitize a mutated governed snapshot', async ({
    page,
    browser,
  }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    await ensureReviewer(page);
    const source = await enterAuthoringFromMenu(page);
    await page.getByRole('button', { name: '新页面 / 菜单' }).click();
    await page
      .getByRole('dialog', { name: '在应用设计中心创建页面' })
      .getByRole('button', { name: '继续到应用设计中心' })
      .click();

    const wizard = page.getByTestId('new-page-workspace-wizard');
    await expect(wizard).toBeVisible();
    const suffix = `${Date.now().toString(36)}_${process.pid}`;
    const pageKey = `authoring_runtime_sanitize_${suffix}`;
    const route = `/${pageKey.replaceAll('_', '-')}`;
    const title = `Runtime sanitize gate ${suffix}`;
    await wizard.getByLabel('页面标题').fill(title);
    await wizard.getByLabel('页面标识').fill(pageKey);
    await wizard
      .locator('label')
      .filter({ hasText: '页面类型' })
      .getByRole('combobox')
      .selectOption('detail');
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
      'create runtime-sanitization workspace',
    );

    try {
      await expect(page.getByTestId('unified-designer-workbench')).toBeVisible();
      await page.getByTestId('designer-template-select').selectOption('core_detail_summary');
      const description = page.locator(
        '[data-testid^="canvas-block-core_detail_summary_description"]',
      );
      await expect(description).toBeVisible();
      const descriptionId = (await description.getAttribute('data-testid'))!.replace(
        'canvas-block-',
        '',
      );
      const saveResponse = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) ===
            `/api/authoring/sessions/${created.sessionPid}/studio-batches`,
      );
      await page.getByTestId('designer-save').click();
      const saved = await expectApiData<PatchResult>(
        await saveResponse,
        'save legitimate runtime-sanitization baseline',
      );
      const maliciousHtml =
        '<b>Runtime boundary safe</b><script>window.__authoring_runtime_xss=1</script><a href="javascript:alert(1)" onclick="window.__authoring_runtime_click=1">unsafe</a>';
      await mutateAuthoringDraftContent(created.changeSetPid, descriptionId, maliciousHtml);

      const prepared = await prepareAndSubmitInStudio(page, created.sessionPid);
      expect(prepared.revision).toBe(saved.session.revision);
      const reviewer = await openReviewer(browser);
      try {
        await openReviewWorkspace(reviewer, created.changeSetPid, prepared.revision);
        await reviewer
          .getByTestId('authoring-governance-reason')
          .fill('PC 门禁：验证发布和运行读取边界二次净化');
        const approvalResponse = reviewer.waitForResponse(
          (response) =>
            response.request().method() === 'POST' &&
            apiPath(response.url()) ===
              `/api/authoring/change-sets/${created.changeSetPid}/approve`,
        );
        await reviewer.getByTestId('authoring-governance-approve').click();
        await expectApiData(await approvalResponse, 'approve runtime-sanitization revision');
      } finally {
        await reviewer.context().close();
      }

      const published = await expectApiData<Release>(
        await page.request.post(`/api/authoring/change-sets/${created.changeSetPid}/publish`, {
          data: { expectedRevision: prepared.revision },
        }),
        'publish runtime-sanitization revision',
      );
      expect(published.status).toBe('ACTIVE');
      const runtimeProbe = await browser.newContext({
        storageState: { cookies: [], origins: [] },
      });
      const probePage = await runtimeProbe.newPage();
      try {
        await login(probePage);
        const deployed = await expectApiData<Record<string, unknown>>(
          await probePage.request.get(`/api/pages/key/${pageKey}`),
          'read runtime-sanitized active release',
        );
        const deployedJson = JSON.stringify(deployed);
        expect(deployedJson).toContain('<b>Runtime boundary safe</b>');
        expect(deployedJson).not.toMatch(/<script|javascript:|onclick/i);
        expect((deployed.runtime as Record<string, unknown>).source).toBe('AUTHORING_RELEASE');
        expect((deployed.runtime as Record<string, unknown>).releasePid).toBe(published.releasePid);

        await probePage.goto('/', { waitUntil: 'domcontentloaded' });
        const newMenu = probePage.locator('nav').locator(`a[href="${route}"]`).first();
        await expect(newMenu).toBeVisible({ timeout: 15_000 });
        await newMenu.click();
        await expect(probePage).toHaveURL(new RegExp(`${route}$`));
        const runtime = probePage.getByRole('main').first();
        await expect(runtime.getByText('Runtime boundary safe')).toBeVisible();
        expect(
          await probePage.evaluate(() => ({
            script: (window as Window & { __authoring_runtime_xss?: number })
              .__authoring_runtime_xss,
            click: (window as Window & { __authoring_runtime_click?: number })
              .__authoring_runtime_click,
          })),
        ).toEqual({ script: undefined, click: undefined });
        await expect(runtime.locator('script')).toHaveCount(0);
        await expect(runtime.locator('a[href^="javascript:"]')).toHaveCount(0);
        await mkdir(SCREENSHOT_DIR, { recursive: true });
        await probePage.screenshot({
          path: resolve(SCREENSHOT_DIR, 'pc-auth-043-runtime-resanitized.png'),
          fullPage: true,
        });
      } finally {
        await runtimeProbe.close();
      }
    } finally {
      await cleanupMaterializedNewPage(pageKey);
    }
  });

  test('PC-AUTH-042 @critical — Studio return restores the real record, query, scroll and selected block', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 520 });
    const menuLink = page.locator('nav').locator(`a[href="${gatePage.route}"]`).first();
    await expect(menuLink).toBeVisible();
    await menuLink.click();
    await expect(page.getByText(gatePage.recordMarker)).toBeVisible();

    const sourceParams = new URLSearchParams({
      tab: 'record-overview',
      'filter.status': 'active',
      sort: 'e2et_name:asc',
      pageNum: '1',
    });
    await page.goto(`${gatePage.route}?${sourceParams.toString()}`, {
      waitUntil: 'domcontentloaded',
    });
    const sourceRow = page.locator('tr').filter({ hasText: gatePage.recordMarker }).first();
    await expect(sourceRow).toBeVisible();
    // This gate copies the currently published runtime schema. Some supported
    // list profiles expose detail navigation on the row itself and intentionally
    // omit a row-action column, so drive the real, profile-independent entry.
    await sourceRow.click();
    await expect(page).toHaveURL(/\/p\/e2et_record\/view\/[^/?#]+/);
    await expect(page.getByTestId('detail-page-skeleton')).toHaveCount(0);
    await expect(page.getByTestId('ab:detail:e2et_record:container')).toBeVisible();
    await expect(page.getByText(gatePage.recordMarker)).toBeVisible();

    const sourceUrl = new URL(page.url());
    const sourceRoute = `${sourceUrl.pathname}${sourceUrl.search}${sourceUrl.hash}`;
    const recordPid = sourceUrl.pathname.split('/').at(-1)!;
    expect(sourceUrl.searchParams.get('tab')).toBe('record-overview');
    expect(sourceUrl.searchParams.get('filter.status')).toBe('active');
    expect(sourceUrl.searchParams.get('sort')).toBe('e2et_name:asc');
    expect(sourceUrl.searchParams.get('pageNum')).toBe('1');

    const pageScroll = page.locator('[data-aura-scroll-container="page-content"]');
    await expect(pageScroll).toBeVisible();
    const sourceScrollY = await pageScroll.evaluate((container) => {
      const target = Math.min(160, container.scrollHeight - container.clientHeight);
      container.scrollTo(0, Math.max(0, target));
      return container.scrollTop;
    });
    expect(
      sourceScrollY,
      'detail route must exercise a non-window scroll container',
    ).toBeGreaterThan(0);

    const sessionResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        apiPath(response.url()) === '/api/authoring/sessions',
    );
    const authoringEntry = page.getByTestId('contextual-authoring-enter');
    await expect(authoringEntry).toBeVisible();
    const entryBox = await authoringEntry.boundingBox();
    expect(entryBox, 'fixed contextual entry pointer target').not.toBeNull();
    expect(await pageScroll.evaluate((container) => container.scrollTop)).toBe(sourceScrollY);
    await page.mouse.click(entryBox!.x + entryBox!.width / 2, entryBox!.y + entryBox!.height / 2);
    const opened = await expectApiData<AuthoringSession>(
      await sessionResponse,
      'open detail authoring with interaction context',
    );
    const interaction = opened.interactionContext ?? {};
    expect(interaction.route).toBe(sourceRoute);
    expect(interaction.recordPid).toBe(recordPid);
    expect(interaction.tabId).toBe('record-overview');
    expect(interaction.filters).toEqual({ 'filter.status': ['active'] });
    expect(interaction.sort).toEqual({ sort: ['e2et_name:asc'] });
    expect(interaction.scroll).toEqual({
      container: 'page-content',
      x: 0,
      y: sourceScrollY,
    });

    const selectedBlock = findFirstSelectableBlock(opened.snapshot);
    expect(selectedBlock?.id, 'detail authoring snapshot selectable block').toBeTruthy();
    const selectedBlockId = String(selectedBlock!.id);
    await page.getByTestId('authoring-outline-open').click();
    const contextualOutline = page.getByTestId(`authoring-outline-${selectedBlockId}`);
    await expect(contextualOutline).toBeVisible();
    await contextualOutline.click();
    await expect(contextualOutline).toHaveClass(/bg-blue-50/);
    await page
      .getByTestId('authoring-inspector')
      .getByRole('button', { name: '高级设置', exact: true })
      .click();
    const explain = page.getByRole('dialog', { name: '进入应用设计中心' });
    await explain.getByRole('button', { name: '继续到应用设计中心' }).click();
    await expect(page).toHaveURL(/\/unified-designer\?authoringSession=/);
    await expect(page.getByTestId(`canvas-block-${selectedBlockId}`)).toHaveAttribute(
      'data-selected',
      'true',
    );

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible();
    await expect(page.getByTestId(`canvas-block-${selectedBlockId}`)).toHaveAttribute(
      'data-selected',
      'true',
    );
    const returnSessionResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' &&
        apiPath(response.url()) === `/api/authoring/sessions/${opened.sessionPid}`,
    );
    const returnCapabilitiesResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' &&
        apiPath(response.url()) === '/api/authoring/capabilities',
    );
    await page.getByTestId('designer-return-link').click();

    await expect(page).toHaveURL(sourceRoute);
    expect((await returnSessionResponse).status(), 'return session reload').toBe(200);
    expect((await returnCapabilitiesResponse).status(), 'return capability reload').toBe(200);
    await expect(page.getByTestId('contextual-authoring-surface')).toBeVisible({ timeout: 10_000 });
    const restoredOutline = page.getByTestId(`authoring-outline-${selectedBlockId}`);
    await expect(page.getByRole('dialog', { name: '页面大纲' })).toBeHidden();
    await expect(restoredOutline).toBeHidden();
    await expect(restoredOutline).toHaveClass(/bg-blue-50/);
    await expect(page.getByTestId('authoring-inspector')).toBeVisible();
    await expect
      .poll(async () =>
        Math.abs((await pageScroll.evaluate((container) => container.scrollTop)) - sourceScrollY),
      )
      .toBeLessThanOrEqual(4);
    const restored = await loadSession(page, opened.sessionPid, 'reload returned detail session');
    expect(restored.interactionContext).toMatchObject({
      route: sourceRoute,
      recordPid,
      tabId: 'record-overview',
      filters: { 'filter.status': ['active'] },
      sort: { sort: ['e2et_name:asc'] },
      scroll: { container: 'page-content', x: 0, y: sourceScrollY },
      selection: selectedBlockId,
    });
    expect((restored.interactionContext?.outlinePath as string[]).at(-1)).toBe(selectedBlockId);

    await mkdir(SCREENSHOT_DIR, { recursive: true });
    await page.screenshot({
      path: resolve(SCREENSHOT_DIR, 'pc-auth-042-context-return.png'),
      fullPage: true,
    });
  });

  test('PC-AUTH-044 @critical — new-page workspace fails closed when no published model is available', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    const source = await enterAuthoringFromMenu(page);
    let createRequests = 0;
    page.on('request', (request) => {
      if (
        request.method() === 'POST' &&
        apiPath(request.url()) ===
          `/api/authoring/sessions/${source.sessionPid}/new-page-workspaces`
      ) {
        createRequests += 1;
      }
    });
    await page.route('**/api/authoring/new-page-workspace-options', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: '0',
          data: { models: [], parentMenus: [], permissions: [] },
        }),
      });
    });

    await page.getByRole('button', { name: '新页面 / 菜单' }).click();
    await page
      .getByRole('dialog', { name: '在应用设计中心创建页面' })
      .getByRole('button', { name: '继续到应用设计中心' })
      .click();

    const wizard = page.getByTestId('new-page-workspace-wizard');
    await expect(wizard).toBeVisible();
    await expect(wizard.getByLabel('业务模型')).toBeDisabled();
    await expect(wizard.getByLabel('业务模型')).toContainText('暂无已发布模型');
    await expect(wizard.getByRole('link', { name: '模型设计器' })).toHaveAttribute(
      'href',
      '/meta/models/new',
    );
    await expect(wizard.getByRole('button', { name: '创建并进入页面设计' })).toBeDisabled();
    expect(createRequests).toBe(0);

    await mkdir(SCREENSHOT_DIR, { recursive: true });
    await page.screenshot({
      path: resolve(SCREENSHOT_DIR, 'pc-auth-044-no-published-model.png'),
      fullPage: true,
    });
    await wizard.getByRole('link', { name: '返回现场' }).click();
    await expect(page).toHaveURL(new RegExp(`${gatePage.route}$`));
    expect(createRequests).toBe(0);
  });

  test('PC-AUTH-045 @critical — role, synthetic and audited identity previews stay isolated, readonly and recoverable', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    const opened = await enterAuthoringFromMenu(page);
    await selectTableInContextualInspector(page, opened);
    await enterStudioFromContextual(page);

    let targetLoadFailurePending = true;
    await page.route('**/api/authoring/sessions/*/role-preview-targets', async (route) => {
      if (route.request().method() === 'GET' && targetLoadFailurePending) {
        targetLoadFailurePending = false;
        await route.fulfill({
          status: 503,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'CONTROLLED_TARGET_FAILURE',
            message: 'target list unavailable',
          }),
        });
        return;
      }
      await route.continue();
    });
    await page.getByTestId('designer-mode-preview').click();
    await expect(page.getByTestId('role-preview-targets-error')).toContainText(
      'target list unavailable',
    );
    await page.getByTestId('designer-mode-edit').click();
    await page.getByTestId('designer-mode-preview').click();
    const roleSelect = page.getByTestId('role-preview-target-select');
    await expect.poll(() => roleSelect.locator('option').count()).toBeGreaterThan(2);
    await page.unroute('**/api/authoring/sessions/*/role-preview-targets');

    const targetRolePid = await roleSelect.locator('option').evaluateAll((options) => {
      const target = options.find((option) => {
        const value = (option as HTMLOptionElement).value;
        return value && value !== '__synthetic_fixture__';
      }) as HTMLOptionElement | undefined;
      return target?.value ?? '';
    });
    expect(targetRolePid, 'at least one real target role').toBeTruthy();

    let rolePreviewFailurePending = true;
    await page.route('**/api/authoring/sessions/*/role-structure-preview?*', async (route) => {
      if (route.request().method() === 'GET' && rolePreviewFailurePending) {
        rolePreviewFailurePending = false;
        await route.fulfill({
          status: 503,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'CONTROLLED_ROLE_FAILURE',
            message: 'role preview unavailable',
          }),
        });
        return;
      }
      await route.continue();
    });
    await roleSelect.selectOption(targetRolePid);
    await expect(page.getByTestId('role-preview-error')).toContainText('role preview unavailable');
    await expect(page.getByTestId('role-preview-fail-closed')).toBeVisible();
    await roleSelect.selectOption('');
    const rolePreviewResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' &&
        apiPath(response.url()) ===
          `/api/authoring/sessions/${opened.sessionPid}/role-structure-preview`,
    );
    await roleSelect.selectOption(targetRolePid);
    const rolePreview = await expectApiData<{
      actorIntersectionApplied: boolean;
      businessDataIncluded: boolean;
      exportAllowed: boolean;
      businessActionsAllowed: boolean;
    }>(await rolePreviewResponse, 'load target-role structure preview');
    expect(rolePreview).toMatchObject({
      actorIntersectionApplied: true,
      businessDataIncluded: false,
      exportAllowed: false,
      businessActionsAllowed: false,
    });
    await expect(page.getByTestId('role-structure-preview-banner')).toContainText(
      '不读取目标角色真实数据',
    );
    await expect(page.getByTestId('designer-export')).toBeDisabled();
    await expect(page.getByText(gatePage.recordMarker)).toHaveCount(0);
    await page.unroute('**/api/authoring/sessions/*/role-structure-preview?*');

    let syntheticFailurePending = true;
    await page.route('**/api/authoring/sessions/*/synthetic-preview', async (route) => {
      if (route.request().method() === 'GET' && syntheticFailurePending) {
        syntheticFailurePending = false;
        await route.fulfill({
          status: 503,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'CONTROLLED_FIXTURE_FAILURE',
            message: 'fixture unavailable',
          }),
        });
        return;
      }
      await route.continue();
    });
    await roleSelect.selectOption('__synthetic_fixture__');
    await expect(page.getByTestId('synthetic-preview-error')).toContainText('fixture unavailable');
    await expect(page.getByTestId('synthetic-preview-fail-closed')).toBeVisible();
    await roleSelect.selectOption('');
    const syntheticResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' &&
        apiPath(response.url()) ===
          `/api/authoring/sessions/${opened.sessionPid}/synthetic-preview`,
    );
    await roleSelect.selectOption('__synthetic_fixture__');
    const synthetic = await expectApiData<{
      source: string;
      isolatedFromTenantData: boolean;
      persisted: boolean;
      exportAllowed: boolean;
      businessActionsAllowed: boolean;
      records: Array<Record<string, unknown>>;
    }>(await syntheticResponse, 'load generated in-memory preview');
    expect(synthetic).toMatchObject({
      source: 'GENERATED_IN_MEMORY',
      isolatedFromTenantData: true,
      persisted: false,
      exportAllowed: false,
      businessActionsAllowed: false,
    });
    expect(synthetic.records).toHaveLength(3);
    expect(JSON.stringify(synthetic)).not.toContain(gatePage.recordMarker);
    await expect(page.getByTestId('synthetic-preview-banner')).toContainText('不查询真实租户记录');
    await expect(page.getByTestId('synthetic-preview-record-count')).toContainText('3 条合成记录');
    await expect(page.getByTestId('designer-export')).toBeDisabled();
    await expect(page.getByText(gatePage.recordMarker)).toHaveCount(0);
    await page.unroute('**/api/authoring/sessions/*/synthetic-preview');

    await roleSelect.selectOption(targetRolePid);
    await expect(page.getByTestId('role-structure-preview-banner')).toBeVisible();
    await page.getByTestId('identity-simulation-open').click();
    await page.getByTestId('identity-simulation-reason').fill('PC-AUTH-045 controlled denial');
    let identityStartFailurePending = true;
    await page.route('**/api/authoring/sessions/*/identity-simulations', async (route) => {
      if (route.request().method() === 'POST' && identityStartFailurePending) {
        identityStartFailurePending = false;
        await route.fulfill({
          status: 503,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'CONTROLLED_IDENTITY_FAILURE',
            message: 'identity start denied',
          }),
        });
        return;
      }
      await route.continue();
    });
    await page.getByTestId('identity-simulation-start').click();
    await expect(page.getByTestId('identity-simulation-error')).toContainText(
      'identity start denied',
    );
    await expect(page.getByTestId('role-structure-preview-banner')).toBeVisible();
    await page
      .getByTestId('identity-simulation-reason')
      .fill('PC-AUTH-045 audited identity review');
    const startResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        apiPath(response.url()) ===
          `/api/authoring/sessions/${opened.sessionPid}/identity-simulations` &&
        response.status() === 200,
    );
    await page.getByTestId('identity-simulation-start').click();
    const firstSimulation = await expectApiData<IdentitySimulation>(
      await startResponse,
      'start audited identity simulation',
    );
    expect(firstSimulation).toMatchObject({
      status: 'ACTIVE',
      actorIntersectionApplied: true,
      businessDataIncluded: false,
      readOnly: true,
      exportAllowed: false,
      businessActionsAllowed: false,
    });
    await expect(page.getByTestId('identity-simulation-banner')).toHaveAttribute(
      'data-status',
      'ACTIVE',
    );
    await expect(page.getByTestId('identity-simulation-countdown')).toBeVisible();
    await expect(page.getByTestId('designer-mode-edit')).toBeDisabled();
    await expect(page.getByTestId('designer-mode-layout')).toBeDisabled();
    await expect(page.getByTestId('designer-save')).toBeDisabled();
    await expect(page.getByTestId('designer-export')).toHaveCount(0);
    await expect(page.getByTestId('designer-import')).toHaveCount(0);
    await expect(page.getByTestId('designer-ai-copilot')).toHaveCount(0);
    await page.unroute('**/api/authoring/sessions/*/identity-simulations');

    const duplicate = await page.request.post(
      `/api/authoring/sessions/${opened.sessionPid}/identity-simulations`,
      {
        data: {
          rolePid: targetRolePid,
          durationMinutes: 5,
          reason: 'PC-AUTH-045 duplicate active attempt',
        },
      },
    );
    expect(duplicate.status()).toBe(409);

    let recoveryFailureEnabled = true;
    await page.route('**/api/authoring/sessions/*/identity-simulations', async (route) => {
      if (route.request().method() === 'GET' && recoveryFailureEnabled) {
        await route.fulfill({
          status: 503,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 'CONTROLLED_RECOVERY_FAILURE',
            message: 'recovery unavailable',
          }),
        });
        return;
      }
      await route.continue();
    });
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('identity-simulation-recovery-fail-closed')).toContainText(
      '工作台已暂停编辑',
    );
    await expect(page.getByTestId('designer-mode-edit')).toBeDisabled();
    recoveryFailureEnabled = false;
    await page.getByTestId('identity-simulation-recovery-retry').click();
    await expect(page.getByTestId('identity-simulation-banner')).toHaveAttribute(
      'data-status',
      'ACTIVE',
    );
    await expect(page.getByTestId('unified-designer-workbench')).toHaveAttribute(
      'data-mode',
      'preview',
    );
    await page.unroute('**/api/authoring/sessions/*/identity-simulations');

    const endResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        apiPath(response.url()) ===
          `/api/authoring/identity-simulations/${firstSimulation.simulationPid}/end`,
    );
    await page.getByTestId('identity-simulation-end').click();
    const ended = await expectApiData<IdentitySimulation>(
      await endResponse,
      'end identity simulation',
    );
    expect(ended.status).toBe('ENDED');
    await expect(page.getByTestId('identity-simulation-banner')).toHaveAttribute(
      'data-status',
      'ENDED',
    );
    await page.getByTestId('identity-simulation-dismiss').click();
    await expect(page.getByTestId('identity-simulation-banner')).toHaveCount(0);

    await expect(page.getByTestId('role-structure-preview-banner')).toBeVisible();
    await page.getByTestId('identity-simulation-open').click();
    await page.getByTestId('identity-simulation-reason').fill('PC-AUTH-045 expiry review');
    const secondStartResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        apiPath(response.url()) ===
          `/api/authoring/sessions/${opened.sessionPid}/identity-simulations`,
    );
    await page.getByTestId('identity-simulation-start').click();
    const expiring = await expectApiData<IdentitySimulation>(
      await secondStartResponse,
      'start expiring identity simulation',
    );
    await expireIdentitySimulation(expiring.simulationPid);
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('identity-simulation-banner')).toHaveAttribute(
      'data-status',
      'EXPIRED',
    );
    await expect(page.getByTestId('identity-simulation-dismiss')).toBeVisible();
    await page.getByTestId('identity-simulation-dismiss').click();
    await expect(page.getByTestId('identity-simulation-banner')).toHaveCount(0);

    const activeAfter = await expectApiData<IdentitySimulation[]>(
      await page.request.get(`/api/authoring/sessions/${opened.sessionPid}/identity-simulations`),
      'verify no active identity simulation remains',
    );
    expect(activeAfter).toEqual([]);
    const audits = await loadAuditRows(opened.changeSetPid);
    expect(audits.map((row) => row.event_type)).toEqual(
      expect.arrayContaining([
        'IDENTITY_SIMULATION_STARTED',
        'IDENTITY_SIMULATION_ACCESSED',
        'IDENTITY_SIMULATION_ENDED',
        'IDENTITY_SIMULATION_EXPIRED',
        'IDENTITY_SIMULATION_ACKNOWLEDGED',
      ]),
    );
    expect(JSON.stringify(audits)).not.toContain('PC-AUTH-045 audited identity review');
    expect(JSON.stringify(audits)).not.toContain('PC-AUTH-045 expiry review');
    expect(await countGateRecord(gatePage.recordMarker)).toBeGreaterThan(0);

    await mkdir(SCREENSHOT_DIR, { recursive: true });
    await page.screenshot({
      path: resolve(SCREENSHOT_DIR, 'pc-auth-045-audited-preview-boundaries.png'),
      fullPage: true,
    });
  });

  test('PC-AUTH-046 @critical — governed AI remains a typed human-reviewed proposal with atomic apply and stale rejection', async ({
    page,
    browser,
  }, testInfo) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    await ensureReviewer(page);
    const opened = await enterAuthoringFromMenu(page);
    await selectTableInContextualInspector(page, opened);
    await enterStudioFromContextual(page);
    await expect(page.getByTestId('designer-ai-copilot')).toBeVisible();

    let liveProviderCanary: {
      status: number | null;
      verdict: 'available' | 'blocked_by_env';
      response: string;
    };
    try {
      const liveProvider = await page.request.post('/api/agent/nl-modeling/generate-page', {
        data: {
          systemPrompt: 'Return one JSON object only: {"items":[]}',
          message: 'AuraBoot WP06 provider canary; do not invoke tools.',
        },
        timeout: 15_000,
      });
      liveProviderCanary = {
        status: liveProvider.status(),
        verdict: liveProvider.ok() ? 'available' : 'blocked_by_env',
        response: (await liveProvider.text()).slice(0, 500),
      };
    } catch (error) {
      liveProviderCanary = {
        status: null,
        verdict: 'blocked_by_env',
        response:
          error instanceof Error ? error.message.slice(0, 500) : String(error).slice(0, 500),
      };
    }
    await testInfo.attach('pc-auth-046-live-provider-canary.json', {
      body: JSON.stringify(liveProviderCanary, null, 2),
      contentType: 'application/json',
    });
    if (liveProviderCanary.status !== null) {
      expect([200, 400, 401, 403, 429, 500, 503]).toContain(liveProviderCanary.status);
    }

    const baseline = await loadSession(page, opened.sessionPid, 'load AI proposal baseline');
    const capabilities = await expectApiData<CapabilityRegistry>(
      await page.request.get('/api/authoring/capabilities'),
      'load AI typed capability registry',
    );
    const target = findGovernedAiDensityTarget(baseline.snapshot, capabilities);
    const initialValue = readObjectPath(target.block, target.propertyPath);
    const proposedValue = initialValue === 'compact' ? 'comfortable' : 'compact';
    const directValue = proposedValue === 'compact' ? 'comfortable' : 'compact';
    const operation: 'ADD' | 'REPLACE' = initialValue === undefined ? 'ADD' : 'REPLACE';
    expect(target.allowedOperations).toContain(operation);

    let providerCall = 0;
    let proposalRequests = 0;
    page.on('request', (request) => {
      if (
        request.method() === 'POST' &&
        apiPath(request.url()) === `/api/authoring/sessions/${opened.sessionPid}/ai-patch-proposals`
      ) {
        proposalRequests += 1;
      }
    });
    await page.route('**/api/agent/nl-modeling/generate-page', async (route) => {
      providerCall += 1;
      if (providerCall === 1) {
        await route.fulfill({
          status: 503,
          contentType: 'application/json',
          body: JSON.stringify({ error: 'controlled provider unavailable' }),
        });
        return;
      }
      if (providerCall === 2) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            content:
              '{"items":[{"blockId":"unknown-block","propertyPath":"/props/density","operation":"REPLACE","value":"compact"}]}',
          }),
        });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          content: JSON.stringify({
            items: [
              {
                blockId: target.blockId,
                propertyPath: target.propertyPath,
                operation: providerCall >= 5 ? 'REPLACE' : operation,
                value: providerCall >= 5 ? directValue : proposedValue,
              },
            ],
          }),
        }),
      });
    });

    await page.getByTestId('designer-ai-copilot').click();
    const dialog = page.getByTestId('governed-ai-proposal-dialog');
    await expect(dialog).toContainText('不会直接修改页面或绕过审批');
    await expect(dialog).toContainText('草稿尚未变化');
    await page
      .getByTestId('governed-ai-description')
      .fill('把列表密度调整为更紧凑；仅生成待人工复核的属性提案');
    await page.getByTestId('governed-ai-proposal-generate').click();
    await expect(page.getByTestId('governed-ai-proposal-error')).toContainText(
      'controlled provider unavailable',
    );
    expect(proposalRequests).toBe(0);
    await page.getByTestId('governed-ai-proposal-generate').click();
    await expect(page.getByTestId('governed-ai-proposal-error')).toContainText(
      'unknown typed target',
    );
    expect(proposalRequests).toBe(0);

    const beforeItems = await loadAuthoringChangeItems(page, opened.sessionPid);
    const firstProposalResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        apiPath(response.url()) ===
          `/api/authoring/sessions/${opened.sessionPid}/ai-patch-proposals`,
    );
    await page.getByTestId('governed-ai-proposal-generate').click();
    const firstProposal = await expectApiData<AiPatchProposal>(
      await firstProposalResponse,
      'persist first governed AI proposal',
    );
    expect(firstProposal).toMatchObject({
      status: 'PROPOSED',
      baseRevision: baseline.revision,
      typedPatchOnly: true,
      requiresHumanApproval: true,
    });
    await expect(page.getByTestId('governed-ai-proposal-review')).toContainText(
      target.propertyPath,
    );
    await expect(page.getByTestId('governed-ai-proposal-item')).toContainText(
      String(proposedValue),
    );
    const beforeHumanDecision = await loadSession(
      page,
      opened.sessionPid,
      'proposal must not mutate draft before human decision',
    );
    expect(beforeHumanDecision.revision).toBe(baseline.revision);
    expect(readObjectPath(findTableBlock(beforeHumanDecision.snapshot)!, target.propertyPath)).toBe(
      initialValue,
    );
    expect(await loadAuthoringChangeItems(page, opened.sessionPid)).toEqual(beforeItems);

    const rejectResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        apiPath(response.url()) ===
          `/api/authoring/sessions/${opened.sessionPid}/ai-patch-proposals/${firstProposal.proposalPid}/reject`,
    );
    await page.getByTestId('governed-ai-proposal-discard').click();
    const rejected = await expectApiData<AiPatchProposal>(
      await rejectResponse,
      'reject AI proposal',
    );
    expect(rejected.status).toBe('REJECTED');
    await expect(dialog).toHaveCount(0);

    await page.getByTestId('designer-ai-copilot').click();
    await page
      .getByTestId('governed-ai-description')
      .fill('再次生成相同属性提案，等待人工确认后应用');
    const secondProposalResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        apiPath(response.url()) ===
          `/api/authoring/sessions/${opened.sessionPid}/ai-patch-proposals`,
    );
    await page.getByTestId('governed-ai-proposal-generate').click();
    const secondProposal = await expectApiData<AiPatchProposal>(
      await secondProposalResponse,
      'persist second governed AI proposal',
    );
    const applyResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        apiPath(response.url()) ===
          `/api/authoring/sessions/${opened.sessionPid}/ai-patch-proposals/${secondProposal.proposalPid}/apply`,
    );
    await page.getByTestId('governed-ai-proposal-apply').click();
    const applied = await expectApiData<ApplyAiPatchProposalResult>(
      await applyResponse,
      'human applies governed AI proposal',
    );
    expect(applied.proposal.status).toBe('APPLIED');
    expect(applied.session.revision).toBe(baseline.revision + 1);
    expect(readObjectPath(findTableBlock(applied.session.snapshot)!, target.propertyPath)).toBe(
      proposedValue,
    );
    expect(await loadAuthoringChangeItems(page, opened.sessionPid)).toHaveLength(
      beforeItems.length + 1,
    );

    await page.getByTestId('designer-ai-copilot').click();
    await page
      .getByTestId('governed-ai-description')
      .fill('创建一个随后被并发 revision 使其陈旧的属性提案');
    const staleProposalResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        apiPath(response.url()) ===
          `/api/authoring/sessions/${opened.sessionPid}/ai-patch-proposals`,
    );
    await page.getByTestId('governed-ai-proposal-generate').click();
    const staleProposal = await expectApiData<AiPatchProposal>(
      await staleProposalResponse,
      'persist soon-stale governed AI proposal',
    );
    const advanced = await expectApiData<PatchResult>(
      await page.request.patch(`/api/authoring/sessions/${opened.sessionPid}/patches`, {
        data: {
          expectedRevision: applied.session.revision,
          blockId: target.blockId,
          propertyPath: target.propertyPath,
          operation: 'REPLACE',
          value: directValue,
          manifestChecksum: target.manifestChecksum,
        },
      }),
      'advance revision outside proposed AI batch',
    );
    const staleApplyResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        apiPath(response.url()) ===
          `/api/authoring/sessions/${opened.sessionPid}/ai-patch-proposals/${staleProposal.proposalPid}/apply`,
    );
    await page.getByTestId('governed-ai-proposal-apply').click();
    expect((await staleApplyResponse).status()).toBe(409);
    await expect(page.getByTestId('governed-ai-proposal-error')).toBeVisible();
    const afterStale = await loadSession(
      page,
      opened.sessionPid,
      'verify stale AI rejection atomicity',
    );
    expect(afterStale.revision).toBe(advanced.session.revision);
    expect(readObjectPath(findTableBlock(afterStale.snapshot)!, target.propertyPath)).toBe(
      directValue,
    );
    expect(await loadAuthoringChangeItems(page, opened.sessionPid)).toHaveLength(
      beforeItems.length + 2,
    );
    const staleRejectResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        apiPath(response.url()) ===
          `/api/authoring/sessions/${opened.sessionPid}/ai-patch-proposals/${staleProposal.proposalPid}/reject`,
    );
    await page.getByTestId('governed-ai-proposal-discard').click();
    const staleRejected = await expectApiData<AiPatchProposal>(
      await staleRejectResponse,
      'reject stale proposal',
    );
    expect(staleRejected.status).toBe('REJECTED');

    const reviewer = await openReviewer(browser);
    try {
      const denied = await reviewer.request.post(
        `/api/authoring/sessions/${opened.sessionPid}/ai-patch-proposals`,
        {
          data: {
            expectedRevision: afterStale.revision,
            items: [
              {
                blockId: target.blockId,
                propertyPath: target.propertyPath,
                operation: 'REPLACE',
                value: proposedValue,
                manifestChecksum: target.manifestChecksum,
              },
            ],
          },
        },
      );
      expect(denied.status()).toBe(403);
    } finally {
      await reviewer.context().close();
    }

    const proposalRows = await loadAiProposalStatusCounts(opened.sessionPid);
    expect(proposalRows).toEqual({ APPLIED: 1, REJECTED: 2 });
    const audits = await loadAuditRows(opened.changeSetPid);
    expect(audits.map((row) => row.event_type)).toEqual(
      expect.arrayContaining([
        'AI_PATCH_PROPOSAL_CREATED',
        'AI_PATCH_PROPOSAL_APPLIED',
        'AI_PATCH_PROPOSAL_REJECTED',
      ]),
    );
    expect(JSON.stringify(audits)).not.toContain('再次生成相同属性提案');
    expect(JSON.stringify(audits)).not.toContain('创建一个随后被并发 revision');
    await page.unroute('**/api/agent/nl-modeling/generate-page');

    await mkdir(SCREENSHOT_DIR, { recursive: true });
    await page.getByTestId('designer-ai-copilot').click();
    await page.getByTestId('governed-ai-description').fill('PC-AUTH-046 最终受治理 AI 边界');
    await page.screenshot({
      path: resolve(SCREENSHOT_DIR, 'pc-auth-046-governed-ai-boundary.png'),
      fullPage: true,
    });
    await page.getByTestId('governed-ai-proposal-discard').click();
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

async function retainPhysicalFixture(
  browser: Browser,
  target: GatePage,
  fixturePath: string,
): Promise<void> {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();
  try {
    await login(page);
    const mobilePage = await expectApiData<Record<string, unknown>>(
      await page.request.get(`/api/mobile/pages/${encodeURIComponent(target.pageKey)}`),
      'load retained physical-device mobile page',
    );
    const runtime = mobilePage.runtime as Record<string, unknown> | undefined;
    expect(runtime?.source, 'physical fixture must resolve through immutable release').toBe(
      'AUTHORING_RELEASE',
    );
    expect(await countGateRecord(target.recordMarker), 'physical fixture real record').toBe(1);

    const auth = await loadAuthSnapshot(page);
    const tenantSpaces = await expectApiData<
      Array<{ tenantId: string | number; tenantName?: string; tenantDisplayName?: string }>
    >(
      await page.request.get('/api/tenant-selection/my-spaces'),
      'load retained physical fixture tenant',
    );
    const tenantSpace = tenantSpaces.find(
      (candidate) => String(candidate.tenantId) === String(auth.user.tenantId),
    );
    expect(tenantSpace, 'retained physical fixture tenant space').toBeTruthy();
    const client = new PgClient(PG_CONN);
    await client.connect();
    try {
      const release = await client.query<{
        release_pid: string;
        channel_version: string;
        source_version: string;
      }>(
        `SELECT release.pid AS release_pid,
                channel.row_version::text AS channel_version,
                item.source_version::text AS source_version
           FROM ab_page_schema page_schema
           JOIN ab_authoring_release_channel channel
             ON channel.tenant_id = page_schema.tenant_id
            AND channel.env_id = page_schema.env_id
            AND channel.resource_type = 'PAGE_SCHEMA'
            AND channel.resource_pid = page_schema.pid
           JOIN ab_authoring_release release
             ON release.id = channel.active_release_id
            AND release.status = 'ACTIVE'
           JOIN ab_authoring_release_item item
             ON item.release_id = release.id
            AND item.resource_type = 'PAGE_SCHEMA'
            AND item.resource_pid = page_schema.pid
          WHERE page_schema.page_key = $1
            AND page_schema.is_current = TRUE
            AND page_schema.deleted_flag = FALSE`,
        [target.pageKey],
      );
      expect(release.rows, 'one active retained physical fixture release').toHaveLength(1);
      const active = release.rows[0];
      expect(String(runtime?.releasePid)).toBe(active.release_pid);
      expect(String(runtime?.channelVersion)).toBe(active.channel_version);
      expect(String(runtime?.sourceVersion)).toBe(active.source_version);

      await mkdir(dirname(fixturePath), { recursive: true });
      await writeFile(
        fixturePath,
        `${JSON.stringify(
          {
            schemaVersion: 1,
            generatedAt: new Date().toISOString(),
            pagePid: target.pid,
            pageKey: target.pageKey,
            pageTitle: target.title,
            route: target.route,
            menuId: String(target.menuId),
            modelCode: 'e2et_record',
            recordMarker: target.recordMarker,
            rawFieldCode: 'e2et_name',
            tenantId: String(auth.user.tenantId),
            tenantName:
              tenantSpace!.tenantDisplayName?.trim() || tenantSpace!.tenantName?.trim() || '',
            runtime: {
              source: 'AUTHORING_RELEASE',
              releasePid: active.release_pid,
              channelVersion: Number(active.channel_version),
              sourceVersion: Number(active.source_version),
            },
          },
          null,
          2,
        )}\n`,
        'utf8',
      );
    } finally {
      await client.end();
    }
  } finally {
    await context.close();
  }
}

async function ensureTenantAdminPermissions(
  browser: Browser,
  permissionCodes: string[],
): Promise<AdminPermissionSnapshot> {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();
  try {
    await login(page);
    const roles = await expectApiData<RoleRecord[]>(
      await page.request.get('/api/roles/all'),
      'load tenant roles for WP06 admin permission setup',
    );
    const role = roles.find((candidate) => candidate.code === 'tenant_admin');
    expect(role?.pid, 'tenant_admin role for WP06 permission setup').toBeTruthy();
    const current = await expectApiData<string[]>(
      await page.request.get(`/api/roles/${encodeURIComponent(role!.pid)}/permissions`),
      'load tenant_admin permissions before WP06 gate',
    );
    const permissions = (
      await Promise.all(
        ['function', 'operation', 'data', 'model'].map(async (resourceType) =>
          expectApiData<PermissionRecord[]>(
            await page.request.get(`/api/permissions/resource-type/${resourceType}`),
            `load ${resourceType} permissions for WP06 gate`,
          ),
        ),
      )
    ).flat();
    const byCode = new Map(permissions.map((permission) => [permission.code, permission.pid]));
    const missing = permissionCodes.filter((code) => !byCode.has(code));
    expect(missing, `missing WP06 permissions: ${missing.join(', ')}`).toEqual([]);
    const merged = Array.from(
      new Set([...current.map(String), ...permissionCodes.map((code) => byCode.get(code)!)]),
    );
    if (merged.length !== current.length) {
      await expectApiData<boolean>(
        await page.request.post(`/api/roles/${encodeURIComponent(role!.pid)}/permissions`, {
          data: merged,
        }),
        'grant WP06 permissions to tenant_admin',
      );
    }
    return { rolePid: role!.pid, permissionPids: current.map(String) };
  } finally {
    await context.close();
  }
}

async function restoreTenantAdminPermissions(
  browser: Browser,
  snapshot: AdminPermissionSnapshot,
): Promise<void> {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();
  try {
    await login(page);
    await expectApiData<boolean>(
      await page.request.post(`/api/roles/${encodeURIComponent(snapshot.rolePid)}/permissions`, {
        data: snapshot.permissionPids,
      }),
      'restore tenant_admin permissions after WP06 gate',
    );
  } finally {
    await context.close();
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
  const inspector = page.getByRole('dialog', { name: '属性检查器' });
  if (!(await inspector.isVisible())) {
    await page.getByTestId('authoring-inspector-open').click();
    await expect(inspector).toBeVisible();
  }
  await inspector.getByRole('button', { name: '高级设置', exact: true }).click();
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
  await expect(page.getByRole('dialog', { name: '页面大纲' })).toBeHidden();
  await expect(page.getByRole('dialog', { name: '属性检查器' })).toBeVisible();
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

async function expireIdentitySimulation(simulationPid: string): Promise<void> {
  const client = new PgClient(PG_CONN);
  await client.connect();
  try {
    const result = await client.query(
      `UPDATE ab_authoring_identity_simulation
          SET started_at = CURRENT_TIMESTAMP - INTERVAL '2 minutes',
              expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute',
              updated_at = CURRENT_TIMESTAMP
        WHERE pid = $1 AND status = 'ACTIVE'`,
      [simulationPid],
    );
    expect(result.rowCount, 'active identity simulation selected for expiry').toBe(1);
  } finally {
    await client.end();
  }
}

async function countGateRecord(recordMarker: string): Promise<number> {
  const client = new PgClient(PG_CONN);
  await client.connect();
  try {
    const result = await client.query<{ count: string }>(
      'SELECT COUNT(*)::text AS count FROM mt_e2et_record WHERE e2et_name = $1',
      [recordMarker],
    );
    return Number(result.rows[0]?.count ?? 0);
  } finally {
    await client.end();
  }
}

async function loadAiProposalStatusCounts(sessionPid: string): Promise<Record<string, number>> {
  const client = new PgClient(PG_CONN);
  await client.connect();
  try {
    const result = await client.query<{ status: string; count: string }>(
      `SELECT status, COUNT(*)::text AS count
         FROM ab_authoring_ai_patch_proposal
        WHERE source_session_pid = $1
        GROUP BY status
        ORDER BY status`,
      [sessionPid],
    );
    return Object.fromEntries(result.rows.map((row) => [row.status, Number(row.count)]));
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

async function mutateAuthoringDraftContent(
  changeSetPid: string,
  blockId: string,
  content: string,
): Promise<void> {
  const client = new PgClient(PG_CONN);
  await client.connect();
  try {
    const draft = await client.query<{ id: string; snapshot: Record<string, unknown> }>(
      `SELECT draft.id::text, draft.snapshot
         FROM ab_authoring_resource_draft draft
         JOIN ab_authoring_change_set change_set ON change_set.id = draft.change_set_id
        WHERE change_set.pid = $1 AND change_set.deleted_flag = FALSE`,
      [changeSetPid],
    );
    expect(draft.rows).toHaveLength(1);
    const snapshot = draft.rows[0].snapshot;
    const mutate = (blocks: unknown[]): boolean => {
      for (const item of blocks) {
        if (!item || typeof item !== 'object' || Array.isArray(item)) continue;
        const block = item as Record<string, unknown>;
        if (block.id === blockId) {
          block.props = {
            ...((block.props as Record<string, unknown> | undefined) ?? {}),
            content,
          };
          return true;
        }
        if (Array.isArray(block.blocks) && mutate(block.blocks)) return true;
      }
      return false;
    };
    expect(mutate(snapshot.blocks as unknown[]), `draft block ${blockId} must exist`).toBe(true);
    await client.query(
      `UPDATE ab_authoring_resource_draft
          SET snapshot = $2::jsonb,
              validation_state = 'UNVALIDATED', impact_state = 'UNKNOWN',
              stale_reason = NULL, updated_at = CURRENT_TIMESTAMP
        WHERE id = $1::bigint`,
      [draft.rows[0].id, JSON.stringify(snapshot)],
    );
    const stored = await client.query<{ content: string }>(
      `WITH RECURSIVE block_tree AS (
         SELECT block
           FROM ab_authoring_resource_draft draft,
                LATERAL jsonb_array_elements(draft.snapshot -> 'blocks') block
          WHERE draft.id = $1::bigint
         UNION ALL
         SELECT child
           FROM block_tree,
                LATERAL jsonb_array_elements(COALESCE(block_tree.block -> 'blocks', '[]'::jsonb)) child
       )
       SELECT block #>> '{props,content}' AS content
         FROM block_tree WHERE block ->> 'id' = $2`,
      [draft.rows[0].id, blockId],
    );
    expect(stored.rows[0]?.content).toBe(content);
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
  await saveInlineAuthoring(page);
  const saved = await expectApiData<PatchResult>(await saveResponse, 'save density revision');
  await expect(page.getByText('0 项未保存')).toBeVisible();

  const inspector = page.getByRole('dialog', { name: '属性检查器' });
  await page.getByTestId('authoring-inspector-open').click();
  await expect(inspector).toBeVisible();
  await inspector.getByRole('button', { name: '高级设置', exact: true }).click();
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

async function settleAuthoringDrag(page: Page): Promise<void> {
  await page
    .locator('[data-testid="drag-overlay-ghost"]')
    .waitFor({ state: 'detached', timeout: 5000 })
    .catch(() => {});
  await page.evaluate(
    () =>
      new Promise<void>((resolveFrame) =>
        requestAnimationFrame(() => requestAnimationFrame(() => resolveFrame())),
      ),
  );
  // @dnd-kit intentionally keeps its capture-phase ghost-click suppressor for
  // 50 ms after pointer-up. Playwright can teleport from the canvas to a toolbar
  // action faster than a person can, so wait out that documented sensor cleanup
  // window before the next independent pointer action.
  await page.waitForTimeout(75);
}

async function authoringPointerDragTo(
  page: Page,
  source: Locator,
  target: Locator,
  options?: { targetPosition?: { x: number; y: number } },
): Promise<void> {
  const canvasBlocks = page.locator('[data-testid^="canvas-block-"]');
  const beforeCount = await canvasBlocks.count();
  await expect(async () => {
    await target.scrollIntoViewIfNeeded();
    await source.scrollIntoViewIfNeeded();
    const sourceBox = await source.boundingBox();
    const targetBox = await target.boundingBox();
    expect(sourceBox, 'pointer drag source bounding box').not.toBeNull();
    expect(targetBox, 'pointer drag target bounding box').not.toBeNull();
    const startX = sourceBox!.x + sourceBox!.width / 2;
    const startY = sourceBox!.y + sourceBox!.height / 2;
    const endX = targetBox!.x + (options?.targetPosition?.x ?? targetBox!.width / 2);
    const endY = targetBox!.y + (options?.targetPosition?.y ?? targetBox!.height / 2);
    await page.mouse.move(startX, startY);
    await page.mouse.down();
    await page.mouse.move(startX + 12, startY + 12, { steps: 6 });
    await expect(
      page.getByTestId('drag-overlay-ghost'),
      'a real pointer drag must expose the visible drag object before drop',
    ).toBeVisible();
    await page.mouse.move(endX, endY, { steps: 18 });
    await page.mouse.move(endX + 2, endY + 2, { steps: 4 });
    await page.mouse.up();
    await settleAuthoringDrag(page);
    expect(await canvasBlocks.count()).toBeGreaterThan(beforeCount);
  }).toPass({ timeout: 20_000 });
}

async function authoringContainerDropPosition(target: Locator): Promise<{ x: number; y: number }> {
  const box = await target.boundingBox();
  expect(box, 'authoring container bounding box').not.toBeNull();
  return { x: 6, y: Math.min(200, Math.max(24, box!.height / 2)) };
}

async function switchAuthoringResourceTab(
  page: Page,
  tab: 'outline' | 'blocks' | 'fields',
): Promise<void> {
  const button = page.getByTestId(`resource-tab-${tab}`);
  await expect(async () => {
    await button.click();
    await expect(button).toHaveAttribute('data-active', 'true', { timeout: 1000 });
  }).toPass({ timeout: 10_000 });
}

async function authoringDragCanvasBlockBefore(
  page: Page,
  movingBlockId: string,
  targetBlockId: string,
): Promise<void> {
  const handle = page.getByTestId(`block-drag-handle-${movingBlockId}`);
  const target = page.getByTestId(`canvas-block-${targetBlockId}`);
  await expect(handle).toBeVisible();
  await expect(target).toBeVisible();
  for (let attempt = 0; attempt < 3; attempt += 1) {
    await target.scrollIntoViewIfNeeded();
    await handle.scrollIntoViewIfNeeded();
    const handleBox = await handle.boundingBox();
    expect(handleBox, 'reorder drag handle bounding box').not.toBeNull();
    const startX = handleBox!.x + handleBox!.width / 2;
    const startY = handleBox!.y + handleBox!.height / 2;
    await page.mouse.move(startX, startY);
    await page.mouse.down();
    let beforeIntentVisible = false;
    try {
      await page.mouse.move(startX + 10, startY + 10, { steps: 6 });
      await expect(
        page.getByTestId('drag-overlay-ghost'),
        'a reorder gesture must activate the visible drag object',
      ).toBeVisible({ timeout: 2_000 });
      // dnd-kit auto-scrolls the canvas while a lower block moves upward. Re-read
      // the live target box after each move instead of dropping at stale coordinates.
      for (let targetAttempt = 0; targetAttempt < 6; targetAttempt += 1) {
        const liveTargetBox = await target.boundingBox();
        expect(liveTargetBox, 'live reorder target bounding box').not.toBeNull();
        await page.mouse.move(
          liveTargetBox!.x + Math.min(24, liveTargetBox!.width / 4),
          liveTargetBox!.y + Math.min(10, liveTargetBox!.height / 6),
          { steps: targetAttempt === 0 ? 20 : 6 },
        );
        beforeIntentVisible = await page
          .getByTestId(`drop-indicator-before-${targetBlockId}`)
          .isVisible()
          .catch(() => false);
        if (beforeIntentVisible) break;
      }
    } finally {
      await page.mouse.up();
    }
    await settleAuthoringDrag(page);
    if (!beforeIntentVisible) continue;
    const ordered = await page.locator('[data-testid^="canvas-block-"]').evaluateAll(
      (nodes, [firstId, secondId]) => {
        const ids = nodes.map(
          (node) => node.getAttribute('data-testid')?.replace('canvas-block-', '') ?? '',
        );
        return ids.indexOf(firstId) < ids.indexOf(secondId);
      },
      [movingBlockId, targetBlockId] as const,
    );
    if (ordered) return;
  }
  throw new Error(`real pointer reorder did not place ${movingBlockId} before ${targetBlockId}`);
}

async function expectAuthoringBlockBefore(
  page: Page,
  firstBlockId: string,
  secondBlockId: string,
): Promise<void> {
  await expect
    .poll(async () => {
      const ids = await page
        .locator('[data-testid^="canvas-block-"]')
        .evaluateAll((nodes) =>
          nodes.map((node) => node.getAttribute('data-testid')?.replace('canvas-block-', '') ?? ''),
        );
      return ids.indexOf(firstBlockId) < ids.indexOf(secondBlockId);
    })
    .toBe(true);
}

async function loadAuthoringChangeItems(page: Page, sessionPid: string): Promise<ChangeItem[]> {
  return expectApiData<ChangeItem[]>(
    await page.request.get(`/api/authoring/sessions/${sessionPid}/change-items`),
    'load governed Builder ChangeItems',
  );
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
  await expect(page.getByRole('dialog', { name: '页面大纲' })).toBeHidden();
  await expect(page.getByRole('dialog', { name: '属性检查器' })).toBeVisible();
  const editor = page.getByTestId('authoring-property-/props/density').locator('input');
  await expect(editor).toBeVisible();
  await editor.fill(density);
  await expect(page.getByText('1 项未保存')).toBeVisible();
  return { density };
}

async function saveInlineAuthoring(page: Page): Promise<void> {
  await closeInlineInspector(page);
  await page.getByRole('button', { name: '保存', exact: true }).click();
}

async function closeInlineInspector(page: Page): Promise<void> {
  const inspector = page.getByRole('dialog', { name: '属性检查器' });
  if (!(await inspector.isVisible())) return;
  await inspector.getByRole('button', { name: '关闭属性检查器' }).click();
  await expect(inspector).toBeHidden();
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

function findGovernedAiDensityTarget(
  snapshot: Record<string, unknown>,
  capabilities: CapabilityRegistry,
): {
  block: Record<string, unknown>;
  blockId: string;
  propertyPath: string;
  allowedOperations: string[];
  manifestChecksum: string;
} {
  const block = findTableBlock(snapshot);
  expect(block?.id, 'table target for governed AI proposal').toBeTruthy();
  const blockType = String(block!.blockType ?? block!.type ?? '');
  const manifest = capabilities.manifests.find((candidate) => candidate.blockType === blockType);
  expect(manifest, `capability manifest for AI target ${blockType}`).toBeTruthy();
  const property = Object.values(manifest!.properties).find(
    (candidate) => candidate.propertyPath === '/props/density',
  );
  expect(property, 'declared /props/density AI target').toBeTruthy();
  return {
    block: block!,
    blockId: String(block!.id),
    propertyPath: property!.propertyPath,
    allowedOperations: property!.allowedOperations,
    manifestChecksum: manifest!.checksum,
  };
}

function findFirstSelectableBlock(value: unknown): Record<string, unknown> | null {
  if (Array.isArray(value)) {
    for (const item of value) {
      const found = findFirstSelectableBlock(item);
      if (found) return found;
    }
    return null;
  }
  if (!value || typeof value !== 'object') return null;
  const record = value as Record<string, unknown>;
  if (
    typeof record.id === 'string' &&
    record.id &&
    (typeof record.blockType === 'string' || typeof record.type === 'string')
  ) {
    return record;
  }
  return findFirstSelectableBlock(record.blocks);
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
