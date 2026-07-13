import { test, expect, type Page, type APIRequestContext } from '@playwright/test';
import { BASE_URL } from '../helpers/environments';

/**
 * Browser golden for the two gap fixes this branch carries alongside the CS channel.
 *
 * Both were previously covered only by unit tests, and both are exactly the kind of defect a unit
 * test cannot see: whether the thing a customer copies off the screen actually works, and whether
 * a button that exists in the component tree is reachable by a person.
 *
 * G1-3 — the web-form embed snippet shipped a 404 to every customer who pasted it: the wrong path,
 * a data attribute the SDK never reads, and no container element for it to render into. The old
 * E2E asserted that a toast appeared, which is why nothing caught it. This one reads the clipboard
 * and then FETCHES the URL in the snippet — the only assertion that can prove the snippet works.
 *
 * G1-1 — the SavedView share backend was complete and the producer UI did not exist:
 * canShareSavedView() had zero call sites, so a user could open a share link but never create one.
 * This drives generate → link → revoke through the panel.
 */
const BASE = process.env.G1_GOLDEN_BASE ?? BASE_URL;
const ADMIN_EMAIL = process.env.G1_ADMIN_EMAIL ?? 'admin@cs.test';
const ADMIN_PW = process.env.G1_ADMIN_PW ?? 'Admin@12345';

async function loginAsAdmin(page: Page) {
  await page.goto(`${BASE}/login`);
  // pressSequentially, not fill: controlled React inputs ignore fill()'s value assignment, and the
  // form then submits empty while the page tells you the password is wrong.
  const identifier = page.getByRole('textbox', { name: /用户名或邮箱|Username|Email/ });
  await identifier.click();
  await identifier.pressSequentially(ADMIN_EMAIL, { delay: 30 });
  const password = page.getByRole('textbox', { name: /密码|Password/ });
  await password.click();
  await password.pressSequentially(ADMIN_PW, { delay: 30 });
  await expect(identifier).toHaveValue(ADMIN_EMAIL);
  await expect(password).toHaveValue(ADMIN_PW);
  await page.getByRole('button', { name: /立即登录|登录|Sign in|Login/i }).first().click();
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 40_000 });
}

async function apiToken(request: APIRequestContext): Promise<string> {
  const res = await request.post(`${BASE}/api/auth/login`, {
    data: { email: ADMIN_EMAIL, password: ADMIN_PW },
    headers: { 'Content-Type': 'application/json' },
  });
  expect(res.ok(), 'login API must succeed').toBeTruthy();
  return (await res.json())?.data?.jwt as string;
}

test.describe('G1-3 — the web-form embed snippet', () => {
  test('the snippet an operator copies actually loads the SDK', async ({ page, context, request }) => {
    const token = await apiToken(request);
    const auth = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
    const uid = Date.now().toString(36);

    // A web form to copy an embed snippet for.
    const channelRes = await request.post(`${BASE}/api/crm/inbound-channels`, {
      headers: auth,
      data: { name: `G1_Channel_${uid}`, channelType: 'web_form', enabled: true },
    });
    expect(channelRes.ok(), `channel create: ${channelRes.status()}`).toBeTruthy();
    const channelPid = (await channelRes.json())?.data?.pid as string;

    const formRes = await request.post(`${BASE}/api/crm/web-forms`, {
      headers: auth,
      data: { name: `G1_Form_${uid}`, channelPid, fields: [], enabled: true },
    });
    expect(formRes.ok(), `form create: ${formRes.status()}`).toBeTruthy();
    const formPid = (await formRes.json())?.data?.pid as string;
    expect(formPid, 'the form must have a pid').toBeTruthy();

    await loginAsAdmin(page);
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await page.goto(`${BASE}/crm/settings/web-form-editor/${formPid}`);

    const copyButton = page.getByRole('button', { name: /嵌入代码|Embed|Copy Embed/i }).first();
    await copyButton.waitFor({ state: 'visible', timeout: 30_000 });
    await copyButton.click();

    const snippet = await page.evaluate(() => navigator.clipboard.readText());

    // The three properties the old snippet broke, one at a time.
    expect(snippet, 'the SDK is served at /api/crm/forms/{pid}/sdk.js').toContain(
      `/api/crm/forms/${formPid}/sdk.js`,
    );
    expect(snippet, 'there is no /sdk/web-form.js route on any backend').not.toContain('/sdk/web-form.js');
    expect(snippet, 'the SDK reads no data-form-id — emitting one is misleading').not.toContain('data-form-id');
    expect(snippet, 'the SDK bails out without its container element').toContain('id="auraboot-form"');

    // And the assertion that actually proves it: fetch what the customer would fetch. A snippet can
    // satisfy every string check above and still 404.
    const scriptUrl = /src="([^"]+)"/.exec(snippet)?.[1];
    expect(scriptUrl, 'the snippet must carry a script src').toBeTruthy();
    const sdk = await request.get(scriptUrl!);
    expect(sdk.status(), `the copied script URL must load: ${scriptUrl}`).toBe(200);
    const sdkBody = await sdk.text();
    // The pid is baked into the served script, which is why the snippet carries no data attribute.
    // Asserted on the config line, not on the file as a whole: the SDK's header comment documents
    // the __FORM_PID__ template form, and a naive "must not contain __FORM_PID__" would fail on
    // documentation while missing an actually unsubstituted config.
    expect(sdkBody, 'the served SDK must have this form baked in').toMatch(
      new RegExp(`FORM_PID\\s*=\\s*'${formPid}'`),
    );
  });
});

test.describe('G1-1 — the SavedView share link', () => {
  test('an operator can generate a share link, and revoke it', async ({ page, context, request }) => {
    const token = await apiToken(request);

    // A global view, not a personal one. The backend only offers the `share` action for team and
    // global views (SavedViewServiceImpl.resolveActions), so the button is correctly disabled on a
    // personal view — a golden that created one would "fail" on a policy working exactly as designed.
    const viewRes = await request.post(`${BASE}/api/views`, {
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      data: {
        name: `G1 Share ${Date.now().toString(36)}`,
        modelCode: 'crm_account',
        pageKey: 'crm_account_list',
        scope: 'global',
        viewType: 'table',
        viewConfig: {},
      },
    });
    expect(viewRes.ok(), `view create: ${viewRes.status()}`).toBeTruthy();
    const created = (await viewRes.json())?.data;
    const viewPid = created?.pid as string;
    expect(created?.actions, 'a global view must offer the share action').toContain('share');

    await loginAsAdmin(page);
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await page.goto(`${BASE}/p/crm_account`);

    // In through the view selector, the way a person gets here.
    await page.getByTestId('view-selector-trigger').click();
    await page.getByTestId('view-selector-manage').click();
    const manage = page.getByTestId('saved-view-manage-panel');
    await manage.waitFor({ state: 'visible', timeout: 30_000 });

    // The share affordance. Before this fix it did not exist at all — canShareSavedView() was
    // defined and called from nowhere, so a user could open a share link but never create one.
    const shareAction = page.getByTestId(`saved-view-action-share-${viewPid}`);
    await shareAction.waitFor({ state: 'visible', timeout: 30_000 });
    await expect(shareAction, 'the share button must be enabled for a shareable view').toBeEnabled();
    await shareAction.click();

    await page
      .getByTestId(`saved-view-share-panel-${viewPid}`)
      .waitFor({ state: 'visible', timeout: 20_000 });
    await page.getByTestId(`saved-view-share-generate-${viewPid}`).click();

    const link = page.getByTestId(`saved-view-share-link-${viewPid}`);
    await link.waitFor({ state: 'visible', timeout: 30_000 });
    const shareUrl = (await link.inputValue().catch(() => null)) ?? (await link.textContent()) ?? '';
    expect(shareUrl, 'a share link must be produced').toMatch(/\/share\/[A-Za-z0-9_-]{8,}/);

    // The recipient's half of the contract: the token the UI just produced must resolve. That route
    // existed all along — it simply had nothing to consume.
    const shareToken = /\/share\/([A-Za-z0-9_-]+)/.exec(shareUrl)?.[1];
    const shared = await request.get(`${BASE}/api/views/shared/${shareToken}`);
    expect(shared.status(), 'the generated token must resolve to the shared view').toBe(200);

    // And revoking must actually revoke.
    await page.getByTestId(`saved-view-share-revoke-${viewPid}`).click();
    await page.waitForTimeout(2500);
    const afterRevoke = await request.get(`${BASE}/api/views/shared/${shareToken}`);
    expect(
      afterRevoke.status(),
      'a revoked link must stop working — otherwise revoke is decorative',
    ).not.toBe(200);
  });
});
