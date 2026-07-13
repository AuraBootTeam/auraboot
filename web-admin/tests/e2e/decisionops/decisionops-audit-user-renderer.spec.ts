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
type UserRecord = { pid?: string; displayName?: string; name?: string; email?: string };

async function readApi<T>(response: APIResponse): Promise<T> {
  expect(response.ok(), `${response.url()} -> ${response.status()}`).toBeTruthy();
  const body = (await response.json()) as ApiEnvelope<T>;
  return body.data as T;
}

async function getApi<T>(page: Page, endpoint: string): Promise<T> {
  return readApi<T>(await page.request.get(endpoint));
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
});
