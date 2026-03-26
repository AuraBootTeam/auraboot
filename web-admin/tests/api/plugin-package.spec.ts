/**
 * Unified Plugin Package E2E Tests
 *
 * Tests unified plugin package functionality including:
 * - PKG-001: Config-only package upload and installation
 * - PKG-002: Full package (config + backend + frontend) installation
 * - PKG-003: Component status verification
 * - PKG-004: Uninstallation and cleanup verification
 * - PKG-005: Installation rollback
 *
 * @since 4.0.0
 */

import { test, expect } from '../fixtures';
import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import AdmZip from 'adm-zip';

// Force serial execution - plugin install/uninstall tests share database state
test.describe.configure({ mode: 'serial' });


// Test plugin manifest for config-only package
const CONFIG_ONLY_PLUGIN = {
  pluginId: 'com.test.pkg-config-only',
  namespace: 'pkgtest',
  version: '1.0.0',
  displayName: 'Package Config Test',
  'displayName:zh-CN': '包配置测试',
  description: 'Config-only package for testing unified package system',
  author: 'Test Team',
  minPlatformVersion: '1.0.0',

  components: {
    config: {
      enabled: true,
      path: 'config/',
    },
    backend: {
      enabled: false,
    },
    frontend: {
      enabled: false,
    },
  },

  dicts: [
    {
      code: 'pkgtest_status',
      name: 'Package Status',
      'name:zh-CN': '包状态',
      dictType: 'static',
      items: [
        { value: 'active', label: 'Active', 'label:zh-CN': '活跃', sortNo: 10, status: 'enabled' },
        { value: 'inactive', label: 'Inactive', 'label:zh-CN': '非活跃', sortNo: 20, status: 'enabled' },
      ],
    },
  ],

  fields: [
    {
      code: 'pkgtest_name',
      displayName: 'Package Name',
      'displayName:zh-CN': '包名称',
      dataType: 'string',
      constraints: { required: true, maxLength: 100 },
    },
    {
      code: 'pkgtest_status',
      displayName: 'Package Status',
      'displayName:zh-CN': '包状态',
      dataType: 'enum',
      dictCode: 'pkgtest_status',
    },
  ],

  models: [
    {
      code: 'pkgtest_package',
      displayName: 'Test Package',
      'displayName:zh-CN': '测试包',
      description: 'Test package model',
      modelType: 'entity',
    },
  ],

  modelFieldBindings: [
    { modelCode: 'pkgtest_package', fieldCode: 'pkgtest_name', sequence: 10, required: true },
    { modelCode: 'pkgtest_package', fieldCode: 'pkgtest_status', sequence: 20, required: false },
  ],

  permissions: [
    {
      code: 'pkgtest:package:read',
      name: 'View Packages',
      'name:zh-CN': '查看包',
      resourceType: 'model',
      resourceCode: 'pkgtest_package',
      action: 'read',
    },
  ],

  menus: [
    {
      code: 'pkgtest_root',
      name: 'Package Test',
      'name:zh-CN': '包测试',
      path: '/pkgtest',
      icon: 'Package',
      type: 1,
      orderNo: 910,
      visible: true,
    },
  ],
};

// Expected counts for config-only package
const CONFIG_ONLY_EXPECTED = {
  dict: 1,
  field: 2,
  model: 1,
  binding: 2,
  permission: 1,
  menu: 1,
};

/**
 * Package API Response Types
 */
interface PackageParseResult {
  packageId: string;
  success: boolean;
  error?: string;
  validationErrors?: string[];
  manifest?: any;
  extractedPath?: string;
  detectedComponents?: {
    hasConfig: boolean;
    configPath?: string;
    configResourceCounts?: Record<string, number>;
    hasBackend: boolean;
    backendJarPath?: string;
    hasFrontend: boolean;
    frontendPath?: string;
  };
  conflicts?: any[];
}

interface PackageInstallResult {
  packageId: string;
  success: boolean;
  error?: string;
  pluginPid?: string;
  pluginId?: string;
  version?: string;
  configResult?: {
    status: string;
    error?: string;
    resourceCounts?: Record<string, number>;
  };
  backendResult?: {
    status: string;
    error?: string;
    backendPluginId?: string;
  };
  frontendResult?: {
    status: string;
    error?: string;
    frontendRemoteUrl?: string;
  };
  canRollback?: boolean;
}

interface PackageStatusDTO {
  pluginPid: string;
  pluginId: string;
  namespace: string;
  version: string;
  status: string;
  hasConfig: boolean;
  hasBackend: boolean;
  hasFrontend: boolean;
  backendStatus?: string;
  backendPluginId?: string;
  frontendStatus?: string;
  frontendRemoteUrl?: string;
}

/**
 * Create a test ZIP package from manifest
 */
function createTestPackageZip(manifest: any): Buffer {
  const zip = new AdmZip();
  zip.addFile('plugin.json', Buffer.from(JSON.stringify(manifest, null, 2)));
  return zip.toBuffer();
}

/**
 * Create a temporary test package file
 */
function createTempPackageFile(manifest: any): string {
  const tempDir = os.tmpdir();
  const fileName = `test-package-${Date.now()}.zip`;
  const filePath = path.join(tempDir, fileName);
  const zipBuffer = createTestPackageZip(manifest);
  fs.writeFileSync(filePath, zipBuffer);
  return filePath;
}

test.describe('Unified Plugin Packages', () => {
  test.describe.configure({ mode: 'serial', timeout: 30000 });

  let installResult: PackageInstallResult | null = null;

  /**
   * PKG-001: Upload and parse config-only package
   */
  test('PKG-001: Upload and parse config-only package', async ({ page }) => {
    // Create package file
    const packagePath = createTempPackageFile(CONFIG_ONLY_PLUGIN);

    try {
      // Upload via multipart form
      const response = await page.request.post(`/api/plugins/packages/upload`, {
        multipart: {
          file: {
            name: 'test-config-package.zip',
            mimeType: 'application/zip',
            buffer: fs.readFileSync(packagePath),
          },
        },
      });

      expect(response.ok()).toBe(true);
      const parseResult: PackageParseResult = await response.json();

      // Verify parse success
      expect(parseResult.success).toBe(true);
      expect(parseResult.packageId).toBeTruthy();
      expect(parseResult.manifest).toBeTruthy();
      expect(parseResult.manifest.pluginId).toBe(CONFIG_ONLY_PLUGIN.pluginId);

      // Verify component detection
      expect(parseResult.detectedComponents).toBeTruthy();
      expect(parseResult.detectedComponents!.hasConfig).toBe(true);
      expect(parseResult.detectedComponents!.hasBackend).toBe(false);
      expect(parseResult.detectedComponents!.hasFrontend).toBe(false);

      // Verify resource counts
      const resourceCounts = parseResult.detectedComponents!.configResourceCounts;
      if (resourceCounts) {
        expect(resourceCounts.dicts || 0).toBe(CONFIG_ONLY_EXPECTED.dict);
        expect(resourceCounts.fields || 0).toBe(CONFIG_ONLY_EXPECTED.field);
        expect(resourceCounts.models || 0).toBe(CONFIG_ONLY_EXPECTED.model);
      }

      // Store for next test
      (test.info() as any).parseResult = parseResult;
    } finally {
      // Cleanup temp file
      fs.unlinkSync(packagePath);
    }
  });

  /**
   * PKG-002: Install config-only package
   */
  test('PKG-002: Install config-only package', async ({ page }) => {
    // Upload and install in one operation
    const packagePath = createTempPackageFile(CONFIG_ONLY_PLUGIN);

    try {
      const response = await page.request.post(`/api/plugins/packages/install`, {
        multipart: {
          file: {
            name: 'test-config-package.zip',
            mimeType: 'application/zip',
            buffer: fs.readFileSync(packagePath),
          },
          skipConfig: 'false',
          skipBackend: 'true',
          skipFrontend: 'true',
          autoEnable: 'true',
          forceOverwrite: 'true',
        },
      });

      expect(response.ok()).toBe(true);
      installResult = await response.json();

      // Verify install success
      expect(installResult).toBeTruthy();
      expect(installResult!.success).toBe(true);
      expect(installResult!.pluginPid).toBeTruthy();
      expect(installResult!.pluginId).toBe(CONFIG_ONLY_PLUGIN.pluginId);

      // Verify config result
      expect(installResult!.configResult).toBeTruthy();
      expect(installResult!.configResult!.status).toBe('success');

      // Verify backend and frontend are skipped
      expect(installResult!.backendResult?.status).toBe('skipped');
      expect(installResult!.frontendResult?.status).toBe('skipped');

      // Verify rollback is available
      expect(installResult!.canRollback).toBe(true);

      console.log(`Package installed with pluginPid: ${installResult!.pluginPid}`);
    } finally {
      fs.unlinkSync(packagePath);
    }
  });

  /**
   * PKG-003: Verify package status
   */
  test('PKG-003: Verify package status', async ({ page }) => {
    expect(installResult).toBeTruthy();
    expect(installResult!.pluginPid).toBeTruthy();

    const response = await page.request.get(
      `/api/plugins/packages/${installResult!.pluginPid}/status`
    );

    expect(response.ok()).toBe(true);
    const status: PackageStatusDTO = await response.json();

    // Verify status fields
    expect(status.pluginPid).toBe(installResult!.pluginPid);
    expect(status.pluginId).toBe(CONFIG_ONLY_PLUGIN.pluginId);
    expect(status.namespace).toBe(CONFIG_ONLY_PLUGIN.namespace);
    expect(status.version).toBe(CONFIG_ONLY_PLUGIN.version);

    // Verify component flags
    expect(status.hasConfig).toBe(true);
    expect(status.hasBackend).toBe(false);
    expect(status.hasFrontend).toBe(false);
  });

  /**
   * PKG-004: Verify imported resources
   */
  test('PKG-004: Verify imported resources', async ({ page }) => {
    // Verify dictionary
    const dictResponse = await page.request.get(
      `/api/meta/dict/by-code/pkgtest_status`
    );

    if (dictResponse.ok()) {
      const dictData = await dictResponse.json();
      const dict = dictData.data || dictData;
      expect(dict).toBeTruthy();
      expect(dict.code).toBe('pkgtest_status');
    }

    // Verify model
    const modelResponse = await page.request.get(
      `/api/meta/models/code/pkgtest_package`
    );

    expect(modelResponse.ok()).toBe(true);
    const modelData = await modelResponse.json();
    const model = modelData.data || modelData;
    expect(model).toBeTruthy();
    expect(model.code).toBe('pkgtest_package');

    // Verify fields bound to model
    const fieldsResponse = await page.request.get(
      `/api/meta/models/${model.pid}/fields`
    );

    if (fieldsResponse.ok()) {
      const fieldsData = await fieldsResponse.json();
      const fields = fieldsData.data || fieldsData;
      expect(Array.isArray(fields)).toBe(true);
      expect(fields.length).toBe(CONFIG_ONLY_EXPECTED.binding);
    }
  });

  /**
   * PKG-005: Get installation history
   */
  test('PKG-005: Get installation history', async ({ page }) => {
    const response = await page.request.get(`/api/plugins/packages/history?limit=10`);

    expect(response.ok()).toBe(true);
    const history = await response.json();

    expect(Array.isArray(history)).toBe(true);

    // Find our installation
    const ourInstall = history.find((h: any) => h.pluginId === CONFIG_ONLY_PLUGIN.pluginId);

    if (ourInstall) {
      expect(ourInstall.status).toBe('success');
      expect(ourInstall.configEnabled).toBe(true);
      expect(ourInstall.configStatus).toBe('success');
      expect(ourInstall.canRollback).toBe(true);
    }
  });

  /**
   * PKG-006: Get uninstall preview
   */
  test('PKG-006: Get uninstall preview', async ({ page }) => {
    expect(installResult).toBeTruthy();
    expect(installResult!.pluginPid).toBeTruthy();

    const response = await page.request.get(
      `/api/plugins/packages/${installResult!.pluginPid}/uninstall/preview`
    );

    expect(response.ok()).toBe(true);
    const preview = await response.json();

    // Verify preview contains resource information
    expect(preview).toBeTruthy();
    expect(preview.pluginPid).toBe(installResult!.pluginPid);

    // Should have resources to uninstall
    if (preview.resources) {
      expect(preview.resources.length).toBeGreaterThan(0);
    }
  });

  /**
   * PKG-007: Uninstall package
   */
  test('PKG-007: Uninstall package', async ({ page }) => {
    expect(installResult).toBeTruthy();
    expect(installResult!.pluginPid).toBeTruthy();

    const response = await page.request.post(
      `/api/plugins/packages/${installResult!.pluginPid}/uninstall`,
      {
        data: {
          removeAllData: true,
          removeFrontendAssets: true,
          removeBackendJar: true,
        },
      }
    );

    expect(response.ok()).toBe(true);
    const uninstallResult = await response.json();

    expect(uninstallResult.success).toBe(true);

    // Verify config component was uninstalled
    if (uninstallResult.configResult) {
      expect(uninstallResult.configResult.status).toBe('success');
    }
  });

  /**
   * PKG-008: Verify resources are removed after uninstall
   */
  test('PKG-008: Verify resources removed after uninstall', async ({ page }) => {
    // Model should be gone
    const modelResponse = await page.request.get(
      `/api/meta/models/code/pkgtest_package`
    );

    // Expect 404 or empty result
    if (modelResponse.ok()) {
      const modelData = await modelResponse.json();
      // If data is returned, it should be null or empty
      const model = modelData.data || modelData;
      // Model might still exist but be marked as deleted
      if (model && model.code === 'pkgtest_package') {
        // Check if it's actually deleted
        expect(model.deletedFlag || model.deleted).toBeTruthy();
      }
    }

    // Plugin status should not be found
    if (installResult?.pluginPid) {
      const statusResponse = await page.request.get(
        `/api/plugins/packages/${installResult.pluginPid}/status`
      );

      // Expect 404 or plugin not found
      expect(statusResponse.status()).toBeGreaterThanOrEqual(400);
    }
  });
});

/**
 * Direct JSON Import Test
 * Tests importing via JSON instead of ZIP
 */
test.describe('Direct JSON Package Import', () => {
  test.describe.configure({ timeout: 30000 });
  let installResult: PackageInstallResult | null = null;

  const JSON_PLUGIN = {
    pluginId: 'com.test.pkg-json-test',
    namespace: 'jsontest',
    version: '1.0.0',
    displayName: 'JSON Import Test',
    dicts: [
      {
        code: 'jsontest_type',
        name: 'Type',
        dictType: 'static',
        items: [{ value: 'A', label: 'Type A', sortNo: 10, status: 'enabled' }],
      },
    ],
    fields: [
      {
        code: 'jsontest_name',
        displayName: 'Name',
        dataType: 'string',
        constraints: { required: true },
      },
    ],
    models: [
      {
        code: 'jsontest_item',
        displayName: 'JSON Test Item',
        modelType: 'entity',
      },
    ],
    modelFieldBindings: [
      { modelCode: 'jsontest_item', fieldCode: 'jsontest_name', sequence: 10, required: true },
    ],
  };

  test('PKG-JSON-001: Direct import via execute-direct API', async ({ page }) => {
    // Use existing import endpoint for JSON
    const response = await page.request.post(`/api/plugins/import/execute-direct?conflictStrategy=OVERWRITE`, {
      data: JSON_PLUGIN,
      headers: {
        'Content-Type': 'application/json',
      },
    });

    expect(response.ok()).toBe(true);
    installResult = await response.json();

    expect(installResult).toBeTruthy();
    expect(installResult!.success).toBe(true);
    expect(installResult!.pluginPid).toBeTruthy();
  });

  test.afterAll(async ({ request }) => {
    if (installResult?.pluginPid) {
      try {
        await request.post(`/api/plugins/${installResult.pluginPid}/uninstall`, {
          data: { force: true, decisions: {} },
        });
      } catch (e) {
        console.warn('Cleanup failed:', e);
      }
    }
  });
});

/**
 * Package History and Rollback Tests
 */
test.describe('Package History and Rollback', () => {
  test.describe.configure({ mode: 'serial', timeout: 30000 });
  let installResult: PackageInstallResult | null = null;
  let packageId: string | null = null;

  const ROLLBACK_PLUGIN = {
    pluginId: 'com.test.pkg-rollback-test',
    namespace: 'rbtest',
    version: '1.0.0',
    displayName: 'Rollback Test',
    fields: [
      {
        code: 'rbtest_field',
        displayName: 'Rollback Field',
        dataType: 'string',
      },
    ],
    models: [
      {
        code: 'rbtest_model',
        displayName: 'Rollback Model',
        modelType: 'entity',
      },
    ],
    modelFieldBindings: [
      { modelCode: 'rbtest_model', fieldCode: 'rbtest_field', sequence: 10 },
    ],
  };

  test('PKG-RB-001: Install package for rollback test', async ({ page }) => {
    const packagePath = createTempPackageFile(ROLLBACK_PLUGIN);

    try {
      const response = await page.request.post(`/api/plugins/packages/install`, {
        multipart: {
          file: {
            name: 'rollback-test.zip',
            mimeType: 'application/zip',
            buffer: fs.readFileSync(packagePath),
          },
          forceOverwrite: 'true',
        },
      });

      expect(response.ok()).toBe(true);
      installResult = await response.json();

      expect(installResult!.success).toBe(true);
      expect(installResult!.canRollback).toBe(true);
      packageId = installResult!.packageId;
    } finally {
      try { fs.unlinkSync(packagePath); } catch {}
    }
  });

  test('PKG-RB-002: Check can-rollback status', async ({ page }) => {
    expect(packageId).toBeTruthy();

    const response = await page.request.get(
      `/api/plugins/packages/${packageId}/can-rollback`
    );

    expect(response.ok()).toBe(true);
    const result = await response.json();
    expect(result.canRollback).toBe(true);
  });

  test('PKG-RB-003: Execute rollback', async ({ page }) => {
    expect(packageId).toBeTruthy();

    const response = await page.request.post(
      `/api/plugins/packages/${packageId}/rollback`
    );

    expect(response.ok()).toBe(true);
    const rollbackResult = await response.json();

    expect(rollbackResult.success).toBe(true);
  });

  test('PKG-RB-004: Verify resources removed after rollback', async ({ page }) => {
    // Model should not exist
    const modelResponse = await page.request.get(
      `/api/meta/models/code/rbtest_model`
    );

    // Should be 404/422 (not found) or marked deleted
    if (modelResponse.ok()) {
      const data = await modelResponse.json();
      const model = data.data || data;
      if (model) {
        expect(model.deletedFlag || model.deleted).toBeTruthy();
      }
    } else {
      // Accept 404 (Not Found) or 422 (model validation/not found error)
      expect([404, 422]).toContain(modelResponse.status());
    }
  });
});
