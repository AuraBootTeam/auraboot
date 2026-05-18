/**
 * Showcase Demo Data — Extended Seed
 *
 * Extends seed-showcase-data.spec.ts with:
 * - C/D tier accounts (30 more)
 * - Dormant accounts (10)
 * - More opportunities (fill pipeline)
 * - Bulk activities (make the system feel alive)
 * - Additional campaigns + members
 *
 * Run AFTER seed-showcase-data.spec.ts:
 *   npx playwright test tests/api/setup/seed-showcase-extended.spec.ts
 *
 * Design doc: docs/strategy/05-Seed数据设计方案.md
 */

import { test, expect } from '@playwright/test';
import { executeCommandViaApi } from '../../e2e/helpers';

// ---------------------------------------------------------------------------
// Helpers (same as base seed)
// ---------------------------------------------------------------------------

function baseDate(): Date {
  const d = new Date();
  d.setMonth(d.getMonth() - 18);
  d.setHours(0, 0, 0, 0);
  return d;
}

function dateAt(monthOffset: number, dayOffset = 0): string {
  const d = new Date(baseDate());
  d.setMonth(d.getMonth() + monthOffset);
  d.setDate(d.getDate() + dayOffset);
  return d.toISOString().split('T')[0];
}

function dateTimeAt(monthOffset: number, dayOffset = 0): string {
  const d = new Date(baseDate());
  d.setMonth(d.getMonth() + monthOffset);
  d.setDate(d.getDate() + dayOffset);
  d.setHours(9, 0, 0, 0);
  return d.toISOString();
}

function datetimeAt(monthOffset: number, dayOffset = 0, hour = 9): string {
  const d = new Date(baseDate());
  d.setMonth(d.getMonth() + monthOffset);
  d.setDate(d.getDate() + dayOffset);
  d.setHours(hour, Math.floor(Math.random() * 60), 0, 0);
  return d.toISOString();
}

function randInt(min: number, max: number): number {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

function pick<T>(arr: T[]): T {
  return arr[Math.floor(Math.random() * arr.length)];
}

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

// Storage for IDs created in this file
const ext = {
  accounts: {} as Record<string, string>,
  contacts: {} as Record<string, string>,
  opportunities: {} as Record<string, string>,
  activities: [] as string[],
  campaigns: {} as Record<string, string>,
};

// ═══════════════════════════════════════════════════════════════════════════
// Phase 8: C-tier accounts (30) + Dormant (10)
// ═══════════════════════════════════════════════════════════════════════════

test.describe.serial('Showcase Seed — Extended', () => {
  test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
  test.setTimeout(600_000); // 10 min

  test('Phase 8: CRM — C/D tier Accounts (40)', async ({ page }) => {
    const accounts = [
      // C-tier — general accounts (30)
      { name: '绵阳鑫源电子科技有限公司', industry: 'electronics', rating: 'C', status: 'active' },
      { name: '常熟达利精密零件有限公司', industry: 'precision', rating: 'C', status: 'active' },
      {
        name: '湖州南太湖光电有限公司',
        industry: 'optoelectronics',
        rating: 'C',
        status: 'active',
      },
      { name: '泰州海陵机电有限公司', industry: 'manufacturing', rating: 'C', status: 'active' },
      { name: '漳州龙海电子科技有限公司', industry: 'electronics', rating: 'C', status: 'active' },
      { name: '赣州稀土电子材料有限公司', industry: 'materials', rating: 'C', status: 'active' },
      {
        name: '芜湖繁昌电气设备有限公司',
        industry: 'power_equipment',
        rating: 'C',
        status: 'active',
      },
      { name: '连云港港城电子有限公司', industry: 'electronics', rating: 'C', status: 'active' },
      { name: '淄博齐鑫陶瓷电子有限公司', industry: 'materials', rating: 'C', status: 'active' },
      { name: '咸阳西北电子仪器有限公司', industry: 'instruments', rating: 'C', status: 'active' },
      { name: '株洲硬质合金电子有限公司', industry: 'materials', rating: 'C', status: 'active' },
      { name: '九江浔阳电子科技有限公司', industry: 'electronics', rating: 'C', status: 'active' },
      { name: '宜昌三峡光电有限公司', industry: 'optoelectronics', rating: 'C', status: 'active' },
      { name: '遵义红城电子科技有限公司', industry: 'electronics', rating: 'C', status: 'active' },
      { name: '襄阳隆中科技有限公司', industry: 'technology', rating: 'C', status: 'active' },
      { name: '龙岩紫金矿业电子有限公司', industry: 'mining', rating: 'C', status: 'active' },
      { name: '德州鲁北电子科技有限公司', industry: 'electronics', rating: 'C', status: 'active' },
      {
        name: '柳州桂中机电设备有限公司',
        industry: 'manufacturing',
        rating: 'C',
        status: 'active',
      },
      {
        name: '南阳中光学电子有限公司',
        industry: 'optoelectronics',
        rating: 'C',
        status: 'active',
      },
      { name: '包头稀土应用电子有限公司', industry: 'materials', rating: 'C', status: 'active' },
      { name: '银川贺兰山电子有限公司', industry: 'electronics', rating: 'C', status: 'active' },
      { name: '临沂沂蒙电子科技有限公司', industry: 'electronics', rating: 'C', status: 'active' },
      { name: '嘉兴瑞丰电子科技有限公司', industry: 'electronics', rating: 'C', status: 'active' },
      { name: '衡阳南岳电子有限公司', industry: 'electronics', rating: 'C', status: 'active' },
      {
        name: '马鞍山钢城电子科技有限公司',
        industry: 'electronics',
        rating: 'C',
        status: 'active',
      },
      {
        name: '绍兴越城纺织电子有限公司',
        industry: 'manufacturing',
        rating: 'C',
        status: 'active',
      },
      { name: '上饶信州电子材料有限公司', industry: 'materials', rating: 'C', status: 'active' },
      { name: '宿迁宿豫电子科技有限公司', industry: 'electronics', rating: 'C', status: 'active' },
      { name: '六安皋城电子有限公司', industry: 'electronics', rating: 'C', status: 'active' },
      {
        name: '黄冈大别山电子科技有限公司',
        industry: 'electronics',
        rating: 'C',
        status: 'active',
      },
      // Dormant — 10 (6+ months no activity)
      { name: '哈尔滨冰城电子有限公司', industry: 'electronics', rating: 'D', status: 'inactive' },
      { name: '兰州金城科技有限公司', industry: 'technology', rating: 'D', status: 'inactive' },
      { name: '贵阳黔灵电子有限公司', industry: 'electronics', rating: 'D', status: 'inactive' },
      { name: '昆明春城电子材料有限公司', industry: 'materials', rating: 'D', status: 'inactive' },
      {
        name: '南宁邕城电子科技有限公司',
        industry: 'electronics',
        rating: 'D',
        status: 'inactive',
      },
      {
        name: '呼和浩特青城电子有限公司',
        industry: 'electronics',
        rating: 'D',
        status: 'inactive',
      },
      {
        name: '西宁高原电子科技有限公司',
        industry: 'electronics',
        rating: 'D',
        status: 'inactive',
      },
      { name: '拉萨雪域电子有限公司', industry: 'electronics', rating: 'D', status: 'inactive' },
      {
        name: '海口椰城电子科技有限公司',
        industry: 'electronics',
        rating: 'D',
        status: 'inactive',
      },
      {
        name: '乌鲁木齐天山电子有限公司',
        industry: 'electronics',
        rating: 'D',
        status: 'inactive',
      },
    ];

    for (const acc of accounts) {
      const id = await cmd(page, 'crm:create_account', {
        crm_acc_name: acc.name,
        crm_acc_industry: acc.industry,
        crm_acc_rating: acc.rating,
        crm_acc_status: acc.status,
      });
      ext.accounts[acc.name] = id;
    }
    console.log(`  Created ${accounts.length} C/D tier accounts`);
  });

  // ═════════════════════════════════════════════════════════════════════════
  // Phase 9: Contacts for C-tier (1 per account)
  // ═════════════════════════════════════════════════════════════════════════

  test('Phase 9: CRM — C-tier Contacts (30)', async ({ page }) => {
    const surnames = [
      '赵',
      '钱',
      '孙',
      '李',
      '周',
      '吴',
      '郑',
      '王',
      '冯',
      '陈',
      '褚',
      '卫',
      '蒋',
      '沈',
      '韩',
      '杨',
      '朱',
      '秦',
      '尤',
      '许',
      '何',
      '吕',
      '施',
      '张',
      '孔',
      '曹',
      '严',
      '华',
      '金',
      '魏',
    ];
    const givenNames = [
      '伟',
      '芳',
      '磊',
      '敏',
      '军',
      '丽',
      '涛',
      '婷',
      '强',
      '娜',
      '刚',
      '燕',
      '勇',
      '霞',
      '明',
      '艳',
      '杰',
      '萍',
      '峰',
      '红',
      '华',
      '平',
      '鹏',
      '琴',
      '建',
      '凤',
      '龙',
      '梅',
      '辉',
      '兰',
    ];
    const titles = ['采购', '总经理', '技术', '销售', '工程', '生产', '品质', '仓管'];

    const cAccounts = Object.entries(ext.accounts).filter(
      ([name]) =>
        ![
          '哈尔滨',
          '兰州',
          '贵阳',
          '昆明',
          '南宁',
          '呼和浩特',
          '西宁',
          '拉萨',
          '海口',
          '乌鲁木齐',
        ].some((city) => name.includes(city)),
    );

    let created = 0;
    for (const [accName, accId] of cAccounts) {
      const idx = created;
      const contactName =
        surnames[idx % surnames.length] + givenNames[(idx * 7 + 3) % givenNames.length];
      const id = await cmd(page, 'crm:create_contact', {
        crm_ct_account_id: accId,
        crm_ct_name: contactName,
        crm_ct_title: pick(titles),
        crm_ct_phone: `138${String(20000001 + idx).padStart(8, '0')}`,
        crm_ct_is_primary: true,
      });
      ext.contacts[contactName] = id;
      created++;
    }
    console.log(`  Created ${created} C-tier contacts`);
  });

  // ═════════════════════════════════════════════════════════════════════════
  // Phase 10: More Leads (60 more, total ~90 with base)
  // ═════════════════════════════════════════════════════════════════════════

  test('Phase 10: CRM — Additional Leads (60)', async ({ page }) => {
    const companies = [
      '温岭精工电子',
      '义乌创新科技',
      '慈溪宏达电器',
      '余姚舜宇光电',
      '诸暨浣纱电子',
      '海宁皮革城电子',
      '桐乡乌镇智能',
      '平湖独山港电子',
      '东阳影视电子',
      '兰溪兰江科技',
      '永康五金电子',
      '武义温泉电子',
      '磐安药材电子',
      '浦江水晶电子',
      '开化钱江源科技',
      '江山廿八都电子',
      '龙泉青瓷电子',
      '云和木玩电子',
      '庆元香菇电子',
      '景宁畲族电子',
      '松阳茶城电子',
      '遂昌金矿电子',
      '缙云仙都电子',
      '丽水绿谷科技',
      '舟山群岛电子',
      '定海海洋科技',
      '普陀渔港电子',
      '岱山海岛电子',
      '奉化溪口电子',
      '宁海前童科技',
      '象山渔山电子',
      '鄞州东钱湖科技',
      '北仑港区电子',
      '镇海化工电子',
      '江北慈城科技',
      '海曙月湖电子',
      '鹿城温州电子',
      '龙湾科技园电子',
      '瓯海梧田科技',
      '瑞安汽配电子',
      '乐清柳市电器',
      '苍南龙港电子',
      '平阳鳌江科技',
      '文成百丈漈电子',
      '泰顺廊桥电子',
      '洞头海岛科技',
      '永嘉楠溪电子',
      '德清莫干山科技',
      '安吉竹乡电子',
      '长兴太湖科技',
      '吴兴织里电子',
      '南浔古镇科技',
      '秀洲王江泾电子',
      '南湖红船科技',
      '桐庐富春电子',
      '建德新安科技',
      '淳安千岛湖电子',
      '临安天目科技',
      '富阳富春江电子',
      '萧山机场科技',
    ];

    const sources = ['exhibition', 'website', 'referral', 'cold_call', 'social_media', 'web_form'];
    const statuses = [
      'new',
      'new',
      'new',
      'contacted',
      'contacted',
      'contacted',
      'qualified',
      'qualified',
      'converted',
      'lost',
    ];
    const requirements = [
      '电源适配器 PCBA 加工',
      '智能门锁控制板',
      '工业传感器模块',
      '充电桩控制器',
      'LED 灯条驱动板',
      '空气净化器主板',
      '智能水表通信模块',
      '电动工具控制板',
      '安防摄像头 PCB',
      '物联网网关模块',
      '无线充电模组',
      '电机驱动板',
      '温控器主板',
      '医疗设备控制板',
      '光伏优化器 PCBA',
      '储能 BMS 模块',
    ];

    const surnames = ['赵', '钱', '孙', '李', '周', '吴', '郑', '王', '冯', '陈'];
    const givenNames = ['伟', '芳', '磊', '敏', '军', '涛', '强', '杰', '峰', '华'];

    for (let i = 0; i < 60; i++) {
      const status = statuses[i % statuses.length];
      const month =
        status === 'new'
          ? 17
          : status === 'contacted'
            ? randInt(14, 16)
            : status === 'qualified'
              ? randInt(11, 14)
              : status === 'converted'
                ? randInt(3, 10)
                : randInt(5, 12);
      const contactName =
        surnames[i % surnames.length] + givenNames[(i * 3 + 1) % givenNames.length];

      const id = await cmd(page, 'crm:create_lead', {
        crm_lead_company: companies[i],
        crm_lead_contact_name: contactName,
        crm_lead_contact_phone: `139${String(30000001 + i).padStart(8, '0')}`,
        crm_lead_source: pick(sources),
        crm_lead_industry: pick([
          'electronics',
          'manufacturing',
          'energy',
          'consumer_electronics',
          'automotive',
        ]),
        crm_lead_requirement: pick(requirements),
      });

      // Transition status
      if (status === 'contacted') {
        await cmd(page, 'crm:contact_lead', {}, id, 'update').catch(() => {});
      } else if (status === 'qualified') {
        await cmd(page, 'crm:contact_lead', {}, id, 'update').catch(() => {});
        await cmd(page, 'crm:qualify_lead', {}, id, 'update').catch(() => {});
      } else if (status === 'converted') {
        await cmd(page, 'crm:contact_lead', {}, id, 'update').catch(() => {});
        await cmd(page, 'crm:qualify_lead', {}, id, 'update').catch(() => {});
        await cmd(page, 'crm:convert_lead', {}, id, 'update').catch(() => {});
      } else if (status === 'lost') {
        await cmd(page, 'crm:contact_lead', {}, id, 'update').catch(() => {});
        await cmd(page, 'crm:lose_lead', {}, id, 'update').catch(() => {});
      }
    }
    console.log('  Created 60 additional leads');
  });

  // ═════════════════════════════════════════════════════════════════════════
  // Phase 11: More Opportunities (30 more, filling pipeline)
  // ═════════════════════════════════════════════════════════════════════════

  test('Phase 11: CRM — Additional Opportunities (30)', async ({ page }) => {
    // First, fetch existing A/B tier account IDs by querying the list
    const listResp = await page.request.get('/api/dynamic/crm_account/list?pageSize=100');
    const listBody = await listResp.json();
    const existingAccounts: Array<{ pid: string; crm_acc_name: string; crm_acc_rating: string }> =
      listBody?.data?.records || [];
    const abAccounts = existingAccounts.filter(
      (a) => a.crm_acc_rating === 'A' || a.crm_acc_rating === 'B',
    );

    if (abAccounts.length < 5) {
      console.warn('  Not enough A/B accounts, skipping extended opportunities');
      return;
    }

    const oppNames = [
      '传感器模组批量',
      '电源模块Q3',
      '控制板年度',
      'WiFi模组方案',
      '充电器PCBA',
      '安防主板批量',
      '智能锁控制板',
      '工控机主板',
      '通信模块定制',
      '电机驱动批量',
      'BMS小批量',
      '车载T-Box',
      '物联网终端',
      '储能逆变器',
      '光伏监控板',
      '医疗电源板',
      '铁路信号板',
      '军工电源模块',
      '矿用终端',
      '船用雷达板',
      '电梯控制板',
      '空调变频模块',
      '洗衣机主板',
      '净水器控制',
      '扫地机方案',
      '无人机飞控',
      '机器人驱动',
      '3D打印控制',
      'VR头显主板',
      '智能音箱方案',
    ];

    const stages = [
      'discovery',
      'discovery',
      'qualification',
      'qualification',
      'qualification',
      'proposal',
      'proposal',
      'proposal',
      'negotiation',
      'negotiation',
      'closed_won',
      'closed_won',
      'closed_won',
      'closed_won',
      'closed_won',
      'closed_won',
      'closed_won',
      'closed_won',
      'closed_won',
      'closed_won',
      'closed_lost',
      'closed_lost',
      'closed_lost',
      'closed_lost',
      'discovery',
      'qualification',
      'proposal',
      'negotiation',
      'closed_won',
      'closed_lost',
    ];

    const transitionMap: Record<string, string[]> = {
      discovery: [],
      qualification: ['crm:qualify_opportunity'],
      proposal: ['crm:qualify_opportunity', 'crm:advance_opp_to_proposal'],
      negotiation: [
        'crm:qualify_opportunity',
        'crm:advance_opp_to_proposal',
        'crm:advance_opp_to_negotiation',
      ],
      closed_won: [
        'crm:qualify_opportunity',
        'crm:advance_opp_to_proposal',
        'crm:advance_opp_to_negotiation',
        'crm:win_opportunity',
      ],
      closed_lost: [
        'crm:qualify_opportunity',
        'crm:advance_opp_to_proposal',
        'crm:lose_opportunity',
      ],
    };

    for (let i = 0; i < 30; i++) {
      const acc = abAccounts[i % abAccounts.length];
      const stage = stages[i];
      const amount = randInt(3, 80) * 10000; // 3万-80万
      const closeMonth = stage.startsWith('closed') ? randInt(2, 15) : randInt(17, 20);

      const id = await cmd(page, 'crm:create_opportunity', {
        crm_opp_name: oppNames[i],
        crm_opp_account_id: acc.pid,
        crm_opp_expected_amount: amount,
        crm_opp_expected_close_date: dateAt(closeMonth, randInt(1, 28)),
      });

      for (const transition of transitionMap[stage] || []) {
        await cmd(page, transition, {}, id, 'update').catch(() => {});
      }

      ext.opportunities[oppNames[i]] = id;
    }
    console.log('  Created 30 additional opportunities');
  });

  // ═════════════════════════════════════════════════════════════════════════
  // Phase 12: Bulk Activities (200+ records, making system feel alive)
  // ═════════════════════════════════════════════════════════════════════════

  test('Phase 12: CRM — Bulk Activities (200)', async ({ page }) => {
    const types = ['call', 'call', 'call', 'email', 'email', 'visit', 'meeting', 'wechat'];

    const callSubjects = [
      '电话询问项目进展',
      '确认交期和数量',
      '技术方案沟通',
      '价格确认',
      '回访客户使用情况',
      '催款跟进',
      '新产品推介',
      '售后问题跟进',
      '月度例行联络',
      '季度业绩复盘电话',
    ];
    const callContents = [
      '客户确认需求不变，预计下月下单。',
      '交期可按计划执行，客户无异议。',
      '技术方案需要微调，增加一道AOI检测工序。',
      '价格已确认，等待PO。',
      '客户反馈品质稳定，考虑增加订单量。',
      '上月货款已安排付款，预计本周到账。',
      '发送新品资料，客户表示感兴趣。',
      '上批次问题已解决，客户满意。',
      '保持联络，了解近期无新需求。',
      '回顾本季度合作情况，整体顺利。',
    ];
    const emailSubjects = [
      '发送报价单',
      '技术资料发送',
      '订单确认函',
      '品质报告',
      '月度对账单',
      '新品推介邮件',
      '展会邀请函',
      '年终感谢信',
    ];
    const emailContents = [
      '附件为最新报价单，请查收确认。有效期30天。',
      '技术方案文档已发送，请技术部评审。',
      '订单确认函已发送，请签字盖章回传。',
      '上批次品质报告：合格率99.5%。',
      '本月对账单已发送，请财务确认。',
      '新品推介资料，包含3款新型MCU方案。',
      '诚邀贵司莅临2025慕尼黑上海电子展我司展位参观。',
      '感谢2024年度的信任与支持，期待2025继续合作。',
    ];
    const visitSubjects = [
      '拜访客户工厂',
      '技术对接会议',
      '品质审核',
      '年度拜访',
      '项目验收',
      '新客户首次拜访',
    ];
    const visitContents = [
      '参观客户产线，了解产能和质量要求。',
      '与客户技术团队对接新项目方案。',
      '进行年度供应商品质审核，结果良好。',
      '年度拜访，总结合作成果，规划明年合作。',
      '项目验收通过，客户确认量产。',
      '首次拜访新客户，了解需求和决策流程。',
    ];
    const meetingSubjects = ['季度复盘会议', '项目启动会', '技术评审会', '年度合作规划会'];
    const meetingContents = [
      '本季度交付准时率96%，品质合格率99.2%。下季度重点跟进新能源项目。',
      '新项目正式启动，明确分工和时间节点。预计6周完成首批交付。',
      '技术评审通过，方案满足客户所有技术指标。进入报价阶段。',
      '签署年度合作框架，预计年度采购额增长20%。',
    ];

    // Generate 200 activities spread across 18 months
    for (let i = 0; i < 200; i++) {
      const type = pick(types);
      let subject: string, content: string;

      if (type === 'call') {
        subject = pick(callSubjects);
        content = pick(callContents);
      } else if (type === 'email') {
        subject = pick(emailSubjects);
        content = pick(emailContents);
      } else if (type === 'visit') {
        subject = pick(visitSubjects);
        content = pick(visitContents);
      } else if (type === 'meeting') {
        subject = pick(meetingSubjects);
        content = pick(meetingContents);
      } else {
        subject = '微信沟通项目进展';
        content = '通过微信确认最新需求和时间安排。';
      }

      // Spread across 18 months with increasing density toward recent months
      // More activities in recent months (realistic)
      const monthWeight = Math.random();
      let month: number;
      if (monthWeight < 0.15)
        month = randInt(0, 5); // older: 15%
      else if (monthWeight < 0.35)
        month = randInt(6, 10); // mid: 20%
      else if (monthWeight < 0.6)
        month = randInt(11, 14); // recent: 25%
      else month = randInt(15, 17); // latest: 40%

      // Working hours: mostly 9-17, some 18-20
      const hour = Math.random() < 0.85 ? randInt(9, 17) : randInt(18, 20);

      await cmd(page, 'crm:create_activity', {
        crm_act_type: type,
        crm_act_subject: subject,
        crm_act_content: content,
        crm_act_date: datetimeAt(month, randInt(1, 28), hour),
      });

      if (i % 50 === 0) console.log(`  Activities: ${i + 1}/200...`);
    }
    console.log('  Created 200 bulk activities');
  });

  // ═════════════════════════════════════════════════════════════════════════
  // Phase 13: Additional Campaigns (3 more)
  // ═════════════════════════════════════════════════════════════════════════

  test('Phase 13: CRM — Additional Campaigns (3)', async ({ page }) => {
    const campaigns = [
      {
        name: '2025 Q1邮件营销',
        type: 'email',
        startDate: dateAt(12, 15),
        endDate: dateAt(14, 31),
        budget: 5000,
        status: 'active',
        description:
          '针对120家存量客户发送新品推介邮件，配合官网SEO引流。目标：打开率25%，转化率5%。',
      },
      {
        name: '老客户转介绍奖励计划',
        type: 'referral',
        startDate: dateAt(13, 1),
        endDate: dateAt(17, 31),
        budget: 15000,
        status: 'active',
        description: '老客户成功推荐新客户签约，按首单金额的3%给予返利奖励。全年持续执行。',
      },
      {
        name: '2025慕尼黑上海电子展',
        type: 'exhibition',
        startDate: dateAt(18, 8),
        endDate: dateAt(18, 10),
        budget: 90000,
        status: 'planned',
        description:
          '计划参加2025慕尼黑上海电子展，展位升级为36平米，重点展示新能源和AI智能检测方案。',
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

      if (cpn.status === 'active') {
        await cmd(page, 'crm:activate_campaign', {}, id, 'update').catch(() => {});
      }

      ext.campaigns[cpn.name] = id;
      console.log(`  Created campaign: ${cpn.name}`);
    }
  });

  // ═════════════════════════════════════════════════════════════════════════
  // Phase 14: C-tier Opportunities (small deals, filling dashboard)
  // ═════════════════════════════════════════════════════════════════════════

  test('Phase 14: CRM — C-tier Small Opportunities (15)', async ({ page }) => {
    // Look up C-tier accounts from DB (so the phase is rerunnable when ext.accounts cache is empty).
    const listResp = await page.request.get('/api/dynamic/crm_account/list?pageSize=200');
    const listBody = await listResp.json();
    const dbAccounts: Array<{ pid: string; crm_acc_name: string; crm_acc_rating: string }> =
      listBody?.data?.records || [];
    const cTierAccounts = dbAccounts.filter((a) => a.crm_acc_rating === 'C');
    if (cTierAccounts.length < 5) {
      console.warn('  Not enough C-tier accounts, skipping C-tier opportunities');
      return;
    }
    const cAccountNames = cTierAccounts.map((a) => a.crm_acc_name);
    const accountIdByName: Record<string, string> = {};
    for (const a of cTierAccounts) accountIdByName[a.crm_acc_name] = a.pid;

    const oppTemplates = [
      { name: '小批量打样', amountMin: 10000, amountMax: 50000 },
      { name: '元器件采购', amountMin: 20000, amountMax: 80000 },
      { name: 'PCB批量', amountMin: 15000, amountMax: 60000 },
      { name: '控制板定制', amountMin: 30000, amountMax: 100000 },
      { name: '连接器供应', amountMin: 8000, amountMax: 40000 },
    ];

    const stages = [
      'discovery',
      'qualification',
      'proposal',
      'closed_won',
      'closed_won',
      'closed_won',
      'closed_won',
      'closed_won',
      'closed_won',
      'closed_won',
      'closed_lost',
      'closed_lost',
      'discovery',
      'qualification',
      'proposal',
    ];

    const transitionMap: Record<string, string[]> = {
      discovery: [],
      qualification: ['crm:qualify_opportunity'],
      proposal: ['crm:qualify_opportunity', 'crm:advance_opp_to_proposal'],
      closed_won: [
        'crm:qualify_opportunity',
        'crm:advance_opp_to_proposal',
        'crm:advance_opp_to_negotiation',
        'crm:win_opportunity',
      ],
      closed_lost: ['crm:qualify_opportunity', 'crm:lose_opportunity'],
    };

    for (let i = 0; i < 15 && i < cAccountNames.length; i++) {
      const accName = cAccountNames[i];
      const accId = accountIdByName[accName];
      const template = oppTemplates[i % oppTemplates.length];
      const stage = stages[i];
      const amount = randInt(template.amountMin, template.amountMax);

      const id = await cmd(page, 'crm:create_opportunity', {
        crm_opp_name: `${accName.slice(0, 4)}-${template.name}`,
        crm_opp_account_id: accId,
        crm_opp_expected_amount: amount,
        crm_opp_expected_close_date: dateAt(randInt(8, 18), randInt(1, 28)),
      });

      for (const transition of transitionMap[stage] || []) {
        await cmd(page, transition, {}, id, 'update').catch(() => {});
      }
    }
    console.log('  Created 15 C-tier opportunities');
  });

  // ═════════════════════════════════════════════════════════════════════════
  // Final Verification
  // ═════════════════════════════════════════════════════════════════════════

  test('Verification: Extended seed summary', async ({ page }) => {
    // Query actual counts from API
    const endpoints = [
      { name: 'Accounts', url: '/api/dynamic/crm_account/list?pageSize=1' },
      { name: 'Contacts', url: '/api/dynamic/crm_contact/list?pageSize=1' },
      { name: 'Leads', url: '/api/dynamic/crm_lead/list?pageSize=1' },
      { name: 'Opportunities', url: '/api/dynamic/crm_opportunity/list?pageSize=1' },
      { name: 'Activities', url: '/api/dynamic/crm_activity/list?pageSize=1' },
      { name: 'Campaigns', url: '/api/dynamic/crm_campaign/list?pageSize=1' },
      { name: 'Departments', url: '/api/dynamic/org_department/list?pageSize=1' },
      { name: 'Employees', url: '/api/dynamic/org_employee/list?pageSize=1' },
    ];

    console.log('\n═══════════════════════════════════════');
    console.log('  Extended Seed — Data Counts (from API)');
    console.log('═══════════════════════════════════════');

    for (const ep of endpoints) {
      const resp = await page.request.get(ep.url);
      const body = await resp.json().catch(() => ({}));
      const total = body?.data?.total ?? '?';
      console.log(`  ${ep.name.padEnd(15)} ${total}`);
    }
    console.log('═══════════════════════════════════════\n');

    // Verify minimum thresholds
    const accResp = await page.request.get('/api/dynamic/crm_account/list?pageSize=1');
    const accBody = await accResp.json();
    expect(accBody?.data?.total).toBeGreaterThanOrEqual(50);

    const leadResp = await page.request.get('/api/dynamic/crm_lead/list?pageSize=1');
    const leadBody = await leadResp.json();
    expect(leadBody?.data?.total).toBeGreaterThanOrEqual(70);

    const oppResp = await page.request.get('/api/dynamic/crm_opportunity/list?pageSize=1');
    const oppBody = await oppResp.json();
    expect(oppBody?.data?.total).toBeGreaterThanOrEqual(40);

    const actResp = await page.request.get('/api/dynamic/crm_activity/list?pageSize=1');
    const actBody = await actResp.json();
    expect(actBody?.data?.total).toBeGreaterThanOrEqual(200);
  });
});
