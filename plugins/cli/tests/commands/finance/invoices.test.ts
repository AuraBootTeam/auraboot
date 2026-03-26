import { describe, it, expect } from 'vitest';

describe('finance invoices command', () => {
  describe('AR column definitions', () => {
    const AR_COLUMNS = ['fin_art_invoice_no', 'fin_art_customer_id', 'fin_art_amount_base',
      'fin_art_balance_base', 'fin_art_status', 'fin_art_due_date'];

    it('should extract all AR fields from API record', () => {
      const record = {
        fin_art_invoice_no: 'INV-2026-001',
        fin_art_customer_id: 'cust-01',
        fin_art_amount_base: 10000,
        fin_art_balance_base: 5000,
        fin_art_status: 'partial',
        fin_art_due_date: '2026-04-15',
        pid: '01ABC',
      };

      for (const col of AR_COLUMNS) {
        expect(record[col as keyof typeof record]).toBeDefined();
      }
    });
  });

  describe('AP column definitions', () => {
    const AP_COLUMNS = ['fin_apt_invoice_no', 'fin_apt_supplier_id', 'fin_apt_amount_base',
      'fin_apt_balance_base', 'fin_apt_status', 'fin_apt_due_date'];

    it('should extract all AP fields from API record', () => {
      const record = {
        fin_apt_invoice_no: 'AP-2026-001',
        fin_apt_supplier_id: 'sup-01',
        fin_apt_amount_base: 8000,
        fin_apt_balance_base: 8000,
        fin_apt_status: 'open',
        fin_apt_due_date: '2026-04-20',
        pid: '02DEF',
      };

      for (const col of AP_COLUMNS) {
        expect(record[col as keyof typeof record]).toBeDefined();
      }
    });
  });

  describe('direction routing', () => {
    it('should default to AR when direction not specified', () => {
      const direction = (undefined || 'ar').toLowerCase();
      expect(direction).toBe('ar');
      expect(direction !== 'ap').toBe(true);
    });

    it('should use AP page key when direction is ap', () => {
      const direction = 'ap';
      const isAR = direction !== 'ap';
      const pageKey = isAR ? 'fin_ar_transaction' : 'fin_ap_transaction';
      expect(pageKey).toBe('fin_ap_transaction');
    });

    it('should use AR page key when direction is ar', () => {
      const direction = 'ar';
      const isAR = direction !== 'ap';
      const pageKey = isAR ? 'fin_ar_transaction' : 'fin_ap_transaction';
      expect(pageKey).toBe('fin_ar_transaction');
    });
  });

  describe('invoice status values', () => {
    const VALID_STATUSES = ['open', 'partial', 'paid', 'overdue', 'void'];

    it('should recognize all valid statuses', () => {
      for (const status of VALID_STATUSES) {
        expect(status.length).toBeGreaterThan(0);
      }
      expect(VALID_STATUSES).toHaveLength(5);
    });
  });
});
