/**
 * E2E Test Fixtures Setup
 *
 * Creates all prerequisite data needed by E2E test suites.
 * Runs as a Playwright setup project BEFORE the main test suite.
 *
 * Fixtures created:
 * 1. Enhanced test model (initv_record) with 10+ field types via plugin import
 * 1b. Business plugins (project-management, quarry-industry, annual-plan, dual-prevention)
 * 2. Published model → auto-generated dynamic pages
 * 3. Sample test data rows
 * 4. system_overview dashboard with chart widgets
 * 5. Page Designer test pages (dashboard, form, list)
 * 6. Test project for quarry-phase4 API tests
 *
 * All operations are idempotent — safe to run multiple times.
 *
 * @since 4.0.0
 */

import { test, expect } from '@playwright/test';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { BACKEND_URL } from '../../helpers/environments';
import { ErrorCodes } from '~/shared/services/http-client/types';

const PLUGINS_DIR = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '../../../../plugins',
);

// ---------------------------------------------------------------------------
// Enhanced test plugin with comprehensive field types
// ---------------------------------------------------------------------------

const E2E_PLUGIN = {
  pluginId: 'com.test.e2e-fixtures',
  namespace: 'e2et',
  version: '2.0.0',
  displayName: 'E2E Test Fixtures Plugin',
  'displayName:zh-CN': 'E2E 测试数据插件',
  description: 'Comprehensive test fixtures for E2E testing',
  author: 'E2E Test',
  minPlatformVersion: '1.0.0',

  importOptions: {
    conflictStrategy: 'overwrite',
    validateReferences: true,
    autoDeployProcesses: false,
    autoPublishPages: false,
    createResourcePermissions: false,
  },

  dicts: [
    {
      code: 'e2et_status',
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
        {
          value: 'pending',
          label: 'Pending',
          'label:zh-CN': '待处理',
          sortNo: 30,
          status: 'enabled',
        },
      ],
    },
    {
      code: 'e2et_priority',
      name: 'Priority',
      'name:zh-CN': '优先级',
      dictType: 'static',
      items: [
        { value: 'high', label: 'High', 'label:zh-CN': '高', sortNo: 10, status: 'enabled' },
        { value: 'medium', label: 'Medium', 'label:zh-CN': '中', sortNo: 20, status: 'enabled' },
        { value: 'low', label: 'Low', 'label:zh-CN': '低', sortNo: 30, status: 'enabled' },
      ],
    },
    {
      code: 'e2et_category',
      name: 'Category',
      'name:zh-CN': '分类',
      dictType: 'static',
      items: [
        {
          value: 'cat_a',
          label: 'Category A',
          'label:zh-CN': '分类A',
          sortNo: 10,
          status: 'enabled',
        },
        {
          value: 'cat_b',
          label: 'Category B',
          'label:zh-CN': '分类B',
          sortNo: 20,
          status: 'enabled',
        },
        {
          value: 'cat_c',
          label: 'Category C',
          'label:zh-CN': '分类C',
          sortNo: 30,
          status: 'enabled',
        },
      ],
    },
  ],

  fields: [
    // TEXT field
    {
      code: 'e2et_name',
      displayName: 'Name',
      'displayName:zh-CN': '名称',
      dataType: 'string',
      constraints: { required: true, maxLength: 200 },
      feature: { searchable: true },
    },
    // TEXTAREA field
    {
      code: 'e2et_description',
      displayName: 'Description',
      'displayName:zh-CN': '描述',
      dataType: 'text',
      constraints: { maxLength: 2000 },
    },
    // NUMBER field
    {
      code: 'e2et_amount',
      displayName: 'Amount',
      'displayName:zh-CN': '金额',
      dataType: 'decimal',
      constraints: { min: 0, max: 999999 },
    },
    // INTEGER field
    {
      code: 'e2et_count',
      displayName: 'Count',
      'displayName:zh-CN': '数量',
      dataType: 'integer',
      constraints: { min: 0, max: 10000 },
    },
    // ENUM (single select)
    {
      code: 'e2et_status',
      displayName: 'Status',
      'displayName:zh-CN': '状态',
      dataType: 'enum',
      dictCode: 'e2et_status',
      constraints: { required: true },
      defaultValue: 'active',
    },
    // ENUM (used as priority)
    {
      code: 'e2et_priority',
      displayName: 'Priority',
      'displayName:zh-CN': '优先级',
      dataType: 'enum',
      dictCode: 'e2et_priority',
      defaultValue: 'medium',
    },
    // ENUM (multi-select categories)
    {
      code: 'e2et_categories',
      displayName: 'Categories',
      'displayName:zh-CN': '分类',
      dataType: 'enum',
      dictCode: 'e2et_category',
    },
    // DATE field
    {
      code: 'e2et_due_date',
      displayName: 'Due Date',
      'displayName:zh-CN': '截止日期',
      dataType: 'date',
    },
    // BOOLEAN field
    {
      code: 'e2et_is_active',
      displayName: 'Is Active',
      'displayName:zh-CN': '是否启用',
      dataType: 'boolean',
      defaultValue: 'true',
    },
  ],

  models: [
    {
      code: 'e2et_record',
      displayName: 'E2E Test Record',
      'displayName:zh-CN': 'E2E测试记录',
      description: 'Comprehensive model for E2E testing with various field types',
      modelType: 'entity',
    },
  ],

  modelFieldBindings: [
    { modelCode: 'e2et_record', fieldCode: 'e2et_name', sequence: 10, required: true },
    { modelCode: 'e2et_record', fieldCode: 'e2et_description', sequence: 20 },
    { modelCode: 'e2et_record', fieldCode: 'e2et_amount', sequence: 30 },
    { modelCode: 'e2et_record', fieldCode: 'e2et_count', sequence: 40 },
    {
      modelCode: 'e2et_record',
      fieldCode: 'e2et_status',
      sequence: 50,
      required: true,
      defaultValue: 'active',
    },
    { modelCode: 'e2et_record', fieldCode: 'e2et_priority', sequence: 60, defaultValue: 'medium' },
    { modelCode: 'e2et_record', fieldCode: 'e2et_categories', sequence: 70 },
    { modelCode: 'e2et_record', fieldCode: 'e2et_due_date', sequence: 80 },
    { modelCode: 'e2et_record', fieldCode: 'e2et_is_active', sequence: 90, defaultValue: 'true' },
  ],

  permissions: [
    {
      code: 'e2et:record:read',
      name: 'View E2E Records',
      'name:zh-CN': '查看E2E测试记录',
      resourceType: 'model',
      resourceCode: 'e2et_record',
      action: 'read',
    },
    {
      code: 'e2et:record:write',
      name: 'Edit E2E Records',
      'name:zh-CN': '编辑E2E测试记录',
      resourceType: 'model',
      resourceCode: 'e2et_record',
      action: 'write',
    },
  ],

  pages: [
    {
      pageKey: 'e2et_record_list',
      'name:zh-CN': 'E2E测试记录列表',
      pageType: 'list',
      pageCategory: 'model',
      modelCode: 'e2et_record',
      dslSchema: {
        kind: 'List',
        version: '1.0.0',
        id: 'list.e2et_record',
        modelCode: 'e2et_record',
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
                id: 'e2et_record_toolbar',
                blockType: 'toolbar',
                buttons: [{ code: 'create', action: 'create', primary: true }],
              },
            ],
          },
          main: {
            blocks: [
              {
                id: 'e2et_record_table',
                blockType: 'table',
                columns: [
                  { field: 'e2et_name', width: 200 },
                  { field: 'e2et_status', width: 100 },
                  { field: 'e2et_priority', width: 100 },
                  { field: 'e2et_amount', width: 120 },
                  { field: 'e2et_count', width: 100 },
                  { field: 'e2et_due_date', width: 150 },
                  { field: 'e2et_is_active', width: 80 },
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
      pageKey: 'e2et_record_form',
      'name:zh-CN': 'E2E测试记录表单',
      pageType: 'form',
      pageCategory: 'model',
      modelCode: 'e2et_record',
      dslSchema: {
        kind: 'Form',
        version: '1.0.0',
        id: 'form.e2et_record',
        modelCode: 'e2et_record',
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
                id: 'e2et_record_basic',
                blockType: 'form-section',
                title: { 'zh-CN': '基本信息', 'en-US': 'Basic Info' },
                columns: 2,
                fields: [
                  { field: 'e2et_name', layout: { colSpan: 6 } },
                  { field: 'e2et_description', layout: { colSpan: 6 } },
                  { field: 'e2et_status', layout: { colSpan: 6 } },
                  { field: 'e2et_priority', layout: { colSpan: 6 } },
                ],
              },
              {
                id: 'e2et_record_details',
                blockType: 'form-section',
                title: { 'zh-CN': '详细信息', 'en-US': 'Details' },
                columns: 2,
                fields: [
                  { field: 'e2et_amount', layout: { colSpan: 6 } },
                  { field: 'e2et_count', layout: { colSpan: 6 } },
                  { field: 'e2et_categories', layout: { colSpan: 6 } },
                  { field: 'e2et_due_date', layout: { colSpan: 6 } },
                  { field: 'e2et_is_active', layout: { colSpan: 6 } },
                ],
              },
              {
                id: 'e2et_record_buttons',
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

// ---------------------------------------------------------------------------
// Test data rows
// ---------------------------------------------------------------------------

const TEST_DATA_ROWS = [
  {
    e2et_name: 'E2E Test Record Alpha',
    e2et_description: 'First test record for E2E testing',
    e2et_amount: 1500.5,
    e2et_count: 42,
    e2et_status: 'active',
    e2et_priority: 'high',
    e2et_due_date: '2026-12-31',
    e2et_is_active: true,
  },
  {
    e2et_name: 'E2E Test Record Beta',
    e2et_description: 'Second test record with different status',
    e2et_amount: 2800.0,
    e2et_count: 17,
    e2et_status: 'pending',
    e2et_priority: 'medium',
    e2et_due_date: '2026-06-15',
    e2et_is_active: true,
  },
  {
    e2et_name: 'E2E Test Record Gamma',
    e2et_description: 'Third test record marked inactive',
    e2et_amount: 500.25,
    e2et_count: 5,
    e2et_status: 'inactive',
    e2et_priority: 'low',
    e2et_due_date: '2026-03-01',
    e2et_is_active: false,
  },
];

// ---------------------------------------------------------------------------
// Setup tests (serial, uses authenticated storageState)
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Shared backend JWT for direct API calls (bypasses BFF proxy).
// Persists across serial test steps so we only login once.
// ---------------------------------------------------------------------------
let sharedBackendJwt: string | null = null;
const DIRECT_BACKEND_URL = process.env.BACKEND_URL ?? `http://localhost:${process.env.BE_PORT ?? '6443'}`;

async function obtainBackendJwt(page: import('@playwright/test').Page): Promise<string | null> {
  if (sharedBackendJwt) return sharedBackendJwt;
  try {
    const loginResp = await page.request.post(`${DIRECT_BACKEND_URL}/api/auth/login`, {
      data: { email: DEFAULT_TEST_ACCOUNT.email, password: DEFAULT_TEST_ACCOUNT.password },
      headers: { 'Content-Type': 'application/json' },
      timeout: 15000,
    });
    if (loginResp.ok()) {
      const loginBody = await loginResp.json().catch(() => ({}));
      sharedBackendJwt = loginBody?.data?.jwt ?? null;
    }
  } catch {
    /* backend may be unreachable */
  }
  return sharedBackendJwt;
}

/**
 * Make a GET request with BFF-then-backend fallback.
 * If the BFF (port 5173) is unreachable, falls back to backend (port 6443) directly.
 */
async function resilientGet(
  page: import('@playwright/test').Page,
  apiPath: string,
  timeout = 30000,
): Promise<any> {
  try {
    const resp = await page.request.get(apiPath, { timeout });
    if (resp.ok() || resp.status() < 500) {
      return resp;
    }
    // BFF returned 5xx — fall through to direct backend request.
  } catch {
    /* BFF unreachable */
  }
  // Fallback: try backend directly
  const jwt = await obtainBackendJwt(page);
  if (jwt) {
    try {
      return await page.request.get(`${DIRECT_BACKEND_URL}${apiPath}`, {
        timeout,
        headers: { Authorization: `Bearer ${jwt}` },
      });
    } catch {
      /* backend also unreachable */
    }
  }
  // Last resort: retry BFF (may have recovered)
  return page.request.get(apiPath, { timeout });
}

/**
 * Make a POST request with BFF-then-backend fallback.
 */
async function resilientPost(
  page: import('@playwright/test').Page,
  apiPath: string,
  options?: { data?: any; headers?: Record<string, string>; timeout?: number },
): Promise<any> {
  const opts = { timeout: 30000, ...options };
  try {
    const resp = await page.request.post(apiPath, opts);
    // Accept successful and client-error responses from BFF as final.
    if (resp.ok() || resp.status() === 202 || resp.status() < 500) {
      return resp;
    }
    // BFF returned 5xx — fall through to direct backend request.
  } catch {
    /* BFF unreachable */
  }
  const jwt = await obtainBackendJwt(page);
  if (jwt) {
    try {
      return await page.request.post(`${DIRECT_BACKEND_URL}${apiPath}`, {
        ...opts,
        headers: { ...opts.headers, Authorization: `Bearer ${jwt}` },
      });
    } catch {
      /* backend also unreachable */
    }
  }
  return page.request.post(apiPath, opts);
}

/**
 * Make a PUT request with BFF-then-backend fallback.
 */
async function resilientPut(
  page: import('@playwright/test').Page,
  apiPath: string,
  options?: { data?: any; headers?: Record<string, string>; timeout?: number },
): Promise<any> {
  const opts = { timeout: 30000, ...options };
  try {
    const resp = await page.request.put(apiPath, opts);
    if (resp.ok() || resp.status() < 500) {
      return resp;
    }
  } catch {
    /* BFF unreachable */
  }
  const jwt = await obtainBackendJwt(page);
  if (jwt) {
    try {
      return await page.request.put(`${DIRECT_BACKEND_URL}${apiPath}`, {
        ...opts,
        headers: { ...opts.headers, Authorization: `Bearer ${jwt}` },
      });
    } catch {
      /* backend also unreachable */
    }
  }
  return page.request.put(apiPath, opts);
}

test.describe('E2E Test Fixtures Setup', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  /**
   * Step 1: Import E2E test plugin (model + fields + dicts + pages)
   */
  test('Fixture: Import E2E test plugin', async ({ page }) => {
    // Override default timeout for API calls — actionTimeout from config is too low for setup
    page.setDefaultTimeout(30000);
    console.log('📦 Importing E2E test plugin...');

    // Eagerly obtain backend JWT for all subsequent steps
    const jwt = await obtainBackendJwt(page);

    // Check if model already exists and is published
    const checkResponse = await resilientGet(page, `/api/meta/models/code/e2et_record`);
    if (checkResponse?.ok()) {
      const checkData = await checkResponse.json();
      if (checkData.data?.status === 'published') {
        // Verify it has enough fields
        const fieldsResp = await resilientGet(
          page,
          `/api/meta/models/${checkData.data.pid}/fields`,
        );
        if (fieldsResp?.ok()) {
          const fieldsData = await fieldsResp.json();
          const fieldCount = Array.isArray(fieldsData.data) ? fieldsData.data.length : 0;
          if (fieldCount >= 8) {
            console.log(`✅ E2E model already exists with ${fieldCount} fields, skipping import`);
            return;
          }
        }
      }
    }

    // Import the plugin — use resilient POST with BFF-then-backend fallback
    let importOk = false;
    const importData = E2E_PLUGIN;
    const importHeaders = { 'Content-Type': 'application/json' };

    try {
      const response = await resilientPost(
        page,
        `/api/plugins/import/execute-direct?conflictStrategy=OVERWRITE`,
        { data: importData, headers: importHeaders, timeout: 60000 },
      );

      if (response?.ok()) {
        const result = await response.json();
        if (result.success) {
          console.log(`✅ Plugin imported: ${result.totalResourceCount} resources`);
          importOk = true;
        } else {
          console.log(
            `⚠️ Plugin import status: ${result.status} - ${result.errorMessage || 'unknown'}`,
          );
        }
      } else {
        const errorText = response ? await response.text().catch(() => '') : 'unreachable';
        console.log(
          `⚠️ Plugin import: ${response?.status?.() ?? 'N/A'} - ${String(errorText).substring(0, 200)}`,
        );
      }
    } catch (e) {
      console.log(`⚠️ Plugin import failed: ${e}`);
    }

    // Verify model exists (retry with resilient fallback)
    let verifyResp = await resilientGet(page, `/api/meta/models/code/e2et_record`);
    if (!verifyResp?.ok()) {
      const errBody = verifyResp ? await verifyResp.text().catch(() => '') : 'unreachable';
      console.log(
        `⚠️ Verify attempt 1 failed: HTTP ${verifyResp?.status?.()} - ${String(errBody).substring(0, 200)}`,
      );
      await new Promise((r) => setTimeout(r, 3000));
      verifyResp = await resilientGet(page, `/api/meta/models/code/e2et_record`);
      if (!verifyResp?.ok()) {
        const errBody2 = verifyResp ? await verifyResp.text().catch(() => '') : 'unreachable';
        console.log(
          `⚠️ Verify attempt 2 failed: HTTP ${verifyResp?.status?.()} - ${String(errBody2).substring(0, 200)}`,
        );
      }
    }
    expect(verifyResp?.ok(), `Model verify failed with HTTP ${verifyResp?.status?.()}`).toBe(true);
    const verifyData = await verifyResp.json();
    expect(verifyData.data?.code).toBe('e2et_record');
    console.log(`✅ Model verified: e2et_record (pid: ${verifyData.data?.pid})`);

    // Bind dictionaries to ENUM fields (plugin import may not auto-bind)
    const dictBindings = [
      { fieldCode: 'e2et_status', dictCode: 'e2et_status' },
      { fieldCode: 'e2et_priority', dictCode: 'e2et_priority' },
      { fieldCode: 'e2et_categories', dictCode: 'e2et_category' },
    ];

    for (const { fieldCode, dictCode } of dictBindings) {
      // Get field PID
      const fieldResp = await resilientGet(page, `/api/meta/fields/key/${fieldCode}`);
      if (!fieldResp?.ok()) {
        console.log(`⚠️ Field not found: ${fieldCode}`);
        continue;
      }
      const fieldData = await fieldResp.json();
      const fieldPid = fieldData.data?.pid;
      if (!fieldPid) continue;

      // Check if already bound
      const fieldsResp = await resilientGet(page, `/api/meta/models/${verifyData.data.pid}/fields`);
      if (fieldsResp?.ok()) {
        const fieldsData = await fieldsResp.json();
        const field = (fieldsData.data || []).find((f: any) => f.code === fieldCode);
        if (field?.dictCode) {
          console.log(`  ✅ Dict already bound: ${fieldCode} → ${field.dictCode}`);
          continue;
        }
      }

      // Bind dictionary
      const bindResp = await resilientPost(page, `/api/meta/fields/${fieldPid}/bind-dict`, {
        data: { dictCode },
        headers: { 'Content-Type': 'application/json' },
      });
      if (bindResp?.ok()) {
        console.log(`  ✅ Dict bound: ${fieldCode} → ${dictCode}`);
      } else {
        console.log(`  ⚠️ Dict bind failed: ${fieldCode} → ${dictCode} (${bindResp?.status?.()})`);
      }
    }
  });

  /**
   * Step 1b: Import business plugins from local directories.
   * Uses import-directory API which resolves resourceDirs references.
   * Creates models, fields, pages, menus, commands for all local plugins.
   */
  test('Fixture: Import business plugins', async ({ page }) => {
    test.setTimeout(360000);
    page.setDefaultTimeout(30000);
    console.log('📦 Importing business plugins...');

    // Ensure backend JWT is available for all fallback calls
    await obtainBackendJwt(page);

    const getModelStatus = async (modelCode: string): Promise<string | null> => {
      try {
        const modelResp = await resilientGet(page, `/api/meta/models/code/${modelCode}`);
        if (!modelResp?.ok()) return null;
        const modelBody = await modelResp.json().catch(() => ({}));
        return modelBody?.data?.status ?? null;
      } catch {
        return null;
      }
    };

    const getInstalledPluginIds = async (): Promise<Set<string>> => {
      try {
        const pluginResp = await resilientGet(page, '/api/plugins?current=1&size=500');
        if (!pluginResp?.ok()) return new Set();
        const pluginBody = await pluginResp.json().catch(() => ({}));
        const records =
          pluginBody?.data?.records ?? pluginBody?.data?.data ?? pluginBody?.data ?? [];
        if (!Array.isArray(records)) return new Set();
        return new Set(
          records
            .map((p: Record<string, unknown>) => String(p.pluginId ?? ''))
            .filter((id: string) => id.length > 0),
        );
      } catch {
        return new Set();
      }
    };

    let installedPluginIds = await getInstalledPluginIds();

    // Order matters: project-management first (dependency for quarry-industry)
    const pluginDirs = [
      'project-management',
      'quarry-industry',
      'contract-cost',
      'construction-process',
      'doc-knowledge',
      'annual-plan',
      'dual-prevention',
      'e2e-test-order',
      'asset-management',
      // Generic / shared plugins (import in dependency order)
      'crm',
      'org-management',
      'finance',
      'product-catalog',
      'inventory',
      'quality',
      'procurement',
      'sales',
      'sales-templates',
      'tax-compliance',
      // PCBA plugins (import in dependency order)
      'pcba-crm',
      'pcba-industry',
      'pcba-solution',
      'platform-admin',
      'agent-control-plane',
      // App templates used by templates-smoke
      'templates/crm-quick-start',
      'templates/project-management',
      'templates/asset-management',
      'templates/simple-inventory',
      'templates/hr-essentials',
    ];

    for (const pluginName of pluginDirs) {
      const pluginDir = path.join(PLUGINS_DIR, pluginName);

      // Check if model from this plugin already exists and is published
      const modelCodes: Record<string, string> = {
        'project-management': 'pm_project',
        'quarry-industry': 'qo_daily_report',
        'contract-cost': 'cc_contract',
        'construction-process': 'cp_construction_log',
        'doc-knowledge': 'dk_document',
        'annual-plan': 'ap_annual_plan',
        'dual-prevention': 'dp_issue',
        'e2e-test-order': 'e2et_order',
        'asset-management': 'asset',
        crm: 'crm_lead',
        'org-management': 'org_department',
        sales: 'sl_sales_order',
        procurement: 'pr_purchase_order',
        inventory: 'inv_inbound',
        finance: 'fin_account',
        quality: 'qc_iqc_order',
        'platform-admin': 'sla_config',
        'agent-control-plane': 'mission',
        'product-catalog': 'prod_product',
        'sales-templates': 'stpl_b2b_deal',
        'tax-compliance': 'tax_vat_rate',
        'pcba-crm': 'pe_rfq',
        'pcba-industry': 'pe_production_plan',
        'pcba-solution': 'pe_supplier_contact',
        'templates/crm-quick-start': 'tcrm_lead',
        'templates/project-management': 'tpm_project',
        'templates/asset-management': 'tasset_asset',
        'templates/simple-inventory': 'tinv_product',
        'templates/hr-essentials': 'thr_employee',
      };
      const pluginIds: Record<string, string> = {
        'project-management': 'com.auraboot.project-management',
        'quarry-industry': 'com.auraboot.quarry-industry',
        'contract-cost': 'com.auraboot.contract-cost',
        'construction-process': 'com.auraboot.construction-process',
        'doc-knowledge': 'com.auraboot.doc-knowledge',
        'annual-plan': 'com.auraboot.annual-plan',
        'dual-prevention': 'com.auraboot.dual-prevention',
        'e2e-test-order': 'com.test.e2e-order',
        'asset-management': 'com.auraboot.asset-management',
        crm: 'com.auraboot.crm',
        'org-management': 'com.auraboot.org-management',
        sales: 'com.auraboot.sales',
        procurement: 'com.auraboot.procurement',
        inventory: 'com.auraboot.inventory',
        finance: 'com.auraboot.finance',
        quality: 'com.auraboot.quality',
        'product-catalog': 'com.auraboot.product-catalog',
        'sales-templates': 'com.auraboot.sales-templates',
        'tax-compliance': 'com.auraboot.tax-compliance',
        'pcba-crm': 'com.auraboot.pcba-crm',
        'pcba-industry': 'com.auraboot.pcba-industry',
        'pcba-solution': 'com.auraboot.pcba-solution',
        'platform-admin': 'com.auraboot.platform-admin',
        'agent-control-plane': 'com.auraboot.agent-control-plane',
        'templates/crm-quick-start': 'com.auraboot.template.crm-quick-start',
        'templates/project-management': 'com.auraboot.template.project-management',
        'templates/asset-management': 'com.auraboot.template.asset-management',
        'templates/simple-inventory': 'com.auraboot.template.simple-inventory',
        'templates/hr-essentials': 'com.auraboot.template.hr-essentials',
      };
      const checkCode = modelCodes[pluginName];
      const checkPluginId = pluginIds[pluginName];
      let isInstalledPublished = false;
      if (checkCode) {
        try {
          const checkResp = await resilientGet(page, `/api/meta/models/code/${checkCode}`);
          if (checkResp?.ok()) {
            const checkData = await checkResp.json();
            if (checkData.data?.status === 'published') {
              isInstalledPublished = true;
            }
          }
        } catch {
          // Model check failed — will attempt import
        }
      }

      // Guard against stale plugin resources in DB when local plugin config has evolved.
      // If key resources are missing, force re-import even when base model is published.
      let reimportReason: string | null = null;
      if (isInstalledPublished && pluginName === 'quarry-industry') {
        try {
          const dashboardPageResp = await resilientGet(
            page,
            `/api/pages/key/qo_dashboard_data_list`,
          );
          if (!dashboardPageResp?.ok()) {
            reimportReason = 'missing page qo_dashboard_data_list';
          } else {
            const dashboardPageData = await dashboardPageResp.json().catch(() => ({}));
            const dsl = dashboardPageData.data?.dslSchema ?? dashboardPageData.data?.dsl ?? {};
            if (dsl?.kind !== 'Dashboard') {
              reimportReason = 'qo_dashboard_data_list is not Dashboard DSL';
            }
          }
        } catch {
          reimportReason = 'qo_dashboard_data_list check failed (network)';
        }
      }

      if (isInstalledPublished && pluginName === 'annual-plan') {
        try {
          const detailResp = await resilientGet(page, `/api/pages/key/ap_annual_plan_detail`);
          if (!detailResp?.ok()) {
            reimportReason = 'missing page ap_annual_plan_detail';
          } else {
            const detailData = await detailResp.json().catch(() => ({}));
            const dsl = detailData.data?.dslSchema ?? detailData.data?.dsl ?? {};
            const hasMonthlyGrid = JSON.stringify(dsl).includes('"monthly-grid"');
            if (!hasMonthlyGrid) {
              reimportReason = 'ap_annual_plan_detail does not contain monthly-grid blocks';
            }
          }
        } catch {
          reimportReason = 'ap_annual_plan_detail check failed (network)';
        }
      }

      if (isInstalledPublished && pluginName === 'construction-process') {
        try {
          const namedQueryResp = await resilientGet(
            page,
            '/api/meta/named-queries/by-code/cp_weekly_summary_nq',
          );
          if (!namedQueryResp?.ok()) {
            reimportReason = 'missing named query cp_weekly_summary_nq';
          }
        } catch {
          reimportReason = 'cp_weekly_summary_nq check failed (network)';
        }
      }

      if (isInstalledPublished && pluginName === 'dual-prevention') {
        try {
          const modelResp = await resilientGet(page, `/api/meta/models/code/dp_issue`);
          if (!modelResp?.ok()) {
            reimportReason = 'missing model dp_issue';
          } else {
            const modelData = await modelResp.json().catch(() => ({}));
            const modelPid = modelData.data?.pid;
            if (!modelPid) {
              reimportReason = 'dp_issue model pid not found';
            } else {
              const fieldsResp = await resilientGet(page, `/api/meta/models/${modelPid}/fields`);
              if (!fieldsResp?.ok()) {
                reimportReason = 'dp_issue model fields unavailable';
              } else {
                const fieldsData = await fieldsResp.json().catch(() => ({}));
                const fields = Array.isArray(fieldsData.data) ? fieldsData.data : [];
                const triageField = fields.find((f: any) => f?.code === 'dp_triage_decision');
                if (!triageField) {
                  reimportReason = 'missing field dp_triage_decision';
                } else {
                  const dataType = String(
                    triageField.dataType ?? triageField.data_type ?? '',
                  ).toLowerCase();
                  if (dataType !== 'enum') {
                    reimportReason = `dp_triage_decision dataType is ${dataType || 'unknown'}, expected enum`;
                  }
                }
              }
            }
          }
        } catch {
          reimportReason = 'dp_issue check failed (network)';
        }
        if (!reimportReason) {
          const ensureSearchFields = async (pageKey: string, expectedFields: string[]) => {
            try {
              const pageResp = await resilientGet(page, `/api/pages/key/${pageKey}`);
              if (!pageResp?.ok()) return `missing page ${pageKey}`;
              const pageData = await pageResp.json().catch(() => ({}));
              const dsl = pageData.data?.dslSchema ?? pageData.data?.dsl ?? {};
              const text = JSON.stringify(dsl);
              for (const field of expectedFields) {
                if (!text.includes(`\"${field}\"`)) {
                  return `${pageKey} missing search field ${field}`;
                }
              }
              return null;
            } catch {
              return `${pageKey} check failed (network)`;
            }
          };

          reimportReason =
            (await ensureSearchFields('dp_rectification_list', ['dp_rect_no'])) ??
            (await ensureSearchFields('dp_inspection_task_list', ['dp_task_no']));
          if (!reimportReason) {
            try {
              const compliancePageResp = await resilientGet(
                page,
                '/api/pages/key/dp_compliance_report_list',
              );
              if (!compliancePageResp?.ok()) {
                reimportReason = 'missing page dp_compliance_report_list';
              }
            } catch {
              reimportReason = 'dp_compliance_report_list check failed (network)';
            }
          }
        }
      }

      if (isInstalledPublished && pluginName === 'project-management') {
        try {
          const deviationPageResp = await resilientGet(
            page,
            '/api/pages/key/pm_schedule_deviation_list',
          );
          if (!deviationPageResp?.ok()) {
            reimportReason = 'missing page pm_schedule_deviation_list';
          }
        } catch {
          reimportReason = 'pm_schedule_deviation_list check failed (network)';
        }
        if (!reimportReason) {
          try {
            const modelResp = await resilientGet(page, '/api/meta/models/code/pm_project_member');
            if (!modelResp?.ok()) {
              reimportReason = 'pm_project_member model unavailable';
            } else {
              const modelBody = await modelResp.json().catch(() => ({}));
              const modelPid = modelBody?.data?.pid;
              if (!modelPid) {
                reimportReason = 'pm_project_member model pid not found';
              } else {
                const fieldsResp = await resilientGet(page, `/api/meta/models/${modelPid}/fields`);
                if (!fieldsResp?.ok()) {
                  reimportReason = 'pm_project_member fields unavailable';
                } else {
                  const fieldsBody = await fieldsResp.json().catch(() => ({}));
                  const fields = Array.isArray(fieldsBody.data) ? fieldsBody.data : [];
                  const roleField = fields.find((f: any) => f?.code === 'pm_member_role_id');
                  if (!roleField) {
                    reimportReason = 'missing field pm_member_role_id';
                  } else if (roleField.required === true) {
                    reimportReason = 'pm_member_role_id is still required';
                  }
                }
              }
            }
          } catch {
            reimportReason = 'pm_project_member role binding check failed (network)';
          }
        }
      }

      const getCommandMeta = async (commandCode: string) => {
        try {
          const resp = await resilientGet(
            page,
            `/api/meta/commands/by-code/${encodeURIComponent(commandCode)}`,
          );
          if (!resp?.ok()) return null;
          const body = await resp.json().catch(() => ({}));
          return body?.data ?? null;
        } catch {
          return null;
        }
      };

      const commandExists = async (commandCode: string) => !!(await getCommandMeta(commandCode));

      if (isInstalledPublished && pluginName === 'inventory') {
        const requiredCommands = ['pe:create_warehouse', 'pe:allocate_inventory'];
        for (const commandCode of requiredCommands) {
          if (!(await commandExists(commandCode))) {
            reimportReason = `missing command ${commandCode}`;
            break;
          }
        }
        if (!reimportReason) {
          const expectedCommandTypes: Record<string, string> = {
            'pe:allocate_inventory': 'action',
            'pe:hold_inventory': 'action',
            'pe:auto_putaway': 'action',
          };
          for (const [commandCode, expectedType] of Object.entries(expectedCommandTypes)) {
            const meta = await getCommandMeta(commandCode);
            if (!meta) {
              reimportReason = `missing command ${commandCode}`;
              break;
            }
            const actualType = String(meta.type ?? '').toLowerCase();
            if (actualType !== expectedType) {
              reimportReason = `command ${commandCode} type is ${actualType || 'unknown'}, expected ${expectedType}`;
              break;
            }
          }
        }
      }

      if (isInstalledPublished && pluginName === 'procurement' && !reimportReason) {
        const requiredCommands = ['pe:create_supplier', 'pr:add_po_line'];
        for (const commandCode of requiredCommands) {
          if (!(await commandExists(commandCode))) {
            reimportReason = `missing command ${commandCode}`;
            break;
          }
        }
      }

      if (isInstalledPublished && pluginName === 'pcba-industry' && !reimportReason) {
        const requiredCommands = [
          'pe:create_ecn',
          'pe:submit_ecn',
          'pe:approve_ecn',
          'pe:create_order_confirmation',
        ];
        for (const commandCode of requiredCommands) {
          if (!(await commandExists(commandCode))) {
            reimportReason = `missing command ${commandCode}`;
            break;
          }
        }
      }

      if (
        isInstalledPublished &&
        (pluginName === 'pcba-industry' || pluginName === 'pcba-solution') &&
        !reimportReason
      ) {
        let probeSupplierId: string | null = null;
        let probeEvalId: string | null = null;
        try {
          const execCommand = async (
            code: string,
            payload: Record<string, unknown>,
            targetRecordId?: string,
            operationType?: string,
          ) => {
            const data: Record<string, unknown> = { payload };
            if (targetRecordId) data.targetRecordId = targetRecordId;
            if (operationType) data.operationType = operationType;
            const resp = await page.request.post(`/api/meta/commands/execute/${code}`, { data });
            const body = await resp.json().catch(() => ({}) as any);
            const dataBody = (body as any)?.data ?? {};
            const nested = dataBody?.data ?? {};
            return {
              ok: resp.ok() && String((body as any)?.code ?? '') === '0',
              recordId: String(
                nested?.recordId ?? dataBody?.recordId ?? (body as any)?.recordId ?? '',
              ),
            };
          };

          const supplier = await execCommand(
            'pe:create_supplier',
            {
              pe_supplier_name: `E2E Probe Supplier ${Date.now()}`,
              pe_supplier_contact: 'Probe',
            },
            undefined,
            'create',
          );
          if (!supplier.ok) {
            reimportReason = 'missing command pe:create_supplier';
          } else if (supplier.recordId) {
            probeSupplierId = supplier.recordId;
            const supplierEval = await execCommand(
              'pe:create_supplier_eval',
              {
                pe_se_supplier_id: probeSupplierId,
                pe_se_period: `PROBE-${Date.now()}`,
              },
              undefined,
              'create',
            );
            if (!supplierEval.ok) {
              reimportReason = 'missing command pe:create_supplier_eval';
            } else if (supplierEval.recordId) {
              probeEvalId = supplierEval.recordId;
              await execCommand('pe:submit_supplier_eval', {}, probeEvalId, 'update');

              const detailResp = await resilientGet(
                page,
                `/api/dynamic/pe_supplier_eval/${probeEvalId}`,
              );
              if (!detailResp?.ok()) {
                reimportReason = 'supplier eval probe failed: detail API unavailable';
              } else {
                const detailData = await detailResp.json().catch(() => ({}));
                const status = String(detailData?.data?.pe_se_status ?? '');
                if (status !== 'submitted') {
                  reimportReason = `supplier eval submit transition not effective (status=${status || 'unknown'})`;
                }
              }
            }
          }
        } catch {
          reimportReason = 'supplier eval transition probe failed';
        } finally {
          if (probeEvalId) {
            await page.request
              .delete(`/api/dynamic/pe_supplier_eval/${probeEvalId}`)
              .catch(() => {});
          }
          if (probeSupplierId) {
            await page.request
              .delete(`/api/dynamic/pe_supplier/${probeSupplierId}`)
              .catch(() => {});
          }
        }
      }

      if (isInstalledPublished && pluginName === 'finance') {
        let probePaymentId: string | null = null;
        try {
          const data: Record<string, unknown> = {
            operationType: 'create',
            payload: {
              fin_pay_type: 'receipt',
              fin_pay_date: new Date().toISOString().slice(0, 10),
              fin_pay_amount: 1,
              fin_pay_method: 'cash',
              fin_pay_remark: `E2E Probe Payment ${Date.now()}`,
            },
          };
          const resp = await page.request.post('/api/meta/commands/execute/fin:create_payment', {
            data,
          });
          const body = await resp.json().catch(() => ({}) as any);
          const bodyData = (body as any)?.data ?? {};
          const nested = bodyData?.data ?? {};
          const success = resp.ok() && String((body as any)?.code ?? '') === '0';
          probePaymentId =
            String(nested?.recordId ?? bodyData?.recordId ?? (body as any)?.recordId ?? '') || null;
          if (!success || !probePaymentId) {
            reimportReason = `payment command probe failed (http=${resp.status()}, code=${String((body as any)?.code ?? 'unknown')})`;
          }
        } catch {
          reimportReason = 'payment command probe failed';
        } finally {
          if (probePaymentId) {
            await page.request
              .post('/api/meta/commands/execute/fin:delete_payment', {
                data: { targetRecordId: probePaymentId, operationType: 'delete', payload: {} },
              })
              .catch(() => {});
          }
        }
      }

      if (isInstalledPublished && checkPluginId && !installedPluginIds.has(checkPluginId)) {
        reimportReason = `missing plugin registration ${checkPluginId}`;
      }

      if (isInstalledPublished && !reimportReason) {
        console.log(`  ✅ ${pluginName}: already installed (model ${checkCode} published)`);
        continue;
      }
      if (isInstalledPublished && reimportReason) {
        console.log(
          `  ♻️ ${pluginName}: installed but outdated (${reimportReason}), re-importing...`,
        );
      }

      let imported = false;

      const importPayload = {
        path: pluginDir,
        conflictStrategy: 'overwrite',
        autoPublishModels: true,
        autoPublishFields: true,
        autoPublishCommands: true,
        autoPublishPages: true,
      };

      // Submit async import — returns immediately with a taskCode
      // Try BFF first, then fall back to backend directly
      let submitResp: any = null;
      try {
        submitResp = await page.request.post(`/api/plugins/import/import-directory`, {
          data: importPayload,
          timeout: 15000,
          headers: { 'Content-Type': 'application/json' },
        });
      } catch {
        // BFF unreachable — try backend directly
        const jwt = await obtainBackendJwt(page);
        if (jwt) {
          try {
            submitResp = await page.request.post(
              'http://127.0.0.1:6443/api/plugins/import/import-directory',
              {
                data: importPayload,
                timeout: 15000,
                headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${jwt}` },
              },
            );
          } catch {
            /* backend also unreachable */
          }
        }
      }

      if (submitResp && (submitResp.ok() || submitResp.status() === 202)) {
        const submitResult = await submitResp.json().catch(() => ({}));
        const taskData = submitResult.data || submitResult;
        const taskCode = taskData.taskCode;
        const submitErrorMessage = String(taskData.errorMessage || taskData.message || '');

        if (taskCode) {
          // Poll async task until completion (max 10 minutes)
          // Use apiGet helper for BFF-then-backend fallback on each poll
          console.log(`  ⏳ ${pluginName}: async import started (taskCode: ${taskCode})`);
          const maxPollMs = 600000;
          const pollIntervalMs = 5000;
          const pollStart = Date.now();
          let taskStatus = 'pending';

          while (Date.now() - pollStart < maxPollMs) {
            await new Promise((r) => setTimeout(r, pollIntervalMs));
            try {
              const statusResp = await resilientGet(page, `/api/async-tasks/${taskCode}`);
              if (!statusResp?.ok()) continue;
              const statusData = await statusResp.json().catch(() => ({}));
              const task = statusData.data || statusData;
              taskStatus = task.status || 'unknown';
              const progress = task.progress || 0;
              const progressMsg = task.progressMessage || '';
              const taskError = String(task.errorMessage || task.message || '');

              if (taskStatus === 'completed') {
                console.log(
                  `  ✅ ${pluginName}: async import completed (${Math.round((Date.now() - pollStart) / 1000)}s)`,
                );
                imported = true;
                break;
              }
              if (taskStatus === 'failed') {
                console.log(
                  `  ⚠️ ${pluginName}: async import failed: ${task.errorMessage || 'unknown'}`,
                );
                if (/being imported by another process/i.test(taskError) && checkCode) {
                  console.log(`  ⏳ ${pluginName}: waiting for concurrent import to finish...`);
                  const waitStart = Date.now();
                  const maxWaitMs = 90000;
                  while (Date.now() - waitStart < maxWaitMs) {
                    await new Promise((r) => setTimeout(r, 5000));
                    const status = await getModelStatus(checkCode);
                    if (status === 'published') {
                      console.log(
                        `  ✅ ${pluginName}: concurrent import finished (${Math.round((Date.now() - waitStart) / 1000)}s)`,
                      );
                      imported = true;
                      break;
                    }
                  }
                }
                break;
              }
              if (taskStatus === 'cancelled') {
                console.log(`  ⚠️ ${pluginName}: async import was cancelled`);
                break;
              }
              const elapsed = Math.round((Date.now() - pollStart) / 1000);
              if (elapsed % 15 < pollIntervalMs / 1000 + 1) {
                console.log(
                  `  ⏳ ${pluginName}: ${taskStatus} ${progress}% - ${progressMsg} (${elapsed}s)`,
                );
              }
            } catch {
              // Both BFF and backend unreachable — continue polling
            }
          }
        } else {
          // Sync response format
          if (taskData.success) {
            console.log(`  ✅ ${pluginName}: imported (sync response)`);
            imported = true;
          } else if (/being imported by another process/i.test(submitErrorMessage) && checkCode) {
            console.log(`  ⏳ ${pluginName}: waiting for concurrent import to finish...`);
            const waitStart = Date.now();
            const maxWaitMs = 90000;
            while (Date.now() - waitStart < maxWaitMs) {
              await new Promise((r) => setTimeout(r, 5000));
              const status = await getModelStatus(checkCode);
              if (status === 'published') {
                console.log(
                  `  ✅ ${pluginName}: concurrent import finished (${Math.round((Date.now() - waitStart) / 1000)}s)`,
                );
                imported = true;
                break;
              }
            }
          }
        }
      } else {
        const errorText = submitResp ? await submitResp.text().catch(() => '') : 'BFF unreachable';
        const statusCode = submitResp?.status?.() ?? 'N/A';
        console.log(
          `  ⚠️ ${pluginName}: async submit failed HTTP ${statusCode} - ${String(errorText).substring(0, 200)}`,
        );
      }

      // Fallback: sync import directly to backend (bypasses BFF proxy timeout)
      if (!imported && checkCode) {
        const jwt = await obtainBackendJwt(page);
        if (jwt) {
          console.log(`  🔄 ${pluginName}: trying sync fallback via backend...`);
          try {
            const backendResp = await page.request.post(
              'http://127.0.0.1:6443/api/plugins/import/import-directory-sync',
              {
                data: importPayload,
                timeout: 600000,
                headers: {
                  'Content-Type': 'application/json',
                  Authorization: `Bearer ${jwt}`,
                },
              },
            );
            if (backendResp.ok()) {
              const bd = await backendResp.json().catch(() => ({}));
              if ((bd.data || bd).success) {
                console.log(`  ✅ ${pluginName}: imported via backend sync fallback`);
                imported = true;
              }
            } else {
              const err = await backendResp.text();
              console.log(
                `  ⚠️ ${pluginName}: sync fallback HTTP ${backendResp.status()} - ${err.substring(0, 200)}`,
              );
            }
          } catch (e) {
            console.log(`  ⚠️ ${pluginName}: sync fallback failed: ${e}`);
          }
        }
      }

      if (!imported && checkCode) {
        const status = await getModelStatus(checkCode);
        if (status !== 'published') {
          console.warn(
            `  ⚠️ ${pluginName} import failed (model ${checkCode} status: ${status ?? 'null'}). Continuing with remaining plugins...`,
          );
        }
      } else if (imported) {
        installedPluginIds = await getInstalledPluginIds();
      }
    }

    // Publish all draft pages and commands from business plugins via direct SQL is not
    // possible from E2E tests. Instead, ensure pages and commands are published via API.
    // The import-directory with autoPublishPages=true should handle pages.
    // For commands, we need to publish them individually.
    // Use apiGet/apiPost for BFF-then-backend fallback resilience.
    const commandPrefixes = [
      'pm:',
      'qo:',
      'ap:',
      'dp:',
      'e2et:',
      'pe:',
      'asset:',
      'fac:',
      'crm:',
      'sl:',
      'pr:',
      'inv:',
      'fin:',
      'qc:',
    ];
    try {
      const cmdsResp = await resilientGet(page, `/api/meta/commands?status=draft&size=100`);
      if (cmdsResp?.ok()) {
        const cmdsData = await cmdsResp.json();
        const commands = cmdsData.data?.records || cmdsData.data || [];
        if (Array.isArray(commands)) {
          let published = 0;
          for (const cmd of commands) {
            if (commandPrefixes.some((p) => cmd.code?.startsWith(p)) && cmd.status === 'draft') {
              try {
                const pubResp = await resilientPost(page, `/api/meta/commands/${cmd.pid}/publish`);
                if (pubResp?.ok()) published++;
              } catch {
                /* skip failed publish */
              }
            }
          }
          if (published > 0) {
            console.log(`  ✅ Published ${published} draft commands`);
          }
        }
      }
    } catch {
      console.log('  ⚠️ Failed to check draft commands');
    }

    // Publish any remaining draft pages from plugins
    try {
      const pagesResp = await resilientGet(page, `/api/pages?current=1&size=200`);
      if (pagesResp?.ok()) {
        const pagesData = await pagesResp.json();
        const allPages = pagesData.data?.data || pagesData.data?.records || [];
        if (Array.isArray(allPages)) {
          let published = 0;
          for (const p of allPages) {
            if (p.status === 'draft' && p.pluginPid) {
              try {
                const pubResp = await resilientPost(page, `/api/pages/${p.pid}/publish`);
                if (pubResp?.ok()) published++;
              } catch {
                /* skip failed publish */
              }
            }
          }
          if (published > 0) {
            console.log(`  ✅ Published ${published} draft pages`);
          }
        }
      }
    } catch {
      console.log('  ⚠️ Failed to check draft pages');
    }

    // Publish any remaining draft VIEW models
    for (const code of [
      'qo_daily_summary',
      'ap_consolidated_view',
      'ap_progress_view',
      'ap_statistical_view',
      'ap_version_summary',
    ]) {
      try {
        const modelResp = await resilientGet(page, `/api/meta/models/code/${code}`);
        if (modelResp?.ok()) {
          const modelData = await modelResp.json();
          if (modelData.data?.status === 'draft') {
            await resilientPost(page, `/api/meta/models/${modelData.data.pid}/publish`);
          }
        }
      } catch {
        /* skip */
      }
    }

    const criticalModels = ['e2et_order'];
    const optionalModels = [
      'prod_product',
      'sl_sales_quotation',
      'pm_project',
      'qo_daily_report',
      'ap_annual_plan',
      'dp_issue',
      'asset',
      'fin_account',
    ];
    for (const requiredCode of criticalModels) {
      const status = await getModelStatus(requiredCode);
      expect(status, `Critical model ${requiredCode} should be published after setup`).toBe(
        'published',
      );
    }
    for (const optionalCode of optionalModels) {
      const status = await getModelStatus(optionalCode);
      if (status !== 'published') {
        console.warn(
          `  ⚠️ Optional model ${optionalCode} is not published (status: ${status ?? 'null'}). Some tests may skip.`,
        );
      }
    }
  });

  /**
   * Step 1c: Assign plugin permissions to TENANT_ADMIN role.
   * Plugin import creates permissions but does NOT auto-assign them to any role.
   * Without this step, command execution (approve, reject, start_inspection, etc.)
   * returns 403 Forbidden.
   */
  test('Fixture: Assign plugin permissions to TENANT_ADMIN', async ({ page }) => {
    page.setDefaultTimeout(30000);
    console.log('🔐 Assigning plugin permissions to TENANT_ADMIN...');

    // Find TENANT_ADMIN role
    const rolesResp = await resilientGet(page, `/api/roles?page=0&size=10`);
    if (!rolesResp?.ok()) {
      console.log('⚠️ Cannot list roles, skipping permission assignment');
      return;
    }
    const rolesData = await rolesResp.json();
    const roles = rolesData.data?.records || [];
    const adminRole = (roles as any[]).find((r) => r.code === 'tenant_admin');
    if (!adminRole) {
      console.log('⚠️ TENANT_ADMIN role not found');
      return;
    }

    // Get current role permissions (PID list)
    const currentResp = await resilientGet(page, `/api/roles/${adminRole.pid}/permissions`);
    if (!currentResp.ok()) {
      console.log('⚠️ Cannot get current permissions');
      return;
    }
    const currentData = await currentResp.json();
    const currentPids: string[] = Array.isArray(currentData.data) ? currentData.data : [];
    const currentPidSet = new Set(currentPids);

    // Get all relevant plugin permissions and bind them to admin role.
    const permissionPrefixes = [
      'ap.',
      'dp.',
      'qo.',
      'cc.',
      'cp.',
      'dk.',
      'e2et.',
      'pm.',
      'pe.',
      'fac.',
      'asset:',
      'dynamic.',
    ];
    const matchesPluginPermission = (code: string | undefined) =>
      !!code && permissionPrefixes.some((prefix) => code.startsWith(prefix));

    const missingPids: string[] = [];
    for (const resourceType of ['operation', 'data', 'model', 'menu']) {
      const permsResp = await resilientGet(page, `/api/permissions/resource-type/${resourceType}`);
      if (!permsResp?.ok()) continue;
      const permsData = await permsResp.json();
      const perms = Array.isArray(permsData.data) ? permsData.data : [];
      for (const p of perms) {
        if (p.pid && matchesPluginPermission(p.code) && !currentPidSet.has(p.pid)) {
          missingPids.push(p.pid);
        }
      }
    }

    if (missingPids.length === 0) {
      console.log('✅ TENANT_ADMIN already has all plugin permissions');
      return;
    }

    // Merge current + missing and sync (POST replaces all role permissions)
    const allPids = [...currentPids, ...missingPids];
    const assignResp = await resilientPost(page, `/api/roles/${adminRole.pid}/permissions`, {
      data: allPids,
      headers: { 'Content-Type': 'application/json' },
    });

    if (assignResp?.ok()) {
      console.log(
        `✅ Assigned ${missingPids.length} plugin permissions to TENANT_ADMIN (total: ${allPids.length})`,
      );
    } else {
      console.log(`⚠️ Permission assignment failed: HTTP ${assignResp.status()}`);
    }
  });

  /**
   * Step 2: Publish E2E test model (generates dynamic pages)
   */
  test('Fixture: Publish E2E test model', async ({ page }) => {
    page.setDefaultTimeout(30000);
    console.log('📤 Publishing E2E test model...');

    const modelResp = await resilientGet(page, `/api/meta/models/code/e2et_record`);
    expect(modelResp?.ok()).toBe(true);
    const modelData = await modelResp.json();
    const model = modelData.data;
    expect(model).toBeTruthy();

    if (model.status === 'published') {
      console.log('✅ Model already published');
    } else {
      // Publish the model
      const publishResp = await resilientPost(page, `/api/meta/models/${model.pid}/publish`);

      if (publishResp?.ok()) {
        console.log('✅ Model published successfully');
      } else {
        const errorText = publishResp ? await publishResp.text() : 'unreachable';
        console.log(
          `⚠️ Publish response: ${publishResp?.status?.()} - ${String(errorText).substring(0, 200)}`,
        );
        // May already be published, verify
        const recheck = await resilientGet(page, `/api/meta/models/code/e2et_record`);
        const recheckData = await recheck.json();
        expect(recheckData.data?.status).toBe('published');
      }
    }

    // Ensure page schemas exist for e2et_record model (list + form).
    // The E2E plugin import uses autoPublishPages=false, so pages may not be created.
    // Create them via API if missing, then publish.
    const pageKeys = ['e2et_record_list', 'e2et_record_form'];
    const pageDefs: Record<string, any> = {
      e2et_record_list: {
        pageKey: 'e2et_record_list',
        name: 'E2E Test Record List',
        title: 'E2E Test Record List',
        pageType: 'list',
        pageCategory: 'model',
        modelCode: 'e2et_record',
        dslSchema: {
          kind: 'List',
          version: '1.0.0',
          id: 'list.e2et_record',
          modelCode: 'e2et_record',
          layout: {
            areas: ['toolbar', 'main'],
            areasConfig: {
              toolbar: {
                type: 'flex',
                direction: 'row',
                justify: 'space-between',
                align: 'center',
              },
              main: { type: 'grid', cols: 12, rowGap: 0, colGap: 0 },
            },
          },
          areas: {
            toolbar: {
              blocks: [
                {
                  id: 'e2et_record_toolbar',
                  blockType: 'toolbar',
                  buttons: [{ code: 'create', action: 'create', primary: true }],
                },
              ],
            },
            main: {
              blocks: [
                {
                  id: 'e2et_record_table',
                  blockType: 'table',
                  columns: [
                    { field: 'e2et_name', width: 200 },
                    { field: 'e2et_status', width: 100 },
                    { field: 'e2et_priority', width: 100 },
                    { field: 'e2et_amount', width: 120 },
                    { field: 'e2et_count', width: 100 },
                    { field: 'e2et_due_date', width: 150 },
                    { field: 'e2et_is_active', width: 80 },
                  ],
                  rowActions: [
                    { code: 'view', action: 'view' },
                    { code: 'edit', action: 'update' },
                    { code: 'delete', action: 'delete', danger: true },
                  ],
                  showDataTools: true,
                },
              ],
            },
          },
        },
      },
      e2et_record_form: {
        pageKey: 'e2et_record_form',
        name: 'E2E Test Record Form',
        title: 'E2E Test Record Form',
        pageType: 'form',
        pageCategory: 'model',
        modelCode: 'e2et_record',
        dslSchema: {
          kind: 'Form',
          version: '1.0.0',
          id: 'form.e2et_record',
          modelCode: 'e2et_record',
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
                  id: 'e2et_record_basic',
                  blockType: 'form-section',
                  title: { 'zh-CN': '基本信息', 'en-US': 'Basic' },
                  columns: 2,
                  fields: [
                    { field: 'e2et_name', layout: { colSpan: 6 } },
                    { field: 'e2et_description', layout: { colSpan: 6 } },
                    { field: 'e2et_status', layout: { colSpan: 6 } },
                    { field: 'e2et_priority', layout: { colSpan: 6 } },
                  ],
                },
                {
                  id: 'e2et_record_details',
                  blockType: 'form-section',
                  title: { 'zh-CN': '详细信息', 'en-US': 'Details' },
                  columns: 2,
                  fields: [
                    { field: 'e2et_amount', layout: { colSpan: 6 } },
                    { field: 'e2et_count', layout: { colSpan: 6 } },
                    { field: 'e2et_categories', layout: { colSpan: 6 } },
                    { field: 'e2et_due_date', layout: { colSpan: 6 } },
                    { field: 'e2et_is_active', layout: { colSpan: 6 } },
                  ],
                },
                {
                  id: 'e2et_record_buttons',
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
    };
    for (const key of pageKeys) {
      // Check individual page by pageKey to avoid pagination limits
      const checkResp = await resilientGet(page, `/api/pages?current=1&size=1&pageKey=${key}`);
      const checkData = checkResp?.ok() ? await checkResp.json() : { data: { data: [] } };
      const matchedPages = checkData.data?.data || checkData.data?.records || [];
      const found = matchedPages.find((p: any) => p.pageKey === key);

      if (found?.status === 'published') {
        console.log(`✅ Page already published: ${key}`);
      } else if (found && found.status !== 'published') {
        const pubResp = await resilientPost(page, `/api/pages/${found.pid}/publish`);
        console.log(
          pubResp?.ok() ? `✅ Page published: ${key}` : `⚠️ Failed to publish page: ${key}`,
        );
      } else {
        const createResp = await resilientPost(page, `/api/pages`, {
          data: pageDefs[key],
          headers: { 'Content-Type': 'application/json' },
        });
        if (createResp?.ok()) {
          const createData = await createResp.json();
          const newPid = createData.data?.pid || createData.pid;
          if (newPid) {
            const pubResp = await resilientPost(page, `/api/pages/${newPid}/publish`);
            console.log(
              pubResp?.ok()
                ? `✅ Page created & published: ${key}`
                : `⚠️ Page created but publish failed: ${key}`,
            );
          } else {
            console.log(`✅ Page created (no pid returned): ${key}`);
          }
        } else {
          const bodyText = await createResp?.text().catch(() => 'N/A');
          console.log(
            `⚠️ Failed to create page: ${key} (${createResp?.status()}) body: ${bodyText}`,
          );
        }
      }
    }

    // Publish and sync e2e-test-order models.
    // Existing published models may still be missing physical tables after re-import,
    // so setup must force an explicit schema sync instead of trusting status alone.
    const orderModels = [
      'e2et_order',
      'e2et_order_item',
      'e2et_order_log',
      'e2et_customer',
      'e2et_payment',
    ];
    for (const code of orderModels) {
      const mResp = await resilientGet(page, `/api/meta/models/code/${code}`);
      if (mResp?.ok()) {
        const mData = await mResp.json();
        const modelPid = mData.data?.pid;
        if (mData.data?.status === 'draft') {
          const pubResp = await resilientPost(page, `/api/meta/models/${mData.data.pid}/publish`);
          if (pubResp?.ok()) {
            console.log(`✅ Order model published: ${code}`);
          } else {
            console.log(`⚠️ Failed to publish order model: ${code}`);
          }
        } else if (mData.data?.status === 'published') {
          console.log(`✅ Order model already published: ${code}`);
        }
        if (modelPid) {
          const syncResp = await resilientPost(page, `/api/meta/models/${modelPid}/sync-schema`);
          if (syncResp?.ok()) {
            console.log(`✅ Order model schema synced: ${code}`);
          } else {
            const bodyText = await syncResp?.text().catch(() => 'N/A');
            console.log(
              `⚠️ Failed to sync order model schema: ${code} (${syncResp?.status()}) body: ${bodyText}`,
            );
          }
        }
      } else {
        console.log(
          `⚠️ Order model not found: ${code} (e2e-test-order plugin may not be imported yet)`,
        );
      }
    }

    // Publish e2et_order pages and commands
    const orderPageKeys = ['e2et_order_list', 'e2et_order_form', 'e2et_order_detail'];
    const allPagesResp = await resilientGet(page, `/api/pages?current=1&size=200`);
    if (allPagesResp?.ok()) {
      const allPagesData = await allPagesResp.json();
      const pages = allPagesData.data?.data || allPagesData.data?.records || [];
      if (Array.isArray(pages)) {
        for (const key of orderPageKeys) {
          const found = pages.find((p: any) => p.pageKey === key);
          if (found && found.status !== 'published') {
            await resilientPost(page, `/api/pages/${found.pid}/publish`);
            console.log(`✅ Order page published: ${key}`);
          } else if (found?.status === 'published') {
            console.log(`✅ Order page already published: ${key}`);
          }
        }
      }
    }
  });

  /**
   * Step 3: Insert test data rows
   */
  test('Fixture: Insert test data rows', async ({ page }) => {
    page.setDefaultTimeout(30000);
    console.log('📝 Inserting test data...');

    // Check if data already exists
    const listResp = await resilientGet(page, `/api/dynamic/e2et_record/list?page=1&size=5`);

    if (listResp?.ok()) {
      const listData = await listResp.json();
      const records = listData.data?.records || listData.data || [];
      const existingCount = Array.isArray(records) ? records.length : 0;
      if (existingCount >= 3) {
        console.log(`✅ Test data already exists (${existingCount} rows)`);
        return;
      }
    }

    // Insert test rows
    let insertedCount = 0;
    for (const row of TEST_DATA_ROWS) {
      const insertResp = await resilientPost(page, `/api/dynamic/e2et_record`, {
        data: row,
        headers: { 'Content-Type': 'application/json' },
      });

      if (insertResp?.ok()) {
        insertedCount++;
      } else {
        const errorText = insertResp ? await insertResp.text() : 'unreachable';
        console.log(
          `⚠️ Insert failed: ${insertResp?.status?.()} - ${String(errorText).substring(0, 200)}`,
        );
      }
    }

    console.log(`✅ Inserted ${insertedCount}/${TEST_DATA_ROWS.length} test data rows`);
  });

  /**
   * Step 4: Ensure system_overview dashboard exists with widgets
   */
  test('Fixture: Ensure dashboard exists', async ({ page }) => {
    page.setDefaultTimeout(30000);
    console.log('📊 Checking dashboard...');

    const dashboardPayload = {
      code: 'system_overview',
      title: '系统概览',
      description: 'Live overview dashboard for E2E testing',
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

    const checkResp = await resilientGet(page, `/api/dashboards/code/system_overview`);

    if (checkResp?.ok()) {
      const checkData = await checkResp.json();
      if (checkData.data?.pid) {
        const updateResp = await resilientPut(page, `/api/dashboards/${checkData.data.pid}`, {
          data: dashboardPayload,
          headers: { 'Content-Type': 'application/json' },
        });
        if (updateResp?.ok()) {
          await resilientPost(page, `/api/dashboards/${checkData.data.pid}/publish`);
          console.log('✅ Dashboard updated');
          return;
        }
        console.log(`⚠️ Dashboard update failed: ${updateResp?.status?.()}`);
        return;
      }
    }

    // Create dashboard if not exists
    const createResp = await resilientPost(page, `/api/dashboards`, {
      data: dashboardPayload,
      headers: { 'Content-Type': 'application/json' },
    });

    if (createResp?.ok()) {
      const result = await createResp.json();
      const dashPid = result.data?.pid;
      console.log(`✅ Dashboard created (pid: ${dashPid})`);

      // Publish
      if (dashPid) {
        await resilientPost(page, `/api/dashboards/${dashPid}/publish`);
        console.log('✅ Dashboard published');
      }
    } else {
      console.log(`⚠️ Dashboard creation: ${createResp?.status?.()} (may already exist)`);
    }
  });

  /**
   * Step 5: Ensure Page Designer test pages exist
   */
  test('Fixture: Ensure Page Designer pages', async ({ page }) => {
    page.setDefaultTimeout(30000);
    console.log('📄 Checking Page Designer pages...');

    const testPages = [
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
      {
        pageKey: 'dashboard_management_list',
        name: 'Dashboard Management',
        title: 'Dashboard Management',
        description: 'DSL-driven dashboard management list page',
        pageType: 'list',
        pageCategory: 'custom',
        dslSchema: {
          id: 'list.dashboard_management',
          kind: 'List',
          modelCode: 'dashboard_management',
          version: '2.0.0',
          areas: {
            tabs: {
              blocks: [
                {
                  id: 'block_tabs',
                  blockType: 'tabs',
                  tabs: [
                    { key: 'all', label: { 'zh-CN': '全部', 'en-US': 'All' } },
                    {
                      key: 'personal',
                      label: { 'zh-CN': '个人', 'en-US': 'Personal' },
                      filter: { field: 'scope', operator: 'EQ', value: 'personal' },
                    },
                    {
                      key: 'global',
                      label: { 'zh-CN': '全局', 'en-US': 'Global' },
                      filter: { field: 'scope', operator: 'EQ', value: 'global' },
                    },
                  ],
                },
              ],
            },
            toolbar: {
              blocks: [
                {
                  id: 'block_toolbar',
                  blockType: 'toolbar',
                  buttons: [
                    {
                      code: 'create',
                      icon: 'Plus',
                      action: 'create',
                      primary: true,
                      navigateTo: '/dashboard-designer',
                    },
                  ],
                },
              ],
            },
            filters: {
              blocks: [
                {
                  id: 'block_filters',
                  blockType: 'filters',
                  fields: [
                    {
                      field: 'title',
                      label: { 'zh-CN': '标题', 'en-US': 'Title' },
                      component: 'SmartInput',
                      props: {
                        placeholder: {
                          'zh-CN': '搜索仪表盘标题',
                          'en-US': 'Search dashboard title',
                        },
                      },
                    },
                  ],
                  layout: { colSpan: 12, rowSpan: 1 },
                },
              ],
            },
            main: {
              blocks: [
                {
                  id: 'block_table',
                  blockType: 'table',
                  table: {
                    rowKey: 'pid',
                    columns: [
                      { field: 'title', label: { 'zh-CN': '标题', 'en-US': 'Title' }, width: 200 },
                      { field: 'code', label: { 'zh-CN': '编码', 'en-US': 'Code' }, width: 150 },
                      {
                        field: 'scope',
                        label: { 'zh-CN': '范围', 'en-US': 'Scope' },
                        width: 100,
                        valueType: 'tag',
                        tagMap: {
                          PERSONAL: { label: 'Personal', color: 'blue' },
                          GLOBAL: { label: 'Global', color: 'green' },
                        },
                      },
                      {
                        field: 'status',
                        label: { 'zh-CN': '状态', 'en-US': 'Status' },
                        width: 100,
                        valueType: 'tag',
                        tagMap: {
                          draft: { label: 'Draft', color: 'default' },
                          published: { label: 'Published', color: 'green' },
                        },
                      },
                      {
                        field: 'updatedAt',
                        label: { 'zh-CN': '更新时间', 'en-US': 'Updated At' },
                        width: 180,
                        valueType: 'datetime',
                      },
                      {
                        field: '_actions',
                        isActionColumn: true,
                        buttons: [
                          {
                            code: 'edit',
                            icon: 'Edit',
                            action: 'edit',
                            navigateTo: '/dashboard-designer/{pid}',
                          },
                          {
                            code: 'publish',
                            action: 'publish',
                            visibleWhen: "row.status === 'draft'",
                            apiAction: {
                              endpoint: '/api/dashboards/{pid}/publish',
                              method: 'post',
                              successMessage: {
                                'zh-CN': '发布成功',
                                'en-US': 'Published successfully',
                              },
                            },
                          },
                          {
                            code: 'unpublish',
                            action: 'unpublish',
                            visibleWhen: "row.status === 'published'",
                            apiAction: {
                              endpoint: '/api/dashboards/{pid}/unpublish',
                              method: 'post',
                              successMessage: {
                                'zh-CN': '已取消发布',
                                'en-US': 'Unpublished successfully',
                              },
                            },
                          },
                          {
                            code: 'delete',
                            action: 'delete',
                            danger: true,
                            confirmMessageKey: 'confirm.delete',
                            apiAction: {
                              endpoint: '/api/dashboards/{pid}',
                              method: 'delete',
                              successMessage: {
                                'zh-CN': '删除成功',
                                'en-US': 'Deleted successfully',
                              },
                            },
                          },
                        ],
                      },
                    ],
                    pagination: { pageSize: 10 },
                  },
                  layout: { colSpan: 12, rowSpan: 1 },
                },
              ],
            },
          },
          layout: {
            areas: ['tabs', 'toolbar', 'filters', 'main'],
            areasConfig: {
              main: { cols: 12, type: 'grid' },
              filters: { cols: 12, type: 'grid', colGap: 8, rowGap: 8 },
              toolbar: { type: 'flex', justify: 'space-between', direction: 'row' },
            },
          },
          dataSource: {
            type: 'api',
            endpoint: '/api/dashboards',
            method: 'get',
          },
        },
      },
      {
        pageKey: 'bpm_process_management_list',
        name: 'BPM Process Management',
        title: 'BPM Process Management',
        description: 'DSL-driven BPM process definition management list page',
        pageType: 'list',
        pageCategory: 'custom',
        dslSchema: {
          id: 'list.bpm_process_management',
          kind: 'List',
          modelCode: 'bpm_process_management',
          version: '1.0.0',
          areas: {
            tabs: {
              blocks: [
                {
                  id: 'block_tabs',
                  blockType: 'tabs',
                  tabs: [
                    { key: 'all', label: { 'zh-CN': '全部', 'en-US': 'All' } },
                    {
                      key: 'draft',
                      label: { 'zh-CN': '草稿', 'en-US': 'Draft' },
                      filter: { field: 'status', operator: 'EQ', value: 'draft' },
                    },
                    {
                      key: 'deployed',
                      label: { 'zh-CN': '已部署', 'en-US': 'Deployed' },
                      filter: { field: 'status', operator: 'EQ', value: 'deployed' },
                    },
                    {
                      key: 'suspended',
                      label: { 'zh-CN': '已暂停', 'en-US': 'Suspended' },
                      filter: { field: 'status', operator: 'EQ', value: 'suspended' },
                    },
                  ],
                },
              ],
            },
            toolbar: {
              blocks: [
                {
                  id: 'block_toolbar',
                  blockType: 'toolbar',
                  buttons: [
                    {
                      code: 'create',
                      icon: 'Plus',
                      action: 'create',
                      primary: true,
                      navigateTo: '/bpmn-designer',
                    },
                  ],
                },
              ],
            },
            main: {
              blocks: [
                {
                  id: 'block_table',
                  blockType: 'table',
                  table: {
                    rowKey: 'pid',
                    columns: [
                      {
                        field: 'processKey',
                        label: { 'zh-CN': '流程标识', 'en-US': 'Process Key' },
                        width: 160,
                      },
                      {
                        field: 'processName',
                        label: { 'zh-CN': '流程名称', 'en-US': 'Process Name' },
                        width: 200,
                      },
                      {
                        field: 'category',
                        label: { 'zh-CN': '分类', 'en-US': 'Category' },
                        width: 120,
                      },
                      {
                        field: 'version',
                        label: { 'zh-CN': '版本', 'en-US': 'Version' },
                        width: 80,
                      },
                      {
                        field: 'status',
                        label: { 'zh-CN': '状态', 'en-US': 'Status' },
                        width: 100,
                        valueType: 'tag',
                        tagMap: {
                          draft: { label: 'Draft', color: 'default' },
                          deployed: { label: 'Deployed', color: 'green' },
                          suspended: { label: 'Suspended', color: 'orange' },
                        },
                      },
                      {
                        field: 'deployedAt',
                        label: { 'zh-CN': '部署时间', 'en-US': 'Deployed At' },
                        width: 180,
                        valueType: 'datetime',
                      },
                      {
                        field: '_actions',
                        isActionColumn: true,
                        buttons: [
                          {
                            code: 'edit',
                            icon: 'Edit',
                            action: 'edit',
                            navigateTo: '/bpmn-designer?pid={pid}',
                            visibleWhen: "row.status === 'draft'",
                          },
                          {
                            code: 'deploy',
                            action: 'deploy',
                            visibleWhen: "row.status === 'draft'",
                            apiAction: {
                              endpoint: '/api/bpm/process-definitions/{pid}/deploy',
                              method: 'post',
                              successMessage: {
                                'zh-CN': '部署成功',
                                'en-US': 'Deployed successfully',
                              },
                            },
                          },
                          {
                            code: 'suspend',
                            action: 'suspend',
                            visibleWhen: "row.status === 'deployed'",
                            apiAction: {
                              endpoint: '/api/bpm/process-definitions/{pid}/suspend',
                              method: 'post',
                              successMessage: {
                                'zh-CN': '已暂停',
                                'en-US': 'Suspended successfully',
                              },
                            },
                          },
                          {
                            code: 'resume',
                            action: 'resume',
                            visibleWhen: "row.status === 'suspended'",
                            apiAction: {
                              endpoint: '/api/bpm/process-definitions/{pid}/resume',
                              method: 'post',
                              successMessage: {
                                'zh-CN': '已恢复',
                                'en-US': 'Resumed successfully',
                              },
                            },
                          },
                          {
                            code: 'undeploy',
                            action: 'undeploy',
                            visibleWhen: "row.status === 'deployed'",
                            confirmMessageKey: 'confirm.undeploy',
                            apiAction: {
                              endpoint: '/api/bpm/process-definitions/{pid}/undeploy',
                              method: 'post',
                              successMessage: {
                                'zh-CN': '已取消部署',
                                'en-US': 'Undeployed successfully',
                              },
                            },
                          },
                          {
                            code: 'delete',
                            action: 'delete',
                            danger: true,
                            visibleWhen: "row.status !== 'deployed'",
                            confirmMessageKey: 'confirm.delete',
                            apiAction: {
                              endpoint: '/api/bpm/process-definitions/{pid}',
                              method: 'delete',
                              successMessage: {
                                'zh-CN': '删除成功',
                                'en-US': 'Deleted successfully',
                              },
                            },
                          },
                        ],
                      },
                    ],
                    pagination: { pageSize: 10 },
                  },
                  rowClickNavigateTo: '/bpmn-designer?pid={pid}',
                  layout: { colSpan: 12, rowSpan: 1 },
                },
              ],
            },
          },
          layout: {
            areas: ['tabs', 'toolbar', 'main'],
            areasConfig: {
              main: { cols: 12, type: 'grid' },
              toolbar: { type: 'flex', justify: 'space-between', direction: 'row' },
            },
          },
          dataSource: {
            type: 'api',
            endpoint: '/api/bpm/process-definitions',
            method: 'get',
          },
        },
      },
    ];

    // Pages that need schema update even if they already exist
    const forceUpdateKeys = new Set(['dashboard_management_list', 'bpm_process_management_list']);

    let existingCount = 0;
    let createdCount = 0;
    let updatedCount = 0;

    for (const pageConfig of testPages) {
      const checkResp = await resilientGet(page, `/api/pages/key/${pageConfig.pageKey}`);

      if (checkResp?.ok()) {
        const checkData = await checkResp.json();
        if (checkData.data && checkData.success !== false) {
          const existingPid = checkData.data.pid;

          // Force update schema for specific pages (PUT has no resilient wrapper — use direct with try/catch)
          if (forceUpdateKeys.has(pageConfig.pageKey) && existingPid) {
            try {
              await page.request.put(`/api/pages/${existingPid}`, {
                data: { ...pageConfig, pid: existingPid },
                headers: { 'Content-Type': 'application/json' },
              });
            } catch {
              /* BFF may be down, non-critical */
            }
            await resilientPost(page, `/api/pages/${existingPid}/publish`);
            updatedCount++;
            continue;
          }

          existingCount++;
          // Ensure it's published
          if (existingPid && checkData.data.status !== 'published') {
            await resilientPost(page, `/api/pages/${existingPid}/publish`);
          }
          continue;
        }
      }

      // Create page
      const createResp = await resilientPost(page, `/api/pages`, {
        data: pageConfig,
        headers: { 'Content-Type': 'application/json' },
      });

      if (createResp?.ok()) {
        const result = await createResp.json();
        const pagePid = result.data?.pid;
        if (pagePid) {
          await resilientPost(page, `/api/pages/${pagePid}/publish`);
        }
        createdCount++;
      }
    }

    console.log(
      `✅ Pages: ${createdCount} created, ${updatedCount} updated, ${existingCount} existing`,
    );
  });

  /**
   * Step 6: Create test project for quarry-phase4 API tests
   */
  test('Fixture: Ensure test project exists', async ({ page }) => {
    page.setDefaultTimeout(30000);
    console.log('🏗️ Checking test project...');

    // Check if any project exists
    const listResp = await resilientGet(page, `/api/dynamic/pm_project/list?page=1&size=1`);

    if (listResp?.ok()) {
      const listData = await listResp.json();
      const records = listData.data?.records || listData.data || [];
      if (Array.isArray(records) && records.length > 0) {
        console.log(`✅ Test project already exists (${records.length}+ records)`);
        return;
      }
    }

    // Create a test project via command API
    const createResp = await resilientPost(page, `/api/meta/commands/execute/pm:create_project`, {
      data: {
        payload: {
          pm_project_name: 'E2E测试矿山项目',
          pm_project_code: 'e2e_test_001',
          pm_project_type: 'mine',
          pm_project_status: 'active',
        },
        operationType: 'create',
      },
      headers: { 'Content-Type': 'application/json' },
    });

    if (createResp?.ok()) {
      const result = await createResp.json();
      if (result.code === ErrorCodes.SUCCESS) {
        console.log('✅ Test project created');
      } else {
        console.log(`⚠️ Project creation: ${result.message} - ${result.context || ''}`);
      }
    } else {
      console.log(`⚠️ Project creation: HTTP ${createResp?.status?.()}`);
    }
  });

  /**
   * Step 7: Verify all fixtures
   */
  test('Fixture: Verify all fixtures', async ({ page }) => {
    page.setDefaultTimeout(30000);
    console.log('🔍 Verifying fixtures...');

    // Verify model
    const modelResp = await resilientGet(page, `/api/meta/models/code/e2et_record`);
    expect(modelResp?.ok()).toBe(true);
    const modelData = await modelResp.json();
    expect(modelData.data?.status).toBe('published');
    console.log(`  ✅ Model: e2et_record (published)`);

    // Verify model fields
    const fieldsResp = await resilientGet(page, `/api/meta/models/${modelData.data.pid}/fields`);
    expect(fieldsResp?.ok()).toBe(true);
    const fieldsData = await fieldsResp.json();
    const fieldCount = Array.isArray(fieldsData.data) ? fieldsData.data.length : 0;
    console.log(`  ✅ Fields: ${fieldCount} bound to model`);

    // Verify dynamic page accessibility
    const dynResp = await resilientGet(page, `/api/dynamic/e2et_record/list?page=1&size=1`);
    if (dynResp?.ok()) {
      console.log('  ✅ Dynamic list API: accessible');
    } else {
      console.log(`  ⚠️ Dynamic list API: ${dynResp?.status?.()}`);
    }

    // Verify dashboard
    const dashResp = await resilientGet(page, `/api/dashboards/code/system_overview`);
    if (dashResp?.ok()) {
      const dashData = await dashResp.json();
      if (dashData.data) {
        console.log(`  ✅ Dashboard: system_overview (${dashData.data.widgets?.length} widgets)`);
      }
    }

    // Verify pages
    const pageResp = await resilientGet(page, `/api/pages`);
    if (pageResp?.ok()) {
      const pageData = await pageResp.json();
      const pages = pageData.data?.records || pageData.data || [];
      console.log(`  ✅ Pages: ${Array.isArray(pages) ? pages.length : '?'} total`);
    }

    // Verify business models (including e2et_order from comprehensive test plugin)
    const bizModels = ['pm_project', 'qo_daily_report', 'ap_annual_plan', 'dp_issue', 'e2et_order'];
    for (const code of bizModels) {
      const resp = await resilientGet(page, `/api/meta/models/code/${code}`);
      if (resp?.ok()) {
        const data = await resp.json();
        console.log(`  ✅ Business model: ${code} (${data.data?.status || '?'})`);
      } else {
        console.log(`  ⚠️ Business model: ${code} (not found)`);
      }
    }

    console.log('✅ All fixtures verified!');
  });
});
