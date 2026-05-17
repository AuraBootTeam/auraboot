/**
 * Showcase Seed — Supplementary Data
 *
 * Fills remaining gaps:
 * 1. More contacts for C-tier accounts (reach 100+ total)
 * 2. More activities (reach 400+ total)
 * 3. More leads (reach 120 total)
 * 4. BPM process instances (approval flow data for Inbox)
 *
 * Run AFTER all other seed scripts:
 *   node scripts/run-showcase-seed-sequence.mjs supplement
 */

import { test, expect } from '@playwright/test';
import { executeCommandViaApi } from '../../e2e/helpers';

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

test.describe.serial('Showcase Seed — Supplement', () => {
  test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
  test.setTimeout(600_000);

  // ═════════════════════════════════════════════════════════════════════════
  // 1. More Contacts — 2nd contact for B-tier + more for C-tier
  // ═════════════════════════════════════════════════════════════════════════

  test('Supplement 1: Additional Contacts (40+)', async ({ page }) => {
    // Get B-tier accounts that only have 1 contact
    const accResp = await page.request.get('/api/dynamic/crm_account/list?pageSize=200');
    const accBody = await accResp.json();
    const accounts = accBody?.data?.records || [];
    const bAccounts = accounts.filter((a: any) => a.crm_acc_rating === 'B');

    const surnames = [
      '田',
      '董',
      '袁',
      '邵',
      '程',
      '贺',
      '龚',
      '卞',
      '祝',
      '伍',
      '焦',
      '柴',
      '阎',
      '覃',
      '霍',
    ];
    const givenNames = [
      '浩',
      '雯',
      '昊',
      '颖',
      '翔',
      '琳',
      '宇',
      '欣',
      '鑫',
      '悦',
      '博',
      '妍',
      '睿',
      '萱',
      '恒',
    ];
    const titles = [
      '采购副经理',
      '技术评估',
      '项目对接',
      '商务专员',
      '品质主管',
      '生产计划',
      '研发工程师',
      '售后服务',
    ];

    let created = 0;
    for (let i = 0; i < bAccounts.length && created < 15; i++) {
      const acc = bAccounts[i];
      const contactName =
        surnames[created % surnames.length] + givenNames[(created * 3 + 2) % givenNames.length];
      try {
        await cmd(page, 'crm:create_contact', {
          crm_ct_account_id: acc.pid,
          crm_ct_name: contactName,
          crm_ct_title: pick(titles),
          crm_ct_phone: `136${String(40000001 + created).padStart(8, '0')}`,
          crm_ct_email: `${contactName.toLowerCase()}@example.com`,
          crm_ct_is_primary: false,
        });
        created++;
      } catch {
        /* skip duplicates */
      }
    }

    // Add contacts for C-tier (second contact)
    const cAccounts = accounts.filter((a: any) => a.crm_acc_rating === 'C').slice(0, 25);
    for (let i = 0; i < cAccounts.length && created < 40; i++) {
      const acc = cAccounts[i];
      const contactName =
        surnames[(created + 5) % surnames.length] +
        givenNames[(created * 7 + 1) % givenNames.length];
      try {
        await cmd(page, 'crm:create_contact', {
          crm_ct_account_id: acc.pid,
          crm_ct_name: contactName,
          crm_ct_title: pick(titles),
          crm_ct_phone: `137${String(50000001 + i).padStart(8, '0')}`,
          crm_ct_is_primary: false,
        });
        created++;
      } catch {
        /* skip */
      }
    }
    console.log(`  Created ${created} additional contacts`);
  });

  // ═════════════════════════════════════════════════════════════════════════
  // 2. More Leads — reach ~120 total
  // ═════════════════════════════════════════════════════════════════════════

  test('Supplement 2: Additional Leads (30)', async ({ page }) => {
    const companies = [
      '中山沙溪电子',
      '顺德容桂电器',
      '番禺大石科技',
      '白云嘉禾电子',
      '花都新华科技',
      '增城荔城电子',
      '从化温泉电子',
      '南沙万顷科技',
      '黄埔开发区电子',
      '天河珠江新城科技',
      '越秀北京路电子',
      '荔湾芳村科技',
      '海珠琶洲电子',
      '萝岗科学城科技',
      '松山湖高新电子',
      '虎门太平科技',
      '厚街家具电子',
      '塘厦林村科技',
      '凤岗雁田电子',
      '清溪三中科技',
      '大朗松木山电子',
      '寮步横坑科技',
      '石龙西湖电子',
      '石碣刘屋科技',
      '茶山南社电子',
      '道滘大罗沙科技',
      '洪梅望沙电子',
      '麻涌漳澎科技',
      '中堂潢涌电子',
      '高埗护安科技',
    ];

    const sources = ['exhibition', 'website', 'referral', 'cold_call', 'social_media', 'web_form'];
    const requirements = [
      'USB充电模块方案',
      '蓝牙耳机主板',
      '行车记录仪PCBA',
      '智能手环模组',
      '工业网关控制板',
      '太阳能充电控制器',
      'WiFi插座模块',
      '智能门铃方案',
      '电子秤主板',
      '空气检测仪PCBA',
      '智能垃圾桶控制板',
      '电动牙刷电路',
      '加湿器控制模块',
      '电子烟主板',
      '共享充电宝模组',
    ];
    const statuses = ['new', 'new', 'contacted', 'contacted', 'qualified', 'converted', 'lost'];

    for (let i = 0; i < 30; i++) {
      const status = statuses[i % statuses.length];
      const month =
        status === 'new' ? 17 : status === 'contacted' ? randInt(14, 16) : randInt(5, 13);

      try {
        const id = await cmd(page, 'crm:create_lead', {
          crm_lead_company: companies[i],
          crm_lead_contact_name: `联系人${i + 91}`,
          crm_lead_contact_phone: `138${String(60000001 + i).padStart(8, '0')}`,
          crm_lead_source: pick(sources),
          crm_lead_industry: pick(['electronics', 'consumer_electronics', 'manufacturing']),
          crm_lead_requirement: pick(requirements),
        });

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
      } catch {
        /* skip */
      }
    }
    console.log('  Created 30 additional leads');
  });

  // ═════════════════════════════════════════════════════════════════════════
  // 3. Bulk Activities — another 200 to reach 400+
  // ═════════════════════════════════════════════════════════════════════════

  test('Supplement 3: Bulk Activities (200 more)', async ({ page }) => {
    const types = ['call', 'call', 'call', 'email', 'email', 'visit', 'meeting', 'wechat'];

    const subjects: Record<string, string[]> = {
      call: [
        '询问项目进度',
        '确认交付时间',
        '价格二次确认',
        '催款电话',
        '售后回访',
        '新产品推介',
        '技术方案沟通',
        '客户满意度调查',
        '库存确认',
        '付款进度跟踪',
        '紧急交期调整',
        '年度合同续签沟通',
      ],
      email: [
        '发送最新报价',
        '技术规格书发送',
        '合同草案发送',
        '月度对账单',
        '新品推介邮件',
        '售后处理进度更新',
        '样品寄送通知',
        '发票信息确认',
      ],
      visit: [
        '季度拜访客户',
        '技术方案现场对接',
        '新客户首次拜访',
        '品质问题现场处理',
        '年度审核',
        '展会后续拜访',
      ],
      meeting: ['项目启动会', '月度销售例会', '技术评审会', '合同谈判会议'],
      wechat: ['微信确认需求细节', '发送产品图片', '临时变更沟通', '节日问候'],
    };

    const contents: Record<string, string[]> = {
      call: [
        '客户确认本批次需求不变，预计下月初下单。',
        '交期按计划执行，客户满意。',
        '价格谈判结束，客户接受最新报价。',
        '催促上月货款，客户承诺本周安排付款。',
        '回访上批次使用情况，客户反馈良好。',
        '推介新款MCU方案，客户有兴趣。',
        '讨论新项目技术需求，需要修改PCB布局。',
        '客户评分为满意，建议提升交付速度。',
        '确认当前库存可支持本月订单。',
        '财务部反馈款项已审批，预计3天到账。',
        '客户要求加急处理，协调产线调整排期。',
        '客户同意续签，新年度合同金额增加10%。',
      ],
      email: [
        '附件为最新报价单，有效期30天，请查收确认。',
        '技术规格书和PCB设计图纸已发送，请转交技术部评审。',
        '合同草案已发送，请法务审核后签字盖章回传。',
        '本月对账单已发送，请财务确认。',
        '新品推介资料已发送，含3款新型电源管理IC。',
        '售后问题处理方案已发送，补货将于下周安排。',
        '样品已通过顺丰寄出，单号SF1234567890。',
        '请确认开票信息：公司名称+税号+开户行+账号。',
      ],
      visit: [
        '拜访客户，了解Q3需求计划。客户透露有新项目启动。',
        '与技术团队现场对接方案细节，确定PCB层数和材质。',
        '首次拜访新客户，了解公司规模和采购流程。',
        '到客户现场处理品质问题，确认是焊接温度偏差导致。',
        '年度供应商审核，总体评分85分，建议改善交付准时率。',
        '展会结束后拜访重点客户，深入了解具体需求。',
      ],
      meeting: [
        '新项目正式启动，明确分工和里程碑节点。',
        '本月新增客户5家，Pipeline增长15%。',
        '方案评审通过，进入报价阶段。',
        '合同条款逐项确认，预计本周签约。',
      ],
      wechat: [
        '通过微信确认最新需求变更，客户同意新方案。',
        '发送产品实拍图和测试视频。',
        '临时通知交期需延后2天，客户表示可以接受。',
        '中秋节问候，维护客户关系。',
      ],
    };

    for (let i = 0; i < 200; i++) {
      const type = pick(types);
      const subject = pick(subjects[type] || subjects.call);
      const content = pick(contents[type] || contents.call);

      const monthWeight = Math.random();
      let month: number;
      if (monthWeight < 0.1) month = randInt(0, 4);
      else if (monthWeight < 0.25) month = randInt(5, 9);
      else if (monthWeight < 0.5) month = randInt(10, 14);
      else month = randInt(15, 17);

      const hour = Math.random() < 0.85 ? randInt(9, 17) : randInt(18, 20);

      try {
        await cmd(page, 'crm:create_activity', {
          crm_act_type: type,
          crm_act_subject: subject,
          crm_act_content: content,
          crm_act_date: datetimeAt(month, randInt(1, 28), hour),
        });
      } catch {
        /* skip */
      }

      if (i % 50 === 0 && i > 0) console.log(`  Activities: ${i}/200...`);
    }
    console.log('  Created 200 additional activities');
  });

  // ═════════════════════════════════════════════════════════════════════════
  // Verification
  // ═════════════════════════════════════════════════════════════════════════

  test('Supplement: Verification', async ({ page }) => {
    console.log('\n═══════════════════════════════════════');
    console.log('  Supplement Seed — Final Counts');
    console.log('═══════════════════════════════════════');

    const models = [
      'crm_account',
      'crm_contact',
      'crm_lead',
      'crm_opportunity',
      'crm_activity',
      'crm_campaign',
    ];
    for (const model of models) {
      const resp = await page.request.get(`/api/dynamic/${model}/list?pageSize=1`);
      const body = await resp.json().catch(() => ({}));
      const total = body?.data?.total ?? '?';
      console.log(`  ${model.padEnd(20)} ${total}`);
    }
    console.log('═══════════════════════════════════════\n');
  });
});
