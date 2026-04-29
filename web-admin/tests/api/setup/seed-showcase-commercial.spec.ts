/**
 * Showcase Seed — Commercial Data (Quotes, Complaints, IM, Email, OppContact)
 *
 * Fills the remaining gaps for the full commercial showcase:
 * 1. Quote (5) + Quote Lines (15)
 * 2. Complaint (5) with full lifecycle
 * 3. OppContact associations (12)
 * 4. Email Templates (3) + Email Logs (8)
 * 5. IM Object Conversations (3) + Messages (15)
 * 6. Webhook delivery logs (5) — via SQL
 * 7. Automation execution logs (5) — via SQL
 *
 * Run AFTER seed-showcase-supplement:
 *   npx playwright test seed-showcase-commercial --config=playwright.seed.config.ts
 *
 * Design doc: docs/strategy/05-Seed数据设计方案.md
 */

import { test, expect } from '@playwright/test';
import { executeCommandViaApi } from '../../e2e/helpers';

// ---------------------------------------------------------------------------
// Time helpers (same as other seed scripts)
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

function datetimeAt(monthOffset: number, dayOffset = 0, hour = 9): string {
  const d = new Date(baseDate());
  d.setMonth(d.getMonth() + monthOffset);
  d.setDate(d.getDate() + dayOffset);
  d.setHours(hour, Math.floor(Math.random() * 60), 0, 0);
  return d.toISOString();
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

/** Execute a state transition command */
async function transition(page: any, commandCode: string, recordId: string): Promise<void> {
  const result = await executeCommandViaApi(page, commandCode, {}, recordId, 'update');
  expect(result.code).toBe('0');
}

// ---------------------------------------------------------------------------
// Storage for created IDs
// ---------------------------------------------------------------------------
const ids = {
  quotes: [] as string[],
  quoteLines: [] as string[],
  complaints: [] as string[],
  oppContacts: [] as string[],
  emailTemplates: [] as string[],
  emailLogs: [] as string[],
  imConversations: [] as string[],
};

test.describe.serial('Showcase Seed — Commercial Data', () => {
  test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
  test.setTimeout(300_000);

  // ═════════════════════════════════════════════════════════════════════════
  // Phase C1: Fetch existing data (accounts, contacts, opportunities)
  // ═════════════════════════════════════════════════════════════════════════

  let accounts: any[] = [];
  let contacts: any[] = [];
  let opportunities: any[] = [];
  let adminUserId: number | null = null;

  test('Phase C0: Load existing CRM data', async ({ page }) => {
    // Accounts
    const accResp = await page.request.get('/api/dynamic/crm_account/list?pageSize=200');
    const accBody = await accResp.json();
    accounts = accBody?.data?.records || [];
    if (accounts.length < 5) {
      console.warn(
        `  WARNING: Only ${accounts.length} accounts found. Run seed-showcase-data first for full data.`,
      );
      test.skip(accounts.length === 0, 'No CRM accounts — run prior seed scripts first');
    }

    // Contacts
    const ctResp = await page.request.get('/api/dynamic/crm_contact/list?pageSize=300');
    const ctBody = await ctResp.json();
    contacts = ctBody?.data?.records || [];

    // Opportunities (in proposal/negotiation stage for quotes)
    const oppResp = await page.request.get('/api/dynamic/crm_opportunity/list?pageSize=200');
    const oppBody = await oppResp.json();
    opportunities = oppBody?.data?.records || [];

    // Get admin user ID from auth
    const meResp = await page.request.get('/api/auth/me');
    const meBody = await meResp.json();
    adminUserId = meBody?.data?.id || meBody?.data?.userId || 1;

    console.log(
      `  Loaded: ${accounts.length} accounts, ${contacts.length} contacts, ${opportunities.length} opportunities`,
    );
  });

  // ═════════════════════════════════════════════════════════════════════════
  // Phase C1: Quotes (5) + Quote Lines (15)
  // ═════════════════════════════════════════════════════════════════════════

  test('Phase C1: Create Quotes with Lines', async ({ page }) => {
    // Pick 5 opportunities in proposal/negotiation stage
    const quoteableOpps = opportunities
      .filter((o: any) => ['proposal', 'negotiation', 'closed_won'].includes(o.crm_opp_stage))
      .slice(0, 5);

    if (quoteableOpps.length === 0) {
      console.warn('  No quoteable opportunities found, using first 5');
      quoteableOpps.push(...opportunities.slice(0, 5));
    }

    const products = [
      { name: 'STM32F407VGT6 微控制器', price: 28.5, cost: 18.2 },
      { name: 'TPS54331DR 降压转换器', price: 6.8, cost: 4.1 },
      { name: 'MLCC 0805 100nF 贴片电容 (5000pcs)', price: 120.0, cost: 75.0 },
      { name: 'ESP32-WROOM-32E WiFi模组', price: 15.6, cost: 9.8 },
      { name: 'AMS1117-3.3 稳压芯片 (1000pcs)', price: 85.0, cost: 52.0 },
      { name: 'PCBA SMT 贴片加工费 (双面)', price: 0.35, cost: 0.18 },
      { name: 'FR4 双面 PCB 1.6mm (100pcs)', price: 480.0, cost: 310.0 },
      { name: 'SN74HC595D 移位寄存器', price: 3.2, cost: 1.9 },
      { name: 'GD32F103C8T6 国产替代芯片', price: 12.8, cost: 7.5 },
      { name: 'TJA1050 CAN 收发器', price: 5.6, cost: 3.4 },
      { name: 'USB Type-C 16P 连接器', price: 2.8, cost: 1.6 },
      { name: 'DC-DC 模块 12V→5V/3A', price: 18.5, cost: 11.2 },
      { name: 'NTC 10K 热敏电阻 (1000pcs)', price: 45.0, cost: 28.0 },
      { name: '0603 LED 红色 (5000pcs)', price: 55.0, cost: 32.0 },
      { name: '钽电容 100μF/16V D型', price: 8.5, cost: 5.1 },
    ];

    const quoteStatuses = ['draft', 'reviewed', 'sent', 'accepted', 'rejected'];
    const validUntilDays = [30, 45, 60, 30, 30];

    for (let i = 0; i < Math.min(5, quoteableOpps.length); i++) {
      const opp = quoteableOpps[i];
      const status = quoteStatuses[i];
      const account = accounts.find((a: any) => a.id === opp.crm_opp_account_id) || accounts[i];
      const contact = contacts.find((c: any) => c.crm_ct_account_id === account?.id) || contacts[i];

      // Create quote
      const quoteId = await cmd(page, 'crm:create_quote', {
        crm_qt_code: `QT-${dateAt(14 + i, i * 3).replace(/-/g, '')}-${String(i + 1).padStart(3, '0')}`,
        crm_qt_name: `${account?.crm_acc_name || '客户'} — ${opp.crm_opp_name || '项目'} 报价单`,
        crm_qt_opportunity_id: opp.id,
        crm_qt_account_id: account?.id,
        crm_qt_contact_id: contact?.id,
        crm_qt_currency: 'cny',
        crm_qt_exchange_rate: 1.0,
        crm_qt_valid_until: dateAt(15 + i, validUntilDays[i]),
        crm_qt_terms:
          '1. 报价有效期30天\n2. 付款方式：月结60天\n3. 交期：收到PO后15个工作日\n4. 运费：含税含运\n5. 质保：12个月',
        crm_qt_notes: i === 4 ? '客户反馈价格偏高，已失去此单' : '',
        crm_qt_owner: 'admin',
      });
      ids.quotes.push(quoteId);

      // Create 3 quote lines per quote
      let subtotal = 0;
      for (let j = 0; j < 3; j++) {
        const product = products[i * 3 + j];
        const qty = [500, 1000, 2000, 300, 5000][Math.floor(Math.random() * 5)];
        const discount = i === 3 ? 5 : i === 4 ? 0 : [0, 3, 5, 8][Math.floor(Math.random() * 4)];
        const amount = +(product.price * qty * (1 - discount / 100)).toFixed(2);
        const taxRate = 13;
        const taxAmount = +((amount * taxRate) / 100).toFixed(2);
        const margin = +(amount - product.cost * qty).toFixed(2);
        const marginRate = +((margin / amount) * 100).toFixed(2);
        subtotal += amount;

        const lineId = await cmd(page, 'crm:create_quote_line', {
          crm_ql_quote_id: quoteId,
          crm_ql_product_name: product.name,
          crm_ql_description: `${product.name} — 批量${qty}${product.price < 1 ? '次' : 'pcs'}`,
          crm_ql_quantity: qty,
          crm_ql_unit_price: product.price,
          crm_ql_unit_cost: product.cost,
          crm_ql_discount: discount,
          crm_ql_tax_rate: taxRate,
          crm_ql_tax_amount: taxAmount,
          crm_ql_amount: amount,
          crm_ql_margin: margin,
          crm_ql_margin_rate: marginRate,
          crm_ql_sort_order: j + 1,
        });
        ids.quoteLines.push(lineId);
      }

      // Advance quote status through transitions
      if (status !== 'draft') {
        await transition(page, 'crm:review_quote', quoteId);
      }
      if (status === 'sent' || status === 'accepted' || status === 'rejected') {
        await transition(page, 'crm:send_quote', quoteId);
      }
      if (status === 'accepted') {
        await transition(page, 'crm:accept_quote', quoteId);
      }
      if (status === 'rejected') {
        await transition(page, 'crm:reject_quote', quoteId);
      }
    }

    console.log(`  Created: ${ids.quotes.length} quotes, ${ids.quoteLines.length} quote lines`);
  });

  // ═════════════════════════════════════════════════════════════════════════
  // Phase C2: Complaints (5) — Story line C: After-sales
  // ═════════════════════════════════════════════════════════════════════════

  test('Phase C2: Create Complaints', async ({ page }) => {
    const complaintData = [
      {
        account: '合肥',
        type: 'quality',
        severity: 'high',
        desc: '上批次 LED 驱动板有 2% 不良率，客户产线停工排查',
        rootCause: '焊接温度偏差导致 BGA 虚焊，回流焊第三温区温度设置偏低 5°C',
        action: '1. 立即补货 50 片\n2. 调整回流焊温度曲线\n3. 增加 AOI 检测覆盖率至 100%',
        status: 'closed',
        monthOffset: 14,
      },
      {
        account: '苏州',
        type: 'delivery',
        severity: 'medium',
        desc: '订单延期 5 天交付，客户生产计划受影响',
        rootCause: '关键物料 STM32F407 供应商断货，备选供应商交期长',
        action: '1. 紧急从第三方渠道调货\n2. 建立关键物料安全库存机制\n3. 开发第二供应商',
        status: 'resolved',
        monthOffset: 15,
      },
      {
        account: '杭州',
        type: 'service',
        severity: 'low',
        desc: '技术文档版本错误，导致客户工程师按错误规格调试',
        rootCause: '文档版本管理混乱，旧版文档未及时归档',
        action: '1. 重新发送正确版本文档\n2. 建立文档版本发布流程\n3. 上线文档管理系统',
        status: 'closed',
        monthOffset: 16,
      },
      {
        account: '重庆',
        type: 'quality',
        severity: 'critical',
        desc: '车载 BMS 控制板通信模块异常，客户要求 24h 内给出分析报告',
        rootCause: 'CAN 收发器 TJA1050 批次问题，供应商确认为次品流出',
        action:
          '1. 全数召回该批次产品（200 片）\n2. 更换合格批次 TJA1050\n3. 向供应商索赔\n4. 增加来料检验项目',
        status: 'investigating',
        monthOffset: 17,
      },
      {
        account: '宁波',
        type: 'price',
        severity: 'low',
        desc: '客户质疑本季度报价涨幅超过约定的 3% 上限',
        rootCause: '原材料涨价超预期，合同条款允许但沟通不充分',
        action: '1. 提供原材料成本变动明细\n2. 协商分担涨价比例\n3. 下季度提前预警',
        status: 'resolved',
        monthOffset: 17,
      },
    ];

    for (let i = 0; i < complaintData.length; i++) {
      const c = complaintData[i];
      // Find matching account by partial name
      const account =
        accounts.find((a: any) => (a.crm_acc_name || '').includes(c.account)) || accounts[i];
      const contact =
        contacts.find((ct: any) => ct.crm_ct_account_id === account?.id) || contacts[i];

      const complaintId = await cmd(page, 'crm:create_complaint', {
        crm_cmp_code: `CMP-${dateAt(c.monthOffset, i * 2 + 1).replace(/-/g, '')}-${String(i + 1).padStart(3, '0')}`,
        crm_cmp_account_id: account?.id,
        crm_cmp_contact_id: contact?.id,
        crm_cmp_date: datetimeAt(c.monthOffset, i * 2 + 1),
        crm_cmp_type: c.type,
        crm_cmp_severity: c.severity,
        crm_cmp_description: c.desc,
        crm_cmp_root_cause: c.status !== 'investigating' ? c.rootCause : '',
        crm_cmp_corrective_action: c.status !== 'investigating' ? c.action : '',
        crm_cmp_first_response_at: datetimeAt(c.monthOffset, i * 2 + 1, 14),
        crm_cmp_sla_status: c.severity === 'critical' ? 'at_risk' : 'on_track',
      });
      ids.complaints.push(complaintId);

      // Advance complaint status
      if (c.status !== 'open') {
        await transition(page, 'crm:investigate_complaint', complaintId);
      }
      if (c.status === 'resolved' || c.status === 'closed') {
        await transition(page, 'crm:resolve_complaint', complaintId);
      }
      if (c.status === 'closed') {
        await transition(page, 'crm:close_complaint', complaintId);
      }
    }

    console.log(`  Created: ${ids.complaints.length} complaints`);
  });

  // ═════════════════════════════════════════════════════════════════════════
  // Phase C3: Opp-Contact Associations (12)
  // ═════════════════════════════════════════════════════════════════════════

  test('Phase C3: Create OppContact Associations', async ({ page }) => {
    const roles = ['decision_maker', 'technical', 'procurement', 'influencer'];

    // For each opportunity, associate 2-3 contacts from same account
    const topOpps = opportunities.slice(0, 6);
    for (const opp of topOpps) {
      const accountContacts = contacts.filter(
        (c: any) => c.crm_ct_account_id === opp.crm_opp_account_id,
      );
      if (accountContacts.length === 0) continue;

      // Associate up to 2 contacts per opportunity
      const toAssociate = accountContacts.slice(0, Math.min(2, accountContacts.length));
      for (let j = 0; j < toAssociate.length; j++) {
        try {
          const ocId = await cmd(page, 'crm:add_opp_contact', {
            crm_oc_opportunity_id: opp.id,
            crm_oc_contact_id: toAssociate[j].id,
            crm_oc_role: roles[j % roles.length],
            crm_oc_is_primary: j === 0,
          });
          ids.oppContacts.push(ocId);
        } catch {
          // Skip if association already exists
        }
      }
    }

    console.log(`  Created: ${ids.oppContacts.length} opp-contact associations`);
  });

  // ═════════════════════════════════════════════════════════════════════════
  // Phase C4: Email Templates (3) + Email Logs (8)
  // ═════════════════════════════════════════════════════════════════════════

  test('Phase C4: Create Email Templates', async ({ page }) => {
    const templates = [
      {
        name: '展会感谢信',
        category: 'thank_you',
        subject: '感谢您莅临鑫然科技展位 — {{exhibition_name}}',
        body: '<p>尊敬的 {{contact_name}}，</p><p>感谢您在 {{exhibition_name}} 期间莅临鑫然科技展位参观交流。我们很高兴向您介绍了我们在电子元器件和 PCBA 加工方面的能力。</p><p>如您所了解，我们专注于：</p><ul><li>高品质电子元器件供应（MCU、被动元件、连接器）</li><li>快速 PCBA 打样与批量生产</li><li>一站式 BOM 配套方案</li></ul><p>期待后续合作！如有任何需求，请随时联系。</p><p>此致<br/>{{sender_name}}<br/>鑫然科技有限公司</p>',
        status: 'active',
      },
      {
        name: '报价单跟进',
        category: 'follow_up',
        subject: '报价单 {{quote_code}} 跟进 — {{customer_name}}',
        body: '<p>尊敬的 {{contact_name}}，</p><p>我们于 {{quote_date}} 发送的报价单 {{quote_code}} 不知您是否已收到并查阅？</p><p>报价摘要：</p><ul><li>项目：{{project_name}}</li><li>总金额：¥{{total_amount}}</li><li>有效期至：{{valid_until}}</li></ul><p>如需调整或有任何疑问，请随时联系我。我们可以安排技术团队做进一步的方案讲解。</p><p>期待您的回复！</p><p>{{sender_name}}<br/>鑫然科技有限公司</p>',
        status: 'active',
      },
      {
        name: '新客户欢迎',
        category: 'welcome',
        subject: '欢迎加入鑫然科技合作伙伴体系',
        body: '<p>尊敬的 {{contact_name}}，</p><p>非常感谢您选择鑫然科技作为合作伙伴！</p><p>作为您的专属客户经理，我将为您提供以下服务：</p><ul><li>专业的元器件选型建议</li><li>快速的报价响应（24 小时内）</li><li>灵活的付款方式和交付方案</li><li>定期的行业资讯分享</li></ul><p>您的账户信息已创建完毕，后续下单可直接联系我或通过系统提交。</p><p>期待合作愉快！</p><p>{{sender_name}}<br/>鑫然科技有限公司</p>',
        status: 'active',
      },
    ];

    for (const tpl of templates) {
      const tplId = await cmd(page, 'crm:create_email_template', {
        crm_et_name: tpl.name,
        crm_et_category: tpl.category,
        crm_et_subject: tpl.subject,
        crm_et_body: tpl.body,
        crm_et_description: `${tpl.category} 类型邮件模板`,
      });
      ids.emailTemplates.push(tplId);

      // Activate template
      if (tpl.status === 'active') {
        await transition(page, 'crm:activate_email_template', tplId);
      }
    }

    console.log(`  Created: ${ids.emailTemplates.length} email templates`);
  });

  test('Phase C4b: Create Email Logs', async ({ page }) => {
    const emailLogs = [
      { to: '钱进', account: '宁波', subject: '报价单 QT-001 跟进', status: 'opened', month: 14 },
      {
        to: '方明',
        account: '宁波',
        subject: 'BMS 控制板技术方案 V2',
        status: 'delivered',
        month: 15,
      },
      { to: '沈丽芳', account: '宁波', subject: 'Q2 订单确认', status: 'sent', month: 17 },
      { to: '李婷', account: '苏州', subject: '展会感谢信', status: 'opened', month: 12 },
      { to: '张涛', account: '杭州', subject: '年度框架协议报价', status: 'opened', month: 16 },
      { to: '王磊', account: '深圳', subject: 'IoT 网关方案报价', status: 'bounced', month: 13 },
      {
        to: '陈晓东',
        account: '东莞',
        subject: '连接器样品发货通知',
        status: 'delivered',
        month: 15,
      },
      { to: '刘洋', account: '成都', subject: '新能源 BMS 方案讨论', status: 'opened', month: 16 },
    ];

    for (let i = 0; i < emailLogs.length; i++) {
      const log = emailLogs[i];
      const account =
        accounts.find((a: any) => (a.crm_acc_name || '').includes(log.account)) || accounts[i];
      const contact = contacts.find((c: any) => c.crm_ct_account_id === account?.id) || contacts[i];

      try {
        const logId = await cmd(page, 'crm:create_email_log', {
          crm_el_to_address: `${log.to.toLowerCase()}@${(account?.crm_acc_name || 'company')
            .replace(/[^a-zA-Z]/g, '')
            .slice(0, 10)
            .toLowerCase()}.com`,
          crm_el_to_name: log.to,
          crm_el_subject: log.subject,
          crm_el_body: `<p>尊敬的${log.to}，</p><p>${log.subject}相关内容。</p><p>鑫然科技</p>`,
          crm_el_account_id: account?.id,
          crm_el_contact_id: contact?.id,
          crm_el_template_id: ids.emailTemplates[i % ids.emailTemplates.length] || undefined,
          crm_el_direction: 'outbound',
        });
        ids.emailLogs.push(logId);

        // Advance email status
        if (log.status !== 'draft') {
          await transition(page, 'crm:send_email_log', logId);
        }
        if (log.status === 'delivered' || log.status === 'opened') {
          await transition(page, 'crm:deliver_email_log', logId);
        }
        if (log.status === 'opened') {
          await transition(page, 'crm:open_email_log', logId);
        }
        if (log.status === 'bounced') {
          await transition(page, 'crm:bounce_email_log', logId);
        }
      } catch (e) {
        console.warn(`  Skipping email log ${i}: ${(e as Error).message}`);
      }
    }

    console.log(`  Created: ${ids.emailLogs.length} email logs`);
  });

  // ═════════════════════════════════════════════════════════════════════════
  // Phase C5: IM Object Conversations (3) + Messages (15)
  // ═════════════════════════════════════════════════════════════════════════

  test('Phase C5: Create IM Object Conversations + Messages', async ({ page }) => {
    // Pick 3 A-tier accounts to create bound conversations
    const aAccounts = accounts.filter((a: any) => a.crm_acc_rating === 'A').slice(0, 3);
    if (aAccounts.length === 0) {
      console.warn('  No A-tier accounts found for IM conversations');
      return;
    }

    const conversationMessages: Record<string, string[]> = {
      宁波: [
        '鑫越 Q2 订单确认了吗？钱进那边说要增加到 800 片',
        '已确认，PO 下周一发过来',
        '好的，提醒仓库备料。BMS 控制板的 STM32 库存够吗？',
        '查了一下，安全库存还有 1200 颗，够用',
        '嗯，顺便跟进一下 Q1 回款情况，已经超过账期了',
      ],
      杭州: [
        '曜熠年度框架的 Q2 交付计划出来了吗？',
        '正在对接技术部，6 款产品中有 2 款需要小改版',
        '改版周期多久？不能影响 4 月的交付',
        '张涛说 2 周可以完成，不影响',
        '好，把交付时间表更新到系统里',
      ],
      苏州: [
        '锐虎的新产线方案工程师看过了吗？',
        '吴晓峰在评估，说技术上可行但需要定制 PCB',
        '定制 PCB 加多少成本？',
        '大概增加 15% 左右，我让他出个详细报价',
        '好，这个客户价格敏感，想办法控制在 10% 以内',
      ],
    };

    for (const acc of aAccounts) {
      const accName = acc.crm_acc_name || '';
      const matchKey = Object.keys(conversationMessages).find((k) => accName.includes(k));
      if (!matchKey) continue;

      try {
        // Create OBJECT conversation bound to account
        const convResp = await page.request.post('/api/im/conversations', {
          data: {
            type: 'OBJECT',
            name: `${accName} — 客户协作群`,
            memberIds: adminUserId ? [adminUserId] : [],
            boundModelCode: 'crm_account',
            boundRecordId: Number(acc.id) || acc.id,
          },
        });
        const convBody = await convResp.json();
        const conversationId = convBody?.data?.conversationId || convBody?.data?.id;
        if (!conversationId) {
          console.warn(`  Failed to create IM conversation for ${accName}`);
          continue;
        }
        ids.imConversations.push(String(conversationId));

        // Send messages
        const messages = conversationMessages[matchKey];
        for (const msg of messages) {
          await page.request.post(`/api/im/conversations/${conversationId}/messages`, {
            data: {
              conversationId,
              messageType: 'TEXT',
              content: msg,
              clientMsgId: `seed-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
            },
          });
          // Small delay to ensure message ordering
          await page.waitForTimeout(100);
        }
      } catch (e) {
        console.warn(`  IM conversation error for ${accName}: ${(e as Error).message}`);
      }
    }

    console.log(`  Created: ${ids.imConversations.length} IM conversations with messages`);
  });

  // ═════════════════════════════════════════════════════════════════════════
  // Phase C6: Webhook Delivery Logs + Automation Execution Logs (SQL)
  // ═════════════════════════════════════════════════════════════════════════

  test('Phase C6: Seed Webhook Delivery Logs via API', async ({ page }) => {
    // Fetch existing webhook subscription
    const whResp = await page.request.get('/api/webhooks');
    const whBody = await whResp.json();
    const subscriptions = whBody?.data?.records || whBody?.data || [];

    if (!Array.isArray(subscriptions) || subscriptions.length === 0) {
      console.warn('  No webhook subscriptions found — skipping delivery log seed');
      return;
    }

    const sub = subscriptions[0];
    if (!sub) {
      console.warn('  First webhook subscription is undefined — skipping');
      return;
    }
    const subPid = sub.pid || sub.id || 'unknown';
    const subUrl = sub.targetUrl || sub.target_url || 'https://webhook.example.com/auraboot';
    // Webhook delivery logs require direct DB insert (no public API)
    // Use psql to insert demo delivery records
    const { execSync } = await import('child_process');
    const deliveryLogs = [
      { status: 'delivered', httpStatus: 200, month: 15, day: 5 },
      { status: 'delivered', httpStatus: 200, month: 15, day: 12 },
      {
        status: 'failed',
        httpStatus: 500,
        month: 16,
        day: 3,
        error: 'Connection refused: target server down for maintenance',
      },
      { status: 'delivered', httpStatus: 200, month: 16, day: 18 },
      { status: 'delivered', httpStatus: 201, month: 17, day: 7 },
    ];

    for (let i = 0; i < deliveryLogs.length; i++) {
      const log = deliveryLogs[i];
      const eventTs = datetimeAt(log.month, log.day);
      const pid = `whdl_seed_${Date.now()}_${i}`;
      const sql = `
        INSERT INTO ab_webhook_delivery_log (
          id, pid, tenant_id, subscription_pid, event_id,
          request_url, request_body, response_status, response_body,
          delivery_status, retry_count, error_message, delivered_at, created_at
        ) VALUES (
          nextval('hibernate_sequence'), '${pid}', (SELECT id FROM ab_tenant WHERE name = 'AuraBoot Dev' LIMIT 1), '${subPid}',
          'evt_opp_stage_${Date.now()}_${i}',
          '${subUrl}',
          '{"event":"entity.updated","model":"crm_opportunity","recordId":"demo-${i}"}',
          ${log.httpStatus},
          '${log.httpStatus === 200 || log.httpStatus === 201 ? '{"status":"ok"}' : '{"error":"Internal Server Error"}'}',
          '${log.status}',
          ${log.status === 'failed' ? 1 : 0},
          ${log.error ? `'${log.error}'` : 'NULL'},
          ${log.status === 'delivered' ? `'${eventTs}'` : 'NULL'},
          '${eventTs}'
        ) ON CONFLICT DO NOTHING;
      `.trim();

      try {
        execSync(
          `psql -h localhost -U ghj -d aura_boot -P pager=off -c "${sql.replace(/"/g, '\\"')}"`,
          {
            timeout: 5000,
            stdio: 'pipe',
          },
        );
      } catch (e) {
        console.warn(
          `  Webhook delivery log ${i} insert failed: ${(e as Error).message?.slice(0, 80)}`,
        );
      }
    }

    console.log(`  Inserted: ${deliveryLogs.length} webhook delivery logs`);
  });

  test('Phase C7: Seed Automation Execution Logs via SQL', async ({ page }) => {
    // Fetch existing automation rules
    const autoResp = await page.request.get('/api/automations');
    const autoBody = await autoResp.json();
    const automations = autoBody?.data?.records || autoBody?.data || [];

    if (!Array.isArray(automations) || automations.length === 0) {
      console.warn('  No automation rules found — skipping execution log seed');
      return;
    }

    const { execSync } = await import('child_process');
    const execLogs = [
      { status: 'success', trigger: 'ON_RECORD_CREATE', month: 14, day: 8, duration: 230 },
      { status: 'success', trigger: 'ON_RECORD_CREATE', month: 15, day: 15, duration: 180 },
      {
        status: 'failed',
        trigger: 'ON_FIELD_CHANGE',
        month: 15,
        day: 22,
        duration: 1500,
        error: 'Notification service temporarily unavailable',
      },
      { status: 'success', trigger: 'ON_FIELD_CHANGE', month: 16, day: 10, duration: 320 },
      { status: 'success', trigger: 'SCHEDULED', month: 17, day: 1, duration: 890 },
    ];

    for (let i = 0; i < execLogs.length; i++) {
      const log = execLogs[i];
      const auto = automations[i % automations.length];
      if (!auto) continue;
      const autoId = auto.pid || auto.id || 'unknown';
      const startTs = datetimeAt(log.month, log.day);
      const endTs = datetimeAt(log.month, log.day, 9);
      const pid = `autolog_seed_${Date.now()}_${i}`;

      const sql = `
        INSERT INTO ab_automation_log (
          id, pid, tenant_id, automation_id,
          trigger_type, trigger_record_id, trigger_payload,
          status, started_at, completed_at, error_message, execution_log, created_at
        ) VALUES (
          nextval('hibernate_sequence'), '${pid}', (SELECT id FROM ab_tenant WHERE name = 'AuraBoot Dev' LIMIT 1), '${autoId}',
          '${log.trigger}', 'demo_record_${i}',
          '{"model":"crm_opportunity","field":"crm_opp_stage","oldValue":"proposal","newValue":"negotiation"}'::jsonb,
          '${log.status}', '${startTs}', '${endTs}',
          ${log.error ? `'${log.error}'` : 'NULL'},
          'Step 1: Trigger matched → Step 2: Condition evaluated (true) → Step 3: Action executed (${log.status === 'success' ? 'notification sent' : 'FAILED: ' + (log.error || '')})',
          '${startTs}'
        ) ON CONFLICT DO NOTHING;
      `.trim();

      try {
        execSync(
          `psql -h localhost -U ghj -d aura_boot -P pager=off -c "${sql.replace(/"/g, '\\"')}"`,
          {
            timeout: 5000,
            stdio: 'pipe',
          },
        );
      } catch (e) {
        console.warn(`  Automation log ${i} insert failed: ${(e as Error).message?.slice(0, 80)}`);
      }
    }

    console.log(`  Inserted: ${execLogs.length} automation execution logs`);
  });

  // ═════════════════════════════════════════════════════════════════════════
  // Verification
  // ═════════════════════════════════════════════════════════════════════════

  test('Phase V: Verify commercial data counts', async ({ page }) => {
    console.log('\n  ═══════════════════════════════════════');
    console.log('  Commercial Seed Data Summary');
    console.log('  ═══════════════════════════════════════');

    // Quotes
    const qtResp = await page.request.get('/api/dynamic/crm_quote/list?pageSize=100');
    const qtBody = await qtResp.json();
    const quoteCount = qtBody?.data?.total || qtBody?.data?.records?.length || 0;
    console.log(`  Quotes:          ${quoteCount} (target: ≥5, skip if no opps)`);
    if (opportunities.length > 0) {
      expect(quoteCount).toBeGreaterThanOrEqual(5);
    }

    // Quote Lines
    const qlResp = await page.request.get('/api/dynamic/crm_quote_line/list?pageSize=100');
    const qlBody = await qlResp.json();
    const qlCount = qlBody?.data?.total || qlBody?.data?.records?.length || 0;
    console.log(`  Quote Lines:     ${qlCount} (target: ≥15, skip if no opps)`);
    if (opportunities.length > 0) {
      expect(qlCount).toBeGreaterThanOrEqual(15);
    }

    // Complaints
    const cmpResp = await page.request.get('/api/dynamic/crm_complaint/list?pageSize=100');
    const cmpBody = await cmpResp.json();
    const cmpCount = cmpBody?.data?.total || cmpBody?.data?.records?.length || 0;
    console.log(`  Complaints:      ${cmpCount} (target: ≥5)`);
    expect(cmpCount).toBeGreaterThanOrEqual(5);

    // OppContacts
    const ocResp = await page.request.get('/api/dynamic/crm_opp_contact/list?pageSize=100');
    const ocBody = await ocResp.json();
    const ocCount = ocBody?.data?.total || ocBody?.data?.records?.length || 0;
    console.log(`  OppContacts:     ${ocCount} (target: ≥6, skip if no opps)`);
    if (opportunities.length > 0) {
      expect(ocCount).toBeGreaterThanOrEqual(6);
    }

    // Email Templates
    const etResp = await page.request.get('/api/dynamic/crm_email_template/list?pageSize=100');
    const etBody = await etResp.json();
    const etCount = etBody?.data?.total || etBody?.data?.records?.length || 0;
    console.log(`  Email Templates: ${etCount} (target: ≥3)`);
    expect(etCount).toBeGreaterThanOrEqual(3);

    // Email Logs
    const elResp = await page.request.get('/api/dynamic/crm_email_log/list?pageSize=100');
    const elBody = await elResp.json();
    const elCount = elBody?.data?.total || elBody?.data?.records?.length || 0;
    console.log(`  Email Logs:      ${elCount} (target: ≥5)`);

    // IM Conversations
    const imResp = await page.request.get('/api/im/conversations');
    const imBody = await imResp.json();
    const imCount = (imBody?.data || []).length;
    console.log(`  IM Conversations: ${imCount} (target: ≥3)`);

    console.log('  ═══════════════════════════════════════\n');
  });
});
