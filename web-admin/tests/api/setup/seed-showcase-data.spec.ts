/**
 * Showcase Demo Data Seed Script
 *
 * Creates realistic business data simulating 18 months of operations
 * for "Xinran Technology" (鑫然科技), an electronics trading company.
 *
 * Run: npx playwright test tests/api/setup/seed-showcase-data.spec.ts
 * Depends on: init-env.spec.ts must have run first (admin user + tenant + plugins imported)
 *
 * Design doc: docs/strategy/05-Seed数据设计方案.md
 */

import { test, expect } from '@playwright/test';
import { executeCommandViaApi } from '../../e2e/helpers';

// ---------------------------------------------------------------------------
// Time helpers — 18-month spread, not all "today"
// ---------------------------------------------------------------------------

/** Base date: 18 months before today */
function baseDate(): Date {
  const d = new Date();
  d.setMonth(d.getMonth() - 18);
  d.setHours(0, 0, 0, 0);
  return d;
}

/** Return a pure date string (yyyy-MM-dd) for DATE fields */
function dateAt(monthOffset: number, dayOffset = 0): string {
  const d = new Date(baseDate());
  d.setMonth(d.getMonth() + monthOffset);
  d.setDate(d.getDate() + dayOffset);
  return d.toISOString().split('T')[0];
}

/** Return full ISO-8601 datetime for DATETIME fields */
function dateTimeAt(monthOffset: number, dayOffset = 0): string {
  const d = new Date(baseDate());
  d.setMonth(d.getMonth() + monthOffset);
  d.setDate(d.getDate() + dayOffset);
  d.setHours(9, 0, 0, 0);
  return d.toISOString();
}

/** Return a datetime string */
function datetimeAt(monthOffset: number, dayOffset = 0, hour = 9): string {
  const d = new Date(baseDate());
  d.setMonth(d.getMonth() + monthOffset);
  d.setDate(d.getDate() + dayOffset);
  d.setHours(hour, Math.floor(Math.random() * 60), 0, 0);
  return d.toISOString();
}

/** Random int between min and max inclusive */
function randInt(min: number, max: number): number {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

/** Pick random element from array */
function pick<T>(arr: T[]): T {
  return arr[Math.floor(Math.random() * arr.length)];
}

// ---------------------------------------------------------------------------
// Seed data storage — IDs populated during creation
// ---------------------------------------------------------------------------

const ids = {
  departments: {} as Record<string, string>,
  positions: {} as Record<string, string>,
  employees: {} as Record<string, string>,
  accounts: {} as Record<string, string>,
  contacts: {} as Record<string, string>,
  leads: {} as Record<string, string>,
  opportunities: {} as Record<string, string>,
  activities: [] as string[],
  campaigns: {} as Record<string, string>,
};

// ---------------------------------------------------------------------------
// Command helper with retry
// ---------------------------------------------------------------------------

async function cmd(
  page: any,
  commandCode: string,
  payload: Record<string, unknown>,
  targetRecordId?: string,
  operationType?: string,
): Promise<string> {
  const result = await executeCommandViaApi(
    page,
    commandCode,
    payload,
    targetRecordId,
    operationType,
  );
  expect(result.code).toBe('0');
  return result.recordId;
}

// ═══════════════════════════════════════════════════════════════════════════
// Phase 1: Organization Structure
// ═══════════════════════════════════════════════════════════════════════════

test.describe.serial('Showcase Seed Data', () => {
  test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
  test.setTimeout(300_000); // 5 min total

  test('Phase 1: Organization — Departments', async ({ page }) => {
    const depts = [
      { name: '销售部', order: 1 },
      { name: '技术部', order: 2 },
      { name: '采购部', order: 3 },
      { name: '仓储物流部', order: 4 },
      { name: '财务部', order: 5 },
      { name: '总经办', order: 6 },
    ];

    for (const dept of depts) {
      const id = await cmd(page, 'org:create_department', {
        org_dept_name: dept.name,
        org_dept_order: dept.order,
        org_dept_status: 'active',
      });
      ids.departments[dept.name] = id;
      console.log(`  Created department: ${dept.name} → ${id}`);
    }
  });

  test('Phase 1: Organization — Positions', async ({ page }) => {
    const positions = [
      { code: 'GM', name: '总经理', dept: '总经办', level: 'L6' },
      { code: 'SD', name: '销售总监', dept: '销售部', level: 'L5' },
      { code: 'TD', name: '技术总监', dept: '技术部', level: 'L5' },
      { code: 'FD', name: '财务总监', dept: '财务部', level: 'L5' },
      { code: 'PM', name: '采购经理', dept: '采购部', level: 'L4' },
      { code: 'KAM', name: '大客户经理', dept: '销售部', level: 'L4' },
      { code: 'WM', name: '仓库主管', dept: '仓储物流部', level: 'L4' },
      { code: 'SR', name: '高级销售', dept: '销售部', level: 'L3' },
      { code: 'SE', name: '方案工程师', dept: '技术部', level: 'L3' },
      { code: 'SALES', name: '销售代表', dept: '销售部', level: 'L2' },
      { code: 'BUYER', name: '采购专员', dept: '采购部', level: 'L2' },
      { code: 'ACCT', name: '会计', dept: '财务部', level: 'L2' },
    ];

    for (const pos of positions) {
      const id = await cmd(page, 'org:create_position', {
        org_pos_code: pos.code,
        org_pos_name: pos.name,
        org_pos_dept_id: ids.departments[pos.dept],
        org_pos_level: pos.level,
        org_pos_status: 'active',
      });
      ids.positions[pos.name] = id;
      console.log(`  Created position: ${pos.name}`);
    }
  });

  test('Phase 1: Organization — Employees', async ({ page }) => {
    // Get admin user pid to satisfy the required org_emp_user_id field
    const userResp = await page.request.get('/api/auth/me');
    const userBody = await userResp.json();
    const adminUserPid =
      userBody?.data?.user?.pid || userBody?.data?.user?.id || userBody?.data?.pid || '';

    const employees = [
      {
        name: '李明远',
        dept: '总经办',
        pos: '总经理',
        phone: '13800001001',
        email: 'limy@xinrantech.com',
        hire: dateAt(0, 1),
      },
      {
        name: '王佳琳',
        dept: '销售部',
        pos: '销售总监',
        phone: '13800001002',
        email: 'wangjl@xinrantech.com',
        hire: dateAt(0, 1),
      },
      {
        name: '陈志豪',
        dept: '销售部',
        pos: '大客户经理',
        phone: '13800001003',
        email: 'chenzh@xinrantech.com',
        hire: dateAt(0, 5),
      },
      {
        name: '张雨晴',
        dept: '销售部',
        pos: '高级销售',
        phone: '13800001004',
        email: 'zhangyq@xinrantech.com',
        hire: dateAt(0, 5),
      },
      {
        name: '林伟杰',
        dept: '销售部',
        pos: '高级销售',
        phone: '13800001005',
        email: 'linwj@xinrantech.com',
        hire: dateAt(0, 5),
      },
      {
        name: '赵小燕',
        dept: '销售部',
        pos: '销售代表',
        phone: '13800001006',
        email: 'zhxy@xinrantech.com',
        hire: dateAt(3, 1),
      },
      {
        name: '孙浩然',
        dept: '销售部',
        pos: '销售代表',
        phone: '13800001007',
        email: 'sunhr@xinrantech.com',
        hire: dateAt(3, 1),
      },
      {
        name: '周梦琪',
        dept: '销售部',
        pos: '销售代表',
        phone: '13800001008',
        email: 'zhoumq@xinrantech.com',
        hire: dateAt(4, 15),
      },
      {
        name: '刘思雨',
        dept: '销售部',
        pos: '销售代表',
        phone: '13800001009',
        email: 'liusy@xinrantech.com',
        hire: dateAt(0, 10),
      },
      {
        name: '杨建国',
        dept: '技术部',
        pos: '技术总监',
        phone: '13800001010',
        email: 'yangjg@xinrantech.com',
        hire: dateAt(0, 1),
      },
      {
        name: '吴晓峰',
        dept: '技术部',
        pos: '方案工程师',
        phone: '13800001011',
        email: 'wuxf@xinrantech.com',
        hire: dateAt(0, 5),
      },
      {
        name: '徐静怡',
        dept: '技术部',
        pos: '方案工程师',
        phone: '13800001012',
        email: 'xujy@xinrantech.com',
        hire: dateAt(1, 15),
      },
      {
        name: '朱明达',
        dept: '技术部',
        pos: '方案工程师',
        phone: '13800001013',
        email: 'zhumd@xinrantech.com',
        hire: dateAt(2, 1),
      },
      {
        name: '马晓燕',
        dept: '技术部',
        pos: '方案工程师',
        phone: '13800001014',
        email: 'maxy@xinrantech.com',
        hire: dateAt(0, 10),
      },
      {
        name: '何志强',
        dept: '技术部',
        pos: '方案工程师',
        phone: '13800001015',
        email: 'hezq@xinrantech.com',
        hire: dateAt(3, 1),
      },
      {
        name: '黄丽华',
        dept: '采购部',
        pos: '采购经理',
        phone: '13800001016',
        email: 'huanglh@xinrantech.com',
        hire: dateAt(0, 1),
      },
      {
        name: '郑伟',
        dept: '采购部',
        pos: '采购专员',
        phone: '13800001017',
        email: 'zhengw@xinrantech.com',
        hire: dateAt(0, 10),
      },
      {
        name: '罗小红',
        dept: '采购部',
        pos: '采购专员',
        phone: '13800001018',
        email: 'luoxh@xinrantech.com',
        hire: dateAt(5, 1),
      },
      {
        name: '刘建平',
        dept: '仓储物流部',
        pos: '仓库主管',
        phone: '13800001019',
        email: 'liujp@xinrantech.com',
        hire: dateAt(0, 1),
      },
      {
        name: '张国华',
        dept: '仓储物流部',
        pos: '采购专员',
        phone: '13800001020',
        email: 'zhangh@xinrantech.com',
        hire: dateAt(0, 15),
      },
      {
        name: '王秀英',
        dept: '仓储物流部',
        pos: '采购专员',
        phone: '13800001021',
        email: 'wangxy@xinrantech.com',
        hire: dateAt(1, 1),
      },
      {
        name: '陈美玲',
        dept: '财务部',
        pos: '财务总监',
        phone: '13800001022',
        email: 'chenml@xinrantech.com',
        hire: dateAt(0, 1),
      },
      {
        name: '李晓芳',
        dept: '财务部',
        pos: '会计',
        phone: '13800001023',
        email: 'lixf@xinrantech.com',
        hire: dateAt(0, 10),
      },
      {
        name: '吴丹',
        dept: '财务部',
        pos: '会计',
        phone: '13800001024',
        email: 'wudan@xinrantech.com',
        hire: dateAt(2, 1),
      },
      {
        name: '赵雅婷',
        dept: '总经办',
        pos: '总经理',
        phone: '13800001025',
        email: 'zhaoyt@xinrantech.com',
        hire: dateAt(0, 1),
      },
    ];

    for (const emp of employees) {
      const id = await cmd(page, 'org:create_employee', {
        org_emp_name: emp.name,
        org_emp_user_id: adminUserPid,
        org_emp_dept_id: ids.departments[emp.dept],
        org_emp_position_id: ids.positions[emp.pos],
        org_emp_phone: emp.phone,
        org_emp_email: emp.email,
        org_emp_hire_date: emp.hire,
      });
      ids.employees[emp.name] = id;
      console.log(`  Created employee: ${emp.name}`);
    }
  });

  // ═════════════════════════════════════════════════════════════════════════
  // Phase 2: CRM Accounts (80 in design, start with 20 core ones)
  // ═════════════════════════════════════════════════════════════════════════

  test('Phase 2: CRM — Accounts (A+B tier, 20)', async ({ page }) => {
    const accounts: Array<{
      name: string;
      industry: string;
      rating: string;
      status: string;
      phone: string;
      website: string;
      address: string;
      month: number;
    }> = [
      // A tier — 5 strategic accounts
      {
        name: '宁波鑫越汽车电子有限公司',
        industry: 'automotive',
        rating: 'A',
        status: 'active',
        phone: '0574-87651234',
        website: 'www.xinyue-auto.com',
        address: '宁波市高新区研发园A栋',
        month: 5,
      },
      {
        name: '苏州锐虎机电科技有限公司',
        industry: 'manufacturing',
        rating: 'A',
        status: 'active',
        phone: '0512-65432100',
        website: 'www.ruihu-mech.com',
        address: '苏州工业园区星海街88号',
        month: 1,
      },
      {
        name: '杭州曜熠智能科技有限公司',
        industry: 'technology',
        rating: 'A',
        status: 'active',
        phone: '0571-88765432',
        website: 'www.yaoyi-smart.com',
        address: '杭州市滨江区网商路599号',
        month: 1,
      },
      {
        name: '重庆驭辰新能源科技有限公司',
        industry: 'energy',
        rating: 'A',
        status: 'active',
        phone: '023-67891234',
        website: 'www.yuchen-energy.com',
        address: '重庆市两江新区科技路100号',
        month: 8,
      },
      {
        name: '上海睿展精密电子有限公司',
        industry: 'electronics',
        rating: 'A',
        status: 'active',
        phone: '021-54321678',
        website: 'www.ruizhan-elec.com',
        address: '上海市闵行区莘庄工业区',
        month: 1,
      },
      // B tier — 15 key accounts
      {
        name: '深圳晶澄微电子有限公司',
        industry: 'semiconductor',
        rating: 'B',
        status: 'active',
        phone: '0755-86543210',
        website: 'www.jingcheng-semi.com',
        address: '深圳市南山区科技园',
        month: 2,
      },
      {
        name: '东莞精密模具科技有限公司',
        industry: 'manufacturing',
        rating: 'B',
        status: 'active',
        phone: '0769-22334455',
        website: '',
        address: '东莞市长安镇工业路',
        month: 2,
      },
      {
        name: '广州铭泰电子有限公司',
        industry: 'telecom',
        rating: 'B',
        status: 'active',
        phone: '020-87654321',
        website: 'www.mingtai-elec.com',
        address: '广州市天河区高唐路',
        month: 3,
      },
      {
        name: '合肥昱辉光电技术有限公司',
        industry: 'optoelectronics',
        rating: 'B',
        status: 'active',
        phone: '0551-63456789',
        website: 'www.yuhui-opto.com',
        address: '合肥市高新区望江西路',
        month: 3,
      },
      {
        name: '成都芯邦集成电路有限公司',
        industry: 'semiconductor',
        rating: 'B',
        status: 'active',
        phone: '028-85678901',
        website: 'www.xinbang-ic.com',
        address: '成都市高新区天府大道',
        month: 4,
      },
      {
        name: '武汉启瑞信息技术有限公司',
        industry: 'technology',
        rating: 'B',
        status: 'active',
        phone: '027-87654322',
        website: 'www.qirui-it.com',
        address: '武汉市东湖高新区光谷大道',
        month: 4,
      },
      {
        name: '厦门宏翔电气有限公司',
        industry: 'power_equipment',
        rating: 'B',
        status: 'active',
        phone: '0592-56789012',
        website: '',
        address: '厦门市集美区软件园',
        month: 5,
      },
      {
        name: '长沙湘江智造科技有限公司',
        industry: 'manufacturing',
        rating: 'B',
        status: 'active',
        phone: '0731-84567890',
        website: 'www.xiangjiang-mfg.com',
        address: '长沙市岳麓区麓谷企业广场',
        month: 6,
      },
      {
        name: '天津北辰精工有限公司',
        industry: 'precision',
        rating: 'B',
        status: 'active',
        phone: '022-26789012',
        website: '',
        address: '天津市北辰区京津科技谷',
        month: 7,
      },
      {
        name: '青岛瀚辰电子技术有限公司',
        industry: 'consumer_electronics',
        rating: 'B',
        status: 'active',
        phone: '0532-85670123',
        website: 'www.hanchen-tech.com',
        address: '青岛市崂山区松岭路',
        month: 8,
      },
      {
        name: '郑州承远电器有限公司',
        industry: 'automotive',
        rating: 'B',
        status: 'active',
        phone: '0371-67890123',
        website: 'www.chengyuan-elec.com',
        address: '郑州市经开区航海东路',
        month: 9,
      },
      {
        name: '南京芯汇半导体有限公司',
        industry: 'semiconductor',
        rating: 'B',
        status: 'active',
        phone: '025-84567891',
        website: 'www.xinhui-semi.com',
        address: '南京市江北新区研创园',
        month: 10,
      },
      {
        name: '珠海启恒智能装备有限公司',
        industry: 'manufacturing',
        rating: 'B',
        status: 'active',
        phone: '0756-87654323',
        website: 'www.qiheng-equip.com',
        address: '珠海市金湾区智造大道',
        month: 10,
      },
      {
        name: '无锡晶澜微电子有限公司',
        industry: 'semiconductor',
        rating: 'B',
        status: 'active',
        phone: '0510-85671234',
        website: 'www.jinglan-micro.com',
        address: '无锡市新吴区太湖国际科技园',
        month: 11,
      },
      {
        name: '佛山德沃楼宇科技有限公司',
        industry: 'building_tech',
        rating: 'B',
        status: 'active',
        phone: '0757-83456789',
        website: 'www.dewo-building.com',
        address: '佛山市顺德区北滘镇',
        month: 12,
      },
    ];

    for (const acc of accounts) {
      const id = await cmd(page, 'crm:create_account', {
        crm_acc_name: acc.name,
        crm_acc_industry: acc.industry,
        crm_acc_rating: acc.rating,
        crm_acc_status: acc.status,
        crm_acc_phone: acc.phone,
        crm_acc_website: acc.website,
        crm_acc_address: acc.address,
      });
      ids.accounts[acc.name] = id;
      console.log(`  Created account: ${acc.name} (${acc.rating})`);
    }
  });

  // ═════════════════════════════════════════════════════════════════════════
  // Phase 3: Contacts — 2-4 per A-tier, 1-2 per B-tier
  // ═════════════════════════════════════════════════════════════════════════

  test('Phase 3: CRM — Contacts (40+)', async ({ page }) => {
    const contacts: Array<{
      account: string;
      name: string;
      title: string;
      email: string;
      phone: string;
      isPrimary: boolean;
    }> = [
      // 宁波鑫越 — 4 contacts
      {
        account: '宁波鑫越汽车电子有限公司',
        name: '钱进',
        title: '采购总监',
        email: 'qianjin@xinyue-auto.com',
        phone: '13812345521',
        isPrimary: true,
      },
      {
        account: '宁波鑫越汽车电子有限公司',
        name: '沈丽芳',
        title: '采购专员',
        email: 'shenlifang@xinyue-auto.com',
        phone: '13912343387',
        isPrimary: false,
      },
      {
        account: '宁波鑫越汽车电子有限公司',
        name: '方明',
        title: '技术主管',
        email: 'fangming@xinyue-auto.com',
        phone: '13712346612',
        isPrimary: false,
      },
      {
        account: '宁波鑫越汽车电子有限公司',
        name: '陆建华',
        title: '财务经理',
        email: 'lujihua@xinyue-auto.com',
        phone: '13612348890',
        isPrimary: false,
      },
      // 苏州锐虎 — 3 contacts
      {
        account: '苏州锐虎机电科技有限公司',
        name: '韩超',
        title: '技术总监',
        email: 'hanchao@ruihu-mech.com',
        phone: '13856781234',
        isPrimary: true,
      },
      {
        account: '苏州锐虎机电科技有限公司',
        name: '吕芳',
        title: '采购经理',
        email: 'lvfang@ruihu-mech.com',
        phone: '13956782345',
        isPrimary: false,
      },
      {
        account: '苏州锐虎机电科技有限公司',
        name: '唐磊',
        title: '项目经理',
        email: 'tanglei@ruihu-mech.com',
        phone: '13756783456',
        isPrimary: false,
      },
      // 杭州曜熠 — 3 contacts
      {
        account: '杭州曜熠智能科技有限公司',
        name: '贾鸿飞',
        title: 'CTO',
        email: 'jiahf@yaoyi-smart.com',
        phone: '13867891001',
        isPrimary: true,
      },
      {
        account: '杭州曜熠智能科技有限公司',
        name: '秦雪',
        title: '供应链经理',
        email: 'qinxue@yaoyi-smart.com',
        phone: '13967892002',
        isPrimary: false,
      },
      {
        account: '杭州曜熠智能科技有限公司',
        name: '萧然',
        title: '产品经理',
        email: 'xiaoran@yaoyi-smart.com',
        phone: '13767893003',
        isPrimary: false,
      },
      // 重庆驭辰 — 3 contacts
      {
        account: '重庆驭辰新能源科技有限公司',
        name: '廖军',
        title: '采购总监',
        email: 'liaojun@yuchen-energy.com',
        phone: '13878901001',
        isPrimary: true,
      },
      {
        account: '重庆驭辰新能源科技有限公司',
        name: '谢婷',
        title: '质量经理',
        email: 'xieting@yuchen-energy.com',
        phone: '13978902002',
        isPrimary: false,
      },
      {
        account: '重庆驭辰新能源科技有限公司',
        name: '丁伟',
        title: '研发工程师',
        email: 'dingwei@yuchen-energy.com',
        phone: '13778903003',
        isPrimary: false,
      },
      // 上海睿展 — 3 contacts
      {
        account: '上海睿展精密电子有限公司',
        name: '蒋明华',
        title: '总经理',
        email: 'jiangmh@ruizhan-elec.com',
        phone: '13889011001',
        isPrimary: true,
      },
      {
        account: '上海睿展精密电子有限公司',
        name: '潘小红',
        title: '采购主管',
        email: 'panxh@ruizhan-elec.com',
        phone: '13989012002',
        isPrimary: false,
      },
      {
        account: '上海睿展精密电子有限公司',
        name: '于峰',
        title: '工程部长',
        email: 'yufeng@ruizhan-elec.com',
        phone: '13789013003',
        isPrimary: false,
      },
      // B-tier accounts — 1-2 contacts each
      {
        account: '深圳晶澄微电子有限公司',
        name: '彭涛',
        title: '采购经理',
        email: 'pengtao@jingcheng.com',
        phone: '13890121001',
        isPrimary: true,
      },
      {
        account: '深圳晶澄微电子有限公司',
        name: '范敏',
        title: '技术评估',
        email: 'fanmin@jingcheng.com',
        phone: '13990122002',
        isPrimary: false,
      },
      {
        account: '东莞精密模具科技有限公司',
        name: '叶强',
        title: '总经理',
        email: 'yeqiang@dgmold.com',
        phone: '13801231001',
        isPrimary: true,
      },
      {
        account: '广州铭泰电子有限公司',
        name: '陶磊',
        title: '采购经理',
        email: 'taolei@mingtai.com',
        phone: '13901232001',
        isPrimary: true,
      },
      {
        account: '广州铭泰电子有限公司',
        name: '甘晓丽',
        title: '财务',
        email: 'ganxl@mingtai.com',
        phone: '13701233001',
        isPrimary: false,
      },
      {
        account: '合肥昱辉光电技术有限公司',
        name: '史磊',
        title: '采购',
        email: 'shilei@yuhui-opto.com',
        phone: '13801241001',
        isPrimary: true,
      },
      {
        account: '成都芯邦集成电路有限公司',
        name: '金晨',
        title: '供应链总监',
        email: 'jinchen@xinbang.com',
        phone: '13901251001',
        isPrimary: true,
      },
      {
        account: '武汉启瑞信息技术有限公司',
        name: '雷洋',
        title: '技术总监',
        email: 'leiyang@qirui.com',
        phone: '13801261001',
        isPrimary: true,
      },
      {
        account: '厦门宏翔电气有限公司',
        name: '邵伟',
        title: '采购',
        email: 'shaowei@hongxiang.com',
        phone: '13901271001',
        isPrimary: true,
      },
      {
        account: '长沙湘江智造科技有限公司',
        name: '段志刚',
        title: '总经理',
        email: 'duanzg@xiangjiang.com',
        phone: '13801281001',
        isPrimary: true,
      },
      {
        account: '天津北辰精工有限公司',
        name: '崔建',
        title: '采购主管',
        email: 'cuijian@beichen.com',
        phone: '13901291001',
        isPrimary: true,
      },
      {
        account: '青岛瀚辰电子技术有限公司',
        name: '尹磊',
        title: '工程总监',
        email: 'yinlei@hanchen.com',
        phone: '13801301001',
        isPrimary: true,
      },
      {
        account: '郑州承远电器有限公司',
        name: '任刚',
        title: '采购经理',
        email: 'rengang@chengyuan.com',
        phone: '13901311001',
        isPrimary: true,
      },
      {
        account: '南京芯汇半导体有限公司',
        name: '曹明',
        title: '供应商管理',
        email: 'caoming@xinhui.com',
        phone: '13801321001',
        isPrimary: true,
      },
      {
        account: '珠海启恒智能装备有限公司',
        name: '侯磊',
        title: '采购',
        email: 'houlei@qiheng.com',
        phone: '13901331001',
        isPrimary: true,
      },
      {
        account: '无锡晶澜微电子有限公司',
        name: '龚婷',
        title: '供应链',
        email: 'gongting@jinglan.com',
        phone: '13801341001',
        isPrimary: true,
      },
      {
        account: '佛山德沃楼宇科技有限公司',
        name: '万涛',
        title: '技术部长',
        email: 'wantao@dewo.com',
        phone: '13901351001',
        isPrimary: true,
      },
    ];

    for (const ct of contacts) {
      const accountId = ids.accounts[ct.account];
      if (!accountId) {
        console.warn(`  Skipping contact ${ct.name}: account "${ct.account}" not found`);
        continue;
      }
      const id = await cmd(page, 'crm:create_contact', {
        crm_ct_account_id: accountId,
        crm_ct_name: ct.name,
        crm_ct_title: ct.title,
        crm_ct_email: ct.email,
        crm_ct_phone: ct.phone,
        crm_ct_is_primary: ct.isPrimary,
      });
      ids.contacts[ct.name] = id;
      console.log(`  Created contact: ${ct.name} @ ${ct.account.slice(0, 6)}`);
    }
  });

  // ═════════════════════════════════════════════════════════════════════════
  // Phase 4: Leads — 30 with various statuses and sources
  // ═════════════════════════════════════════════════════════════════════════

  test('Phase 4: CRM — Leads (30)', async ({ page }) => {
    const sources = ['exhibition', 'website', 'referral', 'cold_call', 'social_media', 'web_form'];
    const industries = [
      'electronics',
      'manufacturing',
      'automotive',
      'telecom',
      'energy',
      'semiconductor',
    ];

    const leads: Array<{
      company: string;
      contact: string;
      phone: string;
      email: string;
      source: string;
      industry: string;
      status: string;
      requirement: string;
      month: number;
    }> = [
      // new — 8
      {
        company: '深圳前海智控科技',
        contact: '王磊',
        phone: '13511110001',
        email: 'wanglei@qhzk.com',
        source: 'exhibition',
        industry: 'technology',
        status: 'new',
        requirement: 'PCBA 小批量打样，IoT 网关控制板',
        month: 17,
      },
      {
        company: '杭州银湖新材料有限公司',
        contact: '李婷',
        phone: '13611110002',
        email: 'liting@yinhu.com',
        source: 'website',
        industry: 'materials',
        status: 'new',
        requirement: '电阻电容批量采购询价',
        month: 17,
      },
      {
        company: '温州永嘉阀门科技',
        contact: '陈刚',
        phone: '13711110003',
        email: 'chengang@yjfm.com',
        source: 'referral',
        industry: 'manufacturing',
        status: 'new',
        requirement: '阀门控制器 PCB 开发',
        month: 17,
      },
      {
        company: '嘉兴瑞丰电子科技',
        contact: '黄磊',
        phone: '13811110004',
        email: 'huanglei@ruifeng.com',
        source: 'social_media',
        industry: 'electronics',
        status: 'new',
        requirement: '充电器 PCBA 方案',
        month: 17,
      },
      {
        company: '中山恒达照明电器',
        contact: '吴丽',
        phone: '13911110005',
        email: 'wuli@hengda-led.com',
        source: 'web_form',
        industry: 'optoelectronics',
        status: 'new',
        requirement: 'LED 驱动板批量',
        month: 17,
      },
      {
        company: '惠州仁和电子',
        contact: '周杰',
        phone: '13511110006',
        email: 'zhoujie@renhe.com',
        source: 'cold_call',
        industry: 'consumer_electronics',
        status: 'new',
        requirement: '蓝牙音箱主板',
        month: 17,
      },
      {
        company: '泉州鸿兴机电',
        contact: '林芳',
        phone: '13611110007',
        email: 'linfang@hongxing.com',
        source: 'exhibition',
        industry: 'manufacturing',
        status: 'new',
        requirement: '电机控制板开发',
        month: 17,
      },
      {
        company: '台州利达电器',
        contact: '张伟',
        phone: '13711110008',
        email: 'zhangwei@lida.com',
        source: 'website',
        industry: 'electronics',
        status: 'new',
        requirement: '小家电控制板',
        month: 17,
      },
      // contacted — 8
      {
        company: '无锡恒信电子科技',
        contact: '张涛',
        phone: '13811110009',
        email: 'zhangtao@hengxin.com',
        source: 'referral',
        industry: 'electronics',
        status: 'contacted',
        requirement: '控制板方案设计与打样',
        month: 16,
      },
      {
        company: '徐州新锐重工',
        contact: '刘洋',
        phone: '13911110010',
        email: 'liuyang@xinrui.com',
        source: 'cold_call',
        industry: 'manufacturing',
        status: 'contacted',
        requirement: '工程机械控制系统',
        month: 16,
      },
      {
        company: '烟台博越电子',
        contact: '赵明',
        phone: '13511110011',
        email: 'zhaoming@boyue.com',
        source: 'exhibition',
        industry: 'electronics',
        status: 'contacted',
        requirement: '海洋设备传感器板',
        month: 16,
      },
      {
        company: '潍坊东方动力',
        contact: '王强',
        phone: '13611110012',
        email: 'wangqiang@dfpower.com',
        source: 'website',
        industry: 'energy',
        status: 'contacted',
        requirement: '发电机组监控模块',
        month: 15,
      },
      {
        company: '绍兴联创纺织电子',
        contact: '陈浩',
        phone: '13711110013',
        email: 'chenhao@lianchuang.com',
        source: 'referral',
        industry: 'manufacturing',
        status: 'contacted',
        requirement: '纺织机自动化控制',
        month: 15,
      },
      {
        company: '常州天合光能配件',
        contact: '李敏',
        phone: '13811110014',
        email: 'limin@tianhe.com',
        source: 'social_media',
        industry: 'energy',
        status: 'contacted',
        requirement: '光伏逆变器 PCBA',
        month: 15,
      },
      {
        company: '镇江润达汽配',
        contact: '孙健',
        phone: '13911110015',
        email: 'sunjian@runda.com',
        source: 'exhibition',
        industry: 'automotive',
        status: 'contacted',
        requirement: '车载充电器模块',
        month: 14,
      },
      {
        company: '宜兴华光陶瓷电子',
        contact: '钱磊',
        phone: '13511110016',
        email: 'qianlei@huaguang.com',
        source: 'cold_call',
        industry: 'materials',
        status: 'contacted',
        requirement: '陶瓷基板方案咨询',
        month: 14,
      },
      // qualified — 6
      {
        company: '东莞旺盛精密科技',
        contact: '陈晓东',
        phone: '13611110017',
        email: 'chenxd@wangsheng.com',
        source: 'cold_call',
        industry: 'precision',
        status: 'qualified',
        requirement: '精密连接器批量采购，年用量50万只',
        month: 14,
      },
      {
        company: '佛山顺德凯达电器',
        contact: '黄志华',
        phone: '13711110018',
        email: 'huangzh@kaida.com',
        source: 'referral',
        industry: 'consumer_electronics',
        status: 'qualified',
        requirement: '智能家居网关主板定制',
        month: 13,
      },
      {
        company: '南通启航船舶电子',
        contact: '赵国强',
        phone: '13811110019',
        email: 'zhaogq@qihang.com',
        source: 'exhibition',
        industry: 'manufacturing',
        status: 'qualified',
        requirement: '船舶通信模块 PCBA',
        month: 13,
      },
      {
        company: '洛阳瑞驰矿业设备',
        contact: '王建国',
        phone: '13911110020',
        email: 'wangjg@ruichi.com',
        source: 'website',
        industry: 'mining',
        status: 'qualified',
        requirement: '矿用监控终端开发',
        month: 12,
      },
      {
        company: '昆山世硕电子',
        contact: '李小龙',
        phone: '13511110021',
        email: 'lixl@shishuo.com',
        source: 'referral',
        industry: 'electronics',
        status: 'qualified',
        requirement: '手机配件 PCBA 代工',
        month: 11,
      },
      {
        company: '湖州德清新材料',
        contact: '吴磊',
        phone: '13611110022',
        email: 'wulei@deqing.com',
        source: 'social_media',
        industry: 'materials',
        status: 'qualified',
        requirement: '新型传感器基板',
        month: 11,
      },
      // converted — 5
      {
        company: '成都蜀芯科技有限公司',
        contact: '刘洋',
        phone: '13711110023',
        email: 'liuyang@shuxin.com',
        source: 'exhibition',
        industry: 'semiconductor',
        status: 'converted',
        requirement: '新能源 BMS 板开发',
        month: 9,
      },
      {
        company: '西安航天电子',
        contact: '张强',
        phone: '13811110024',
        email: 'zhangqiang@xaht.com',
        source: 'referral',
        industry: 'aerospace',
        status: 'converted',
        requirement: '航天级 PCB 供应',
        month: 7,
      },
      {
        company: '济南浪潮信息配件',
        contact: '刘明',
        phone: '13911110025',
        email: 'liuming@langchao.com',
        source: 'exhibition',
        industry: 'technology',
        status: 'converted',
        requirement: '服务器主板元器件供应',
        month: 5,
      },
      {
        company: '福州新大陆数码',
        contact: '周伟',
        phone: '13511110026',
        email: 'zhouwei@newland.com',
        source: 'website',
        industry: 'technology',
        status: 'converted',
        requirement: 'POS 终端主板方案',
        month: 3,
      },
      {
        company: '大连船舶重工电子',
        contact: '孙磊',
        phone: '13611110027',
        email: 'sunlei@dlshipyard.com',
        source: 'cold_call',
        industry: 'manufacturing',
        status: 'converted',
        requirement: '船用电源模块',
        month: 2,
      },
      // lost — 3
      {
        company: '深圳某贸易公司',
        contact: '匿名',
        phone: '13711110028',
        email: 'info@unknown-trade.com',
        source: 'website',
        industry: 'trading',
        status: 'lost',
        requirement: '询价后无回复',
        month: 10,
      },
      {
        company: '义乌小商品电子',
        contact: '马超',
        phone: '13811110029',
        email: 'machao@ywxsp.com',
        source: 'cold_call',
        industry: 'consumer_electronics',
        status: 'lost',
        requirement: '价格太高，选择了其他供应商',
        month: 8,
      },
      {
        company: '汕头澄海玩具电子',
        contact: '黄小明',
        phone: '13911110030',
        email: 'huangxm@chenghai.com',
        source: 'exhibition',
        industry: 'consumer_electronics',
        status: 'lost',
        requirement: '遥控玩具电路板，MOQ 太高放弃',
        month: 6,
      },
    ];

    for (const lead of leads) {
      const id = await cmd(page, 'crm:create_lead', {
        crm_lead_company: lead.company,
        crm_lead_contact_name: lead.contact,
        crm_lead_contact_phone: lead.phone,
        crm_lead_contact_email: lead.email,
        crm_lead_source: lead.source,
        crm_lead_industry: lead.industry,
        crm_lead_requirement: lead.requirement,
      });

      // Transition status if not "new" (default)
      if (lead.status !== 'new') {
        const transitions: Record<string, string[]> = {
          contacted: ['crm:contact_lead'],
          qualified: ['crm:contact_lead', 'crm:qualify_lead'],
          converted: ['crm:contact_lead', 'crm:qualify_lead', 'crm:convert_lead'],
          lost: ['crm:contact_lead', 'crm:lose_lead'],
        };
        for (const transition of transitions[lead.status] || []) {
          await cmd(page, transition, {}, id, 'update').catch(() => {
            console.warn(`    Transition ${transition} failed for ${lead.company}, skipping`);
          });
        }
      }

      ids.leads[lead.company] = id;
      console.log(`  Created lead: ${lead.company} (${lead.status})`);
    }
  });

  // ═════════════════════════════════════════════════════════════════════════
  // Phase 5: Opportunities — 15 with story lines
  // ═════════════════════════════════════════════════════════════════════════

  test('Phase 5: CRM — Opportunities (15)', async ({ page }) => {
    const opps: Array<{
      name: string;
      account: string;
      stage: string;
      amount: number;
      closeDate: string;
      notes: string;
      transitions: string[];
    }> = [
      // Story A: 宁波鑫越 — 3 opportunities (all won)
      {
        name: 'BMS控制板项目',
        account: '宁波鑫越汽车电子有限公司',
        stage: 'closed_won',
        amount: 175750,
        closeDate: dateAt(6, 25),
        notes: '首批500片，含PCB+SMT+测试',
        transitions: [
          'crm:qualify_opportunity',
          'crm:propose_opportunity',
          'crm:negotiate_opportunity',
          'crm:win_opportunity',
        ],
      },
      {
        name: 'Q4追加订单',
        account: '宁波鑫越汽车电子有限公司',
        stage: 'closed_won',
        amount: 280000,
        closeDate: dateAt(9, 20),
        notes: 'Q4追加800片',
        transitions: [
          'crm:qualify_opportunity',
          'crm:propose_opportunity',
          'crm:negotiate_opportunity',
          'crm:win_opportunity',
        ],
      },
      {
        name: '2025年度框架协议',
        account: '宁波鑫越汽车电子有限公司',
        stage: 'closed_won',
        amount: 3800000,
        closeDate: dateAt(12, 15),
        notes: '年度框架，分季度交付',
        transitions: [
          'crm:qualify_opportunity',
          'crm:propose_opportunity',
          'crm:negotiate_opportunity',
          'crm:win_opportunity',
        ],
      },
      // Story B: 杭州曜熠 — annual deal
      {
        name: '2025年度合作协议',
        account: '杭州曜熠智能科技有限公司',
        stage: 'closed_won',
        amount: 4600000,
        closeDate: dateAt(12, 15),
        notes: '年度协议，6款产品，原始报价480万，谈判至460万',
        transitions: [
          'crm:qualify_opportunity',
          'crm:propose_opportunity',
          'crm:negotiate_opportunity',
          'crm:win_opportunity',
        ],
      },
      // Active pipeline
      {
        name: '伺服驱动器PCBA',
        account: '苏州锐虎机电科技有限公司',
        stage: 'proposal',
        amount: 580000,
        closeDate: dateAt(18, 15),
        notes: '定制伺服驱动器方案',
        transitions: ['crm:qualify_opportunity', 'crm:propose_opportunity'],
      },
      {
        name: '新能源充电模块',
        account: '重庆驭辰新能源科技有限公司',
        stage: 'negotiation',
        amount: 420000,
        closeDate: dateAt(18, 0),
        notes: '7kW充电模块，质量要求极严',
        transitions: [
          'crm:qualify_opportunity',
          'crm:propose_opportunity',
          'crm:negotiate_opportunity',
        ],
      },
      {
        name: '消费电子主板Q2',
        account: '上海睿展精密电子有限公司',
        stage: 'qualification',
        amount: 350000,
        closeDate: dateAt(19, 0),
        notes: '量大但利润低',
        transitions: ['crm:qualify_opportunity'],
      },
      {
        name: 'LED驱动模块',
        account: '合肥昱辉光电技术有限公司',
        stage: 'discovery',
        amount: 120000,
        closeDate: dateAt(19, 15),
        notes: '新客户首次合作机会',
        transitions: [],
      },
      {
        name: '5G基站滤波器',
        account: '广州铭泰电子有限公司',
        stage: 'proposal',
        amount: 280000,
        closeDate: dateAt(18, 20),
        notes: '5G小基站配套',
        transitions: ['crm:qualify_opportunity', 'crm:propose_opportunity'],
      },
      {
        name: '工业网关方案',
        account: '武汉启瑞信息技术有限公司',
        stage: 'qualification',
        amount: 200000,
        closeDate: dateAt(19, 10),
        notes: '工业物联网网关',
        transitions: ['crm:qualify_opportunity'],
      },
      {
        name: '矩形连接器批量',
        account: '天津北辰精工有限公司',
        stage: 'discovery',
        amount: 80000,
        closeDate: dateAt(20, 0),
        notes: '精密连接器年度供应',
        transitions: [],
      },
      {
        name: '智能装备控制器',
        account: '珠海启恒智能装备有限公司',
        stage: 'negotiation',
        amount: 180000,
        closeDate: dateAt(17, 25),
        notes: '自动化产线控制',
        transitions: [
          'crm:qualify_opportunity',
          'crm:propose_opportunity',
          'crm:negotiate_opportunity',
        ],
      },
      // Lost opportunities
      {
        name: 'IoT网关PCBA项目',
        account: '深圳晶澄微电子有限公司',
        stage: 'closed_lost',
        amount: 92000,
        closeDate: dateAt(13, 5),
        notes: '客户选择了更便宜的供应商（竞品低15%）',
        transitions: [
          'crm:qualify_opportunity',
          'crm:propose_opportunity',
          'crm:lose_opportunity',
        ],
      },
      {
        name: '电源模块方案',
        account: '东莞精密模具科技有限公司',
        stage: 'closed_lost',
        amount: 65000,
        closeDate: dateAt(10, 20),
        notes: '技术方案不符合客户要求',
        transitions: ['crm:qualify_opportunity', 'crm:lose_opportunity'],
      },
      // More won
      {
        name: '车载充电器模块',
        account: '郑州承远电器有限公司',
        stage: 'closed_won',
        amount: 920000,
        closeDate: dateAt(11, 8),
        notes: '大批量车载充电器',
        transitions: [
          'crm:qualify_opportunity',
          'crm:propose_opportunity',
          'crm:negotiate_opportunity',
          'crm:win_opportunity',
        ],
      },
    ];

    for (const opp of opps) {
      const accountId = ids.accounts[opp.account];
      if (!accountId) {
        console.warn(`  Skipping opp "${opp.name}": account not found`);
        continue;
      }

      const id = await cmd(page, 'crm:create_opportunity', {
        crm_opp_name: opp.name,
        crm_opp_account_id: accountId,
        crm_opp_expected_amount: opp.amount,
        crm_opp_expected_close_date: opp.closeDate,
        crm_opp_notes: opp.notes,
      });

      // Transition through stages
      for (const transition of opp.transitions) {
        await cmd(page, transition, {}, id, 'update').catch(() => {
          console.warn(`    Transition ${transition} failed for "${opp.name}", skipping`);
        });
      }

      ids.opportunities[opp.name] = id;
      console.log(
        `  Created opportunity: ${opp.name} (${opp.stage}, ¥${opp.amount.toLocaleString()})`,
      );
    }
  });

  // ═════════════════════════════════════════════════════════════════════════
  // Phase 6: Activities — follow-up records with real-world content
  // ═════════════════════════════════════════════════════════════════════════

  test('Phase 6: CRM — Activities (30+ key records)', async ({ page }) => {
    const activities: Array<{
      type: string;
      subject: string;
      content: string;
      month: number;
      day: number;
    }> = [
      // Story A: 宁波鑫越 timeline
      {
        type: 'call',
        subject: '初次电话联系钱进总监',
        content: '了解到宁波鑫越有新能源BMS控制板需求，计划Q3立项，预算约20万。约下周拜访工厂。',
        month: 5,
        day: 8,
      },
      {
        type: 'visit',
        subject: '拜访宁波鑫越工厂',
        content:
          '参观工厂，与技术主管方明对接需求。控制板需满足AEC-Q100标准，含PCB+SMT+AOI检测。方案可行，回去出报价。',
        month: 5,
        day: 15,
      },
      {
        type: 'email',
        subject: '发送BMS控制板报价方案',
        content: '报价185,000元，含PCB打样费、SMT贴片、AOI检测、功能测试。账期60天。',
        month: 5,
        day: 20,
      },
      {
        type: 'call',
        subject: '价格谈判',
        content:
          '客户要求降价5%，理由是竞品报价更低。经测算，降至175,750元仍有合理利润。已申请折扣审批。',
        month: 6,
        day: 10,
      },
      {
        type: 'meeting',
        subject: '签约会议',
        content: '签署首批500片订单合同，金额175,750元。交付周期4周，质量标准按AEC-Q100执行。',
        month: 6,
        day: 25,
      },
      {
        type: 'call',
        subject: '确认Q2订单量',
        content: '与钱进确认Q2订单量，预计800片，下周发PO。客户反馈上批次品质良好。',
        month: 17,
        day: 18,
      },
      // Story B: regular follow-ups
      {
        type: 'call',
        subject: '跟进苏州锐虎伺服驱动器项目',
        content: '技术方案已通过评审，客户要求增加EMC测试。预计下月出最终报价。',
        month: 16,
        day: 5,
      },
      {
        type: 'visit',
        subject: '拜访杭州曜熠，季度复盘',
        content: 'Q1交付准时率95%，客户满意。Q2增加2款新品，预计年度框架增加15%。',
        month: 17,
        day: 12,
      },
      {
        type: 'email',
        subject: '发送上海睿展新报价单',
        content: '含3%季度折扣的Q2报价单。消费电子主板，量大价低，利润率偏薄。',
        month: 17,
        day: 10,
      },
      {
        type: 'call',
        subject: '跟进合肥昱辉LED驱动项目',
        content: '上批次有2%不良率，已安排补货50片。客户接受处理方案，关系修复中。',
        month: 17,
        day: 8,
      },
      {
        type: 'wechat',
        subject: '发送深圳晶澄新品资料',
        content: '发送新品推介资料，客户表示对新型MCU方案感兴趣，约下周详聊。',
        month: 17,
        day: 5,
      },
      {
        type: 'visit',
        subject: '重庆驭辰PPAP文件审核',
        content: 'PPAP文件审核，客户要求补充可靠性测试报告。技术部安排3天内补充完成。',
        month: 17,
        day: 1,
      },
      // More generic activities
      {
        type: 'call',
        subject: '广州铭泰5G项目进展跟踪',
        content: '方案评审通过，等待客户内部审批。预计2周内有结果。',
        month: 16,
        day: 15,
      },
      {
        type: 'email',
        subject: '武汉启瑞技术资料发送',
        content: '发送工业网关方案PPT和报价，等待技术评估反馈。',
        month: 16,
        day: 8,
      },
      {
        type: 'meeting',
        subject: '2024深圳电子展总结会',
        content: '展会共收集28张名片，已分配给各销售跟进。重点客户5家，计划月底前完成首次联系。',
        month: 11,
        day: 8,
      },
      {
        type: 'call',
        subject: '珠海启恒控制器项目谈判',
        content: '价格基本达成一致，客户要求账期从30天延至45天。已提交审批。',
        month: 16,
        day: 22,
      },
      {
        type: 'call',
        subject: '天津北辰连接器询价',
        content: '新客户初次询价，精密矩形连接器，年用量约5万只。下周发样品。',
        month: 16,
        day: 1,
      },
      {
        type: 'email',
        subject: '佛山德沃楼宇科技合作意向',
        content: '客户对智能楼宇控制方案感兴趣，安排技术对接。',
        month: 15,
        day: 20,
      },
      // Older activities
      {
        type: 'visit',
        subject: '参加2024慕尼黑上海电子展',
        content: '展会第一天，共收集35张名片，其中10家重点客户。展位效果好于预期。',
        month: 6,
        day: 10,
      },
      {
        type: 'call',
        subject: '展会后批量跟进电话（第一批10家）',
        content: '完成10家展会客户首次电话跟进，其中3家有明确需求，7家表示后续关注。',
        month: 6,
        day: 18,
      },
      {
        type: 'meeting',
        subject: '月度销售例会',
        content: '7月新增客户6家，赢单3笔，Pipeline增长25%。下月重点跟进苏州锐虎和重庆驭辰。',
        month: 6,
        day: 30,
      },
      {
        type: 'call',
        subject: '成都蜀芯新能源BMS项目回访',
        content: '项目已交付，客户满意度高。计划推荐给其他新能源客户。',
        month: 10,
        day: 15,
      },
      {
        type: 'email',
        subject: '年终客户感谢信群发',
        content: '向所有活跃客户发送年终感谢信+2025年新品预告。共发送65封。',
        month: 11,
        day: 28,
      },
      {
        type: 'visit',
        subject: '郑州承远车载充电器批量交付验收',
        content: '大批量车载充电器交付验收通过，品质达标率99.2%。客户表示明年继续合作。',
        month: 10,
        day: 25,
      },
    ];

    for (const act of activities) {
      const id = await cmd(page, 'crm:create_activity', {
        crm_act_type: act.type,
        crm_act_subject: act.subject,
        crm_act_content: act.content,
        crm_act_date: datetimeAt(act.month, act.day, randInt(9, 17)),
      });
      ids.activities.push(id);
      console.log(`  Created activity: [${act.type}] ${act.subject.slice(0, 30)}`);
    }
  });

  // ═════════════════════════════════════════════════════════════════════════
  // Phase 7: Campaigns
  // ═════════════════════════════════════════════════════════════════════════

  test('Phase 7: CRM — Campaigns (3)', async ({ page }) => {
    const campaigns = [
      {
        name: '2024慕尼黑上海电子展',
        type: 'exhibition',
        startDate: dateAt(6, 10),
        endDate: dateAt(6, 12),
        budget: 85000,
        status: 'completed',
        description: '上海新国际博览中心，W3馆-3B12展位。展示PCBA加工能力和元器件供应链优势。',
      },
      {
        name: '2024深圳国际电子展',
        type: 'exhibition',
        startDate: dateAt(11, 4),
        endDate: dateAt(11, 6),
        budget: 62000,
        status: 'completed',
        description: '深圳会展中心，重点展示新能源和汽车电子解决方案。',
      },
      {
        name: '2025官网SEO优化计划',
        type: 'digital',
        startDate: dateAt(12, 1),
        endDate: dateAt(17, 30),
        budget: 36000,
        status: 'active',
        description: '全年SEO优化，目标关键词：PCBA加工、电子元器件供应、BMS控制板。',
      },
    ];

    for (const cpn of campaigns) {
      const id = await cmd(page, 'crm:create_campaign', {
        crm_cpn_name: cpn.name,
        crm_cpn_type: cpn.type,
        crm_cpn_start_date: cpn.startDate,
        crm_cpn_end_date: cpn.endDate,
        crm_cpn_budget: cpn.budget,
        crm_cpn_description: cpn.description,
      });

      // Transition status
      if (cpn.status === 'active') {
        await cmd(page, 'crm:activate_campaign', {}, id, 'update').catch(() => {});
      } else if (cpn.status === 'completed') {
        await cmd(page, 'crm:activate_campaign', {}, id, 'update').catch(() => {});
        await cmd(page, 'crm:complete_campaign', {}, id, 'update').catch(() => {});
      }

      ids.campaigns[cpn.name] = id;
      console.log(`  Created campaign: ${cpn.name} (${cpn.status})`);
    }
  });

  // ═════════════════════════════════════════════════════════════════════════
  // Final verification
  // ═════════════════════════════════════════════════════════════════════════

  test('Verification: Count seeded data', async ({ page }) => {
    console.log('\n═══════════════════════════════════════');
    console.log('  Showcase Seed Data — Summary');
    console.log('═══════════════════════════════════════');
    console.log(`  Departments:   ${Object.keys(ids.departments).length}`);
    console.log(`  Positions:     ${Object.keys(ids.positions).length}`);
    console.log(`  Employees:     ${Object.keys(ids.employees).length}`);
    console.log(`  Accounts:      ${Object.keys(ids.accounts).length}`);
    console.log(`  Contacts:      ${Object.keys(ids.contacts).length}`);
    console.log(`  Leads:         ${Object.keys(ids.leads).length}`);
    console.log(`  Opportunities: ${Object.keys(ids.opportunities).length}`);
    console.log(`  Activities:    ${ids.activities.length}`);
    console.log(`  Campaigns:     ${Object.keys(ids.campaigns).length}`);
    console.log('═══════════════════════════════════════\n');

    // Verify minimums
    expect(Object.keys(ids.departments).length).toBeGreaterThanOrEqual(6);
    expect(Object.keys(ids.employees).length).toBeGreaterThanOrEqual(25);
    expect(Object.keys(ids.accounts).length).toBeGreaterThanOrEqual(20);
    expect(Object.keys(ids.contacts).length).toBeGreaterThanOrEqual(30);
    expect(Object.keys(ids.leads).length).toBeGreaterThanOrEqual(25);
    expect(Object.keys(ids.opportunities).length).toBeGreaterThanOrEqual(12);
    expect(ids.activities.length).toBeGreaterThanOrEqual(20);
    expect(Object.keys(ids.campaigns).length).toBeGreaterThanOrEqual(3);
  });
});
