import { test, expect, type APIResponse, type Page, type TestInfo } from '@playwright/test';
import { waitForDynamicPageLoad } from '../helpers';

/**
 * DecisionOps audit block — createdBy / updatedBy must render a resolved user name,
 * never the raw user pid (ULID).
 *
 * ab_drt_definition.created_by stores MetaContext.getCurrentUserPid() (a ULID), and the
 * DrtDefinitionDTO passes it through verbatim. Before the `memberpicker` component was
 * declared on these fields, the detail page's form-section printed the pid as plain text
 * (e.g. "01KXB6RPKGCZSYDDZG1HKD515T") — a raw-code leak.
 */

const ULID_RE = /^[0-9A-HJKMNP-TV-Z]{26}$/;

type ApiEnvelope<T> = { code?: number | string; success?: boolean; data?: T };
type DecisionDefinition = { pid: string; decisionCode: string; createdBy?: string; updatedBy?: string };
type DecisionVersion = { pid: string; version?: number };
type DecisionRollout = { pid: string; startedBy?: string; endedBy?: string };
type UserRecord = { pid?: string; displayName?: string; name?: string; email?: string };

function amountGtAst(threshold: number) {
  return {
    type: 'compare',
    left: { type: 'path', scope: 'record', path: 'data.amount', dataType: 'decimal' },
    operator: 'GT',
    right: { type: 'literal', value: threshold, dataType: 'decimal' },
  };
}

async function readApi<T>(response: APIResponse): Promise<T> {
  if (!response.ok()) {
    const text = await response.text().catch(() => '<unreadable body>');
    expect(response.ok(), `${response.url()} -> ${response.status()}\n${text}`).toBeTruthy();
  }
  const body = (await response.json()) as ApiEnvelope<T>;
  return body.data as T;
}

async function getApi<T>(page: Page, endpoint: string): Promise<T> {
  return readApi<T>(await page.request.get(endpoint));
}

async function postApi<T>(page: Page, endpoint: string, data?: unknown): Promise<T> {
  return readApi<T>(await page.request.post(endpoint, { data }));
}

/**
 * A rollout whose startedBy AND endedBy are both populated: `activate` stamps startedBy,
 * `rollback` stamps endedBy (`pause` does not — see DecisionRolloutServiceImpl).
 */
async function seedEndedRollout(page: Page, suffix: string): Promise<DecisionRollout> {
  const decisionCode = `audit_renderer_${suffix}`;
  await postApi(page, '/api/decision/definitions', {
    decisionCode,
    decisionName: `Audit renderer rollout ${suffix}`,
    description: 'Fixture for the audit-block user-renderer golden',
    scopeType: 'GOVERNANCE',
    ownerModule: 'decision',
    enabled: true,
  });

  for (const [tag, threshold] of [
    ['baseline', 10_000],
    ['candidate', 5_000],
  ] as const) {
    const draft = await postApi<DecisionVersion>(
      page,
      `/api/decision/definitions/${encodeURIComponent(decisionCode)}/versions`,
      {
        kind: 'SIMPLE_CONDITION',
        runtimeAdapter: 'AST_EVALUATOR',
        versionTag: `${tag}-${suffix}`,
        contentJson: amountGtAst(threshold),
      },
    );
    await postApi(page, `/api/decision/versions/${encodeURIComponent(draft.pid)}/validate`);
    await postApi(page, `/api/decision/versions/${encodeURIComponent(draft.pid)}/publish`, {
      impactAcknowledged: true,
      note: `Publish ${tag} for the audit-renderer golden`,
    });
  }

  const rollout = await postApi<DecisionRollout>(
    page,
    `/api/decision/definitions/${encodeURIComponent(decisionCode)}/rollouts`,
    { baselineVersion: 1, candidateVersion: 2, percentage: 10 },
  );
  await postApi(page, `/api/decision/rollouts/${encodeURIComponent(rollout.pid)}/activate`, {
    note: 'audit-renderer golden: stamp startedBy',
  });
  return postApi<DecisionRollout>(
    page,
    `/api/decision/rollouts/${encodeURIComponent(rollout.pid)}/rollback`,
    { note: 'audit-renderer golden: stamp endedBy' },
  );
}

async function capture(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  await page.screenshot({ path: testInfo.outputPath(`${name}.png`), fullPage: true });
}

/** The pid the backend stores, and the display name the picker is expected to resolve it to. */
async function expectedIdentity(page: Page, decisionCode: string) {
  const definition = await getApi<DecisionDefinition>(
    page,
    `/api/decision/definitions/${encodeURIComponent(decisionCode)}`,
  );

  const createdByPid = String(definition.createdBy ?? '');
  expect(createdByPid, 'seed decision must carry a createdBy pid, otherwise this golden is vacuous').toMatch(ULID_RE);

  const user = await getApi<UserRecord>(page, `/api/admin/users/${encodeURIComponent(createdByPid)}`);
  const displayName = String(user.displayName || user.name || user.email || '');
  expect(displayName, 'user lookup must yield a display name').not.toEqual('');
  expect(displayName).not.toMatch(ULID_RE);

  return { createdByPid, displayName };
}

test.describe('DecisionOps audit block renders user names, not pids', () => {
  test('decision definition detail resolves createdBy / updatedBy to a display name', async ({
    page,
  }, testInfo) => {
    const decisionCode = 'leave_request_automation';
    const { createdByPid, displayName } = await expectedIdentity(page, decisionCode);

    await page.goto(`/p/decisionops_definitions/view/${encodeURIComponent(decisionCode)}`, {
      waitUntil: 'domcontentloaded',
    });
    await waitForDynamicPageLoad(page);

    const createdBy = page.getByTestId('form-field-createdBy');
    const updatedBy = page.getByTestId('form-field-updatedBy');
    await expect(createdBy).toBeVisible();
    await expect(updatedBy).toBeVisible();

    // The memberpicker read-only renderer is what resolves the pid → name.
    await expect(createdBy.getByTestId('member-picker-readonly')).toBeVisible({ timeout: 10000 });

    // The resolved name is on screen …
    await expect(createdBy).toContainText(displayName, { timeout: 10000 });
    await expect(updatedBy).toContainText(displayName, { timeout: 10000 });

    // … and the raw pid is not. This is the regression: the pid used to be the visible text.
    await expect(createdBy).not.toContainText(createdByPid);
    await expect(updatedBy).not.toContainText(createdByPid);

    // Belt and braces: no bare ULID anywhere in the audit section.
    const auditText = (await createdBy.innerText()) + (await updatedBy.innerText());
    for (const token of auditText.split(/\s+/).filter(Boolean)) {
      expect(token, `audit block leaked a raw pid: ${token}`).not.toMatch(ULID_RE);
    }

    // The detail page scrolls inside an inner container, so fullPage alone would not
    // reach the audit section — bring it into view before capturing the evidence shot.
    await createdBy.scrollIntoViewIfNeeded();
    await capture(page, testInfo, 'decisionops-definition-audit-createdby');
  });

  test('rollout detail resolves startedBy / endedBy to a display name', async ({
    page,
  }, testInfo) => {
    const rollout = await seedEndedRollout(page, `${Date.now()}`);

    const startedByPid = String(rollout.startedBy ?? '');
    const endedByPid = String(rollout.endedBy ?? '');
    expect(startedByPid, 'activate must stamp startedBy').toMatch(ULID_RE);
    expect(endedByPid, 'rollback must stamp endedBy').toMatch(ULID_RE);

    const user = await getApi<UserRecord>(page, `/api/admin/users/${encodeURIComponent(startedByPid)}`);
    const displayName = String(user.displayName || user.name || user.email || '');
    expect(displayName).not.toEqual('');
    expect(displayName).not.toMatch(ULID_RE);

    await page.goto(`/p/decisionops_rollouts/view/${encodeURIComponent(rollout.pid)}`, {
      waitUntil: 'domcontentloaded',
    });
    await waitForDynamicPageLoad(page);

    const startedBy = page.getByTestId('form-field-startedBy');
    const endedBy = page.getByTestId('form-field-endedBy');
    await expect(startedBy.getByTestId('member-picker-readonly')).toBeVisible({ timeout: 10000 });
    await expect(startedBy).toContainText(displayName, { timeout: 10000 });
    await expect(endedBy).toContainText(displayName, { timeout: 10000 });
    await expect(startedBy).not.toContainText(startedByPid);
    await expect(endedBy).not.toContainText(endedByPid);

    await startedBy.scrollIntoViewIfNeeded();
    await capture(page, testInfo, 'decisionops-rollout-audit-startedby');
  });
});
