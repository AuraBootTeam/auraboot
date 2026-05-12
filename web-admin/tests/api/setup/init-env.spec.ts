/**
 * Environment Initialization E2E Test
 *
 * This test performs initial environment setup:
 * 1. Register test user (admin@auraboot.com / Test2026x)
 * 2. Create tenant (Xinran)
 * 3. Test plugin import
 *
 * Run with: npx playwright test tests/api/setup/init-env.spec.ts --headed
 *
 * @since 4.0.0
 */

import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import path from 'path';
import { BASE_URL } from '../../helpers/environments';
import { fileURLToPath } from 'url';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const ADMIN_STORAGE_PATH = path.resolve(__dirname, '../../storage/admin.json');

// Test credentials
const TEST_USER = {
  email: DEFAULT_TEST_ACCOUNT.email,
  password: DEFAULT_TEST_ACCOUNT.password,
};

// Tenant info
const TENANT_INFO = {
  name: 'Xinran',
  displayName: 'Xinran Technology',
  industry: 'technology', // 科技互联网
};

// Test pages for Page Designer tests
const TEST_PAGES = [
  {
    pageKey: 'e2e_test_dashboard',
    name: 'E2E Test Dashboard',
    title: 'E2E Test Dashboard',
    modelCode: 'page_schema',
    description: 'Overview-style list fixture for Page Designer E2E tests',
    kind: 'list',
    layout: { type: 'grid', cols: 12 },
    blocks: [
      {
        id: 'block_overview_stats',
        blockType: 'stat-card',
        layout: { colSpan: 12, rowSpan: 1 },
        title: 'Overview',
        cards: [
          { label: 'Total', value: '1234' },
          { label: 'Today', value: '56' },
        ],
      },
      {
        id: 'block_overview_table',
        blockType: 'table',
        layout: { colSpan: 12, rowSpan: 1 },
        columns: [
          { field: 'name', title: 'Name', width: 200 },
          { field: 'page_key', title: 'Page Key', width: 220 },
          { field: 'status', title: 'Status', width: 120 },
          { field: 'updated_at', title: 'Updated At', width: 180 },
        ],
      },
    ],
  },
  {
    pageKey: 'e2e_test_form',
    name: 'E2E Test Form',
    title: 'E2E Test Form',
    modelCode: 'page_schema',
    description: 'Form fixture for Page Designer E2E tests',
    kind: 'form',
    layout: { type: 'grid', cols: 12, gap: 12 },
    blocks: [
      {
        id: 'block_form_main',
        blockType: 'form-section',
        title: 'Basic Information',
        layout: { colSpan: 12, rowSpan: 1 },
        columns: 2,
        fields: [
          { field: 'name', layout: { colSpan: 6, rowSpan: 1 } },
          { field: 'page_key', layout: { colSpan: 6, rowSpan: 1 } },
          { field: 'kind', layout: { colSpan: 4, rowSpan: 1 } },
          { field: 'profile', layout: { colSpan: 4, rowSpan: 1 } },
          { field: 'model_code', layout: { colSpan: 4, rowSpan: 1 } },
          { field: 'description', layout: { colSpan: 12, rowSpan: 1 } },
        ],
      },
      {
        id: 'block_form_actions',
        blockType: 'form-buttons',
        layout: { colSpan: 12, rowSpan: 1 },
        buttons: [
          { code: 'save', primary: true, label: 'save' },
          { code: 'reset', label: 'reset' },
        ],
      },
    ],
  },
  {
    pageKey: 'e2e_test_list',
    name: 'E2E Test List',
    title: 'E2E Test List',
    modelCode: 'page_schema',
    description: 'List fixture for Page Designer E2E tests',
    kind: 'list',
    layout: { type: 'stack' },
    blocks: [
      {
        id: 'block_list_toolbar',
        blockType: 'toolbar',
        buttons: [
          { code: 'create', variant: 'primary', label: 'create' },
          { code: 'refresh', label: 'refresh' },
        ],
      },
      {
        id: 'block_list_filters',
        blockType: 'filters',
        fields: [{ field: 'name' }, { field: 'status' }],
      },
      {
        id: 'block_list_table',
        blockType: 'table',
        columns: [
          { field: 'name', title: 'Name', width: 200 },
          { field: 'page_key', title: 'Page Key', width: 220 },
          { field: 'status', title: 'Status', width: 120 },
          { field: 'updated_at', title: 'Updated At', width: 180 },
        ],
        rowActions: [
          { code: 'view', label: 'view' },
          { code: 'edit', label: 'edit' },
          { code: 'delete', label: 'delete' },
        ],
      },
    ],
  },
];

// Test plugin for import verification
const TEST_PLUGIN = {
  pluginId: 'com.test.init-verify',
  namespace: 'initv',
  version: '1.0.0',
  displayName: 'Init Verification Plugin',
  'displayName:zh-CN': '初始化验证插件',
  description: 'Plugin for verifying environment initialization',
  author: 'Test Team',
  minPlatformVersion: '1.0.0',

  // Import options - use OVERWRITE to handle repeated imports
  importOptions: {
    conflictStrategy: 'overwrite',
    validateReferences: true,
    autoDeployProcesses: true,
    autoPublishPages: false,
    createResourcePermissions: false,
  },

  dicts: [
    {
      code: 'initv_status',
      name: 'Status',
      'name:zh-CN': '状态',
      dictType: 'static',
      items: [
        { value: 'active', label: 'Active', 'label:zh-CN': '活跃', sortNo: 10, status: 'enabled' },
        {
          value: 'inactive',
          label: 'Inactive',
          'label:zh-CN': '非活跃',
          sortNo: 20,
          status: 'enabled',
        },
      ],
    },
  ],

  fields: [
    {
      code: 'initv_name',
      displayName: 'Name',
      'displayName:zh-CN': '名称',
      dataType: 'string',
      constraints: { required: true, maxLength: 100 },
      feature: { searchable: true },
    },
    {
      code: 'initv_status',
      displayName: 'Status',
      'displayName:zh-CN': '状态',
      dataType: 'enum',
      dictCode: 'initv_status',
      constraints: { required: true },
      defaultValue: 'active',
    },
  ],

  models: [
    {
      code: 'initv_record',
      displayName: 'Init Record',
      'displayName:zh-CN': '初始化记录',
      description: 'Record for init verification',
      modelType: 'entity',
    },
  ],

  modelFieldBindings: [
    { modelCode: 'initv_record', fieldCode: 'initv_name', sequence: 10, required: true },
    {
      modelCode: 'initv_record',
      fieldCode: 'initv_status',
      sequence: 20,
      required: true,
      defaultValue: 'active',
    },
  ],

  permissions: [
    {
      code: 'initv:record:read',
      name: 'View Init Records',
      'name:zh-CN': '查看初始化记录',
      resourceType: 'model',
      resourceCode: 'initv_record',
      action: 'read',
    },
  ],

  menus: [
    {
      code: 'initv_root',
      name: 'Init Verify',
      'name:zh-CN': '初始化验证',
      path: '/initv',
      icon: 'CheckCircle',
      type: 1,
      orderNo: 999,
      visible: false,
    },
    {
      code: 'initv_record_list',
      name: 'Init Records',
      'name:zh-CN': '初始化记录',
      path: '/initv/records',
      icon: 'List',
      type: 2,
      parentCode: 'initv_root',
      orderNo: 1,
      visible: false,
      pageKey: 'initv_record_list',
    },
  ],

  pages: [
    {
      pageKey: 'initv_record_list',
      'name:zh-CN': '初始化记录列表',
      pageType: 'list',
      pageCategory: 'model',
      modelCode: 'initv_record',
      dslSchema: {
        kind: 'List',
        version: '1.0.0',
        id: 'list.initv_record',
        modelCode: 'initv_record',
        layout: {
          areas: ['toolbar', 'main'],
          areasConfig: {
            toolbar: { type: 'flex', direction: 'row', justify: 'space-between', align: 'center' },
            main: { type: 'grid', cols: 12, rowGap: 0, colGap: 0 },
          },
        },
        areas: {
          toolbar: {
            blocks: [
              {
                id: 'initv_record_toolbar',
                blockType: 'toolbar',
                buttons: [{ code: 'create', action: 'create', primary: true }],
              },
            ],
          },
          main: {
            blocks: [
              {
                id: 'initv_record_table',
                blockType: 'table',
                columns: [
                  { field: 'initv_name', width: 200 },
                  { field: 'initv_status', width: 100 },
                ],
                rowActions: [
                  { code: 'view', action: 'view' },
                  { code: 'edit', action: 'update' },
                  { code: 'delete', action: 'delete', danger: true },
                ],
              },
            ],
          },
        },
      },
    },
    {
      pageKey: 'initv_record_form',
      'name:zh-CN': '初始化记录表单',
      pageType: 'form',
      pageCategory: 'model',
      modelCode: 'initv_record',
      dslSchema: {
        kind: 'Form',
        version: '1.0.0',
        id: 'form.initv_record',
        modelCode: 'initv_record',
        layout: {
          areas: ['main'],
          areasConfig: {
            main: { type: 'flex', direction: 'column', rowGap: 16 },
          },
        },
        areas: {
          main: {
            blocks: [
              {
                id: 'initv_record_basic',
                blockType: 'form-section',
                title: { 'zh-CN': '基本信息', 'en-US': 'Basic Info' },
                columns: 2,
                fields: [
                  { field: 'initv_name', layout: { colSpan: 6 } },
                  { field: 'initv_status', layout: { colSpan: 6 } },
                ],
              },
              {
                id: 'initv_record_buttons',
                blockType: 'form-buttons',
                buttons: [
                  {
                    code: 'create',
                    action: 'create',
                    primary: true,
                    visibleWhen: "state.mode === 'create'",
                  },
                  {
                    code: 'update',
                    action: 'update',
                    primary: true,
                    visibleWhen: "state.mode === 'edit'",
                  },
                  { code: 'cancel', action: 'cancel' },
                ],
              },
            ],
          },
        },
      },
    },
  ],
};

async function tryLoginByApi(
  request: APIRequestContext,
  email: string,
  password: string,
): Promise<{ jwt: string; tenantId: number | null; tenantStatus: string | null } | null> {
  const response = await request.post('/api/auth/login', {
    data: { email, password },
    headers: { 'Content-Type': 'application/json' },
  });
  if (!response.ok()) return null;
  const body = await response.json();
  const user = body?.data ?? body;
  const jwt = user?.jwt;
  if (!jwt || typeof jwt !== 'string') return null;
  return {
    jwt,
    tenantId: typeof user?.tenantId === 'number' ? user.tenantId : null,
    tenantStatus: typeof user?.tenantStatus === 'string' ? user.tenantStatus : null,
  };
}

async function loginByApi(request: APIRequestContext, email: string, password: string) {
  const result = await tryLoginByApi(request, email, password);
  expect(result, `Login failed for ${email}`).toBeTruthy();
  return result!;
}

function authHeaders(jwt: string): Record<string, string> {
  return {
    Authorization: `Bearer ${jwt}`,
    'Content-Type': 'application/json',
  };
}

async function refreshAdminStorageState(page: Page): Promise<void> {
  const loginResp = await page.request.post('/login', {
    form: {
      email: TEST_USER.email,
      password: TEST_USER.password,
      remember: 'on',
      redirectTo: '/',
    },
    maxRedirects: 0,
  });
  expect(loginResp.status(), 'BFF login should return redirect').toBe(302);
  const setCookie = loginResp.headers()['set-cookie'] || '';
  const match = setCookie.match(/__session=([^;]+)/);
  expect(match?.[1], 'Session cookie should be present').toBeTruthy();

  const cookieBase = {
    name: '__session',
    value: match![1],
    path: '/',
    httpOnly: true,
    sameSite: 'Lax' as const,
    expires: Math.floor(Date.now() / 1000) + 604800,
  };

  await page.context().addCookies([
    { ...cookieBase, domain: 'localhost' },
    { ...cookieBase, domain: '127.0.0.1' },
  ]);
  await page.context().storageState({ path: ADMIN_STORAGE_PATH });
}

async function registerByApiIfNeeded(
  request: APIRequestContext,
  email: string,
  password: string,
): Promise<void> {
  const existing = await tryLoginByApi(request, email, password);
  if (existing) return;

  const registerResponse = await request.post('/api/auth/register', {
    data: { email, password, displayName: email.split('@')[0] },
    headers: { 'Content-Type': 'application/json' },
  });

  if (!registerResponse.ok()) {
    const errorText = await registerResponse.text();
    const loginAfterFailure = await tryLoginByApi(request, email, password);
    if (loginAfterFailure) {
      return;
    }
    const knownAcceptable =
      /already|已存在|exists|duplicate|重复|bad parameter|self-registration.*disabled|single-tenant/i.test(
        errorText,
      ) || registerResponse.status() === 409;
    if (!knownAcceptable) {
      throw new Error(
        `Registration failed for ${email}: ${registerResponse.status()} ${errorText}`,
      );
    }
    // Self-registration disabled — user may have been provisioned by admin (POST /api/admin/users)
    // or via reset-and-init.sh. Not a failure, just skip.
  }

  const loginAfterRegister = await tryLoginByApi(request, email, password);
  if (!loginAfterRegister) {
    console.log(
      `   User ${email} not available (self-registration may be disabled, and admin has not provisioned this user yet)`,
    );
  }
}

async function assertUserAndTenant(
  request: APIRequestContext,
  jwt: string,
  options?: { requireTenant?: boolean },
) {
  const meResp = await request.get('/api/auth/me', { headers: authHeaders(jwt) });
  const meText = await meResp.text();
  expect(meResp.ok(), `GET /api/auth/me failed: ${meResp.status()} ${meText}`).toBe(true);
  const meJson = JSON.parse(meText);
  const meData = meJson?.data ?? meJson;
  const user = meData?.user ?? meData;
  expect(user, 'user payload missing in /api/auth/me').toBeTruthy();
  if (options?.requireTenant) {
    expect(user?.tenantId, 'tenantId should exist after tenant setup').toBeTruthy();
  }
  return user;
}

test.describe('Environment Initialization', () => {
  test.describe.configure({ mode: 'serial' });
  test.use({ storageState: { cookies: [], origins: [] } });

  test('Step 1: Register test user', async ({ page }) => {
    test.setTimeout(30000);
    console.log('Checking/registering test user...');
    await registerByApiIfNeeded(page.request, TEST_USER.email, TEST_USER.password);
    const login = await loginByApi(page.request, TEST_USER.email, TEST_USER.password);
    await assertUserAndTenant(page.request, login.jwt, { requireTenant: false });
    console.log('✅ Test user ready');
  });

  test('Step 2: Create tenant', async ({ page }) => {
    console.log('Creating tenant...');
    const login = await loginByApi(page.request, TEST_USER.email, TEST_USER.password);

    if (login.tenantId) {
      console.log(`   User already has tenant: ${login.tenantId}`);
      await assertUserAndTenant(page.request, login.jwt, { requireTenant: true });
      await refreshAdminStorageState(page);
      return;
    }

    const createWithName = async (tenantName: string, displayName: string) => {
      const resp = await page.request.post('/api/tenant-selection/process', {
        data: {
          action: 'create',
          tenantName,
          displayName,
          industry: TENANT_INFO.industry,
        },
        headers: authHeaders(login.jwt),
      });
      return { resp, text: await resp.text(), tenantName, displayName };
    };

    let createAttempt = await createWithName(TENANT_INFO.name, TENANT_INFO.displayName);
    if (!createAttempt.resp.ok() && createAttempt.resp.status() === 422) {
      const suffix = Date.now().toString().slice(-6);
      const fallbackName = `${TENANT_INFO.name}-${suffix}`;
      const fallbackDisplay = `${TENANT_INFO.displayName}-${suffix}`;
      console.log(`   Tenant name exists, retrying with: ${fallbackName}`);
      createAttempt = await createWithName(fallbackName, fallbackDisplay);
    }

    expect(
      createAttempt.resp.ok(),
      `Create tenant request failed: ${createAttempt.resp.status()} ${createAttempt.text}`,
    ).toBe(true);

    const createJson = JSON.parse(createAttempt.text);
    const result = createJson?.data ?? createJson;
    const status = result?.status;
    const newJwt = result?.jwt as string | undefined;
    expect(status, `Unexpected tenant creation status: ${createAttempt.text}`).toBe('success');
    expect(newJwt, 'Tenant creation should return refreshed jwt').toBeTruthy();

    await assertUserAndTenant(page.request, newJwt!, { requireTenant: true });
    await refreshAdminStorageState(page);
    console.log('✅ Tenant created successfully');
  });

  test('Step 3: Test plugin import', async ({ page }) => {
    console.log('📦 Testing plugin import...');
    const login = await loginByApi(page.request, TEST_USER.email, TEST_USER.password);
    await assertUserAndTenant(page.request, login.jwt, { requireTenant: true });

    const response = await page.request.post(
      '/api/plugins/import/execute-direct?conflictStrategy=OVERWRITE',
      {
        data: TEST_PLUGIN,
        headers: authHeaders(login.jwt),
      },
    );
    if (!response.ok()) {
      const bodyText = await response.text();
      const status = response.status();
      const isAcceptable =
        status === 409 ||
        /already exists|conflict|duplicate|being imported by another process/i.test(bodyText);
      if (!isAcceptable) {
        expect.soft(response.ok(), `Plugin import failed: ${status} ${bodyText}`).toBe(true);
        return;
      }
      console.log(`⚠️ Plugin already imported (${status}), verifying model still exists...`);
    } else {
      const result = await response.json();
      console.log('Plugin import result:', result);
    }

    await expect
      .poll(
        async () => {
          const modelResponse = await page.request.get('/api/meta/models/code/initv_record', {
            headers: authHeaders(login.jwt),
          });
          if (!modelResponse.ok()) {
            return null;
          }
          const modelData = await modelResponse.json();
          return modelData.data || modelData;
        },
        { timeout: 30000, intervals: [1000, 2000, 3000] },
      )
      .toMatchObject({ code: 'initv_record' });
    console.log('✅ Plugin model verified');
  });

  test('Step 4: Create test pages for Page Designer', async ({ page }) => {
    console.log('📄 Creating test pages for Page Designer...');
    const login = await loginByApi(page.request, TEST_USER.email, TEST_USER.password);
    await assertUserAndTenant(page.request, login.jwt, { requireTenant: true });

    let createdCount = 0;
    let existingCount = 0;

    for (const pageConfig of TEST_PAGES) {
      const checkResponse = await page.request.get(`/api/pages/key/${pageConfig.pageKey}`, {
        headers: authHeaders(login.jwt),
      });
      if (checkResponse.ok()) {
        const checkData = await checkResponse.json();
        if (checkData.success !== false && checkData.data) {
          existingCount++;
          continue;
        }
      }

      const createResponse = await page.request.post('/api/pages', {
        data: pageConfig,
        headers: authHeaders(login.jwt),
      });
      if (!createResponse.ok()) {
        console.log(
          `   ⚠️ Failed to create page ${pageConfig.pageKey}: ${createResponse.status()} ${await createResponse.text()}`,
        );
        continue;
      }

      const result = await createResponse.json();
      const pageData = result.data || result;
      const pagePid = pageData.pid;
      expect(pagePid, `Created page missing pid: ${pageConfig.pageKey}`).toBeTruthy();
      createdCount++;

      const publishResponse = await page.request.post(`/api/pages/${pagePid}/publish`, {
        headers: authHeaders(login.jwt),
      });
      if (!publishResponse.ok()) {
        console.log(
          `   ⚠️ Failed to publish page ${pageConfig.pageKey}: ${publishResponse.status()}`,
        );
      }
    }

    console.log(`✅ Test pages setup complete: ${createdCount} created, ${existingCount} existing`);
  });

  test('Step 5: Create system_overview dashboard', async ({ page }) => {
    console.log('📊 Creating system_overview dashboard...');
    const login = await loginByApi(page.request, TEST_USER.email, TEST_USER.password);
    await assertUserAndTenant(page.request, login.jwt, { requireTenant: true });

    const dashboardPayload = {
      code: 'system_overview',
      title: '系统概览',
      description: 'Live overview dashboard for Reports page',
      scope: 'global',
      layoutConfig: {
        columns: 12,
        rowHeight: 100,
        gap: 16,
      },
      widgets: [
        {
          i: 'widget_total_pages',
          x: 0,
          y: 0,
          w: 3,
          h: 2,
          type: 'NumberCard',
          title: '页面',
          config: {
            title: '页面',
            label: '页面',
            color: '#2563EB',
            dataSource: {
              type: 'aggregate',
              modelCode: 'page_schema',
              metrics: [{ field: 'id', aggregation: 'count', alias: 'count' }],
            },
          },
        },
        {
          i: 'widget_total_agents',
          x: 3,
          y: 0,
          w: 3,
          h: 2,
          type: 'NumberCard',
          title: 'Agent',
          config: {
            title: 'Agent',
            label: 'Agent',
            color: '#10B981',
            dataSource: {
              type: 'aggregate',
              modelCode: 'agent_definition',
              metrics: [{ field: 'id', aggregation: 'count', alias: 'count' }],
            },
          },
        },
        {
          i: 'widget_total_members',
          x: 6,
          y: 0,
          w: 3,
          h: 2,
          type: 'NumberCard',
          title: '成员',
          config: {
            title: '成员',
            label: '成员',
            color: '#F59E0B',
            dataSource: {
              type: 'aggregate',
              modelCode: 'tenant_member',
              metrics: [{ field: 'id', aggregation: 'count', alias: 'count' }],
            },
          },
        },
        {
          i: 'widget_total_missions',
          x: 9,
          y: 0,
          w: 3,
          h: 2,
          type: 'NumberCard',
          title: '任务',
          config: {
            title: '任务',
            label: '任务',
            color: '#8B5CF6',
            dataSource: {
              type: 'aggregate',
              modelCode: 'mission',
              metrics: [{ field: 'id', aggregation: 'count', alias: 'count' }],
            },
          },
        },
      ],
      isDefault: true,
    };

    const checkResponse = await page.request.get('/api/dashboards/code/system_overview', {
      headers: authHeaders(login.jwt),
    });
    if (checkResponse.ok()) {
      const checkData = await checkResponse.json();
      if (checkData.success !== false && checkData.data?.pid) {
        const updateResponse = await page.request.put(
          `/api/dashboards/${checkData.data.pid}`,
          {
            data: dashboardPayload,
            headers: authHeaders(login.jwt),
          },
        );
        expect(updateResponse.ok(), 'system_overview dashboard should update').toBeTruthy();
        await page.request.post(`/api/dashboards/${checkData.data.pid}/publish`, {
          headers: authHeaders(login.jwt),
        });
        console.log('✅ system_overview dashboard updated');
        return;
      }
    }

    const createResponse = await page.request.post(`/api/dashboards`, {
      data: dashboardPayload,
      headers: authHeaders(login.jwt),
    });
    if (!createResponse.ok()) {
      console.log(
        `   ⚠️ Failed to create dashboard: ${createResponse.status()} ${await createResponse.text()}`,
      );
      return;
    }
    const result = await createResponse.json();
    const dashboardPid = (result.data || result).pid;
    expect(dashboardPid, 'Created dashboard should have pid').toBeTruthy();

    const publishResponse = await page.request.post(`/api/dashboards/${dashboardPid}/publish`, {
      headers: authHeaders(login.jwt),
    });
    if (!publishResponse.ok()) {
      console.log(`   ⚠️ Failed to publish dashboard: ${publishResponse.status()}`);
      return;
    }
    console.log(`   ✅ Created and published dashboard: system_overview (${dashboardPid})`);
  });

  test('Step 6: Final verification', async ({ page }) => {
    console.log('🔍 Running final verification...');
    const login = await loginByApi(page.request, TEST_USER.email, TEST_USER.password);
    await assertUserAndTenant(page.request, login.jwt, { requireTenant: true });
    // Optional checks: page/dashboard permissions can vary by tenant bootstrap policy.
    const pagesResp = await page.request.get('/api/pages', { headers: authHeaders(login.jwt) });
    if (pagesResp.ok()) {
      const pagesBody = await pagesResp.json();
      const pagesData = pagesBody.data || pagesBody;
      const pages = Array.isArray(pagesData)
        ? pagesData
        : Array.isArray(pagesData?.records)
          ? pagesData.records
          : Array.isArray(pagesData?.items)
            ? pagesData.items
            : Array.isArray(pagesData?.content)
              ? pagesData.content
              : [];
      console.log(`   Pages visible: ${pages.length}`);
    } else {
      console.log(`   ⚠️ /api/pages not accessible for current tenant role: ${pagesResp.status()}`);
    }

    console.log('✅ Environment initialization complete!');
    console.log(`   User: ${TEST_USER.email}`);
    console.log(`   Tenant: ${TENANT_INFO.name}`);
    console.log(`   Plugin: ${TEST_PLUGIN.pluginId}`);
    console.log(`   Test Pages: ${TEST_PAGES.length}`);
    console.log('   Dashboard: system_overview');
  });

  test('Step 7: Register multi-role test users', async ({ page }) => {
    console.log('👥 Setting up multi-role test users...');
    test.setTimeout(60000);

    const ROLE_USERS = [
      {
        email: 'e2e-operator@test.com',
        password: DEFAULT_TEST_ACCOUNT.password,
        roleCode: 'operator',
      },
      { email: 'e2e-viewer@test.com', password: DEFAULT_TEST_ACCOUNT.password, roleCode: 'viewer' },
    ];

    const adminLogin = await loginByApi(page.request, TEST_USER.email, TEST_USER.password);
    await assertUserAndTenant(page.request, adminLogin.jwt, { requireTenant: true });

    const inviteResp = await page.request.post('/api/tenant/invite-code/generate?expiryDays=30', {
      headers: authHeaders(adminLogin.jwt),
    });
    let inviteCode: string | null = null;
    if (inviteResp.ok()) {
      const inviteData = await inviteResp.json();
      inviteCode = inviteData.data;
      console.log(`   Generated invite code: ${inviteCode}`);
    } else {
      console.log(`   ⚠️ Failed to generate invite code: ${inviteResp.status()}`);
      test.skip(true, 'Invite code API not available — multi-role setup skipped');
      return;
    }

    // Get all roles to find OPERATOR and VIEWER role IDs.
    // Use /api/roles/all (stable endpoint); /api/admin/roles does not exist.
    const rolesResp = await page.request.get('/api/roles/all', {
      headers: authHeaders(adminLogin.jwt),
    });
    let roleMap: Record<string, number> = {};
    if (rolesResp.ok()) {
      const rolesData = await rolesResp.json();
      const roles = Array.isArray(rolesData?.data)
        ? rolesData.data
        : Array.isArray(rolesData?.data?.records)
          ? rolesData.data.records
          : Array.isArray(rolesData)
            ? rolesData
            : [];
      if (Array.isArray(roles)) {
        for (const role of roles) {
          if (role.code === 'operator' || role.code === 'viewer') {
            const roleId = Number(role.id ?? role.roleId);
            if (Number.isFinite(roleId)) {
              roleMap[role.code] = roleId;
            }
          }
        }
      }
      console.log(`   Found roles: ${JSON.stringify(roleMap)}`);
    }

    if (!roleMap['operator'] || !roleMap['viewer']) {
      console.log('   ⚠️ operator/viewer roles not found. Run reset-and-init.sh first.');
      test.skip(true, 'operator/viewer roles not found');
      return;
    }

    // Register each role user
    for (const roleUser of ROLE_USERS) {
      console.log(`   Registering ${roleUser.email} (${roleUser.roleCode})...`);

      // Register/login user in isolated context, then use invite code
      const regBrowser = await (
        await import('@playwright/test')
      ).chromium.launch({
        args: ['--no-proxy-server'],
      });
      const regContext = await regBrowser.newContext({
        baseURL: (page.context() as any)._options?.baseURL || BASE_URL,
      });

      try {
        await registerByApiIfNeeded(regContext.request, roleUser.email, roleUser.password);
        const memberLogin = await tryLoginByApi(
          regContext.request,
          roleUser.email,
          roleUser.password,
        );
        if (memberLogin && inviteCode) {
          const useInviteResp = await regContext.request.post(
            `/api/tenant/invite-code/use?code=${inviteCode}`,
            { headers: authHeaders(memberLogin.jwt) },
          );
          if (useInviteResp.ok()) {
            console.log(`   ✅ ${roleUser.email} joined tenant via invite code`);
          } else {
            console.log(
              `   ℹ️ Invite code use status for ${roleUser.email}: ${useInviteResp.status()}`,
            );
          }
        } else if (!memberLogin) {
          // User may have been provisioned by admin (POST /api/admin/users) and is already a tenant member.
          // Login fails because JWT has no tenantId, but the user IS in the tenant.
          console.log(
            `   ℹ️ ${roleUser.email}: login returned no JWT (user may already be provisioned by admin)`,
          );
        }
      } finally {
        await regBrowser.close();
      }

      const membersResp = await page.request.get('/api/tenant/members', {
        headers: authHeaders(adminLogin.jwt),
      });
      if (membersResp.ok()) {
        const membersData = await membersResp.json();
        const members = membersData.data || membersData;
        if (Array.isArray(members)) {
          const member = members.find(
            (m: any) => m.email === roleUser.email || m.userEmail === roleUser.email,
          );
          if (member) {
            const userId = member.userId || member.id;
            const memberPid = member.pid;

            // Approve member if pending
            if (member.status === 'pending' && memberPid) {
              await page.request.post(`/api/tenant/members/${memberPid}/approve`, {
                data: { action: 'approve', reason: 'E2E test setup' },
                headers: authHeaders(adminLogin.jwt),
              });
              console.log(`   ✅ Approved ${roleUser.email}`);
            }

            // Assign role
            const roleId = roleMap[roleUser.roleCode];
            if (roleId && userId) {
              const assignResp = await page.request.put(`/api/user-roles/sync?userId=${userId}`, {
                data: [roleId],
                headers: authHeaders(adminLogin.jwt),
              });
              if (assignResp.ok()) {
                console.log(`   ✅ Assigned ${roleUser.roleCode} role to ${roleUser.email}`);
              } else {
                console.log(`   ⚠️ Role assignment failed: ${assignResp.status()}`);
              }
            }
          } else {
            console.log(`   ⚠️ ${roleUser.email} not found in tenant members`);
          }
        }
      }
    }

    console.log('✅ Multi-role test users setup complete');
  });

  test('Step 8: Seed marketplace plugins', async ({ page }) => {
    console.log('🛒 Seeding marketplace plugin registry...');

    // Check if marketplace already has plugins registered
    const login = await loginByApi(page.request, TEST_USER.email, TEST_USER.password);
    const checkResp = await page.request.get('/api/marketplace/plugins?pageNum=1&pageSize=1', {
      headers: authHeaders(login.jwt),
    });

    if (checkResp.ok()) {
      const body = await checkResp.json();
      const total = body?.data?.total ?? body?.data?.records?.length ?? 0;
      if (total > 0) {
        console.log(`   ℹ️ Marketplace already has ${total} plugins — skipping seed`);
        return;
      }
    }

    // Run seed-marketplace.sh to populate marketplace registry via psql
    const { execSync } = await import('child_process');
    const path = await import('path');
    const scriptPath = path.resolve(
      path.dirname(new URL(import.meta.url).pathname),
      '../../../../scripts/seed-marketplace.sh',
    );

    try {
      execSync(`bash "${scriptPath}"`, {
        encoding: 'utf-8',
        timeout: 60_000,
        stdio: 'pipe',
      });
      console.log('✅ Marketplace plugins seeded');
    } catch (error: unknown) {
      // Non-fatal — marketplace tests will be skipped or fail with clear message
      const msg = error instanceof Error ? error.message : String(error);
      console.log(`   ⚠️ Marketplace seeding failed (non-fatal): ${msg.slice(0, 200)}`);
    }
  });

  // Note: Test plugin is intentionally NOT cleaned up.
  // The initv_record model and related data are used by other E2E test suites.
  // See test-fixtures.setup.ts for the comprehensive fixture setup.
});
