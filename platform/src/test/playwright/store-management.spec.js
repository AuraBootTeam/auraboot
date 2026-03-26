const { test, expect } = require('@playwright/test');

/**
 * 门店管理功能的端到端测试
 */
test.describe('门店管理', () => {
  let page;
  
  // 测试数据
  const testStore = {
    name: '测试门店001',
    code: 'TEST001',
    type: 'FLAGSHIP',
    status: 'ACTIVE',
    province: '广东省',
    city: '深圳市',
    district: '南山区',
    street: '科技园南区',
    detailAddress: '深圳湾科技生态园10栋A座',
    postalCode: '518000',
    contactPhone: '13800138000',
    email: 'test@example.com',
    manager: '张三',
    businessHours: '09:00-21:00',
    description: '这是一个测试门店'
  };
  
  const updatedStore = {
    name: '更新测试门店001',
    description: '这是一个更新后的测试门店'
  };

  test.beforeEach(async ({ browser }) => {
    page = await browser.newPage();
    
    // 设置视口大小
    await page.setViewportSize({ width: 1280, height: 720 });
    
    // 模拟登录（根据实际登录流程调整）
    await page.goto('/login');
    await page.fill('[data-testid="username"]', 'admin');
    await page.fill('[data-testid="password"]', 'admin123');
    await page.click('[data-testid="login-button"]');
    
    // 等待登录成功并跳转到主页
    await page.waitForURL('/dashboard');
    
    // 导航到门店管理页面
    await page.goto('/store-management');
    await page.waitForLoadState('networkidle');
  });

  test.afterEach(async () => {
    await page.close();
  });

  test('应该显示门店管理页面', async () => {
    // 验证页面标题
    await expect(page.locator('h1')).toContainText('门店管理');
    
    // 验证主要功能按钮存在
    await expect(page.locator('[data-testid="add-store-button"]')).toBeVisible();
    await expect(page.locator('[data-testid="search-input"]')).toBeVisible();
    await expect(page.locator('[data-testid="search-button"]')).toBeVisible();
    
    // 验证门店列表表格存在
    await expect(page.locator('[data-testid="store-table"]')).toBeVisible();
  });

  test('应该能够创建新门店', async () => {
    // 点击新增门店按钮
    await page.click('[data-testid="add-store-button"]');
    
    // 等待弹窗出现
    await expect(page.locator('[data-testid="store-modal"]')).toBeVisible();
    await expect(page.locator('[data-testid="modal-title"]')).toContainText('新增门店');
    
    // 填写基本信息
    await page.fill('[data-testid="store-name"]', testStore.name);
    await page.fill('[data-testid="store-code"]', testStore.code);
    await page.selectOption('[data-testid="store-type"]', testStore.type);
    await page.selectOption('[data-testid="store-status"]', testStore.status);
    
    // 填写地址信息
    await page.fill('[data-testid="province"]', testStore.province);
    await page.fill('[data-testid="city"]', testStore.city);
    await page.fill('[data-testid="district"]', testStore.district);
    await page.fill('[data-testid="street"]', testStore.street);
    await page.fill('[data-testid="detail-address"]', testStore.detailAddress);
    await page.fill('[data-testid="postal-code"]', testStore.postalCode);
    
    // 填写扩展信息
    await page.fill('[data-testid="contact-phone"]', testStore.contactPhone);
    await page.fill('[data-testid="email"]', testStore.email);
    await page.fill('[data-testid="manager"]', testStore.manager);
    await page.fill('[data-testid="business-hours"]', testStore.businessHours);
    await page.fill('[data-testid="description"]', testStore.description);
    
    // 提交表单
    await page.click('[data-testid="submit-button"]');
    
    // 等待成功提示
    await expect(page.locator('[data-testid="success-message"]')).toContainText('门店创建成功');
    
    // 验证弹窗关闭
    await expect(page.locator('[data-testid="store-modal"]')).not.toBeVisible();
    
    // 验证新门店出现在列表中
    await page.waitForTimeout(1000); // 等待列表刷新
    await expect(page.locator(`[data-testid="store-row-${testStore.code}"]`)).toBeVisible();
    await expect(page.locator(`[data-testid="store-name-${testStore.code}"]`)).toContainText(testStore.name);
  });

  test('应该验证必填字段', async () => {
    // 点击新增门店按钮
    await page.click('[data-testid="add-store-button"]');
    
    // 等待弹窗出现
    await expect(page.locator('[data-testid="store-modal"]')).toBeVisible();
    
    // 直接点击提交按钮，不填写任何信息
    await page.click('[data-testid="submit-button"]');
    
    // 验证必填字段的错误提示
    await expect(page.locator('[data-testid="store-name-error"]')).toContainText('门店名称不能为空');
    await expect(page.locator('[data-testid="store-code-error"]')).toContainText('门店编码不能为空');
    await expect(page.locator('[data-testid="province-error"]')).toContainText('省份不能为空');
    await expect(page.locator('[data-testid="city-error"]')).toContainText('城市不能为空');
  });

  test('应该验证门店编码唯一性', async () => {
    // 先创建一个门店
    await page.click('[data-testid="add-store-button"]');
    await page.fill('[data-testid="store-name"]', testStore.name);
    await page.fill('[data-testid="store-code"]', testStore.code);
    await page.fill('[data-testid="province"]', testStore.province);
    await page.fill('[data-testid="city"]', testStore.city);
    await page.click('[data-testid="submit-button"]');
    await expect(page.locator('[data-testid="success-message"]')).toContainText('门店创建成功');
    
    // 尝试创建相同编码的门店
    await page.click('[data-testid="add-store-button"]');
    await page.fill('[data-testid="store-name"]', '另一个测试门店');
    await page.fill('[data-testid="store-code"]', testStore.code); // 使用相同编码
    await page.fill('[data-testid="province"]', testStore.province);
    await page.fill('[data-testid="city"]', testStore.city);
    await page.click('[data-testid="submit-button"]');
    
    // 验证错误提示
    await expect(page.locator('[data-testid="error-message"]')).toContainText('门店编码已存在');
  });

  test('应该能够搜索门店', async () => {
    // 先创建一个门店用于搜索
    await page.click('[data-testid="add-store-button"]');
    await page.fill('[data-testid="store-name"]', testStore.name);
    await page.fill('[data-testid="store-code"]', testStore.code);
    await page.fill('[data-testid="province"]', testStore.province);
    await page.fill('[data-testid="city"]', testStore.city);
    await page.click('[data-testid="submit-button"]');
    await expect(page.locator('[data-testid="success-message"]')).toContainText('门店创建成功');
    
    // 搜索门店
    await page.fill('[data-testid="search-input"]', testStore.name);
    await page.click('[data-testid="search-button"]');
    
    // 验证搜索结果
    await page.waitForTimeout(1000);
    await expect(page.locator(`[data-testid="store-row-${testStore.code}"]`)).toBeVisible();
    
    // 清空搜索
    await page.fill('[data-testid="search-input"]', '');
    await page.click('[data-testid="search-button"]');
  });

  test('应该能够查看门店详情', async () => {
    // 先创建一个门店
    await page.click('[data-testid="add-store-button"]');
    await page.fill('[data-testid="store-name"]', testStore.name);
    await page.fill('[data-testid="store-code"]', testStore.code);
    await page.fill('[data-testid="province"]', testStore.province);
    await page.fill('[data-testid="city"]', testStore.city);
    await page.fill('[data-testid="description"]', testStore.description);
    await page.click('[data-testid="submit-button"]');
    await expect(page.locator('[data-testid="success-message"]')).toContainText('门店创建成功');
    
    // 点击查看详情
    await page.click(`[data-testid="view-store-${testStore.code}"]`);
    
    // 验证详情弹窗
    await expect(page.locator('[data-testid="store-detail-modal"]')).toBeVisible();
    await expect(page.locator('[data-testid="detail-store-name"]')).toContainText(testStore.name);
    await expect(page.locator('[data-testid="detail-store-code"]')).toContainText(testStore.code);
    await expect(page.locator('[data-testid="detail-description"]')).toContainText(testStore.description);
    
    // 关闭详情弹窗
    await page.click('[data-testid="close-detail-button"]');
    await expect(page.locator('[data-testid="store-detail-modal"]')).not.toBeVisible();
  });

  test('应该能够编辑门店', async () => {
    // 先创建一个门店
    await page.click('[data-testid="add-store-button"]');
    await page.fill('[data-testid="store-name"]', testStore.name);
    await page.fill('[data-testid="store-code"]', testStore.code);
    await page.fill('[data-testid="province"]', testStore.province);
    await page.fill('[data-testid="city"]', testStore.city);
    await page.fill('[data-testid="description"]', testStore.description);
    await page.click('[data-testid="submit-button"]');
    await expect(page.locator('[data-testid="success-message"]')).toContainText('门店创建成功');
    
    // 点击编辑按钮
    await page.click(`[data-testid="edit-store-${testStore.code}"]`);
    
    // 验证编辑弹窗
    await expect(page.locator('[data-testid="store-modal"]')).toBeVisible();
    await expect(page.locator('[data-testid="modal-title"]')).toContainText('编辑门店');
    
    // 验证表单已填充原有数据
    await expect(page.locator('[data-testid="store-name"]')).toHaveValue(testStore.name);
    await expect(page.locator('[data-testid="store-code"]')).toHaveValue(testStore.code);
    
    // 修改门店信息
    await page.fill('[data-testid="store-name"]', updatedStore.name);
    await page.fill('[data-testid="description"]', updatedStore.description);
    
    // 提交修改
    await page.click('[data-testid="submit-button"]');
    
    // 验证成功提示
    await expect(page.locator('[data-testid="success-message"]')).toContainText('门店更新成功');
    
    // 验证列表中的数据已更新
    await page.waitForTimeout(1000);
    await expect(page.locator(`[data-testid="store-name-${testStore.code}"]`)).toContainText(updatedStore.name);
  });

  test('应该能够删除门店', async () => {
    // 先创建一个门店
    await page.click('[data-testid="add-store-button"]');
    await page.fill('[data-testid="store-name"]', testStore.name);
    await page.fill('[data-testid="store-code"]', testStore.code);
    await page.fill('[data-testid="province"]', testStore.province);
    await page.fill('[data-testid="city"]', testStore.city);
    await page.click('[data-testid="submit-button"]');
    await expect(page.locator('[data-testid="success-message"]')).toContainText('门店创建成功');
    
    // 点击删除按钮
    await page.click(`[data-testid="delete-store-${testStore.code}"]`);
    
    // 验证确认删除弹窗
    await expect(page.locator('[data-testid="confirm-delete-modal"]')).toBeVisible();
    await expect(page.locator('[data-testid="confirm-message"]')).toContainText('确定要删除这个门店吗？');
    
    // 确认删除
    await page.click('[data-testid="confirm-delete-button"]');
    
    // 验证成功提示
    await expect(page.locator('[data-testid="success-message"]')).toContainText('门店删除成功');
    
    // 验证门店从列表中消失
    await page.waitForTimeout(1000);
    await expect(page.locator(`[data-testid="store-row-${testStore.code}"]`)).not.toBeVisible();
  });

  test('应该能够批量删除门店', async () => {
    // 创建多个门店
    const stores = [
      { ...testStore, name: '批量测试门店1', code: 'BATCH001' },
      { ...testStore, name: '批量测试门店2', code: 'BATCH002' }
    ];
    
    for (const store of stores) {
      await page.click('[data-testid="add-store-button"]');
      await page.fill('[data-testid="store-name"]', store.name);
      await page.fill('[data-testid="store-code"]', store.code);
      await page.fill('[data-testid="province"]', store.province);
      await page.fill('[data-testid="city"]', store.city);
      await page.click('[data-testid="submit-button"]');
      await expect(page.locator('[data-testid="success-message"]')).toContainText('门店创建成功');
    }
    
    // 选择多个门店
    await page.check(`[data-testid="checkbox-${stores[0].code}"]`);
    await page.check(`[data-testid="checkbox-${stores[1].code}"]`);
    
    // 点击批量删除按钮
    await page.click('[data-testid="batch-delete-button"]');
    
    // 验证确认删除弹窗
    await expect(page.locator('[data-testid="confirm-delete-modal"]')).toBeVisible();
    await expect(page.locator('[data-testid="confirm-message"]')).toContainText('确定要删除选中的2个门店吗？');
    
    // 确认删除
    await page.click('[data-testid="confirm-delete-button"]');
    
    // 验证成功提示
    await expect(page.locator('[data-testid="success-message"]')).toContainText('批量删除成功');
    
    // 验证门店从列表中消失
    await page.waitForTimeout(1000);
    for (const store of stores) {
      await expect(page.locator(`[data-testid="store-row-${store.code}"]`)).not.toBeVisible();
    }
  });

  test('应该能够导出门店数据', async () => {
    // 先创建一个门店
    await page.click('[data-testid="add-store-button"]');
    await page.fill('[data-testid="store-name"]', testStore.name);
    await page.fill('[data-testid="store-code"]', testStore.code);
    await page.fill('[data-testid="province"]', testStore.province);
    await page.fill('[data-testid="city"]', testStore.city);
    await page.click('[data-testid="submit-button"]');
    await expect(page.locator('[data-testid="success-message"]')).toContainText('门店创建成功');
    
    // 点击导出按钮
    const downloadPromise = page.waitForEvent('download');
    await page.click('[data-testid="export-button"]');
    const download = await downloadPromise;
    
    // 验证下载文件
    expect(download.suggestedFilename()).toMatch(/stores.*\.xlsx$/);
  });

  test('应该能够按状态筛选门店', async () => {
    // 创建不同状态的门店
    const activeStore = { ...testStore, name: '活跃门店', code: 'ACTIVE001', status: 'ACTIVE' };
    const inactiveStore = { ...testStore, name: '非活跃门店', code: 'INACTIVE001', status: 'INACTIVE' };
    
    // 创建活跃门店
    await page.click('[data-testid="add-store-button"]');
    await page.fill('[data-testid="store-name"]', activeStore.name);
    await page.fill('[data-testid="store-code"]', activeStore.code);
    await page.selectOption('[data-testid="store-status"]', activeStore.status);
    await page.fill('[data-testid="province"]', activeStore.province);
    await page.fill('[data-testid="city"]', activeStore.city);
    await page.click('[data-testid="submit-button"]');
    await expect(page.locator('[data-testid="success-message"]')).toContainText('门店创建成功');
    
    // 创建非活跃门店
    await page.click('[data-testid="add-store-button"]');
    await page.fill('[data-testid="store-name"]', inactiveStore.name);
    await page.fill('[data-testid="store-code"]', inactiveStore.code);
    await page.selectOption('[data-testid="store-status"]', inactiveStore.status);
    await page.fill('[data-testid="province"]', inactiveStore.province);
    await page.fill('[data-testid="city"]', inactiveStore.city);
    await page.click('[data-testid="submit-button"]');
    await expect(page.locator('[data-testid="success-message"]')).toContainText('门店创建成功');
    
    // 筛选活跃门店
    await page.selectOption('[data-testid="status-filter"]', 'ACTIVE');
    await page.click('[data-testid="filter-button"]');
    
    // 验证只显示活跃门店
    await page.waitForTimeout(1000);
    await expect(page.locator(`[data-testid="store-row-${activeStore.code}"]`)).toBeVisible();
    await expect(page.locator(`[data-testid="store-row-${inactiveStore.code}"]`)).not.toBeVisible();
    
    // 筛选非活跃门店
    await page.selectOption('[data-testid="status-filter"]', 'INACTIVE');
    await page.click('[data-testid="filter-button"]');
    
    // 验证只显示非活跃门店
    await page.waitForTimeout(1000);
    await expect(page.locator(`[data-testid="store-row-${activeStore.code}"]`)).not.toBeVisible();
    await expect(page.locator(`[data-testid="store-row-${inactiveStore.code}"]`)).toBeVisible();
    
    // 清除筛选
    await page.selectOption('[data-testid="status-filter"]', '');
    await page.click('[data-testid="filter-button"]');
    
    // 验证显示所有门店
    await page.waitForTimeout(1000);
    await expect(page.locator(`[data-testid="store-row-${activeStore.code}"]`)).toBeVisible();
    await expect(page.locator(`[data-testid="store-row-${inactiveStore.code}"]`)).toBeVisible();
  });
});