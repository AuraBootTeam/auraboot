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
 * G1-1 (SavedView sharing) is deliberately NOT driven here. The producer UI now exists and its
 * plumbing is complete, but the backend offers the `share` action for team and global views alone
 * while ViewManagePanel lists personal views — so no shareable view is reachable from that panel
 * today, and the button correctly does not render. Sharing is not a capability customers need yet;
 * when the policy opens up, the button appears on its own and this file gets its second test.
 * Until then it is covered by unit tests, and a browser golden here would be driving a path that
 * is switched off on purpose.
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
