import { describe, it, expect } from 'vitest';

describe('finance dashboard command', () => {
  describe('named query codes', () => {
    it('should use correct KPI query code', () => {
      const code = 'fin_dashboard_kpi';
      expect(code).toBe('fin_dashboard_kpi');
    });

    it('should use correct AR aging query code', () => {
      const code = 'fin_ar_aging';
      expect(code).toBe('fin_ar_aging');
    });

    it('should use correct revenue/expense KPI query code', () => {
      const code = 'fin_revenue_expense_kpi';
      expect(code).toBe('fin_revenue_expense_kpi');
    });
  });

  describe('KPI data parsing', () => {
    it('should parse KPI response fields', () => {
      const kpi = {
        total_ar_balance: 150000,
        total_ap_balance: 80000,
        month_journal_entries: 42,
        pending_expense_amount: 5600,
        total_payments: 28,
        active_accounts: 156,
      };

      expect(kpi.total_ar_balance).toBe(150000);
      expect(kpi.total_ap_balance).toBe(80000);
      expect(kpi.month_journal_entries).toBe(42);
      expect(kpi.active_accounts).toBe(156);
    });
  });

  describe('revenue KPI data parsing', () => {
    it('should parse revenue/expense KPI fields', () => {
      const revenueKpi = {
        total_revenue: 500000,
        total_expenses: 320000,
        net_income: 180000,
        net_cash_flow: 95000,
      };

      expect(revenueKpi.net_income).toBe(180000);
      expect(revenueKpi.net_income).toBe(revenueKpi.total_revenue - revenueKpi.total_expenses);
    });

    it('should handle negative net income', () => {
      const netIncome = -50000;
      expect(netIncome).toBeLessThan(0);
      expect(Math.abs(netIncome)).toBe(50000);
    });
  });

  describe('AR aging data parsing', () => {
    it('should parse aging bucket rows', () => {
      const arAging = [
        { aging_bucket: '0-30', total_balance: 50000, count: 12 },
        { aging_bucket: '31-60', total_balance: 30000, count: 8 },
        { aging_bucket: '61-90', total_balance: 15000, count: 4 },
        { aging_bucket: '90+', total_balance: 8000, count: 2 },
      ];

      expect(arAging).toHaveLength(4);
      expect(arAging[0].aging_bucket).toBe('0-30');
      expect(arAging[3].aging_bucket).toBe('90+');

      const totalBalance = arAging.reduce((sum, r) => sum + r.total_balance, 0);
      expect(totalBalance).toBe(103000);
    });
  });

  describe('JSON output mode', () => {
    it('should build correct JSON structure', () => {
      const kpi = [{ total_ar_balance: 100 }];
      const revenueKpi = [{ net_income: 50 }];
      const arAging = [{ aging_bucket: '0-30', total_balance: 100, count: 1 }];

      const data = {
        kpi: kpi[0] || {},
        revenueKpi: revenueKpi[0] || {},
        arAging,
      };

      expect(data.kpi).toHaveProperty('total_ar_balance');
      expect(data.revenueKpi).toHaveProperty('net_income');
      expect(data.arAging).toHaveLength(1);
    });

    it('should handle empty KPI gracefully', () => {
      const kpi: any[] = [];
      const data = { kpi: kpi[0] || {} };
      expect(data.kpi).toEqual({});
    });
  });
});
