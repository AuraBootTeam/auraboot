/**
 * AMOS T06 trace graph — deployed browser permission/limit/error matrix.
 *
 * This suite is intentionally opt-in because it provisions personas and appends
 * trace facts to the deployed runtime. Run it against a fresh composed stack with:
 *   MO_TRACE_VISUAL_MATRIX=1
 * Evidence screenshots are emitted as Playwright attachments under the run output.
 */
import {
  expect,
  test,
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type Page,
} from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { loginViaUI } from '../../helpers/wd-fixtures';

const PASSWORD = 'Test2026x';
const ENABLED = process.env.MO_TRACE_VISUAL_MATRIX === '1';
const TAG = process.env.MO_TRACE_VISUAL_TAG ?? `visual-${Date.now()}`;

const FORWARD_BLOCK = '[data-testid="trace-graph-block-mo_trace_forward_graph"]';
const BACKWARD_BLOCK = '[data-testid="trace-graph-block-mo_trace_backward_graph"]';

test.skip(!ENABLED, 'set MO_TRACE_VISUAL_MATRIX=1 to run against a seeded deployed stack');

test.describe.serial('mo trace graph — permission, limit and error matrix', () => {
  const state: {
    rootPid?: string;
    childPids: string[];
    firstEdgePid?: string;
    viewerEmail?: string;
    deniedEmail?: string;
  } = { childPids: [] };

  function unwrap(result: unknown): Record<string, unknown> {
    if (result && typeof result === 'object' && 'data' in result) {
      return (result as { data?: unknown }).data as Record<string, unknown>;
    }
    return (result ?? {}) as Record<string, unknown>;
  }

  function pid(result: unknown): string {
    const value = unwrap(result);
    for (const key of ['pid', 'recordPid', 'recordId', 'id']) {
      if (value[key]) return String(value[key]);
    }
    throw new Error(`command result has no pid: ${JSON.stringify(result)}`);
  }

  async function executeAdminCommand(
    request: APIRequestContext,
    command: string,
    payload: Record<string, unknown>,
  ): Promise<Record<string, unknown>> {
    const response = await request.post(`/api/meta/commands/execute/${command}`, {
      data: {
        clientRequestId: `amos-t06-matrix:${TAG}:${command}:${Math.random()}`,
        payload,
      },
    });
    const body = await response.json().catch(() => null);
    expect(
      response.ok(),
      `${command} HTTP ${response.status()}: ${JSON.stringify(body)}`,
    ).toBe(true);
    expect(body?.code, `${command} envelope: ${JSON.stringify(body)}`).toBe('0');
    return unwrap(body?.data);
  }

  async function ensureUser(
    request: APIRequestContext,
    email: string,
    roleCodes: string[],
  ): Promise<void> {
    const login = await request.post('/api/auth/login', {
      data: { email, password: PASSWORD },
    });
    if (login.ok()) {
      const body = await login.json().catch(() => null);
      if (body?.code === '0' && body?.data?.jwt) return;
    }

    const response = await request.post('/api/admin/users', {
      data: {
        email,
        displayName: roleCodes[0],
        initialPassword: PASSWORD,
        roleCodes,
        sendInviteEmail: false,
      },
    });
    const body = await response.json().catch(() => null);
    expect(
      response.ok(),
      `provision ${email} HTTP ${response.status()}: ${JSON.stringify(body)}`,
    ).toBe(true);
  }

  async function openAs(
    browser: Browser,
    email: string,
  ): Promise<{ context: BrowserContext; page: Page }> {
    const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    const page = await context.newPage();
    await loginViaUI(page, email, PASSWORD);
    return { context, page };
  }

  async function attachVisual(page: Page, name: string): Promise<void> {
    const screenshot = await page.screenshot({ fullPage: true });
    const evidenceDir = process.env.MO_TRACE_EVIDENCE_DIR;
    if (evidenceDir) {
      await mkdir(evidenceDir, { recursive: true });
      await writeFile(path.join(evidenceDir, `${name}.png`), screenshot);
    }
    await test.info().attach(name, { body: screenshot, contentType: 'image/png' });
  }

  test.beforeAll(async ({ request }) => {
    state.viewerEmail = `amos-t06-viewer-${TAG}@e2e.local`;
    state.deniedEmail = `amos-t06-denied-${TAG}@e2e.local`;
    await ensureUser(request, state.viewerEmail, ['mo_trace_viewer']);
    await ensureUser(request, state.deniedEmail, ['tenant_member']);

    const occurredAt = new Date(Date.now() - 5_000).toISOString();
    const makeUnit = (ownerPid: string) => executeAdminCommand(request, 'mo:append_trace_unit', {
      ownerNamespace: 'inventory',
      ownerPid,
      version: 1,
      kind: 'batch',
      materialRef: `material:amos-t06-matrix-${TAG}`,
      quantity: 100,
      uom: 'EA',
      occurredAt,
    });

    state.rootPid = pid(await makeUnit(`t06-matrix-root-${TAG}`));
    state.childPids.push(pid(await makeUnit(`t06-matrix-child-a-${TAG}`)));
    state.childPids.push(pid(await makeUnit(`t06-matrix-child-b-${TAG}`)));

    const endpoint = (unitPid: string, quantity: number) => ({ unitPid, quantity, uom: 'EA' });
    const firstEdge = await executeAdminCommand(request, 'mo:append_trace_edge', {
      edgeType: 'split',
      occurredAt,
      sources: [endpoint(state.rootPid, 60)],
      targets: [endpoint(state.childPids[0], 60)],
      factRefs: [{
        type: 'material_movement',
        ownerNamespace: 'inventory',
        ownerPid: `t06-matrix-move-a-${TAG}`,
        version: 1,
      }],
    });
    const secondEdge = await executeAdminCommand(request, 'mo:append_trace_edge', {
      edgeType: 'split',
      occurredAt,
      sources: [endpoint(state.rootPid, 40)],
      targets: [endpoint(state.childPids[1], 40)],
      factRefs: [{
        type: 'material_movement',
        ownerNamespace: 'inventory',
        ownerPid: `t06-matrix-move-b-${TAG}`,
        version: 1,
      }],
    });
    state.firstEdgePid = pid(firstEdge);
    expect(pid(secondEdge)).not.toBe(state.firstEdgePid);
  });

  test('permission: viewer renders the real graph while a member is denied', async ({ browser }) => {
    const allowed = await openAs(browser, state.viewerEmail!);
    try {
      await allowed.page.goto(`/p/mo_trace_graph/view/${state.rootPid}`);
      await expect(allowed.page.locator(FORWARD_BLOCK)).toBeVisible({ timeout: 30_000 });
      await expect(
        allowed.page.locator(`${FORWARD_BLOCK} [data-testid="trace-node-${state.rootPid}"]`),
      ).toBeVisible();
      await expect(allowed.page.locator(`${FORWARD_BLOCK} .react-flow__edge`).first()).toBeVisible();
      await expect(allowed.page.locator(BACKWARD_BLOCK)).toBeVisible();
      await attachVisual(allowed.page, 'permission-viewer-graph');
    } finally {
      await allowed.context.close();
    }

    const denied = await openAs(browser, state.deniedEmail!);
    try {
      await denied.page.goto(`/p/mo_trace_graph/view/${state.rootPid}`);
      await expect(denied.page.getByRole('heading', { name: /Page Unavailable/i })).toBeVisible({
        timeout: 20_000,
      });
      await expect(
        denied.page.getByText(/Access denied|无权限|未授权|403/i),
      ).toBeVisible();
      await expect(denied.page.locator(FORWARD_BLOCK)).toHaveCount(0);
      await expect(denied.page.locator(BACKWARD_BLOCK)).toHaveCount(0);
      await expect(denied.page.locator('.react-flow__edge')).toHaveCount(0);
      const direct = await denied.page.request.get('/api/ext/mo/trace/graph', {
        params: { unitPid: state.rootPid!, direction: 'forward' },
      });
      expect(direct.status()).toBe(403);
      await attachVisual(denied.page, 'permission-denied-page');
    } finally {
      await denied.context.close();
    }
  });

  test('limit: traversal bounds fail closed with readable UI feedback', async ({ browser }) => {
    const session = await openAs(browser, state.viewerEmail!);
    const { page } = session;
    try {
      await page.goto(`/p/mo_trace_unit/view/${state.rootPid}`);
      await expect(page.getByTestId('workbench-action-trace_forward')).toBeVisible({
        timeout: 30_000,
      });
      await page.getByTestId('workbench-action-trace_forward').click();
      await expect(page.getByTestId('form-dialog')).toBeVisible();

      await page.getByTestId('form-dialog-field-maxDepth').fill('33');
      await page.getByTestId('form-dialog-submit').click();
      await expect(page.getByTestId('toast-stack')).toContainText(/maxDepth\/maxVisited 边界|maxDepth\/maxVisited bounds/i, {
        timeout: 20_000,
      });
      await attachVisual(page, 'limit-max-depth-error');

      await page.getByTestId('workbench-action-trace_forward').click();
      await expect(page.getByTestId('form-dialog')).toBeVisible();
      await page.getByTestId('form-dialog-field-maxVisited').fill('10001');
      await page.getByTestId('form-dialog-submit').click();
      await expect(page.getByTestId('toast-stack')).toContainText(/maxDepth\/maxVisited 边界|maxDepth\/maxVisited bounds/i, {
        timeout: 20_000,
      });
      await attachVisual(page, 'limit-max-visited-error');
    } finally {
      await session.context.close();
    }
  });

  test('error: unknown record boundary remains closed and readable', async ({ browser }) => {
    const session = await openAs(browser, state.viewerEmail!);
    const { page } = session;
    try {
      await page.goto('/p/mo_trace_graph/view/tu_does_not_exist');
      await expect(page.getByRole('heading', { name: /记录不存在|Record not found/i })).toBeVisible({
        timeout: 20_000,
      });
      await expect(page.getByText(/Business error|业务错误/i)).toBeVisible();
      await expect(page.locator(FORWARD_BLOCK)).toHaveCount(0);
      await expect(page.locator(BACKWARD_BLOCK)).toHaveCount(0);
      await attachVisual(page, 'error-unknown-record');
    } finally {
      await session.context.close();
    }
  });

  test('correction: signed-in reversal appends a fact and removes its edge from traversal', async ({ browser, page }) => {
    await page.goto(`/p/mo_trace_edge/view/${state.firstEdgePid}`);
    const reverseButton = page.getByTestId('toolbar-btn-reverse_trace_edge');
    await expect(reverseButton).toBeVisible({ timeout: 30_000 });
    await reverseButton.click();
    await expect(page.getByTestId('confirm-dialog')).toBeVisible();
    await page.getByTestId('confirm-ok').click();

    await expect(page.getByTestId('form-dialog')).toBeVisible();
    await page
      .getByTestId('form-dialog-field-reason')
      .fill(`T06 visual matrix correction ${TAG}`);
    await page
      .getByTestId('form-dialog-field-occurredAt')
      .fill(new Date().toISOString());
    await page.getByTestId('form-dialog-submit').click();
    await expect(page.getByTestId('toast-stack')).toContainText(
      /冲销事实已追加|Reversal fact appended/i,
      { timeout: 20_000 },
    );
    await attachVisual(page, 'correction-reversal-receipt');

    const refreshed = await openAs(browser, state.viewerEmail!);
    try {
      await refreshed.page.goto(`/p/mo_trace_graph/view/${state.rootPid}`);
      await expect(refreshed.page.locator(FORWARD_BLOCK)).toBeVisible({ timeout: 30_000 });
      await expect(refreshed.page.locator(`${FORWARD_BLOCK} .react-flow__edge`)).toHaveCount(1);
      await attachVisual(refreshed.page, 'correction-graph-after-reversal');
    } finally {
      await refreshed.context.close();
    }
  });
});
