import { expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import { ErrorCodes } from '~/services/http-client/types';

export class StoreListPage {
  constructor(private page: Page) {}

  // 页面元素选择器
  get searchInput() {
    return this.page.locator('input[placeholder="门店名称、编码"]');
  }

  get typeSelect() {
    return this.page.locator('select').nth(0);
  }

  get statusSelect() {
    return this.page.locator('select').nth(1);
  }

  get searchButton() {
    return this.page.locator('[data-testid="filter-search"]');
  }

  get newStoreButton() {
    return this.page.locator('a:has-text("新建门店")');
  }

  get selectAllCheckbox() {
    return this.page.locator('thead input[type="checkbox"]');
  }

  get batchDeleteButton() {
    return this.page.locator('button:has-text("批量删除")');
  }

  get storeRows() {
    return this.page.locator('tbody tr');
  }

  get loadingSpinner() {
    return this.page.locator('.animate-spin');
  }

  get emptyState() {
    return this.page.locator('text=暂无门店数据');
  }

  get toast() {
    return this.page.locator('[data-testid="toast"]');
  }

  // 页面操作方法
  async goto() {
    await this.page.goto('/enterprise/stores');
    await this.page.waitForLoadState('domcontentloaded');
  }

  async searchStores(keyword: string) {
    await this.searchInput.fill(keyword);
    await this.searchButton.click();
    await this.page.waitForLoadState('domcontentloaded');
  }

  async filterByType(type: string) {
    await this.typeSelect.selectOption(type);
    await this.searchButton.click();
    await this.page.waitForLoadState('domcontentloaded');
  }

  async filterByStatus(status: string) {
    await this.statusSelect.selectOption(status);
    await this.searchButton.click();
    await this.page.waitForLoadState('domcontentloaded');
  }

  async selectStore(index: number) {
    await this.storeRows.nth(index).locator('input[type="checkbox"]').check();
  }

  async selectAllStores() {
    await this.selectAllCheckbox.check();
  }

  async deleteStore(index: number) {
    const deleteButton = this.storeRows
      .nth(index)
      .locator('button:has([data-testid="trash-icon"])');
    await deleteButton.click();

    // 处理确认对话框
    this.page.on('dialog', async (dialog) => {
      expect(dialog.message()).toContain('确定要删除这个门店吗？');
      await dialog.accept();
    });
  }

  async editStore(index: number) {
    const editLink = this.storeRows.nth(index).locator('a:has([data-testid="pencil-icon"])');
    await editLink.click();
  }

  async batchDelete() {
    await this.batchDeleteButton.click();

    // 处理确认对话框
    this.page.on('dialog', async (dialog) => {
      expect(dialog.message()).toContain('确定要删除选中的');
      await dialog.accept();
    });
  }

  async waitForStoresLoaded() {
    await expect(this.loadingSpinner).not.toBeVisible();
  }

  async getStoreCount() {
    await this.waitForStoresLoaded();
    return await this.storeRows.count();
  }

  async getStoreData(index: number) {
    const row = this.storeRows.nth(index);
    return {
      name: await row.locator('td').nth(1).textContent(),
      code: await row.locator('td').nth(2).textContent(),
      type: await row.locator('td').nth(3).textContent(),
      address: await row.locator('td').nth(4).textContent(),
      phone: await row.locator('td').nth(5).textContent(),
      createdAt: await row.locator('td').nth(6).textContent(),
      status: await row.locator('td').nth(7).textContent(),
    };
  }
}

// Mock API响应工具
export async function mockStoreListAPI(page: Page, stores: any[] = []) {
  await page.route('**/api/stores/search', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: ErrorCodes.SUCCESS,
        desc: 'success',
        data: {
          records: stores,
          total: stores.length,
          pageNum: 1,
          pageSize: 10,
        },
      }),
    });
  });
}

export async function mockDeleteAPI(page: Page, success: boolean = true) {
  await page.route('**/api/stores/*', async (route) => {
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

export async function mockBatchDeleteAPI(page: Page, success: boolean = true) {
  await page.route('**/api/stores/batch-delete', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: success ? '0' : '1',
        desc: success ? 'batch delete success' : 'batch delete failed',
      }),
    });
  });
}

// 测试数据
export const mockStores = [
  {
    id: 1,
    pid: 'store-001',
    name: '测试门店1',
    code: 'st001',
    type: 'flagship',
    status: 'active',
    address: { fullAddress: '北京市朝阳区测试街道1号' },
    contactPhone: '13800138001',
    createdAt: '2024-01-01T00:00:00Z',
  },
  {
    id: 2,
    pid: 'store-002',
    name: '测试门店2',
    code: 'st002',
    type: 'branch',
    status: 'inactive',
    address: { fullAddress: '上海市浦东新区测试路2号' },
    contactPhone: '13800138002',
    createdAt: '2024-01-02T00:00:00Z',
  },
  {
    id: 3,
    pid: 'store-003',
    name: '测试门店3',
    code: 'st003',
    type: 'franchise',
    status: 'closed',
    address: { fullAddress: '广州市天河区测试大道3号' },
    contactPhone: '13800138003',
    createdAt: '2024-01-03T00:00:00Z',
  },
];
