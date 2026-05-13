import { test, expect, type Page, type APIRequestContext } from '../../fixtures';
import {
  clickRowActionByLocator,
  executeCommandViaApi,
  findRowInPaginatedList,
  waitForFormReady,
  dateOffsetStr,
  uniqueId,
} from '../helpers/index';
import {
  createDefaultTableView,
  restoreDefaultTableView,
  type DefaultTableViewState,
} from './helpers/default-table-view';

test.describe.configure({ mode: 'serial' });

const MODEL_CODE = 'showcase_all_fields';
const PAGE_KEY = 'showcase_all_fields';
const LIST_URL = `/p/${MODEL_CODE}`;

const ALL_FIELD_CODES = [
  'sc_name',
  'sc_code',
  'sc_description',
  'sc_quantity',
  'sc_price',
  'sc_budget',
  'sc_progress',
  'sc_rating',
  'sc_status',
  'sc_priority',
  'sc_category',
  'sc_tags',
  'sc_is_active',
  'sc_advanced_settings',
  'sc_start_date',
  'sc_end_date',
  'sc_created_at',
  'sc_time_slot',
  'sc_date_range',
  'sc_working_hours',
  'sc_cascade_category',
  'sc_tree_node',
  'sc_assignee',
  'sc_team_members',
  'sc_department',
  'sc_owner_user',
  'sc_address',
  'sc_website',
  'sc_email',
  'sc_phone',
  'sc_color',
  'sc_richtext_content',
  'sc_attachment',
  'sc_attachment_file',
  'sc_ai_summary',
  'sc_remark',
];

const FIELD_LABELS: Record<string, RegExp> = {
  sc_name: /名称|Name/i,
  sc_code: /编号|Code/i,
  sc_description: /描述|Description/i,
  sc_quantity: /数量|Quantity/i,
  sc_price: /价格|Price/i,
  sc_budget: /预算金额|Budget/i,
  sc_progress: /进度|Progress/i,
  sc_rating: /评分|Rating/i,
  sc_status: /状态|Status/i,
  sc_priority: /优先级|Priority/i,
  sc_category: /分类|Category/i,
  sc_tags: /标签|Tags/i,
  sc_is_active: /是否启用|Active/i,
  sc_advanced_settings: /高级设置|Advanced Settings/i,
  sc_start_date: /开始日期|Start Date/i,
  sc_end_date: /结束日期|End Date/i,
  sc_created_at: /创建时间|Created At/i,
  sc_time_slot: /时间点|Time Slot/i,
  sc_date_range: /日期范围|Date Range/i,
  sc_working_hours: /工作时段|Working Hours/i,
  sc_cascade_category: /级联分类|Cascade Category/i,
  sc_tree_node: /树形选择|Tree Select/i,
  sc_assignee: /负责人|Assignee/i,
  sc_team_members: /团队成员|Team Members/i,
  sc_department: /所属部门|Department/i,
  sc_owner_user: /负责人|Owner/i,
  sc_address: /地址|Address/i,
  sc_website: /网站URL|Website/i,
  sc_email: /邮箱|Email/i,
  sc_phone: /电话|Phone/i,
  sc_color: /颜色标记|Color/i,
  sc_richtext_content: /富文本内容|Rich Text Content/i,
  sc_attachment: /附件|Attachment/i,
  sc_attachment_file: /附件文件|Attachment File/i,
  sc_ai_summary: /AI 摘要|AI Summary/i,
  sc_remark: /备注|Remark/i,
};

const UID = uniqueId('all_fields_audit');
const RECORD_NAME = `All Fields Audit ${UID}`;
const RECORD_DESC = `Full form audit ${UID}`;
const UPDATED_RECORD_NAME = `${RECORD_NAME} Saved`;
const UPDATED_BUDGET = '9001.25';
const UPDATED_QUANTITY = '21';
const UPDATED_TIME_SLOT = '10:45';
const UPDATED_AI_SUMMARY = 'AI summary updated through edit form roundtrip.';
const START_DATE = dateOffsetStr(2);
const END_DATE = dateOffsetStr(6);
const DATE_RANGE_START = dateOffsetStr(8);
const DATE_RANGE_END = dateOffsetStr(12);

let recordPid = '';
let seedUserId = '';
let defaultTableView: DefaultTableViewState | null = null;

async function navigateToShowcaseListViaMenu(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' }).catch(() => {});
  await page.evaluate(() => localStorage.removeItem('sidebar-collapsed'));
  await page.reload({ waitUntil: 'domcontentloaded' });

  const parent = page
    .locator('button, [role="menuitem"]', {
      hasText: /字段展示|能力展示|Field Showcase|Showcase|menu\.sc_root/i,
    })
    .first();
  await parent.waitFor({ state: 'visible', timeout: 10_000 });
  await parent.evaluate((el: HTMLElement) => el.click());

  const listResp = page.waitForResponse(
    (r) => r.url().includes(`/api/dynamic/${MODEL_CODE}/list`) && r.status() === 200,
    { timeout: 15_000 },
  );

  const leaf = page.locator(`a[href="${LIST_URL}"], a[href*="${LIST_URL}"]`).first();
  await leaf.waitFor({ state: 'attached', timeout: 5_000 });
  await leaf.evaluate((el: HTMLElement) => el.click());
  await listResp.catch(() => null);

  await expect(page).toHaveURL(new RegExp(`${LIST_URL}(?:$|\\?)`), { timeout: 10_000 });
  await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible({
    timeout: 10_000,
  });
}

async function deleteRecord(request: APIRequestContext, pid: string): Promise<void> {
  await request
    .post('/api/meta/commands/execute/sc:delete_showcase', {
      data: { operationType: 'delete', targetRecordId: pid },
    })
    .catch(() => null);
}

async function resolveSeedUserId(page: Page): Promise<string> {
  const resp = await page.request.get('/api/auth/me');
  expect(resp.ok()).toBeTruthy();
  const body = await resp.json();
  const user = body?.data?.user ?? body?.data ?? body;
  expect(user).toBeTruthy();
  return String(user.pid ?? user.id);
}

async function seedAuditRecord(page: Page): Promise<string> {
  const createResult = await executeCommandViaApi(
    page,
    'sc:create_showcase',
    {
      sc_name: RECORD_NAME,
      sc_description: RECORD_DESC,
      sc_quantity: 1,
    },
    undefined,
    'create',
  );

  expect(createResult.code).toBe('0');
  expect(createResult.recordId).toBeTruthy();

  const updateResult = await executeCommandViaApi(
    page,
    'sc:update_showcase',
    {
      sc_name: RECORD_NAME,
      sc_description: RECORD_DESC,
      sc_quantity: 18,
      sc_price: 256.75,
      sc_budget: 8000.5,
      sc_progress: 72,
      sc_rating: 4,
      sc_status: 'active',
      sc_priority: 'high',
      sc_category: 'electronics',
      sc_is_active: true,
      sc_start_date: START_DATE,
      sc_end_date: END_DATE,
      sc_time_slot: '09:30',
      sc_date_range: JSON.stringify({ start: DATE_RANGE_START, end: DATE_RANGE_END }),
      sc_working_hours: JSON.stringify({ start: '09:00', end: '18:00' }),
      sc_cascade_category: 'electronics_phone_smart',
      sc_tree_node: 'tech_frontend',
      sc_assignee: seedUserId,
      sc_team_members: JSON.stringify([seedUserId]),
      sc_department: 'org-2',
      sc_owner_user: seedUserId,
      sc_address: JSON.stringify({
        province: '浙江省',
        city: '杭州市',
        district: '西湖区',
        detail: '文三路 90 号',
      }),
      sc_website: 'https://example.com/all-fields-audit',
      sc_email: 'audit@example.com',
      sc_phone: '13800138000',
      sc_color: '#22c55e',
      sc_richtext_content: '<p>All field audit rich text</p>',
      sc_attachment: JSON.stringify([
        {
          name: 'audit-attachment.pdf',
          url: '/files/audit-attachment.pdf',
          size: 1024,
          type: 'application/pdf',
        },
      ]),
      sc_attachment_file: JSON.stringify([
        {
          name: 'audit-upload.txt',
          url: '/files/audit-upload.txt',
          size: 12,
          type: 'text/plain',
        },
      ]),
      sc_ai_summary: 'AI summary seeded for full showcase audit.',
      sc_remark: 'Remark seeded for form audit.',
      sc_advanced_settings: 'Advanced setting visible in active status.',
    },
    createResult.recordId,
    'update',
  );

  expect(updateResult.code).toBe('0');
  return createResult.recordId;
}

async function openEditForm(page: Page, name: string): Promise<void> {
  await navigateToShowcaseListViaMenu(page);
  const row = await findRowInPaginatedList(page, name);
  await clickRowActionByLocator(page, row, 'edit', '编辑');
  await page.waitForURL(new RegExp(`/p/${MODEL_CODE}/edit/.+`), { timeout: 10_000 });
  await waitForFormReady(page, 15_000);
}

function field(code: string) {
  return `[data-testid="field-${code}"]`;
}

test.describe('showcase_all_fields form audit', () => {
  test.setTimeout(45_000);

  const adminStorageState = process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json';
  test.use({ storageState: adminStorageState });

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: adminStorageState });
    const page = await ctx.newPage();
    try {
      defaultTableView = await createDefaultTableView(
        page.request,
        MODEL_CODE,
        PAGE_KEY,
        'form audit',
      );
      seedUserId = await resolveSeedUserId(page);
      recordPid = await seedAuditRecord(page);
    } finally {
      await ctx.close();
    }
  });

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: adminStorageState });
    const page = await ctx.newPage();
    try {
      if (recordPid) {
        await deleteRecord(page.request, recordPid);
      }
      await restoreDefaultTableView(page.request, defaultTableView);
      defaultTableView = null;
    } finally {
      await ctx.close();
    }
  });

  test('renders showcase_all_fields detail view without record-load errors', async ({ page }) => {
    await page.goto(`/p/${MODEL_CODE}/view/${recordPid}`, { waitUntil: 'domcontentloaded' });

    await expect(page.locator('body')).not.toContainText(/Access forbidden|Page not found/i, {
      timeout: 5_000,
    });
    await expect(page.locator('body')).not.toContainText(/Internal system error|No static resource/i, {
      timeout: 5_000,
    });

    await expect(page.locator('body')).toContainText(RECORD_NAME, {
      timeout: 10_000,
    });
    await expect(page.locator('body')).toContainText(/名称|Name/i);
    await expect(page.locator('body')).toContainText(/预算金额|Budget/i);
    await expect(page.locator('body')).toContainText(/AI 摘要|AI Summary/i);
  });

  test('renders every showcase_all_fields form field on edit, saves updates, and cancel returns to list', async ({
    page,
  }) => {
    await openEditForm(page, RECORD_NAME);

    await expect(page.locator('body')).not.toContainText(/Access forbidden|Page not found/i, {
      timeout: 5_000,
    });

    for (const code of ALL_FIELD_CODES) {
      const wrapper = page.locator(field(code)).first();
      await wrapper.scrollIntoViewIfNeeded();
      await expect(wrapper, `field ${code} should render`).toBeVisible({ timeout: 5_000 });
      await expect(wrapper, `field ${code} should show translated label`).toContainText(
        FIELD_LABELS[code],
        { timeout: 5_000 },
      );
      await expect(wrapper, `field ${code} should not show Access forbidden`).not.toContainText(
        'Access forbidden',
      );
    }

    await expect(page.locator(`${field('sc_name')} input`).first()).toHaveValue(RECORD_NAME);
    await expect(page.locator(`${field('sc_description')} textarea`).first()).toHaveValue(
      RECORD_DESC,
    );
    await expect(page.locator(`${field('sc_quantity')} input`).first()).toHaveValue('18');
    await expect(page.locator(`${field('sc_price')} input`).first()).toHaveValue(/256\.75/);
    await expect(page.locator(`${field('sc_budget')} input`).first()).toHaveValue(
      /8,?000\.50/,
    );
    await expect(page.locator('[data-testid="date-picker-input-sc_start_date"]')).toHaveValue(
      START_DATE,
    );
    await expect(page.locator('[data-testid="date-picker-input-sc_end_date"]')).toHaveValue(
      END_DATE,
    );
    await expect(page.locator(`${field('sc_time_slot')} input[step]`).first()).toHaveValue(
      '09:30',
    );
    await expect(page.locator('[data-testid="daterange-sc_date_range-start"]')).toHaveValue(
      DATE_RANGE_START,
    );
    await expect(page.locator('[data-testid="daterange-sc_date_range-end"]')).toHaveValue(
      DATE_RANGE_END,
    );
    await expect(page.locator(field('sc_working_hours')).first()).toContainText('09:00');
    await expect(page.locator(field('sc_working_hours')).first()).toContainText('18:00');
    await expect(page.locator(`${field('sc_address')} select[aria-label="province"]`)).toHaveValue(
      '浙江省',
    );
    await expect(page.locator(`${field('sc_address')} select[aria-label="city"]`)).toHaveValue(
      '杭州市',
    );
    await expect(
      page.locator(`${field('sc_address')} textarea[aria-label="detail-address"]`),
    ).toHaveValue('文三路 90 号');
    await expect(page.locator(field('sc_attachment_file')).first()).toContainText(
      'audit-upload.txt',
    );
    await expect(page.locator(`${field('sc_ai_summary')} textarea`).first()).toHaveValue(
      'AI summary seeded for full showcase audit.',
    );
    await expect(page.locator(`${field('sc_advanced_settings')} textarea`).first()).toHaveValue(
      'Advanced setting visible in active status.',
    );

    const ownerTrigger = page.locator(`${field('sc_owner_user')} [data-testid="select-trigger-sc_owner_user"]`).first();
    await ownerTrigger.scrollIntoViewIfNeeded();
    await expect(ownerTrigger).toBeVisible({ timeout: 5_000 });
    await expect(ownerTrigger).not.toContainText(/^请选择$/);

    const nameInput = page.locator(`${field('sc_name')} input`).first();
    await nameInput.fill(UPDATED_RECORD_NAME);

    const quantityInput = page.locator(`${field('sc_quantity')} input`).first();
    await quantityInput.fill(UPDATED_QUANTITY);

    const budgetInput = page.locator(`${field('sc_budget')} input`).first();
    await budgetInput.click();
    await budgetInput.press('ControlOrMeta+A');
    await budgetInput.type(UPDATED_BUDGET);
    await budgetInput.blur();

    const timeSlotInput = page.locator(`${field('sc_time_slot')} input[step]`).first();
    await timeSlotInput.fill(UPDATED_TIME_SLOT);

    const aiSummaryInput = page.locator(`${field('sc_ai_summary')} textarea`).first();
    await aiSummaryInput.fill(UPDATED_AI_SUMMARY);

    const saveBtn = page.getByTestId('form-btn-submit').first();
    await saveBtn.scrollIntoViewIfNeeded();
    await expect(saveBtn).toBeVisible({ timeout: 5_000 });
    await saveBtn.click();
    await expect(page).toHaveURL(new RegExp(`${LIST_URL}(?:$|\\?)`), { timeout: 15_000 });
    await expect(page.locator('body')).not.toContainText(/Access forbidden|Page not found/i, {
      timeout: 5_000,
    });
    await openEditForm(page, UPDATED_RECORD_NAME);

    await expect(page.locator(`${field('sc_name')} input`).first()).toHaveValue(UPDATED_RECORD_NAME);
    await expect(page.locator(`${field('sc_quantity')} input`).first()).toHaveValue(UPDATED_QUANTITY);
    await expect(page.locator(`${field('sc_budget')} input`).first()).toHaveValue(/9,?001\.25/);
    await expect(page.locator(`${field('sc_time_slot')} input[step]`).first()).toHaveValue(
      UPDATED_TIME_SLOT,
    );
    await expect(page.locator(`${field('sc_ai_summary')} textarea`).first()).toHaveValue(
      UPDATED_AI_SUMMARY,
    );
    await expect(ownerTrigger).not.toContainText(/^请选择$/);

    const cancelBtn = page.locator('[data-testid="form-btn-cancel"]').first();
    await expect(cancelBtn).toBeVisible({ timeout: 5_000 });
    await cancelBtn.click();

    await expect(page).toHaveURL(new RegExp(`${LIST_URL}(?:$|\\?)`), { timeout: 10_000 });
    await expect(page.locator('body')).not.toContainText(/Access forbidden|Page not found/i, {
      timeout: 5_000,
    });
    await expect(page.locator('tbody tr').first()).toBeVisible({ timeout: 10_000 });
  });
});
