import { expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import { ErrorCodes } from '~/shared/services/http-client/types';

/**
 * Model Management Page Object Model
 * Provides reusable methods for E2E testing
 */
export class ModelManagementPage {
  constructor(private page: Page) {}

  // ==================== Navigation ====================

  async goto() {
    await this.page.goto('/meta/models');
    await this.page.waitForLoadState('domcontentloaded');
  }

  async gotoModelDetail(pid: string) {
    await this.page.goto(`/meta/models/${pid}`);
    await this.page.waitForLoadState('domcontentloaded');
  }

  async gotoCreateModel() {
    await this.page.goto('/meta/models/new');
    await this.page.waitForLoadState('domcontentloaded');
  }

  // ==================== List Page Elements ====================

  get searchInput() {
    return this.page.locator('input[placeholder*="搜索"], input[placeholder*="模型"]').first();
  }

  get createButton() {
    return this.page.locator('button:has-text("新建模型"), a:has-text("新建模型")').first();
  }

  get modelRows() {
    return this.page.locator('tbody tr');
  }

  get loadingSpinner() {
    return this.page.locator('.animate-spin, [data-testid="loading"]');
  }

  get emptyState() {
    return this.page.locator('text=暂无数据, text=No data');
  }

  get gitFirstNotice() {
    return this.page.locator('[data-testid="git-first-notice"], text="Git-First"');
  }

  // ==================== Form Elements ====================

  get codeInput() {
    return this.page.locator('input[name="code"]');
  }

  get nameInput() {
    return this.page.locator('input[name="name"]');
  }

  get tableNameInput() {
    return this.page.locator('input[name="tableName"]');
  }

  get descriptionInput() {
    return this.page.locator('textarea[name="description"]');
  }

  get categorySelect() {
    return this.page.locator('select[name="category"]');
  }

  get submitButton() {
    return this.page.locator('button[type="submit"]:has-text("保存"), button:has-text("创建")');
  }

  get cancelButton() {
    return this.page.locator('[data-testid="dialog-cancel"], button:has-text("取消")');
  }

  // ==================== Detail Page Tabs ====================

  get basicInfoTab() {
    return this.page.locator('button:has-text("基本信息"), a:has-text("基本信息")');
  }

  get fieldManagementTab() {
    return this.page.locator('button:has-text("字段管理"), a:has-text("字段管理")');
  }

  get crudGenerationTab() {
    return this.page.locator('button:has-text("CRUD生成"), a:has-text("CRUD生成")');
  }

  get permissionPermissionTab() {
    return this.page.locator('button:has-text("权限权限"), a:has-text("权限映射")');
  }

  get versionHistoryTab() {
    return this.page.locator('button:has-text("版本历史"), a:has-text("版本历史")');
  }

  get runtimeVerificationTab() {
    return this.page.locator('button:has-text("运行时验证"), a:has-text("运行时验证")');
  }

  // ==================== Field Management Elements ====================

  get addFieldButton() {
    return this.page.locator('button:has-text("添加字段"), button:has-text("新建字段")').first();
  }

  get fieldCodeInput() {
    return this.page.locator('input[name="code"]');
  }

  get fieldNameInput() {
    return this.page.locator('input[name="name"]');
  }

  get fieldTypeSelect() {
    return this.page.locator('select[name="fieldType"]');
  }

  get fieldRequiredCheckbox() {
    return this.page.locator('input[name="required"], input[type="checkbox"]').first();
  }

  get fieldDescriptionInput() {
    return this.page.locator('textarea[name="description"], input[name="description"]');
  }

  // ==================== CRUD Generation Elements ====================

  get generateCRUDButton() {
    return this.page.locator('button:has-text("生成CRUD"), button:has-text("生成页面")').first();
  }

  get listPreview() {
    return this.page.locator('[data-testid="list-preview"]');
  }

  get formPreview() {
    return this.page.locator('[data-testid="form-preview"]');
  }

  // ==================== Runtime Verification Elements ====================

  get verifyButton() {
    return this.page.locator('button:has-text("开始验证"), button:has-text("验证")').first();
  }

  get verificationResults() {
    return this.page.locator('[data-testid="verification-results"]');
  }

  // ==================== Actions ====================

  async searchModels(keyword: string) {
    await this.searchInput.fill(keyword);
    await this.page.keyboard.press('Enter');
    await this.page.waitForLoadState('domcontentloaded');
  }

  async clickCreateModel() {
    await this.createButton.click();
    await this.page.waitForLoadState('domcontentloaded');
  }

  async createModel(data: {
    code: string;
    name: string;
    tableName: string;
    description?: string;
    category?: string;
  }) {
    await this.codeInput.fill(data.code);
    await this.nameInput.fill(data.name);
    await this.tableNameInput.fill(data.tableName);

    if (data.description) {
      await this.descriptionInput.fill(data.description);
    }

    if (data.category && (await this.categorySelect.isVisible())) {
      await this.categorySelect.selectOption(data.category);
    }

    await this.submitButton.click();
    await this.page.waitForLoadState('domcontentloaded');
  }

  async addField(data: {
    code: string;
    name: string;
    fieldType: string;
    required?: boolean;
    description?: string;
  }) {
    await this.addFieldButton.click();
    await this.page.waitForLoadState('domcontentloaded');

    await this.fieldCodeInput.fill(data.code);
    await this.fieldNameInput.fill(data.name);

    if (await this.fieldTypeSelect.isVisible()) {
      await this.fieldTypeSelect.selectOption(data.fieldType);
    }

    if (data.required && (await this.fieldRequiredCheckbox.isVisible())) {
      await this.fieldRequiredCheckbox.check();
    }

    if (data.description && (await this.fieldDescriptionInput.isVisible())) {
      await this.fieldDescriptionInput.fill(data.description);
    }

    await this.submitButton.click();
    await this.page.waitForLoadState('domcontentloaded');
  }

  async generateCRUD() {
    if (await this.generateCRUDButton.isVisible()) {
      await this.generateCRUDButton.click();

      // Wait for success message
      const successMessage = this.page.locator('text="生成成功", text="CRUD页面已生成"');
      await expect(successMessage).toBeVisible({ timeout: 10000 });
    }
  }

  async switchToTab(tabName: string) {
    const tab = this.page.locator(`button:has-text("${tabName}"), a:has-text("${tabName}")`);
    if (await tab.isVisible()) {
      await tab.click();
      await this.page.waitForLoadState('domcontentloaded');
      return true;
    }
    return false;
  }

  async verifyRuntime() {
    if (await this.verifyButton.isVisible()) {
      await this.verifyButton.click();
      await this.page.waitForLoadState('domcontentloaded');
      return true;
    }
    return false;
  }

  async getModelCount() {
    await this.waitForModelsLoaded();
    return await this.modelRows.count();
  }

  async getModelData(index: number) {
    const row = this.modelRows.nth(index);
    return {
      code: await row.locator('td').nth(1).textContent(),
      name: await row.locator('td').nth(2).textContent(),
      tableName: await row.locator('td').nth(3).textContent(),
      category: await row.locator('td').nth(4).textContent(),
      status: await row.locator('td').nth(5).textContent(),
    };
  }

  async editModel(index: number) {
    const editButton = this.modelRows
      .nth(index)
      .locator('button:has([data-testid="pencil-icon"]), a:has-text("编辑")');
    await editButton.click();
    await this.page.waitForLoadState('domcontentloaded');
  }

  async deleteModel(index: number) {
    const deleteButton = this.modelRows
      .nth(index)
      .locator('button:has([data-testid="trash-icon"]), button:has-text("删除")');
    await deleteButton.click();

    // Handle confirmation dialog
    this.page.on('dialog', async (dialog) => {
      await dialog.accept();
    });

    await this.page.waitForLoadState('domcontentloaded');
  }

  async waitForModelsLoaded() {
    await expect(this.loadingSpinner).not.toBeVisible({ timeout: 10000 });
  }

  async waitForSuccess() {
    const successMessage = this.page.locator('text="成功", text="Success"');
    await expect(successMessage).toBeVisible({ timeout: 5000 });
  }

  async waitForError() {
    const errorMessage = this.page.locator('text="错误", text="失败", text="Error"');
    await expect(errorMessage).toBeVisible({ timeout: 5000 });
  }
}

/**
 * Dynamic Page Helper
 * For testing generated CRUD pages
 */
export class DynamicPageHelper {
  constructor(private page: Page) {}

  async goto(tableName: string) {
    await this.page.goto(`/p/${tableName}`);
    await this.page.waitForLoadState('domcontentloaded');
  }

  get table() {
    return this.page.locator('table, [role="table"]');
  }

  get createButton() {
    return this.page.locator('button:has-text("新建"), button:has-text("创建")').first();
  }

  get searchInput() {
    return this.page.locator('input[placeholder*="搜索"]').first();
  }

  get rows() {
    return this.page.locator('tbody tr');
  }

  async isPageAccessible() {
    const hasTable = await this.table.isVisible().catch(() => false);
    const hasForm = await this.page
      .locator('form')
      .isVisible()
      .catch(() => false);
    return hasTable || hasForm;
  }

  async createRecord(data: Record<string, any>) {
    await this.createButton.click();
    await this.page.waitForLoadState('domcontentloaded');

    // Fill form fields dynamically
    for (const [key, value] of Object.entries(data)) {
      const input = this.page.locator(`input[name="${key}"], textarea[name="${key}"]`);
      if (await input.isVisible()) {
        await input.fill(String(value));
      }
    }

    await this.page.locator('button[type="submit"]').click();
    await this.page.waitForLoadState('domcontentloaded');
  }

  async getRecordCount() {
    return await this.rows.count();
  }
}

/**
 * Mock API Helpers
 */
export async function mockModelListAPI(page: Page, models: any[] = []) {
  await page.route('**/api/meta/models/search', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: ErrorCodes.SUCCESS,
        desc: 'success',
        data: {
          records: models,
          total: models.length,
          pageNum: 1,
          pageSize: 10,
        },
      }),
    });
  });
}

export async function mockModelCreateAPI(page: Page, success: boolean = true) {
  await page.route('**/api/meta/models', async (route) => {
    if (route.request().method().toLowerCase() === 'post') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: success ? '0' : '1',
          desc: success ? 'success' : 'create failed',
          data: success ? { id: 1, pid: 'model-001' } : null,
        }),
      });
    }
  });
}

export async function mockModelDeleteAPI(page: Page, success: boolean = true) {
  await page.route('**/api/meta/models/*', async (route) => {
    if (route.request().method().toLowerCase() === 'delete') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: success ? '0' : '1',
          desc: success ? 'success' : 'delete failed',
        }),
      });
    }
  });
}

/**
 * Test Data Generators
 */
export function generateTestModel(prefix: string = 'test') {
  const timestamp = Date.now();
  return {
    code: `${prefix}_model_${timestamp}`,
    name: `${prefix}测试模型`,
    tableName: `${prefix}_table_${timestamp}`,
    description: `${prefix} E2E测试用模型`,
    category: 'business',
  };
}

export function generateTestField(prefix: string = 'test') {
  return {
    code: `${prefix}_field`,
    name: `${prefix}测试字段`,
    fieldType: 'string',
    required: true,
    description: `${prefix}测试字段描述`,
  };
}

export const mockModels = [
  {
    id: 1,
    pid: 'model-001',
    code: 'user_model',
    name: '用户模型',
    tableName: 'ab_user',
    category: 'business',
    status: 'active',
    description: '用户信息模型',
    createdAt: '2024-01-01T00:00:00Z',
  },
  {
    id: 2,
    pid: 'model-002',
    code: 'order_model',
    name: '订单模型',
    tableName: 'ab_order',
    category: 'business',
    status: 'active',
    description: '订单信息模型',
    createdAt: '2024-01-02T00:00:00Z',
  },
  {
    id: 3,
    pid: 'model-003',
    code: 'product_model',
    name: '产品模型',
    tableName: 'ab_product',
    category: 'business',
    status: 'draft',
    description: '产品信息模型',
    createdAt: '2024-01-03T00:00:00Z',
  },
];
