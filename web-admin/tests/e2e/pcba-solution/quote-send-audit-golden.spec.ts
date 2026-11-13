import { test, expect } from '../../fixtures';
import {
  cleanupRows,
  executeCommand,
  seedBomPriceManualReviewQuote,
  cleanupImmutableQuoteArtifacts,
  queryDynamicRecords,
  type CreatedRows,
} from './quote-e2e-helpers';

test.describe('PCBA quote send audit golden', () => {
  test.describe.configure({ timeout: 120_000 });

  // Q20-03: 查询报价发送记录——审计含发送人/时间/版本;追加发送不覆盖历史。
  test('Q20-03 send audit records carry sender/time/version and append without overwriting history', async ({
    page,
  }) => {
    const created = await seedBomPriceManualReviewQuote(page);
    try {
      const sent: Record<string, any>[] = [];
      const documents = new Map<string, number>();
      for (const version of [1, 2]) {
        const generated = await executeCommand(page, 'qo_quote_common:generate_document', {}, created.quoteId, 'update');
        expect(Number(generated.documentVersion), 'real document generation advances the version').toBe(version);
        const history = await queryDynamicRecords(page, 'qo_quote_document_common', [
          { fieldName: 'qo_qd_quote_id', operator: 'EQ', value: created.quoteId },
        ]);
        for (const row of history) {
          documents.set(String(row.pid), Number(row.qo_qd_version));
          if (!created.rows.some(owned => owned.pid === String(row.pid)))
            created.rows.push({ model: 'qo_quote_document_common', pid: String(row.pid) });
        }
        const artifacts = await queryDynamicRecords(page, 'ab_document_artifact', [
          { fieldName: 'ab_da_source_record_pid', operator: 'EQ', value: created.quoteId },
        ]);
        for (const row of artifacts) {
          if (!created.rows.some(owned => owned.pid === String(row.pid)))
            created.rows.push({ model: 'ab_document_artifact', pid: String(row.pid) });
        }
        // Approval is a fixture precondition; the real send handler owns every audit row.
        const approved = await page.request.put(`/api/dynamic/qo_quote_common/${created.quoteId}`, {
          data: { qo_quote_status: 'approved' },
        });
        expect(approved.ok()).toBe(true);
        expect(String((await approved.json()).code)).toBe('0');
        const result = await executeCommand(page, 'qo_quote_common:send', {
          recipient: 'buyer@customer.example', subject: `Controlled local send v${version}`,
        }, created.quoteId, 'update');
        expect(result.sent).toBe(true);
        expect(documents.get(String(result.documentId))).toBe(version);
        const rows = await queryDynamicRecords(page, 'qo_quote_send_audit_common', [
          { fieldName: 'qo_qsa_quote_id', operator: 'EQ', value: created.quoteId },
        ]);
        expect(rows).toHaveLength(version);
        for (const row of rows) {
          if (!created.rows.some(owned => owned.pid === String(row.pid)))
            created.rows.push({ model: 'qo_quote_send_audit_common', pid: String(row.pid) });
        }
        const current = rows.find(row => String(row.pid) === String(result.auditId));
        expect(current, 'the send command returns its actual audit fact').toBeTruthy();
        expect(documents.get(String(current!.qo_qsa_document_id))).toBe(version);
        expect(current!.qo_qsa_document_file_id).toBe(result.documentFileId);
        expect(Number.isFinite(Date.parse(String(current!.qo_qsa_sent_at)))).toBe(true);
        expect(current!.qo_qsa_status).toBe('sent');
        if (version === 2) expect(rows.find(row => row.pid === sent[0].pid), 'v1 audit is immutable across v2 send').toEqual(sent[0]);
        sent.push(current!);
      }
      const auditResponse = await page.request.get('/api/meta/commands/audit-logs?commandCode=qo_quote_common:send&success=true&pageNum=1&pageSize=100');
      expect(auditResponse.ok()).toBe(true);
      const auditBody = await auditResponse.json();
      expect(String(auditBody.code)).toBe('0');
      const own = auditBody.data.records.filter((row: any) => String(row.requestPayload).includes(created.quoteId));
      expect(own, 'both real sends have a traceable command actor').toHaveLength(2);
      expect(new Set(own.map((row: any) => String(row.userId))).size).toBe(1);
      for (const row of own) {
        expect(String(row.userId)).toMatch(/^[1-9]\d*$/);
        expect(row.success).toBe(true);
        expect(Number.isFinite(Date.parse(row.createdAt))).toBe(true);
      }
    } finally {
      await cleanupImmutableQuoteArtifacts(created);
      await cleanupRows(page, created);
    }
  });
});
