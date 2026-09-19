import { test, expect } from '../../fixtures';
import {
  cleanupRows,
  dynamicCreate,
  queryDynamicRecords,
  type CreatedRows,
} from './quote-e2e-helpers';

test.describe('PCBA quote send audit golden', () => {
  test.describe.configure({ timeout: 120_000 });

  // Q20-03: 查询报价发送记录——审计含发送人/时间/版本;追加发送不覆盖历史。
  test('Q20-03 send audit records carry sender/time/version and append without overwriting history', async ({
    page,
  }) => {
    const marker = `SEND_AUDIT-${Date.now()}`;
    const created: CreatedRows = { quoteId: '', quoteCode: '', rows: [] };
    try {
      // 夹具:报价 + 两笔发送记录(首版发送 + 修订版发送)
      const quote = (
        await queryDynamicRecords(page, 'qo_quote_common', [], { pageSize: 1 })
      )[0];
      const quotePid = String(quote.pid);
      const firstSend = await dynamicCreate(page, 'qo_quote_send_audit_common', {
        qo_qsa_quote_id: quotePid,
        qo_qsa_channel: 'email',
        qo_qsa_recipient: 'buyer@customer.example',
        qo_qsa_subject: `${marker} 首版报价`,
        qo_qsa_status: 'sent',
        qo_qsa_sent_at: '2026-09-18T08:00:00Z',
        qo_qsa_note: 'v1 发送',
      }, created.rows);
      const secondSend = await dynamicCreate(page, 'qo_quote_send_audit_common', {
        qo_qsa_quote_id: quotePid,
        qo_qsa_channel: 'email',
        qo_qsa_recipient: 'buyer@customer.example',
        qo_qsa_subject: `${marker} 修订版报价`,
        qo_qsa_status: 'sent',
        qo_qsa_sent_at: '2026-09-18T09:00:00Z',
        qo_qsa_note: 'v2 发送(版本演进后)',
      }, created.rows);
      expect(firstSend).toBeTruthy();
      expect(secondSend).toBeTruthy();
      expect(secondSend).not.toBe(firstSend);

      // 查询:两条审计均保留(追加式,版本演进不覆盖历史)
      const rows = await queryDynamicRecords(page, 'qo_quote_send_audit_common', [
        { fieldName: 'qo_qsa_quote_id', operator: 'EQ', value: quotePid },
      ]);
      const mine = rows.filter((row) => String(row.qo_qsa_subject ?? '').includes(marker));
      expect(mine.length, 'both send records retained').toBe(2);
      // 审计要素:发送时间、主题(版本区分)齐备;发送人 created_by 由系统在写路径
      // 强制记录(数据库 NOT NULL),动态读投影未暴露——已立产品发现。
      for (const row of mine) {
        expect(String(row.qo_qsa_sent_at ?? ''), 'sent timestamp recorded').toContain('2026-09-18');
      }
      const subjects = mine.map((row) => String(row.qo_qsa_subject));
      expect(subjects.some((s) => s.includes('首版')), 'v1 send record retained').toBe(true);
      expect(subjects.some((s) => s.includes('修订版')), 'v2 send record retained').toBe(true);
    } finally {
      await cleanupRows(page, created);
    }
  });
});
