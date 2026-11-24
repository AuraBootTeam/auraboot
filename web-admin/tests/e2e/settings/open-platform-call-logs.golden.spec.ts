import {
  expect,
  test,
  type Page,
  type APIRequestContext,
} from "@playwright/test";
import { createCookieSessionStorage } from "react-router";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { BACKEND_URL, BASE_URL, BFF_URL } from "../../helpers/environments";
import {
  publishOfficeDeviceResourceFixture,
  createOfficeDeviceCallerFixture,
} from "../../helpers/open-platform-device-fixtures";

test.use({
  storageState: { cookies: [], origins: [] },
  locale: "zh-CN",
  viewport: { width: 1600, height: 1100 },
  trace: "off",
  video: "off",
  screenshot: "off",
});
process.env.PLAYWRIGHT_NO_COPY_PROMPT = "1";
const evidence = path.join(
  process.env.AURA_EVIDENCE_ROOT || "/tmp",
  "open-platform-call-logs",
);
const tag = `${Date.now()}-${Math.random().toString(16).slice(2, 7)}`;
const sessionStorage = createCookieSessionStorage({
  cookie: {
    name: "__session",
    httpOnly: true,
    path: "/",
    sameSite: "lax",
    secrets: [
      process.env.SESSION_SECRET || "dev-only-secret-do-not-use-in-production",
    ],
    secure: false,
  },
});
const scenarios = [
  ["NAV01", "主菜单开放平台入口及账号菜单", "正常"],
  ["NAV02", "平台控制台空间切换", "授权管理员"],
  ["LOG01", "真实调用列表", "有数据"],
  ["LOG02", "完整请求响应及展开详情", "201"],
  ["LOG03", "Headers/Query/空请求体", "GET"],
  ["LOG04", "实际失败请求与响应", "4xx"],
  ["LOG05", "限流响应", "429"],
  ["LOG06", "筛选无结果与恢复", "empty"],
  ["LOG07", "报文独立权限", "viewer"],
  ["LOG08", "历史未采集", "legacy fixture"],
  ["LOG09", "有界采集", "oversize"],
  ["LOG10", "环境切换", "empty environment"],
  ["LOG11", "Webhook 独立页签", "existing operations"],
  ["LOG12", "详情窄屏返回", "390px"],
  ["LOG13", "详情执行信息", "execution"],
  ["LOG14", "分页与方法状态时间筛选", "pagination"],
  ["LOG15", "加载与失败恢复", "network fault injection"],
  ["LOG16", "英文界面", "en-US"],
  ["LOG17", "应用切换隔离", "other application"],
  ["LOG18", "维护者报文访问", "maintainer"],
];
let manifest = scenarios.map(([id, feature, state]) => ({
  id,
  feature,
  state,
  actionPoint: feature,
  expectation: "Actual scoped backend result and corresponding UI state",
  driver: "journey/real-stack/browser",
  screenshotId: `${id}.png`,
  status: "new",
  evidence: [] as string[],
}));
async function capture(page: Page, id: string) {
  const row = manifest.find((item) => item.id === id);
  expect(row).toBeTruthy();
  const screenshot = path.join(evidence, `${id}.png`);
  await page.screenshot({ path: screenshot, fullPage: true });
  row!.status = "captured";
  row!.evidence = [`${id}.png`];
  await writeFile(
    path.join(evidence, "acceptance-matrix.json"),
    JSON.stringify(manifest, null, 2),
  );
}
async function data(
  context: APIRequestContext,
  method: "get" | "post" | "put",
  url: string,
  options: object = {},
) {
  const response = await context[method](url, options);
  expect(response.status(), `${method} ${new URL(url).pathname}`).toBe(200);
  const body = await response.json();
  expect(String(body.code)).toBe("0");
  return body.data;
}
async function login(
  page: Page,
  email = "admin@auraboot.com",
  password = "Test2026x",
  locale = "zh-CN",
) {
  const result = await data(
    page.request,
    "post",
    `${BACKEND_URL}/api/auth/login`,
    { data: { email, password } },
  );
  const spaces = await data(
    page.request,
    "get",
    `${BACKEND_URL}/api/tenant-selection/my-spaces`,
    { headers: { Authorization: `Bearer ${result.jwt}` } },
  );
  const business = spaces.find(
    (space: { spaceType: string }) => space.spaceType === "business",
  );
  expect(business).toBeTruthy();
  const selected = await data(
    page.request,
    "post",
    `${BACKEND_URL}/api/tenant-selection/process`,
    {
      headers: { Authorization: `Bearer ${result.jwt}` },
      data: { action: "select", tenantId: business.tenantId },
    },
  );
  const session = await sessionStorage.getSession();
  session.set("jwtToken", selected.jwt);
  const value = (
    await sessionStorage.commitSession(session, { maxAge: 604800 })
  ).match(/__session=([^;]+)/)![1];
  await page.context().addCookies([
    {
      name: "__session",
      value,
      url: BASE_URL,
      httpOnly: true,
      sameSite: "Lax",
    },
    { name: "locale", value: locale, url: BASE_URL, sameSite: "Lax" },
  ]);
  await page.addInitScript(() => {
    const locale = document.cookie.match(/(?:^|; )locale=([^;]+)/)?.[1];
    if (locale) localStorage.setItem('locale', decodeURIComponent(locale));
  });
  return {
    jwt: selected.jwt as string,
    tenantId: String(business.tenantId),
    spaces,
  };
}
function sql(query: string) {
  // Isolated fixture setup/readback only. Never accepts a shared or production database.
  expect(process.env.PGDATABASE).toBe("auraboot_191");
  return execFileSync(
    "psql",
    [
      "-h",
      process.env.PGHOST || "127.0.0.1",
      "-p",
      process.env.PGPORT || "5432",
      "-U",
      process.env.PGUSER || "auraboot",
      "-d",
      process.env.PGDATABASE!,
      "-At",
      "-c",
      query,
    ],
    { encoding: "utf8", env: process.env },
  ).trim();
}
let installationPid = "",
  appPid = "",
  appName = "",
  resourceCode = "",
  machineToken = "",
  createRequestId = "",
  getRequestId = "",
  failedRequestId = "",
  largeRequestId = "",
  historicalRequestId = "",
  limitedRequestId = "",
  productionPid = "",
  stagingPid = "",
  otherAppName = "";
let tenantId = "";
const accounts: Record<
  string,
  { email: string; password: string; pid: string }
> = {};
const requestIds: Record<string, string> = {};
async function rows(
  context: APIRequestContext,
  installation = installationPid,
) {
  return data(
    context,
    "get",
    `${BASE_URL}/api/open-platform/installations/${installation}/audits?limit=200&windowHours=168`,
  );
}
async function navigate(
  page: Page,
  name = appName,
  installation = installationPid,
) {
  await page.goto("/home");
  const nav = page.getByTestId("sidebar");
  await expect(
    nav.getByRole("button", { name: "平台管理", exact: true }),
  ).toBeVisible();
  await nav.getByRole("link", { name: "接口调用日志", exact: true }).click();
  await expect(page.getByTestId("open-platform-call-logs")).toBeVisible();
  await page.getByLabel("应用", { exact: true }).selectOption({ label: name });
  await expect(page).toHaveURL(/[?&]application=/);
  await expect(page.getByLabel('环境', { exact: true })).toBeEnabled();
  await page.getByLabel("环境", { exact: true }).selectOption(installation);
  await expect(page).toHaveURL(new RegExp(`[?&]installation=${installation}`));
  await expect(page.getByRole('textbox', { name: '搜索请求 ID 或接口路径', exact: true })).toBeEnabled();
  await expect(page.getByLabel("应用", { exact: true }).locator("option:checked")).toHaveText(name);
}
async function choose(page: Page, id: string) {
  await page
    .getByRole("button", { name: `查看调用 ${id}`, exact: true })
    .click();
  await expect(page.getByTestId("http-audit-inspector")).toBeVisible();
  await expect(page.getByTestId("http-audit-inspector")).toContainText(id);
}

test.describe
  .serial("Approved Open Platform navigation and captured HTTP audit journey", () => {
  test.setTimeout(300_000);
  test.beforeAll(async ({ browser }) => {
    await mkdir(evidence, { recursive: true });
    await writeFile(
      path.join(evidence, "acceptance-matrix.json"),
      JSON.stringify(manifest, null, 2),
    );
    await writeFile(
      path.join(evidence, "acceptance-matrix.md"),
      "# Runtime scenario plan\n\nID | Feature | State | Screenshot | Status\n--- | --- | --- | --- | ---\n" +
        manifest
          .map(
            (row) =>
              `${row.id} | ${row.feature} | ${row.state} | ${row.screenshotId} | new`,
          )
          .join("\n"),
    );
    const context = await browser.newContext({ locale: "zh-CN" });
    const page = await context.newPage();
    const identity = await login(page);
    tenantId = identity.tenantId;
    expect(tenantId).toMatch(/^[0-9]+$/);
    const health = await page.request.get(`${BFF_URL}/health`);
    expect(health.status()).toBe(200);
    const h = await health.json();
    expect(h.status).toBe("ok");
    expect(h.services.springBoot.status).toBe("healthy");
    resourceCode = `audit-${tag}`;
    const resource = await publishOfficeDeviceResourceFixture(
      page,
      resourceCode,
    );
    const caller = await createOfficeDeviceCallerFixture(page, {
      code: tag,
      resourceCode,
      location: resource.location,
      scope: "openapi.profile.read",
      deviceName: `Audit office device ${tag}`,
    });
    installationPid = caller.installation.pid;
    appName = caller.name;
    machineToken = caller.machine.Authorization.slice(7);
    createRequestId = caller.createdResponse.headers()["x-request-id"];
    expect(createRequestId).toBeTruthy();
    const apps = await data(
      page.request,
      "get",
      `${BASE_URL}/api/open-platform/applications`,
    );
    appPid = apps.find((app: { name: string }) => app.name === appName).pid;
    const prod = await data(
      page.request,
      "post",
      `${BASE_URL}/api/open-platform/applications/${appPid}/installations`,
      {
        data: {
          environment: "production",
          scopes: ["openapi.profile.read"],
          rateLimitPerMinute: 1,
        },
      },
    );
    productionPid = prod.pid;
    const stage = await data(
      page.request,
      "post",
      `${BASE_URL}/api/open-platform/applications/${appPid}/installations`,
      {
        data: {
          environment: "staging",
          scopes: ["openapi.profile.read"],
          rateLimitPerMinute: 1000,
        },
      },
    );
    stagingPid = stage.pid;
    const credential = await data(
      page.request,
      "post",
      `${BASE_URL}/api/open-platform/installations/${productionPid}/integration-tokens`,
      {
        headers: { "Idempotency-Key": `limit-${tag}` },
        data: { name: "Limit fixture", expirationMode: "never" },
      },
    );
    const limitedHeaders = { Authorization: `Bearer ${credential.token}` };
    expect(
      (
        await page.request.get(`${BACKEND_URL}/api/open/v1/whoami`, {
          headers: limitedHeaders,
        })
      ).status(),
    ).toBe(200);
    const limited = await page.request.get(
      `${BACKEND_URL}/api/open/v1/whoami`,
      { headers: limitedHeaders },
    );
    expect(limited.status()).toBe(429);
    limitedRequestId = limited.headers()["x-request-id"];
    const get = await page.request.get(
      `${BACKEND_URL}/api/open/v1/whoami?access_token=private-query-token&reference=READABLE`,
      { headers: caller.machine },
    );
    expect(get.status()).toBe(200);
    getRequestId = get.headers()["x-request-id"];
    const bad = await page.request.post(
      `${BACKEND_URL}/api/open/v1/resources/${resourceCode}`,
      {
        headers: { ...caller.machine, "Idempotency-Key": `invalid-${tag}` },
        data: {
          assetCode: `BAD-${tag}`,
          password: "audit-private-password",
          clientSecret: "audit-private-client-secret",
        },
      },
    );
    expect([400, 422]).toContain(bad.status());
    failedRequestId = bad.headers()["x-request-id"];
    const large = await page.request.post(
      `${BACKEND_URL}/api/open/v1/resources/${resourceCode}`,
      {
        headers: { ...caller.machine, "Idempotency-Key": `large-${tag}` },
        data: {
          assetCode: `BIG-${tag}`,
          name: "x".repeat(70000),
          location: resource.location,
        },
      },
    );
    expect([201, 400, 422]).toContain(large.status());
    largeRequestId = large.headers()["x-request-id"];
    for (let i = 0; i < 28; i++) {
      const response = await page.request.get(
        `${BACKEND_URL}/api/open/v1/whoami`,
        {
          headers: {
            ...caller.machine,
            "X-Request-Id": `audit-page-${tag}-${i}`,
          },
        },
      );
      expect(response.status()).toBe(200);
      if (i === 0) historicalRequestId = response.headers()["x-request-id"];
    }
    // Controlled historical fixture: one owned real call has no pre-existing HTTP capture.
    expect(
      sql(
        `UPDATE ab_open_api_call_audit SET http_details=NULL WHERE tenant_id='${tenantId}'::bigint AND installation_pid='${installationPid}' AND request_id='${historicalRequestId}' RETURNING request_id`,
      ),
    ).toContain(historicalRequestId);
    otherAppName = `Isolated second application ${tag}`;
    const other = await data(
      page.request,
      "post",
      `${BASE_URL}/api/open-platform/applications`,
      { data: { name: otherAppName } },
    );
    await data(
      page.request,
      "post",
      `${BASE_URL}/api/open-platform/applications/${other.pid}/installations`,
      {
        data: {
          environment: "development",
          scopes: ["openapi.profile.read"],
          rateLimitPerMinute: 1000,
        },
      },
    );
    for (const role of ["viewer", "maintainer", "nonmember"]) {
      const account = {
        email: `audit-${role}-${tag}@audit.test`,
        password: "Audit2026x!",
        pid: "",
      };
      const created = await data(
        page.request,
        "post",
        `${BASE_URL}/api/meta/commands/execute/admin:create_member`,
        {
          data: {
            payload: {
              name: `Audit ${role}`,
              email: account.email,
              password: account.password,
            },
          },
        },
      );
      account.pid = created.data.userPid;
      expect(account.pid).toBeTruthy();
      accounts[role] = account;
      if (role !== "nonmember")
        await data(
          page.request,
          "put",
          `${BASE_URL}/api/open-platform/applications/${appPid}/members/${account.pid}`,
          { data: { role } },
        );
    }
    const audits = await rows(page.request);
    expect(audits.length).toBeGreaterThan(25);
    for (const id of [
      createRequestId,
      getRequestId,
      failedRequestId,
      largeRequestId,
      historicalRequestId,
    ]) {
      const audit = audits.find(
        (item: { requestId: string }) => item.requestId === id,
      );
      expect(audit).toBeTruthy();
      requestIds[id] = audit.auditPid;
    }
    const persisted = sql(
      `SELECT http_details::text FROM ab_open_api_call_audit WHERE tenant_id='${tenantId}'::bigint AND installation_pid='${installationPid}' AND http_details IS NOT NULL`,
    );
    expect(persisted.includes(machineToken), "Persisted audit must not contain the live token").toBe(false);
    expect(persisted).not.toContain("audit-private-password");
    expect(persisted).not.toContain("audit-private-client-secret");
    expect(persisted).not.toContain("private-query-token");
    await writeFile(
      path.join(evidence, "fixture-identity.json"),
      JSON.stringify(
        {
          tenantId,
          appPid,
          installationPid,
          productionPid,
          stagingPid,
          requestIds,
          resourceCode,
          backend: BACKEND_URL,
          web: BASE_URL,
          bff: BFF_URL,
        },
        null,
        2,
      ),
    );
    await context.close();
  });
  test("owner enters through actual menu, inspects captured traffic, filters and downloads", async ({
    page,
  }) => {
    await login(page);
    await navigate(page);
    await page.getByTestId("user-menu").getByRole("button").first().click();
    await expect(
      page.getByTestId("user-dropdown").getByTestId("open-platform-link"),
    ).toHaveCount(0);
    await expect(
      page.getByTestId("user-dropdown").getByTestId("platform-console-link"),
    ).toHaveCount(0);
    await capture(page, "NAV01");
    await page.keyboard.press("Escape");
    const countBefore = sql(
      `SELECT count(*) FROM ab_open_api_call_audit WHERE tenant_id='${tenantId}'::bigint AND installation_pid='${installationPid}'`,
    );
    await expect(
      page.getByTestId("open-platform-call-logs").locator("tbody tr"),
    ).toHaveCount(25);
    await capture(page, "LOG01");
    await page.getByRole("button", { name: "下一页", exact: true }).click();
    await expect(
      page.getByRole("button", {
        name: `查看调用 ${createRequestId}`,
        exact: true,
      }),
    ).toBeVisible();
    await capture(page, "LOG14");
    await page.getByRole('button', { name: '上一页', exact: true }).click();
    await expect(page.getByTestId('open-platform-call-logs').locator('tbody tr')).toHaveCount(25);
    await page.getByRole('button', { name: '下一页', exact: true }).click();
    await choose(page, createRequestId);
    const inspector = page.getByTestId("http-audit-inspector");
    await expect(inspector.getByTestId("http-request")).toContainText(
      `UI-${tag}`,
    );
    await expect(inspector.getByTestId("http-response")).toContainText("201");
    await inspector
      .getByRole("button", { name: "展开详情", exact: true })
      .click();
    await capture(page, "LOG02");
    await page
      .context()
      .grantPermissions(["clipboard-read", "clipboard-write"], {
        origin: BASE_URL,
      });
    await inspector.getByTestId("copy-curl").click();
    const curl = await page.evaluate(() => navigator.clipboard.readText());
    expect(curl).toContain("api.example.com");
    expect(curl).toContain("<YOUR_ACCESS_TOKEN>");
    expect(curl.includes(machineToken), "cURL must not contain the live token").toBe(false);
    await inspector.getByRole('button', { name: '复制请求体', exact: true }).click();
    expect(await page.evaluate(() => navigator.clipboard.readText())).toContain(`UI-${tag}`);
    await inspector.getByRole('button', { name: '复制响应体', exact: true }).click();
    const copiedResponse = JSON.parse(await page.evaluate(() => navigator.clipboard.readText()));
    expect(copiedResponse.assetCode).toBe(`UI-${tag}`);
    await inspector.getByRole('button', { name: '复制请求 ID', exact: true }).click();
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(createRequestId);
    await inspector.getByTestId('response-headers').locator('summary').click();
    await expect(inspector.getByTestId('response-headers')).toContainText('application/json');
    const requestBody = inspector.getByTestId('http-request').locator('details').last();
    await requestBody.locator('summary').click();
    await expect(inspector.getByRole('button', { name: '复制请求体', exact: true })).toBeHidden();
    await requestBody.locator('summary').click();
    await inspector.getByRole('button', { name: '还原详情宽度', exact: true }).click();
    await inspector.getByRole('button', { name: '展开详情', exact: true }).click();
    const downloadPromise = page.waitForEvent("download");
    await inspector.getByTestId("download-http-audit").click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toBe(
      `api-call-${requestIds[createRequestId]}-redacted.json`,
    );
    const saved = path.join(evidence, download.suggestedFilename());
    await download.saveAs(saved);
    const artifact = JSON.parse(await readFile(saved, "utf8"));
    expect(artifact.requestId).toBe(createRequestId);
    expect(artifact.httpDetails.request.body.assetCode).toBe(`UI-${tag}`);
    expect(JSON.stringify(artifact).includes(machineToken), "Download must not contain the live token").toBe(false);
    await inspector.getByRole("tab", { name: "执行信息", exact: true }).click();
    await expect(inspector).toContainText(appName);
    await inspector.getByRole('tab', { name: '执行信息', exact: true }).press('ArrowLeft');
    await expect(inspector.getByRole('tab', { name: '请求与响应', exact: true })).toHaveAttribute('aria-selected', 'true');
    await inspector.getByRole('tab', { name: '请求与响应', exact: true }).press('ArrowRight');
    await expect(inspector.getByRole('tab', { name: '执行信息', exact: true })).toHaveAttribute('aria-selected', 'true');
    await capture(page, "LOG13");
    await inspector
      .getByRole("button", { name: "关闭详情", exact: true })
      .click();
    const search = page.getByRole("textbox", {
      name: "搜索请求 ID 或接口路径",
      exact: true,
    });
    await search.fill(getRequestId);
    await expect(
      page.getByTestId("open-platform-call-logs").locator("tbody tr"),
    ).toHaveCount(1);
    await choose(page, getRequestId);
    await inspector.getByTestId("request-headers").locator("summary").click();
    await inspector.getByTestId("request-query").locator("summary").click();
    await expect(inspector).toContainText("[REDACTED]");
    await expect(inspector).not.toContainText("private-query-token");
    await expect(inspector.getByTestId("request-body-state")).toContainText(
      "未携带正文",
    );
    await inspector.getByTestId('copy-curl').click();
    const getTemplate = await page.evaluate(() => navigator.clipboard.readText());
    expect(getTemplate).toContain('reference=READABLE');
    expect(getTemplate).not.toContain('private-query-token');
    await capture(page, "LOG03");
    await inspector
      .getByRole("button", { name: "关闭详情", exact: true })
      .click();
    await search.fill(failedRequestId);
    await choose(page, failedRequestId);
    await expect(inspector.getByTestId("http-request")).toContainText(
      "[REDACTED]",
    );
    await expect(inspector).not.toContainText("audit-private-password");
    await expect(inspector.getByTestId("http-response")).toContainText("JSON");
    await capture(page, "LOG04");
    await inspector
      .getByRole("button", { name: "关闭详情", exact: true })
      .click();
    await search.fill(largeRequestId);
    await choose(page, largeRequestId);
    await expect(inspector.getByTestId("request-body-state")).toContainText(
      "64 KB",
    );
    await capture(page, "LOG09");
    await inspector
      .getByRole("button", { name: "关闭详情", exact: true })
      .click();
    await search.fill(historicalRequestId);
    await choose(page, historicalRequestId);
    await expect(inspector).toContainText("历史日志未采集报文");
    await expect(inspector.getByTestId("download-http-audit")).toBeDisabled();
    await capture(page, "LOG08");
    await inspector
      .getByRole("button", { name: "关闭详情", exact: true })
      .click();
    await search.fill("no-such-request-for-this-owned-fixture");
    await expect(page.getByTestId("call-logs-empty")).toBeVisible();
    await capture(page, "LOG06");
    await page.getByRole("button", { name: "清空筛选", exact: true }).click();
    await expect(
      page.getByTestId("open-platform-call-logs").locator("tbody tr"),
    ).toHaveCount(25);
    await page.getByRole('button', { name: '2', exact: true }).click();
    await expect(page.getByLabel('调用状态', { exact: true })).toHaveValue('error');
    await page.getByRole('button', { name: '重置', exact: true }).click();
    await page.getByLabel("请求方法", { exact: true }).selectOption("POST");
    await expect(
      page.getByTestId("open-platform-call-logs").locator("tbody tr"),
    ).toHaveCount(3);
    await page.getByLabel("调用状态", { exact: true }).selectOption("error");
    await expect(
      page.getByTestId("open-platform-call-logs").locator("tbody tr"),
    ).not.toHaveCount(0);
    await page.getByLabel("时间范围", { exact: true }).selectOption("1");
    await expect(
      page.getByTestId("open-platform-call-logs").locator("tbody tr"),
    ).not.toHaveCount(0);
    await page.getByLabel("环境", { exact: true }).selectOption(stagingPid);
    await expect(page.getByTestId("call-logs-empty")).toBeVisible();
    await capture(page, "LOG10");
    await page.getByLabel("环境", { exact: true }).selectOption(productionPid);
    await choose(page, limitedRequestId);
    await expect(inspector).toContainText("429");
    await expect(inspector.getByTestId("http-response")).toContainText(
      "rate_limit_exceeded",
    );
    await capture(page, "LOG05");
    await page
      .getByRole("navigation", { name: "开放平台导航" })
      .getByRole("link", { name: "Webhook 投递", exact: true })
      .click();
    await expect(
      page.getByTestId("open-platform-operations-panel"),
    ).toBeVisible();
    await expect(page.getByTestId("open-platform-call-audit")).toBeHidden();
    await capture(page, "LOG11");
    await navigate(page);
    await page
      .getByLabel("应用", { exact: true })
      .selectOption({ label: otherAppName });
    await expect(page.getByTestId("call-logs-empty")).toBeVisible();
    await capture(page, "LOG17");
    expect(
      sql(
        `SELECT count(*) FROM ab_open_api_call_audit WHERE tenant_id='${tenantId}'::bigint AND installation_pid='${installationPid}'`,
      ),
    ).toBe(countBefore);
  });
  test("real viewer, maintainer, non-member and foreign installation enforce detail authority", async ({
    page,
  }) => {
    await login(page, accounts.viewer.email, accounts.viewer.password);
    await navigate(page);
    await page
      .getByRole("textbox", { name: "搜索请求 ID 或接口路径" })
      .fill(createRequestId);
    await page
      .getByRole("button", { name: `查看调用 ${createRequestId}`, exact: true })
      .click();
    await expect(page.getByTestId("http-audit-state")).toContainText(
      "暂无报文查看权限",
    );
    await capture(page, "LOG07");
    expect(
      (
        await page.request.get(
          `${BASE_URL}/api/open-platform/installations/${installationPid}/audits/${requestIds[createRequestId]}`,
        )
      ).status(),
    ).toBe(403);
    expect(
      (await rows(page.request)).some(
        (r: { auditPid: string }) => r.auditPid === requestIds[createRequestId],
      ),
    ).toBe(true);
    await login(page, accounts.maintainer.email, accounts.maintainer.password);
    await navigate(page);
    await page
      .getByRole("textbox", { name: "搜索请求 ID 或接口路径" })
      .fill(createRequestId);
    await choose(page, createRequestId);
    await capture(page, "LOG18");
    const wrong = await page.request.get(
      `${BASE_URL}/api/open-platform/installations/${stagingPid}/audits/${requestIds[createRequestId]}`,
    );
    expect(wrong.status()).toBe(404);
    await login(page, accounts.nonmember.email, accounts.nonmember.password);
    expect(
      (
        await page.request.get(
          `${BASE_URL}/api/open-platform/installations/${installationPid}/audits`,
        )
      ).status(),
    ).toBe(403);
    expect(
      (
        await page.request.get(
          `${BASE_URL}/api/open-platform/installations/${installationPid}/audits/${requestIds[createRequestId]}`,
        )
      ).status(),
    ).toBe(403);
  });
  test("real menu console switch, responsive state recovery and English display", async ({
    page,
  }) => {
    const admin = await login(page);
    await navigate(page);
    await page
      .getByRole("textbox", { name: "搜索请求 ID 或接口路径" })
      .fill(createRequestId);
    await choose(page, createRequestId);
    await page.setViewportSize({ width: 390, height: 844 });
    await expect(page.getByTestId("http-audit-inspector")).toBeVisible();
    await capture(page, "LOG12");
    await page.getByRole("button", { name: "关闭详情", exact: true }).click();
    await expect(
      page.getByRole("button", {
        name: `查看调用 ${createRequestId}`,
        exact: true,
      }),
    ).toBeVisible();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth > innerWidth,
      ),
    ).toBe(false);
    await page.setViewportSize({ width: 1600, height: 1100 });
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    let started!: () => void;
    const waiting = new Promise<void>((resolve) => (started = resolve));
    const pattern = "**/api/open-platform/installations/*/audits?*";
    await page.route(pattern, async (route) => {
      started();
      await gate;
      await route.abort("failed");
    });
    await page.getByRole("button", { name: "刷新日志", exact: true }).click();
    await waiting;
    await expect(
      page.getByRole("status", { name: "正在加载日志" }),
    ).toBeVisible();
    await capture(page, "LOG15");
    release();
    await expect(page.getByText("日志加载失败", { exact: true })).toBeVisible();
    await page.screenshot({
      path: path.join(evidence, "LOG15-error.png"),
      fullPage: true,
    });
    await page.unroute(pattern);
    await page.getByRole("button", { name: "重试", exact: true }).click();
    await expect(
      page.getByRole("button", {
        name: `查看调用 ${createRequestId}`,
        exact: true,
      }),
    ).toBeVisible();
    const platform = admin.spaces.find(
      (space: { spaceType: string; roleCodes: string[] }) =>
        space.spaceType === "platform" &&
        space.roleCodes.includes("platform_admin"),
    );
    expect(platform).toBeTruthy();
    await page
      .getByTestId("sidebar")
      .getByTestId("platform-console-link")
      .click();
    await expect(page).toHaveURL(/\/platform\/plugins/);
    await expect(page.getByTestId("sidebar")).toBeVisible();
    await capture(page, "NAV02");
    await login(page, "admin@auraboot.com", "Test2026x", "en-US");
    await page.goto("/home");
    const nav = page.getByTestId("sidebar");
    await nav.getByRole("link", { name: "API call logs", exact: true }).click();
    await page
      .getByLabel("Application", { exact: true })
      .selectOption({ label: appName });
    await page
      .getByLabel("Environment", { exact: true })
      .selectOption(installationPid);
    await expect(
      page.getByRole("heading", { name: "API call logs", exact: true }),
    ).toBeVisible();
    await capture(page, "LOG16");
  });
});
